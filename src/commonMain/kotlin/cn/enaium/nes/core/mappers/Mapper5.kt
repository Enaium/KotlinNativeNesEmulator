package cn.enaium.nes.core.mappers

import cn.enaium.nes.core.NES
import cn.enaium.nes.core.Tile
import cn.enaium.nes.core.copyArrayElements

// MMC5 / ExROM (EKROM, ELROM, ETROM, EWROM)
// The most complex Nintendo mapper. Flexible PRG/CHR banking (up to 1 MB each),
// expansion audio (2 pulse + PCM), 8x8 hardware multiplier, 1 KB ExRAM for
// extended nametable attributes, vertical split screen, and scanline-counting IRQ.
class Mapper5(nes: NES) : Mapper(nes) {
    // PRG banking
    // $5100: PRG mode (0=32K, 1=16K+16K, 2=16K+8K+8K, 3=8K+8K+8K+8K)
    var prgMode = 3 // Power-on default: mode 3 (8K banks)
    // $5113-$5117: PRG bank registers. Raw values written by the game.
    // $5113 always maps RAM to $6000-$7FFF.
    // $5114-$5116 bit 7: 0=RAM, 1=ROM. $5117 always ROM.
    var prgBankReg = IntArray(5) // indices 0-4 for $5113-$5117

    // PRG RAM: up to 64 KB (two 32 KB chips), banked into $6000-$7FFF.
    // Also mappable into $8000-$DFFF via bank registers with bit 7 clear.
    var prgRam = IntArray(0x10000) // 64 KB PRG RAM

    // PRG RAM write protection: $5102 and $5103
    // Writes only enabled when $5102=%10 and $5103=%01
    var prgRamProtectA = 0x03 // $5102
    var prgRamProtectB = 0x03 // $5103

    // CHR banking
    // $5101: CHR mode (0=8K, 1=4K, 2=2K, 3=1K)
    var chrMode = 3 // Power-on default: mode 3 (1K banks)
    // $5120-$5127: CHR bank set A (sprite banks)
    var chrBankA = IntArray(8)
    // $5128-$512B: CHR bank set B (background banks)
    var chrBankB = IntArray(4)
    // $5130: Upper CHR bank bits (bits 8-9 appended to bank registers)
    var chrUpperBits = 0
    // Tracks which CHR set was last written (0=A, 1=B) for $2007 access
    var lastChrWrite = 0

    // Nametable mapping: $5105
    // Each 2-bit field: 0=CIRAM A, 1=CIRAM B, 2=ExRAM, 3=Fill
    var ntMapping = IntArray(4)

    // ExRAM: 1 KB internal to MMC5, used for nametable/extended attributes/RAM
    // $5104: ExRAM mode (0=nametable, 1=ext attributes, 2=RAM, 3=read-only)
    var exramMode = 0
    var exram = IntArray(0x400) // 1 KB

    // Fill mode: $5106/$5107
    var fillTile = 0
    var fillAttr = 0

    // Scanline IRQ: $5203/$5204
    var irqTarget = 0 // $5203: target scanline
    var irqEnabled = false // $5204 bit 7 write: IRQ enable
    var irqPending = false // $5204 bit 7 read: IRQ pending flag
    var inFrame = false // $5204 bit 6 read: in-frame flag
    var irqCounter = 0 // Internal scanline counter

    // Hardware multiplier: $5205/$5206
    var multA = 0
    var multB = 0

    // Split screen: $5200-$5202
    var splitEnabled = false // $5200 bit 7
    var splitRight = false // $5200 bit 6 (0=left, 1=right)
    var splitTile = 0 // $5200 bits 0-4: tile threshold
    var splitScroll = 0 // $5201: vertical scroll for split
    var splitPage = 0 // $5202: 4K CHR page for split

    // Expansion audio: two pulse channels + PCM
    private var pulse1 = _initPulse()
    private var pulse2 = _initPulse()
    var pcmValue = 0 // $5011: raw 8-bit PCM output
    var pcmReadMode = false // $5010 bit 0
    var pcmIrqEnabled = false // $5010 bit 7
    var audioEnabled = 0 // $5015: pulse channel enable bits

    // Tracks which CHR bank set is currently loaded into the PPU's pattern
    // table cache. -1 = unknown/dirty, 0 = set A (sprites), 1 = set B (BG).
    var _chrBankTarget = -1

    init {
        prgBankReg[4] = 0xff // $5117 defaults to last page (0xFF)
    }

    // Initialize a pulse channel state object.
    // MMC5 pulse channels are like APU square channels minus the sweep unit.
    private fun _initPulse(): PulseChannel {
        return PulseChannel()
    }

