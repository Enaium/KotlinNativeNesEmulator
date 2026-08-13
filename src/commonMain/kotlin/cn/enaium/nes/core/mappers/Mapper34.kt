package cn.enaium.nes.core.mappers

import cn.enaium.nes.core.NES

// BNROM (NES-BNROM)
// Simple 32 KB PRG-ROM bank switching via writes to $8000-$FFFF.
// No CHR bank switching (uses CHR-RAM or fixed CHR-ROM).
// Note: iNES mapper 34 also covers NINA-001; this implementation handles BNROM only.
class Mapper34(nes: NES) : Mapper(nes) {
    override fun write(address: Int, value: Int) {
        if (address < 0x8000) {
            super.write(address, value)
            return
        } else {
            load32kRomBank(value, 0x8000)
        }
    }
}
