package cn.enaium.nes.core.mappers

import cn.enaium.nes.core.NES

// UxROM (NES-UNROM, NES-UOROM)
// 16 KB switchable PRG-ROM bank at $8000, last 16 KB bank fixed at $C000.
// Uses CHR-RAM (no CHR-ROM bank switching).
class Mapper2(nes: NES) : Mapper(nes) {
    override fun write(address: Int, value: Int) {
        // Writes to addresses other than MMC registers are handled by Mapper.
        if (address < 0x8000) {
            super.write(address, value)
            return
        } else {
            // This is a ROM bank select command.
            // Swap in the given ROM bank at 0x8000:
            loadRomBank(value, 0x8000)
        }
    }

    override fun loadROM() {
        if (!nes.rom!!.valid) {
            throw Error("UNROM: Invalid ROM! Unable to load.")
        }

        // Load PRG-ROM:
        loadRomBank(0, 0x8000)
        loadRomBank(nes.rom!!.romCount - 1, 0xc000)

        // Load CHR-ROM:
        loadCHRROM()

        // Do Reset-Interrupt:
        nes.cpu.requestIrq(nes.cpu.IRQ_RESET)
    }
}