    // --- CPU Read Handler ---
    // Override load() to handle MMC5 register reads and banked PRG access.
    override fun load(address: Int): Int {
        var addr = address and 0xffff

        if (addr < 0x5000) {
            // Standard read (RAM, PPU regs, APU regs, controllers)
            return super.load(addr)
        }

        // $5000-$5017: Expansion audio read-back
        if (addr == 0x5015) {
            // Status register: bits 0-1 indicate pulse channel length counter > 0
            var result = 0
            if (pulse1.lengthCounter > 0) result = result or 0x01
            if (pulse2.lengthCounter > 0) result = result or 0x02
            return result
        }

        if (addr == 0x5010) {
            // PCM IRQ status (bit 7). Reading clears the flag.
            // PCM IRQ is rarely used; return 0 for now.
            return 0
        }

        // $5100-$5104: Write-only control registers — return open bus
        if (addr >= 0x5100 && addr <= 0x5104) {
            return nes.cpu.dataBus
        }

        // $5105: Nametable mapping (write-only, open bus on read)
        if (addr == 0x5105) {
            return nes.cpu.dataBus
        }

        // $5204: Scanline IRQ status
        if (addr == 0x5204) {
            val ppu = nes.ppu
            val rendering =
                ppu.scanline >= 20 &&
                    ppu.scanline <= 260 &&
                    (ppu.f_bgVisibility == 1 || ppu.f_spVisibility == 1)
            if (!rendering) {
                inFrame = false
            }

            var result = 0
            if (irqPending) result = result or 0x80
            if (inFrame) result = result or 0x40
            // Reading $5204 acknowledges (clears) the IRQ pending flag
            irqPending = false
            return result
        }

        // $5205: Multiplier result low byte
        if (addr == 0x5205) {
            return (multA * multB) and 0xff
        }

        // $5206: Multiplier result high byte
        if (addr == 0x5206) {
            return ((multA * multB) shr 8) and 0xff
        }

        // $5C00-$5FFF: ExRAM
        if (addr >= 0x5c00 && addr <= 0x5fff) {
            // Readable in modes 2 and 3 only; otherwise open bus
            if (exramMode >= 2) {
                return exram[addr - 0x5c00]
            }
            return nes.cpu.dataBus
        }

        // $5000-$5BFF other: expansion area, return open bus
        if (addr < 0x6000) {
            return nes.cpu.dataBus
        }

        // $6000-$7FFF: PRG RAM (banked via $5113)
        if (addr < 0x8000) {
            val bank = prgBankReg[0] and 0x07 // 3-bit page within 64K RAM
            val offset = bank * 0x2000 + (addr - 0x6000)
            return prgRam[offset and 0xffff]
        }

        // $8000-$FFFF: PRG ROM/RAM (banked via $5114-$5117 and prgMode)
        return _readPrg(addr)
    }

    // Read from banked PRG space ($8000-$FFFF).
    // In modes where a region can map to RAM (bit 7 of bank reg = 0),
    // reads come from prgRam. Otherwise, reads come from ROM.
    private fun _readPrg(address: Int): Int {
        var slot = 0
        var reg: Int
        var isRam: Boolean
        var bank: Int
        var base = 0

        when (prgMode) {
            0 -> {
                // Mode 0: One 32K bank at $8000-$FFFF, controlled by $5117
                // Ignore low 2 bits for 32K alignment
                reg = prgBankReg[4]
                bank = (reg and 0x7c) shr 2 // 32K page = bits 6-2
                return _readPrgRom32k(bank, address - 0x8000)
            }

            1 -> {
                // Mode 1: Two 16K banks
                // $8000-$BFFF: $5115 (can be RAM if bit 7=0)
                // $C000-$FFFF: $5117 (always ROM)
                if (address < 0xc000) {
                    reg = prgBankReg[2] // $5115
                    isRam = (reg and 0x80) == 0
                    if (isRam) {
                        bank = (reg and 0x06) shr 1 // 16K RAM page
                        return prgRam[bank * 0x4000 + (address - 0x8000)]
                    }
                    bank = (reg and 0x7e) shr 1 // 16K ROM page (ignore bit 0)
                    return _readPrgRom16k(bank, address - 0x8000)
                } else {
                    reg = prgBankReg[4] // $5117
                    bank = (reg and 0x7e) shr 1 // 16K ROM page
                    return _readPrgRom16k(bank, address - 0xc000)
                }
            }

            2 -> {
                // Mode 2: 16K + 8K + 8K
                // $8000-$BFFF: $5115 (RAM or ROM)
                // $C000-$DFFF: $5116 (RAM or ROM)
                // $E000-$FFFF: $5117 (always ROM)
                if (address < 0xc000) {
                    reg = prgBankReg[2] // $5115
                    isRam = (reg and 0x80) == 0
                    if (isRam) {
                        bank = (reg and 0x06) shr 1
                        return prgRam[bank * 0x4000 + (address - 0x8000)]
                    }
                    bank = (reg and 0x7e) shr 1
                    return _readPrgRom16k(bank, address - 0x8000)
                } else if (address < 0xe000) {
                    reg = prgBankReg[3] // $5116
                    isRam = (reg and 0x80) == 0
                    if (isRam) {
                        bank = reg and 0x07
                        return prgRam[bank * 0x2000 + (address - 0xc000)]
                    }
                    bank = reg and 0x7f
                    return _readPrgRom8k(bank, address - 0xc000)
                } else {
                    reg = prgBankReg[4] // $5117
                    bank = reg and 0x7f
                    return _readPrgRom8k(bank, address - 0xe000)
                }
            }

            else -> {
                // Mode 3: Four 8K banks
                // $8000-$9FFF: $5114 (RAM or ROM)
                // $A000-$BFFF: $5115 (RAM or ROM)
                // $C000-$DFFF: $5116 (RAM or ROM)
                // $E000-$FFFF: $5117 (always ROM)
                if (address < 0xa000) {
                    slot = 1 // $5114
                } else if (address < 0xc000) {
                    slot = 2 // $5115
                } else if (address < 0xe000) {
                    slot = 3 // $5116
                } else {
                    slot = 4 // $5117
                }
                reg = prgBankReg[slot]
                base =
                    if (slot == 1) 0x8000
                    else if (slot == 2) 0xa000
                    else if (slot == 3) 0xc000
                    else 0xe000
                // $5117 is always ROM; $5114-$5116 use bit 7 for RAM/ROM select
                if (slot < 4 && (reg and 0x80) == 0) {
                    bank = reg and 0x07
                    return prgRam[bank * 0x2000 + (address - base)]
                }
                bank = reg and 0x7f
                return _readPrgRom8k(bank, address - base)
            }
        }
    }

