package cn.enaium.nes.core.mappers

import cn.enaium.nes.core.NES

// GxROM (NES-GNROM, NES-MHROM)
// Discrete mapper with 32 KB PRG and 8 KB CHR bank switching via a single
// register at $8000-$FFFF. Bits 4-5 select PRG bank, bits 0-1 select CHR bank.
class Mapper66(nes: NES) : Mapper(nes) {
    override fun write(address: Int, value: Int) {
        if (address < 0x8000) {
            super.write(address, value)
            return
        } else {
            // Swap in the given PRG-ROM bank at 0x8000:
            load32kRomBank((value shr 4) and 3, 0x8000)

            // Swap in the given VROM bank at 0x0000:
            load8kVromBank((value and 3) * 2, 0x0000)
        }
    }
}
