package cn.enaium.nes.core.mappers

import cn.enaium.nes.core.NES

// Mapper 240 (Jing Ke Xin Zhuan / Sheng Huo Lie Zhuan PCBs)
// Register at $4020-$5FFF: upper nibble selects 32 KB PRG bank,
// lower nibble selects 8 KB CHR bank.
class Mapper240(nes: NES) : Mapper(nes) {
    override fun write(address: Int, value: Int) {
        if (address < 0x4020 || address > 0x5fff) {
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