    // Read a byte from PRG ROM given a 32K bank number and offset within it.
    private fun _readPrgRom32k(bank32k: Int, offset: Int): Int {
        // ROM is stored as 16K banks in rom.rom[]
        val bank16k = (bank32k * 2 + offset / 0x4000) % nes.rom!!.romCount
        val innerOffset = offset % 0x4000
        return nes.rom!!.rom[bank16k][innerOffset]
    }

    // Read a byte from PRG ROM given a 16K bank number and offset within it.
    private fun _readPrgRom16k(bank16k: Int, offset: Int): Int {
        val bank = bank16k % nes.rom!!.romCount
        return nes.rom!!.rom[bank][offset]
    }

    // Read a byte from PRG ROM given an 8K bank number and offset within it.
    private fun _readPrgRom8k(bank8k: Int, offset: Int): Int {
        val bank16k = (bank8k / 2) % nes.rom!!.romCount
        val innerOffset = (bank8k % 2) * 0x2000 + offset
        if (bank16k < nes.rom!!.romCount) {
            return nes.rom!!.rom[bank16k][innerOffset]
        }
        return 0
    }

    // --- CPU Write Handler ---
    override fun write(address: Int, value: Int) {
        // Standard NES write handling for addresses below $5000
        if (address < 0x5000) {
            super.write(address, value)
            // MMC5 monitors writes to $2000 to track 8x8 vs 8x16 sprite mode.
            // This affects which CHR bank set is used for rendering.
            return
        }

        // $5000-$5015: Expansion audio registers
        if (address >= 0x5000 && address <= 0x5003) {
            _writePulse(pulse1, address - 0x5000, value)
            return
        }
        if (address >= 0x5004 && address <= 0x5007) {
            _writePulse(pulse2, address - 0x5004, value)
            return
        }
        if (address == 0x5010) {
            pcmReadMode = (value and 0x01) != 0
            pcmIrqEnabled = (value and 0x80) != 0
            return
        }
        if (address == 0x5011) {
            // Raw PCM write. Writing $00 has no effect on the output.
            if (!pcmReadMode && value != 0) {
                pcmValue = value
            }
            return
        }
        if (address == 0x5015) {
            // Expansion audio status: bits 0-1 enable pulse channels
            audioEnabled = value and 0x03
            pulse1.enabled = (value and 0x01) != 0
            pulse2.enabled = (value and 0x02) != 0
            if (!pulse1.enabled) pulse1.lengthCounter = 0
            if (!pulse2.enabled) pulse2.lengthCounter = 0
            return
        }

        // $5100: PRG banking mode
        if (address == 0x5100) {
            prgMode = value and 0x03
            _syncPrg()
            return
        }

        // $5101: CHR banking mode
        if (address == 0x5101) {
            chrMode = value and 0x03
            _syncChr()
            return
        }

        // $5102/$5103: PRG RAM write protection
        if (address == 0x5102) {
            prgRamProtectA = value and 0x03
            return
        }
        if (address == 0x5103) {
            prgRamProtectB = value and 0x03
            return
        }

        // $5104: ExRAM mode
        if (address == 0x5104) {
            exramMode = value and 0x03
            // ExRAM mode 1 enables per-tile BG override: each ExRAM byte provides
            // a 4KB CHR bank + attribute for the corresponding background tile.
            bgTileOverride = exramMode == 1
            _syncNametables()
            return
        }

        // $5105: Nametable mapping
        if (address == 0x5105) {
            var v = value
            ntMapping[0] = v and 0x03
            v = v shr 2
            ntMapping[1] = v and 0x03
            v = v shr 2
            ntMapping[2] = v and 0x03
            v = v shr 2
            ntMapping[3] = v and 0x03
            _syncNametables()
            return
        }

        // $5106: Fill-mode tile
        if (address == 0x5106) {
            fillTile = value
            _syncNametables()
            return
        }

        // $5107: Fill-mode attribute (bottom 2 bits)
        if (address == 0x5107) {
            fillAttr = value and 0x03
            _syncNametables()
            return
        }

        // $5113: PRG RAM bank for $6000-$7FFF
        if (address == 0x5113) {
            prgBankReg[0] = value and 0x07
            return
        }

        // $5114-$5117: PRG bank registers
        if (address >= 0x5114 && address <= 0x5117) {
            val idx = address - 0x5113 // 1-4
            prgBankReg[idx] = value
            _syncPrg()
            return
        }

        // $5120-$5127: CHR bank set A (sprites / "last written" set)
        if (address >= 0x5120 && address <= 0x5127) {
            val reg = address - 0x5120
            chrBankA[reg] = (chrUpperBits shl 8) or value
            lastChrWrite = 0
            _syncChr()
            return
        }

        // $5128-$512B: CHR bank set B (background)
        if (address >= 0x5128 && address <= 0x512b) {
            val reg = address - 0x5128
            chrBankB[reg] = (chrUpperBits shl 8) or value
            lastChrWrite = 1
            _syncChr()
            return
        }

        // $5130: Upper CHR bank bits
        if (address == 0x5130) {
            chrUpperBits = value and 0x03
            return
        }

        // $5200: Split screen control
        if (address == 0x5200) {
            splitEnabled = (value and 0x80) != 0
            splitRight = (value and 0x40) != 0
            splitTile = value and 0x1f
            return
        }

        // $5201: Split screen Y scroll
        if (address == 0x5201) {
            splitScroll = value
            return
        }

        // $5202: Split screen CHR page
        if (address == 0x5202) {
            splitPage = value and 0x3f
            return
        }

        // $5203: Scanline IRQ target
        if (address == 0x5203) {
            irqTarget = value
            return
        }

        // $5204: Scanline IRQ enable
        if (address == 0x5204) {
            irqEnabled = (value and 0x80) != 0
            // If both enabled and pending, fire IRQ immediately
            if (irqEnabled && irqPending) {
                nes.cpu.requestIrq(nes.cpu.IRQ_NORMAL)
            }
            return
        }

        // $5205: Multiplier operand A
        if (address == 0x5205) {
            multA = value
            return
        }

        // $5206: Multiplier operand B
        if (address == 0x5206) {
            multB = value
            return
        }

        // $5C00-$5FFF: ExRAM writes
        if (address >= 0x5c00 && address <= 0x5fff) {
            val exAddr = address - 0x5c00
            if (exramMode == 0 || exramMode == 1) {
                // Modes 0/1: writable only during rendering (in-frame).
                // If not in-frame, $00 is written instead.
                exram[exAddr] = if (inFrame) value else 0x00
                // If ExRAM is used as a nametable, sync it to VRAM
                _syncExramToVram(exAddr)
            } else if (exramMode == 2) {
                // Mode 2: general-purpose RAM, always writable
                exram[exAddr] = value
            }
            // Mode 3: read-only, writes have no effect
            return
        }

        // $6000-$7FFF: PRG RAM writes (write-protected via $5102/$5103)
        if (address >= 0x6000 && address <= 0x7fff) {
            if (prgRamProtectA == 0x02 && prgRamProtectB == 0x01) {
                val bank = prgBankReg[0] and 0x07
                val offset = bank * 0x2000 + (address - 0x6000)
                prgRam[offset and 0xffff] = value
                // Also write to CPU mem for compatibility with save state / battery RAM
                nes.cpu.mem[address] = value
                nes.opts.onBatteryRamWrite(address, value)
            }
            return
        }

        // $8000-$FFFF: PRG ROM/RAM writes
        if (address >= 0x8000) {
            _writePrg(address, value)
            return
        }
    }

