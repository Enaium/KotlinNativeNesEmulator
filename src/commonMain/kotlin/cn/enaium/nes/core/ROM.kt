package cn.enaium.nes.core

import cn.enaium.nes.core.mappers.Mapper
import cn.enaium.nes.core.mappers.Mappers
import kotlin.math.ceil
import kotlin.math.pow

class ROM(val nes: NES) {
    // Mirroring types (instance properties so they're accessible via
    // this.nes.rom.HORIZONTAL_MIRRORING etc. in PPU and mappers):
    val VERTICAL_MIRRORING = 0
    val HORIZONTAL_MIRRORING = 1
    val FOURSCREEN_MIRRORING = 2
    val SINGLESCREEN_MIRRORING = 3
    val SINGLESCREEN_MIRRORING2 = 4
    val SINGLESCREEN_MIRRORING3 = 5
    val SINGLESCREEN_MIRRORING4 = 6
    val CHRROM_MIRRORING = 7

    var valid = false
    var header = IntArray(16)

    var mirroring = 0
    var batteryRam = false
    var trainer = false
    var fourScreen = false
    var isNES2 = false

    var romCount = 0
    var vromCount = 0
    var mapperType = 0
    var subMapper = 0
    var prgRamSize = 0
    var prgNvRamSize = 0
    var chrRamSize = 0
    var chrNvRamSize = 0
    var timingMode = 0
    var consoleType = 0

    lateinit var rom: Array<IntArray>
    lateinit var vrom: Array<IntArray>
    lateinit var vromTile: Array<Array<Tile>>

    fun load(data: ByteArray) {
        if (data.size < 4 ||
            data[0].toInt() != 0x4e ||
            data[1].toInt() != 0x45 ||
            data[2].toInt() != 0x53 ||
            data[3].toInt() != 0x1a
        ) {
            throw Error("Not a valid NES ROM.")
        }

        header = IntArray(16)
        for (i in 0 until 16) {
            header[i] = data[i].toInt() and 0xff
        }

        // Flags from byte 6 (shared between iNES 1.0 and NES 2.0)
        mirroring = if ((header[6] and 1) != 0) 1 else 0
        batteryRam = (header[6] and 2) != 0
        trainer = (header[6] and 4) != 0
        fourScreen = (header[6] and 8) != 0

        // Detect NES 2.0: byte 7 bits 3..2 == 0b10
        // https://www.nesdev.org/wiki/NES_2.0
        isNES2 = (header[7] and 0x0c) == 0x08

        if (isNES2) {
            loadNES2Header()
        } else {
            loadINES1Header()
        }

        /* TODO
            if (this.batteryRam)
                this.loadBatteryRam();*/

        // Load PRG-ROM banks:
        rom = Array(romCount) { IntArray(16384) }
        // Skip past the 16-byte header, plus 512-byte trainer if present.
        // See https://www.nesdev.org/wiki/INES#Trainer
        var offset = 16 + (if (trainer) 512 else 0)
        for (i in 0 until romCount) {
            for (j in 0 until 16384) {
                if (offset + j >= data.size) {
                    break
                }
                rom[i][j] = data[offset + j].toInt() and 0xff
            }
            offset += 16384
        }
        // Load CHR-ROM banks:
        vrom = Array(vromCount) { IntArray(4096) }
        for (i in 0 until vromCount) {
            for (j in 0 until 4096) {
                if (offset + j >= data.size) {
                    break
                }
                vrom[i][j] = data[offset + j].toInt() and 0xff
            }
            offset += 4096
        }

        // Create VROM tiles:
        vromTile = Array(vromCount) { Array(256) { Tile() } }

        // Convert CHR-ROM banks to tiles:
        for (v in 0 until vromCount) {
            for (i in 0 until 4096) {
                val tileIndex = i shr 4
                val leftOver = i % 16
                if (leftOver < 8) {
                    vromTile[v][tileIndex].setScanline(
                        leftOver,
                        vrom[v][i],
                        vrom[v][i + 8],
                    )
                } else {
                    vromTile[v][tileIndex].setScanline(
                        leftOver - 8,
                        vrom[v][i - 8],
                        vrom[v][i],
                    )
                }
            }
        }

        valid = true
    }

    // Parse iNES 1.0 header fields (bytes 4-15).
    private fun loadINES1Header() {
        romCount = header[4]
        vromCount = header[5] * 2 // Get the number of 4kB banks, not 8kB
        mapperType = (header[6] shr 4) or (header[7] and 0xf0)

        // Check whether bytes 8-15 are zero. Non-zero values in this region
        // typically indicate garbage (e.g. "DiskDude!" in old ROM dumps), so
        // we discard the upper mapper nibble from byte 7 to be safe.
        var foundError = false
        for (i in 8 until 16) {
            if (header[i] != 0) {
                foundError = true
                break
            }
        }
        if (foundError) {
            mapperType = mapperType and 0xf // Ignore byte 7
        }

        // Default NES 2.0 fields to zero for iNES 1.0 ROMs so consumers
        // don't need to check isNES2 before accessing them.
        subMapper = 0
        prgRamSize = 0
        prgNvRamSize = 0
        chrRamSize = 0
        chrNvRamSize = 0
        timingMode = 0
        consoleType = 0
    }

