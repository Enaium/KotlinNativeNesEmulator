package cn.enaium.nes.core.mappers

import cn.enaium.nes.core.NES

// PCI556 (UNL-PCI556) - Bit Corp
// Nearly identical to GxROM (mapper 66) but the register is at $7000-$7FFF.
// Bits 0-1 select 32 KB PRG bank, bits 2-3 select 8 KB CHR bank.
class Mapper38(nes: NES) : Mapper(nes) {
    override fun write(address: Int, value: Int) {
        if (address < 0x7000 || address > 0x7fff) {
            super.write(address, value)
            return
        } else {
            // Swap in the given PRG-ROM bank at 0x8000:
            load32kRomBank(value and 3, 0x8000)

            // Swap in the given VROM bank at 0x0000:
            load8kVromBank(((value shr 2) and 3) * 2, 0x0000)
        }
    }
}
