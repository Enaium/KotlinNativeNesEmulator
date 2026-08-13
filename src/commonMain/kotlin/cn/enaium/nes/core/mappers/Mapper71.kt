package cn.enaium.nes.core.mappers

import cn.enaium.nes.core.NES

// Camerica/Codemasters mapper (BF9093/BF9097)
// Largely a clone of UxROM with optional 1-screen mirroring control.
class Mapper71(nes: NES) : Mapper(nes) {
    override fun write(address: Int, value: Int) {
        if (address < 0x8000) {
            super.write(address, value)
            return
        }

        if (address >= 0x9000 && address < 0xa000) {
            // $9000-$9FFF: 1-screen mirroring control (Fire Hawk / BF9097 variant)
            // Bit 4 selects which CIRAM nametable to fill all four screen slots
            if ((value and 0x10) != 0) {
                nes.ppu.setMirroring(nes.rom!!.SINGLESCREEN_MIRRORING2)
            } else {
                nes.ppu.setMirroring(nes.rom!!.SINGLESCREEN_MIRRORING)
            }
        } else if (address >= 0xc000) {
            // $C000-$FFFF: PRG bank select (bits 3-0 select 16 KiB bank at $8000)
            loadRomBank(value and 0x0f, 0x8000)
        }
    }

    override fun loadROM() {
        if (!nes.rom!!.valid) {
            throw Error("Mapper 71: Invalid ROM! Unable to load.")
        }

        // Load first PRG bank at $8000, last at $C000 (fixed)
        loadRomBank(0, 0x8000)
        loadRomBank(nes.rom!!.romCount - 1, 0xc000)

        // Load CHR-ROM (usually CHR-RAM, so this may be a no-op)
        loadCHRROM()

        nes.cpu.requestIrq(nes.cpu.IRQ_RESET)
    }
}