    // Handle writes to the PRG address space ($8000-$FFFF).
    // Some bank slots may be mapped to RAM if bit 7 of the bank register is 0.
    private fun _writePrg(address: Int, value: Int) {
        var slot = 0
        var reg: Int
        var isRam: Boolean
        var bank: Int
        var base = 0

        when (prgMode) {
            0 ->
                // Mode 0: Entire $8000-$FFFF is a single 32K ROM bank — not writable
                return

            1 -> {
                // $8000-$BFFF: $5115 (can be RAM)
                // $C000-$FFFF: $5117 (always ROM)
                if (address < 0xc000) {
                    reg = prgBankReg[2]
                    isRam = (reg and 0x80) == 0
                    if (isRam && _isPrgRamWritable()) {
                        bank = (reg and 0x06) shr 1
                        prgRam[bank * 0x4000 + (address - 0x8000)] = value
                    }
                }
                return
            }

            2 -> {
                // $8000-$BFFF: $5115 (can be RAM)
                // $C000-$DFFF: $5116 (can be RAM)
                // $E000-$FFFF: $5117 (always ROM)
                if (address < 0xc000) {
                    reg = prgBankReg[2]
                    isRam = (reg and 0x80) == 0
                    if (isRam && _isPrgRamWritable()) {
                        bank = (reg and 0x06) shr 1
                        prgRam[bank * 0x4000 + (address - 0x8000)] = value
                    }
                } else if (address < 0xe000) {
                    reg = prgBankReg[3]
                    isRam = (reg and 0x80) == 0
                    if (isRam && _isPrgRamWritable()) {
                        bank = reg and 0x07
                        prgRam[bank * 0x2000 + (address - 0xc000)] = value
                    }
                }
                return
            }

            else -> {
                // $8000-$9FFF: $5114 (can be RAM)
                // $A000-$BFFF: $5115 (can be RAM)
                // $C000-$DFFF: $5116 (can be RAM)
                // $E000-$FFFF: $5117 (always ROM)
                if (address < 0xa000) {
                    slot = 1
                    base = 0x8000
                } else if (address < 0xc000) {
                    slot = 2
                    base = 0xa000
                } else if (address < 0xe000) {
                    slot = 3
                    base = 0xc000
                } else {
                    return // $5117 is always ROM
                }
                reg = prgBankReg[slot]
                isRam = (reg and 0x80) == 0
                if (isRam && _isPrgRamWritable()) {
                    bank = reg and 0x07
                    prgRam[bank * 0x2000 + (address - base)] = value
                }
                return
            }
        }
    }

