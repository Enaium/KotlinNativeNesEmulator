package cn.enaium.nes.core.mappers

import cn.enaium.nes.core.NES

// Color Dreams (unlicensed discrete mapper)
// Single register at $8000-$FFFF: bits 0-1 select 32 KB PRG bank,
// bits 4-7 select 8 KB CHR bank.
class Mapper11(nes: NES) : Mapper(nes) {
    override fun write(address: Int, value: Int) {
        if (address < 0x8000) {
            super.write(address, value)
            return
        } else {
            // Swap in the given PRG-ROM bank:
            val prgbank1 = ((value and 0xf) * 2) % nes.rom!!.romCount
            val prgbank2 = ((value and 0xf) * 2 + 1) % nes.rom!!.romCount

            loadRomBank(prgbank1, 0x8000)
            loadRomBank(prgbank2, 0xc000)

            if (nes.rom!!.vromCount > 0) {
                // Swap in the given VROM bank at 0x0000:
                val bank = ((value shr 4) * 2) % nes.rom!!.vromCount
                loadVromBank(bank, 0x0000)
                loadVromBank(bank + 1, 0x1000)
            }
        }
    }
}
