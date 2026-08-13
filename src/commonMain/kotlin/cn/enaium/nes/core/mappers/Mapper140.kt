package cn.enaium.nes.core.mappers

import cn.enaium.nes.core.NES

// Jaleco JF-11 / JF-14
// Similar to GxROM (mapper 66) but register is at $6000-$7FFF instead of $8000+,
// which means it cannot coexist with SRAM. Bits 4-5 select 32 KB PRG bank,
// bits 0-3 select 8 KB CHR bank.
class Mapper140(nes: NES) : Mapper(nes) {
    override fun write(address: Int, value: Int) {
        if (address < 0x6000 || address > 0x7fff) {
            super.write(address, value)
            return
        } else {
            // Swap in the given PRG-ROM bank at 0x8000:
            load32kRomBank((value shr 4) and 3, 0x8000)

            // Swap in the given VROM bank at 0x0000:
            load8kVromBank((value and 0xf) * 2, 0x0000)
        }
    }
}