    // Check if PRG RAM writes are enabled via the two protection registers.
    private fun _isPrgRamWritable(): Boolean {
        return prgRamProtectA == 0x02 && prgRamProtectB == 0x01
    }

    // --- PRG Synchronization ---
    // Copy the selected PRG ROM banks into CPU address space so the CPU can
    // read them directly. Called when prgMode or bank registers change.
    private fun _syncPrg() {
        when (prgMode) {
            0 -> {
                // 32K bank at $8000-$FFFF from $5117
                val reg = prgBankReg[4]
                val bank = (reg and 0x7c) shr 2 // 32K page
                load32kRomBank(bank, 0x8000)
            }

            1 -> {
                // $8000-$BFFF from $5115, $C000-$FFFF from $5117
                val regLo = prgBankReg[2] // $5115
                if ((regLo and 0x80) != 0) {
                    // ROM
                    val bank16k = (regLo and 0x7e) shr 1
                    loadRomBank(bank16k % nes.rom!!.romCount, 0x8000)
                }
                // else: RAM — reads will be handled by load() override

                val regHi = prgBankReg[4] // $5117
                val bank16kHi = (regHi and 0x7e) shr 1
                loadRomBank(bank16kHi % nes.rom!!.romCount, 0xc000)
            }

            2 -> {
                // $8000-$BFFF from $5115, $C000-$DFFF from $5116, $E000-$FFFF from $5117
                val regA = prgBankReg[2] // $5115
                if ((regA and 0x80) != 0) {
                    val bank16k = (regA and 0x7e) shr 1
                    loadRomBank(bank16k % nes.rom!!.romCount, 0x8000)
                }

                val regB = prgBankReg[3] // $5116
                if ((regB and 0x80) != 0) {
                    load8kRomBank(regB and 0x7f, 0xc000)
                }

                val regC = prgBankReg[4] // $5117
                load8kRomBank(regC and 0x7f, 0xe000)
            }

            else -> {
                // Four 8K banks from $5114-$5117
                for (i in 1..4) {
                    val reg = prgBankReg[i]
                    val addr = 0x6000 + i * 0x2000 // $8000, $A000, $C000, $E000
                    // $5117 (i=4) is always ROM; $5114-$5116 check bit 7
                    if (i == 4 || (reg and 0x80) != 0) {
                        load8kRomBank(reg and 0x7f, addr)
                    }
                    // RAM banks are handled dynamically in load()
                }
            }
        }
    }

    // --- CHR Synchronization ---
    // Apply the current CHR bank registers to PPU pattern table memory.
    private fun _syncChr() {
        // Trigger rendering before changing banks, so any accumulated scanlines
        // are drawn with the OLD CHR bank values.
        nes.ppu.triggerRendering()

        // Invalidate cached CHR bank target so the render hooks re-apply
        // when rendering starts.
        _chrBankTarget = -1

        if (nes.ppu.f_spriteSize == 0) {
            // 8x8 sprite mode: only bank set A is used for ALL fetches (sprites,
            // backgrounds, and $2007 reads). Bank set B is completely ignored.
            _applyChrSetA()
            _chrBankTarget = 0
        }
        // In 8x16 sprite mode, the onBgRender/onSpriteRender hooks handle
        // switching between set A (sprites) and set B (backgrounds) during
        // rendering.
    }