    // Parse NES 2.0 header fields (bytes 4-15).
    // https://www.nesdev.org/wiki/NES_2.0
    private fun loadNES2Header() {
        // Mapper number: 12 bits from bytes 6, 7, and 8.
        //   Byte 6 D7..D4: mapper D3..D0
        //   Byte 7 D7..D4: mapper D7..D4
        //   Byte 8 D3..D0: mapper D11..D8
        mapperType =
            (header[6] shr 4) or
                (header[7] and 0xf0) or
                ((header[8] and 0x0f) shl 8)

        // Submapper: byte 8 D7..D4
        subMapper = (header[8] shr 4) and 0x0f

        // PRG-ROM size: byte 9 D3..D0 (MSB) combined with byte 4 (LSB).
        // When MSB nibble is 0xF, an exponent-multiplier encoding is used:
        //   size = 2^E * (M*2 + 1) bytes, where E = bits 7..2, M = bits 1..0.
        val prgMsb = header[9] and 0x0f
        if (prgMsb == 0x0f) {
            val e = (header[4] shr 2) and 0x3f
            val m = header[4] and 0x03
            romCount = ceil((2.0.pow(e) * (m * 2 + 1)) / 16384).toInt()
        } else {
            romCount = (prgMsb shl 8) or header[4]
        }

        // CHR-ROM size: byte 9 D7..D4 (MSB) combined with byte 5 (LSB).
        // Same exponent-multiplier encoding when MSB nibble is 0xF.
        // Internally we store as 4KB bank count (vromCount = 8KB units * 2).
        val chrMsb = (header[9] shr 4) and 0x0f
        if (chrMsb == 0x0f) {
            val e = (header[5] shr 2) and 0x3f
            val m = header[5] and 0x03
            vromCount = ceil((2.0.pow(e) * (m * 2 + 1)) / 4096).toInt()
        } else {
            // 12-bit value is in 8KB units; double it for 4KB bank count.
            vromCount = ((chrMsb shl 8) or header[5]) * 2
        }

        // PRG-RAM sizes (byte 10).
        // Lower nibble: volatile PRG-RAM; upper nibble: non-volatile PRG-NVRAM.
        // Encoding: 0 = none, otherwise 64 << value bytes.
        prgRamSize = decodeRamSize(header[10] and 0x0f)
        prgNvRamSize = decodeRamSize((header[10] shr 4) and 0x0f)

        // CHR-RAM sizes (byte 11).
        // Lower nibble: volatile CHR-RAM; upper nibble: non-volatile CHR-NVRAM.
        // Note: with NES 2.0, do not assume 8KB CHR-RAM when CHR-ROM is 0;
        // CHR-RAM must be explicitly specified here.
        chrRamSize = decodeRamSize(header[11] and 0x0f)
        chrNvRamSize = decodeRamSize((header[11] shr 4) and 0x0f)

        // CPU/PPU timing mode (byte 12, low 2 bits).
        // 0 = NTSC (RP2C02), 1 = PAL (RP2C07), 2 = Multi-region, 3 = Dendy (UA6538)
        timingMode = header[12] and 0x03

        // Console type (byte 7, bits 1..0).
        // 0 = NES/Famicom, 1 = Vs. System, 2 = Playchoice 10, 3 = Extended
        consoleType = header[7] and 0x03
    }

    // Decode NES 2.0 RAM shift-count encoding.
    // Value 0 means no RAM; otherwise size = 64 << value (in bytes).
    // https://www.nesdev.org/wiki/NES_2.0#PRG-(NV)RAM/EEPROM
    private fun decodeRamSize(value: Int): Int {
        if (value == 0) return 0
        return 64 shl value
    }

    fun getMirroringType(): Int {
        if (fourScreen) {
            return FOURSCREEN_MIRRORING
        }
        if (mirroring == 0) {
            return HORIZONTAL_MIRRORING
        }
        return VERTICAL_MIRRORING
    }

    fun mapperSupported(): Boolean {
        return Mappers.isSupported(mapperType)
    }

    fun createMapper(): Mapper {
        if (mapperSupported()) {
            return Mappers.create(nes, mapperType)
        } else {
            throw Error("Unsupported mapper: $mapperType")
        }
    }
}
