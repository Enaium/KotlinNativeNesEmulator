package cn.enaium.nes.core.mappers

import cn.enaium.nes.core.NES

// NINA-03/NINA-06 (American Video Entertainment)
// GxROM-like mapper with the register in the expansion area ($4100-$5FFF)
// instead of the cartridge space. Address decode: (addr & $E100) == $4100.
// Register format: .... PCCC
//   P (bit 3): selects 32 KB PRG bank
//   CCC (bits 0-2): selects 8 KB CHR bank
class Mapper79(nes: NES) : Mapper(nes) {
    override fun write(address: Int, value: Int) {
        // The NINA register is active at addresses where (address & $E100) == $4100.
        // This covers $4100-$41FF, $4300-$43FF, $4500-$45FF, ... $5F00-$5FFF.
        if ((address and 0xe100) == 0x4100) {
            // Swap 32 KB PRG bank based on bit 3
            load32kRomBank((value shr 3) and 1, 0x8000)

            // Swap 8 KB CHR bank based on bits 0-2
            load8kVromBank((value and 7) * 2, 0x0000)
        }

        super.write(address, value)
    }
}