    // Apply CHR bank set A ($5120-$5127) based on chrMode.
    private fun _applyChrSetA() {
        if (nes.rom!!.vromCount == 0) return

        when (chrMode) {
            0 ->
                // 8K mode: $5127 selects an 8K page
                load8kVromBank((chrBankA[7] and 0xff) * 2, 0x0000)

            1 -> {
                // 4K mode: $5123 selects 4K at $0000, $5127 selects 4K at $1000
                loadVromBank(chrBankA[3] and 0xff, 0x0000)
                loadVromBank(chrBankA[7] and 0xff, 0x1000)
            }

            2 -> {
                // 2K mode: $5121/$5123/$5125/$5127 each select 2K
                load2kVromBank(chrBankA[1] and 0x1ff, 0x0000)
                load2kVromBank(chrBankA[3] and 0x1ff, 0x0800)
                load2kVromBank(chrBankA[5] and 0x1ff, 0x1000)
                load2kVromBank(chrBankA[7] and 0x1ff, 0x1800)
            }

            else ->
                // 1K mode: $5120-$5127 each select a 1K page
                for (i in 0 until 8) {
                    load1kVromBank(chrBankA[i] and 0x3ff, i * 0x0400)
                }
        }
    }

    // Apply CHR bank set B ($5128-$512B) based on chrMode.
    // Set B uses only 4 registers, so larger modes replicate them.
    private fun _applyChrSetB() {
        if (nes.rom!!.vromCount == 0) return

        when (chrMode) {
            0 ->
                // 8K mode: $512B selects an 8K page
                load8kVromBank((chrBankB[3] and 0xff) * 2, 0x0000)

            1 -> {
                // 4K mode: $512B selects 4K at both halves
                loadVromBank(chrBankB[3] and 0xff, 0x0000)
                loadVromBank(chrBankB[3] and 0xff, 0x1000)
            }

            2 -> {
                // 2K mode: $5129/$512B each select 2K, replicated across 8K
                load2kVromBank(chrBankB[1] and 0x1ff, 0x0000)
                load2kVromBank(chrBankB[3] and 0x1ff, 0x0800)
                load2kVromBank(chrBankB[1] and 0x1ff, 0x1000)
                load2kVromBank(chrBankB[3] and 0x1ff, 0x1800)
            }

            else ->
                // 1K mode: $5128-$512B each select 1K, replicated for both halves
                for (i in 0 until 4) {
                    load1kVromBank(chrBankB[i] and 0x3ff, i * 0x0400)
                    load1kVromBank(chrBankB[i] and 0x3ff, (i + 4) * 0x0400)
                }
        }
    }

    // --- Nametable Synchronization ---
    // Configure the PPU's vramMirrorTable AND internal NameTable objects to
    // reflect the MMC5's nametable mapping. Each of the 4 nametable slots
    // ($2000/$2400/$2800/$2C00) can be mapped to:
    //   0: NES CIRAM page A ($2000)
    //   1: NES CIRAM page B ($2400)
    //   2: ExRAM (internal 1KB, stored at $2800 in VRAM)
    //   3: Fill mode (stored at $2C00 in VRAM)
    private fun _syncNametables() {
        val ppu = nes.ppu

        // First, populate the fill-mode nametable at VRAM $2C00.
        // 960 bytes of tile index followed by 64 bytes of attribute.
        // The attribute byte packs the fill palette into all four sub-quadrants.
        val fillAttrByte =
            fillAttr or
                (fillAttr shl 2) or
                (fillAttr shl 4) or
                (fillAttr shl 6)
        for (i in 0 until 960) {
            ppu.vramMem[0x2c00 + i] = fillTile
        }
        for (i in 960 until 1024) {
            ppu.vramMem[0x2c00 + i] = fillAttrByte
        }

        // Copy ExRAM into VRAM at $2800 for nametable use.
        // In modes 2/3 (general-purpose RAM), ExRAM reads as all zeros for nametable.
        if (exramMode >= 2) {
            for (i in 0 until 0x400) {
                ppu.vramMem[0x2800 + i] = 0
            }
        } else {
            copyArrayElements(exram, 0, ppu.vramMem, 0x2800, 0x400)
        }

        // Physical VRAM locations for each source:
        //   0 -> $2000 (CIRAM A)
        //   1 -> $2400 (CIRAM B)
        //   2 -> $2800 (ExRAM copy)
        //   3 -> $2C00 (Fill mode)
        val sourceBase = intArrayOf(0x2000, 0x2400, 0x2800, 0x2c00)

        for (nt in 0 until 4) {
            val logicalBase = 0x2000 + nt * 0x400
            val physBase = sourceBase[ntMapping[nt]]
            ppu.defineMirrorRegion(logicalBase, physBase, 0x400)
        }

        // Also mirror $3000-$3EFF -> $2000-$2EFF as per normal NES behavior
        ppu.defineMirrorRegion(0x3000, 0x2000, 0xf00)

        // Update ntable1 so the renderer reads from the correct NameTable objects.
        for (nt in 0 until 4) {
            ppu.ntable1[nt] = ntMapping[nt]
        }

        // Populate NameTable 2 with ExRAM data so the renderer can see it.
        this._populateNameTable(2, 0x2800)

        // Populate NameTable 3 with fill-mode data.
        this._populateNameTable(3, 0x2c00)
    }

