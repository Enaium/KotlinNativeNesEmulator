package cn.enaium.nes.core.mappers

import cn.enaium.nes.core.NES
import cn.enaium.nes.core.Tile
import cn.enaium.nes.core.copyArrayElements

// TQROM - MMC3 variant that supports both CHR ROM and CHR RAM simultaneously.
// Identical to standard MMC3 except: bit 6 of the CHR bank register values
// selects between CHR ROM (0) and CHR RAM (1). Bits 0-5 specify the bank
// within the selected chip, allowing up to 64KB CHR ROM and 8KB CHR RAM.
class Mapper119(nes: NES) : Mapper4(nes) {
    // 8KB of CHR RAM (8 x 1KB banks)
    var chrRam = IntArray(8192)

    // Pre-decoded tiles for CHR RAM banks. Each 1KB bank has 64 tiles.
    // These are persistent Tile objects: when a CHR RAM bank is loaded into a
    // PPU slot, ptTile entries point here, and PPU patternWrite() updates them
    // in place on $2007 writes.
    var chrRamTiles = Array(8) { Array(64) { Tile() } }

    // Tracks which CHR RAM bank (0-7) is mapped at each 1KB PPU pattern table
    // slot (0-7 for addresses $0000-$1FFF), or -1 if CHR ROM is there.
    var chrRamSlots = intArrayOf(-1, -1, -1, -1, -1, -1, -1, -1)

    override fun executeCommand(cmd: Int, arg: Int) {
        when (cmd) {
            Mapper4.CMD_SEL_2_1K_VROM_0000 -> {
                // Select 2 consecutive 1KB banks at $0000/$0400 (or $1000/$1400)
                val base = if (chrAddressSelect == 0) 0x0000 else 0x1000
                if ((arg and 0x40) != 0) {
                    val bank = arg and 0x06 // 2KB-aligned within CHR RAM
                    load1kChrRamBank(bank, base)
                    load1kChrRamBank(bank + 1, base + 0x0400)
                } else {
                    val bank = arg and 0x3f
                    saveChrRamSlot(base)
                    saveChrRamSlot(base + 0x0400)
                    chrRamSlots[base shr 10] = -1
                    chrRamSlots[(base shr 10) + 1] = -1
                    load1kVromBank(bank, base)
                    load1kVromBank(bank + 1, base + 0x0400)
                }
            }

            Mapper4.CMD_SEL_2_1K_VROM_0800 -> {
                val base = if (chrAddressSelect == 0) 0x0800 else 0x1800
                if ((arg and 0x40) != 0) {
                    val bank = arg and 0x06
                    load1kChrRamBank(bank, base)
                    load1kChrRamBank(bank + 1, base + 0x0400)
                } else {
                    val bank = arg and 0x3f
                    saveChrRamSlot(base)
                    saveChrRamSlot(base + 0x0400)
                    chrRamSlots[base shr 10] = -1
                    chrRamSlots[(base shr 10) + 1] = -1
                    load1kVromBank(bank, base)
                    load1kVromBank(bank + 1, base + 0x0400)
                }
            }

            Mapper4.CMD_SEL_1K_VROM_1000 -> {
                val base = if (chrAddressSelect == 0) 0x1000 else 0x0000
                if ((arg and 0x40) != 0) {
                    load1kChrRamBank(arg and 0x07, base)
                } else {
                    saveChrRamSlot(base)
                    chrRamSlots[base shr 10] = -1
                    load1kVromBank(arg and 0x3f, base)
                }
            }

            Mapper4.CMD_SEL_1K_VROM_1400 -> {
                val base = if (chrAddressSelect == 0) 0x1400 else 0x0400
                if ((arg and 0x40) != 0) {
                    load1kChrRamBank(arg and 0x07, base)
                } else {
                    saveChrRamSlot(base)
                    chrRamSlots[base shr 10] = -1
                    load1kVromBank(arg and 0x3f, base)
                }
            }

            Mapper4.CMD_SEL_1K_VROM_1800 -> {
                val base = if (chrAddressSelect == 0) 0x1800 else 0x0800
                if ((arg and 0x40) != 0) {
                    load1kChrRamBank(arg and 0x07, base)
                } else {
                    saveChrRamSlot(base)
                    chrRamSlots[base shr 10] = -1
                    load1kVromBank(arg and 0x3f, base)
                }
            }

            Mapper4.CMD_SEL_1K_VROM_1C00 -> {
                val base = if (chrAddressSelect == 0) 0x1c00 else 0x0c00
                if ((arg and 0x40) != 0) {
                    load1kChrRamBank(arg and 0x07, base)
                } else {
                    saveChrRamSlot(base)
                    chrRamSlots[base shr 10] = -1
                    load1kVromBank(arg and 0x3f, base)
                }
            }

            else ->
                // PRG commands (6, 7) pass through to MMC3
                super.executeCommand(cmd, arg)
        }
    }

    // Save the current vramMem content of a 1KB PPU slot back to chrRam.
    // This must be called before overwriting a slot that has CHR RAM mapped,
    // so that any PPU $2007 writes to that region are preserved.
    private fun saveChrRamSlot(address: Int) {
        val slot = address shr 10
        val bank = chrRamSlots[slot]
        if (bank == -1) return
        copyArrayElements(
            nes.ppu.vramMem,
            slot shl 10,
            chrRam,
            bank * 1024,
            1024,
        )
    }

    // Load a 1KB CHR RAM bank into the PPU pattern table at the given address.
    private fun load1kChrRamBank(bankIn: Int, address: Int) {
        nes.ppu.triggerRendering()
        var bank = bankIn and 0x07

        // Save the old CHR RAM content if this slot had a different bank mapped
        saveChrRamSlot(address)

        val slot = address shr 10
        chrRamSlots[slot] = bank

        // Copy CHR RAM data into PPU VRAM
        val srcOffset = bank * 1024
        copyArrayElements(
            chrRam,
            srcOffset,
            nes.ppu.vramMem,
            address,
            1024,
        )

        // Rebuild tiles from CHR RAM data and install them in ppuTile
        rebuildChrRamTiles(bank)
        val baseIndex = address shr 4
        for (i in 0 until 64) {
            nes.ppu.ptTile[baseIndex + i] = chrRamTiles[bank][i]
        }
    }

    // Rebuild the pre-decoded Tile objects for a CHR RAM bank from raw bytes.
    private fun rebuildChrRamTiles(bank: Int) {
        val base = bank * 1024
        for (i in 0 until 1024) {
            val tileIndex = i shr 4
            val leftOver = i % 16
            if (leftOver < 8) {
                chrRamTiles[bank][tileIndex].setScanline(
                    leftOver,
                    chrRam[base + i],
                    chrRam[base + i + 8],
                )
            } else {
                chrRamTiles[bank][tileIndex].setScanline(
                    leftOver - 8,
                    chrRam[base + i - 8],
                    chrRam[base + i],
                )
            }
        }
    }

    // Allow PPU writes to pattern table addresses that are mapped to CHR RAM.
    override fun canWriteChr(address: Int): Boolean {
        if (address >= 0x2000) return false
        return chrRamSlots[address shr 10] != -1
    }
}
