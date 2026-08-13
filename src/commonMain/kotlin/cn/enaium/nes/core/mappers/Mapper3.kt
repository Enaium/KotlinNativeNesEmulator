package cn.enaium.nes.core.mappers

import cn.enaium.nes.core.NES

// CNROM
// Fixed PRG-ROM (up to 32 KB), with switchable 8 KB CHR-ROM banks.
class Mapper3(nes: NES) : Mapper(nes) {
    override fun write(address: Int, value: Int) {
        // Writes to addresses other than MMC registers are handled by Mapper.
        if (address < 0x8000) {
            super.write(address, value)
            return
        } else {
            // This is a VROM bank select command.
            // Swap in the given VROM bank at 0x0000:
            load8kVromBank(value * 2, 0x0000)
        }
    }
}