    // Populate a NameTable object from a 1KB region of vramMem.
    // The first 960 bytes are tile indices, the next 64 are attribute table bytes.
    private fun _populateNameTable(ntIndex: Int, vramBase: Int) {
        val ppu = nes.ppu
        val nt = ppu.nameTable[ntIndex]

        // Copy tile indices (960 bytes = 30 rows x 32 columns)
        for (i in 0 until 960) {
            nt.tile[i] = ppu.vramMem[vramBase + i]
        }

        // Decode attribute table (64 bytes) into per-tile attributes.
        for (i in 0 until 64) {
            nt.writeAttrib(i, ppu.vramMem[vramBase + 960 + i])
        }
    }

    // Sync a single ExRAM byte to both the VRAM copy at $2800 and NameTable 2.
    // Called when ExRAM is written via $5C00-$5FFF in modes 0/1.
    private fun _syncExramToVram(exAddr: Int) {
        if (exramMode < 2) {
            val ppu = nes.ppu
            ppu.vramMem[0x2800 + exAddr] = exram[exAddr]

            // Also update NameTable 2 so the renderer sees the change.
            if (exAddr < 960) {
                // Tile index update
                ppu.nameTable[2].tile[exAddr] = exram[exAddr]
            } else if (exAddr < 1024) {
                // Attribute table update — decode into per-tile attributes
                ppu.nameTable[2].writeAttrib(exAddr - 960, exram[exAddr])
            }
        }
    }

    // --- Expansion Audio ---
    // Write to a pulse channel register. Layout matches the NES APU square
    // channels ($4000-$4003) except that $5001/$5005 (sweep) has no effect.
    private fun _writePulse(pulse: PulseChannel, reg: Int, value: Int) {
        when (reg) {
            0 -> {
                // $5000/$5004: Duty, length counter halt, constant volume, volume/envelope
                pulse.dutyCycle = (value shr 6) and 0x03
                pulse.lengthHalt = (value and 0x20) != 0
                pulse.constantVolume = (value and 0x10) != 0
                pulse.volume = value and 0x0f
            }

            1 ->
                // $5001/$5005: Sweep — no effect on MMC5 pulse channels
                Unit

            2 ->
                // $5002/$5006: Timer low 8 bits
                pulse.timer = (pulse.timer and 0x700) or value

            3 -> {
                // $5003/$5007: Length counter load, timer high 3 bits
                pulse.timer = (pulse.timer and 0x0ff) or ((value and 0x07) shl 8)
                if (pulse.enabled) {
                    pulse.lengthCounter = nes.papu.getLengthMax(value)
                }
                pulse.envelopeStart = true
                pulse.sequencePos = 0
            }
        }
    }

    // --- Scanline IRQ Counter ---
    // Called by the PPU once per scanline when BG or sprites are enabled.
    // The MMC5 uses an up-counter that resets when entering rendering and
    // increments each scanline, firing an IRQ when it matches the target in $5203.
    override fun clockIrqCounter() {
        val scanline = nes.ppu.scanline

        if (scanline == 20) {
            // Pre-render scanline: entering active rendering.
            // Set in-frame and reset the scanline counter.
            inFrame = true
            irqCounter = 0
            return
        }

        // Visible scanlines (21-260): increment counter and compare.
        irqCounter++
        // $5203 value of 0 is a special case that never matches.
        if (irqTarget != 0 && irqCounter == irqTarget) {
            irqPending = true
            if (irqEnabled) {
                nes.cpu.requestIrq(nes.cpu.IRQ_NORMAL)
            }
        }

        // Clock expansion audio length counters once per scanline.
        // The MMC5 has no frame sequencer; length counters run at a fixed rate
        // tied to scanline timing. We approximate by clocking every 4 scanlines
        // (~240 Hz, matching the APU frame counter quarter-frame rate).
        if ((irqCounter and 3) == 0) {
            _clockPulseLengthCounter(pulse1)
            _clockPulseLengthCounter(pulse2)
        }
    }

    // Decrement a pulse channel's length counter if it's active and not halted.
    private fun _clockPulseLengthCounter(pulse: PulseChannel) {
        if (pulse.enabled && !pulse.lengthHalt && pulse.lengthCounter > 0) {
            pulse.lengthCounter--
        }
    }

    // --- CHR Bank Switching for Sprite/BG Phases ---
    // The MMC5 uses dual CHR bank sets in 8x16 sprite mode ($2000 bit 5 = 1):
    //   - Bank set A ($5120-$5127) is used for sprite pattern fetches
    //   - Bank set B ($5128-$512B) is used for background pattern fetches
    // In 8x8 sprite mode, only bank set A is used for all fetches.
    override fun onBgRender() {
        if (nes.ppu.f_spriteSize == 1 && _chrBankTarget != 1) {
            _applyChrSetB()
            _chrBankTarget = 1
            // Invalidate the PPU's tile cache since we swapped CHR data
            nes.ppu.validTileData = false
        }
    }

    override fun onSpriteRender() {
        if (nes.ppu.f_spriteSize == 1 && _chrBankTarget != 0) {
            _applyChrSetA()
            _chrBankTarget = 0
        }
    }

    // Look up a sprite pattern tile from Set A's VROM banks directly.
    // In 8x16 mode, ptTile may have BG data (Set B) during BG rendering,
    // but sprite 0 hit detection needs Set A data. This method reads from
    // the pre-decoded VROM tile cache without modifying ptTile or calling
    // load*VromBank (which would trigger triggerRendering).
    override fun getSpritePatternTile(index: Int): Tile {
        // In 8x8 mode, ptTile has the correct Set A data already
        if (nes.ppu.f_spriteSize != 1 || nes.rom!!.vromCount == 0) {
            return nes.ppu.ptTile[index]
        }

        // In 8x16 mode, look up the tile from Set A's VROM banks.
        // index 0-255 -> $0000-$0FFF, index 256-511 -> $1000-$1FFF
        val vromCount = nes.rom!!.vromCount
        val vromTile = nes.rom!!.vromTile

        when (chrMode) {
            0 -> {
                // 8K mode: chrBankA[7] selects an 8K page (two 4K banks)
                val bank4kStart = (chrBankA[7] and 0xff) * 2
                val half = if (index >= 256) 1 else 0
                val bank4k = (bank4kStart + half) % vromCount
                return vromTile[bank4k][index - half * 256]
            }

            1 -> {
                // 4K mode: chrBankA[3] -> $0000, chrBankA[7] -> $1000
                val bank4k: Int
                if (index < 256) {
                    bank4k = (chrBankA[3] and 0xff) % vromCount
                } else {
                    bank4k = (chrBankA[7] and 0xff) % vromCount
                }
                return vromTile[bank4k][index % 256]
            }

            2 -> {
                // 2K mode: chrBankA[1]/[3]/[5]/[7] select four 2K chunks (128 tiles each)
                val regIndex = intArrayOf(1, 3, 5, 7)
                val slot = index shr 7 // 0-3
                val tileInSlot = index and 127
                val bank2k = chrBankA[regIndex[slot]] and 0x1ff
                val bank4k = (bank2k / 2) % vromCount
                return vromTile[bank4k][((bank2k % 2) shl 7) + tileInSlot]
            }

            else -> {
                // 1K mode: chrBankA[0-7] each select a 1K chunk (64 tiles each)
                val slot = index shr 6 // 0-7
                val tileInSlot = index and 63
                val bank1k = chrBankA[slot] and 0x3ff
                val bank4k = (bank1k / 4) % vromCount
                return vromTile[bank4k][((bank1k % 4) shl 6) + tileInSlot]
            }
        }
    }

    // ExRAM mode 1 (extended attributes): per-tile CHR bank and palette override.
    // Each byte in ExRAM at $5C00-$5FFF corresponds to a nametable tile position:
    //   Bits 5-0: 4KB CHR bank number (combined with $5130 upper bits)
    //   Bits 7-6: Palette/attribute number for this tile
    // NOTE: The JS source returns the pre-decoded Tile object
    // (vromTile[bank4k][tileIndex]) here, letting each tile come from an
    // arbitrary CHR bank. The Kotlin base class BgTileData only carries an Int
    // tile index, so the nametable tile index is returned and the ExRAM bank
    // selection is lost. REVIEW: BgTileData.tile likely needs to hold a Tile.
    override fun getBgTileData(baseTile: Int, tileIndex: Int, ht: Int, vt: Int): BgTileData? {
        if (exramMode != 1 || nes.rom!!.vromCount == 0) return null

        // ExRAM byte for this nametable tile position
        val exAddr = vt * 32 + ht
        val exByte = exram[exAddr]

        // Bits 5-0 select a 4KB CHR bank (combined with $5130 upper bits);
        // the pre-decoded tile for the selected bank is vromTile[bank4k][tileIndex].
        val chrBank4k = (exByte and 0x3f) or (chrUpperBits shl 6)
        val bank4k = chrBank4k % nes.rom!!.vromCount
        val tile = nes.rom!!.vromTile[bank4k][tileIndex]

        // Bits 7-6 provide the attribute (palette number), replacing the
        // normal attribute table. Shift left by 2 to match PPU palette format.
        val attrib = ((exByte shr 6) and 0x03) shl 2

        return BgTileData(tile, attrib)
    }

    // --- ROM Loading ---
    override fun loadROM() {
        if (!nes.rom!!.valid) {
            throw Error("MMC5: Invalid ROM! Unable to load.")
        }

        // Default PRG banking: last bank at $E000-$FFFF (mode 3 default)
        prgBankReg[4] = 0xff
        _syncPrg()

        // Load CHR-ROM if present
        loadCHRROM()

        // Load Battery RAM (if present)
        loadBatteryRam()

        // Initialize nametable mapping (default to vertical mirroring pattern)
        _syncNametables()

        // Reset interrupt
        nes.cpu.requestIrq(nes.cpu.IRQ_RESET)
    }

    private class PulseChannel {
        var enabled = false
        var dutyCycle = 0 // 2-bit duty
        var lengthHalt = false // envelope loop / length counter halt
        var constantVolume = false
        var volume = 0 // 4-bit volume/envelope
        var timer = 0 // 11-bit timer period
        var timerCounter = 0
        var lengthCounter = 0
        var envelopeCounter = 0
        var envelopeDecay = 15
        var envelopeStart = false
        var sequencePos = 0
    }
}
