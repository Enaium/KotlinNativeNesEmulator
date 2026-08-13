package cn.enaium.nes.core.mappers

import cn.enaium.nes.core.NES

// UNROM (AND-logic variant, HVC-UNROM)
// Inverted UxROM: first 16 KB bank fixed at $8000, switchable bank at $C000.
// Standard UxROM fixes the last bank; this variant uses AND logic instead of
// OR logic on the bank select lines, producing the opposite fixed-bank behavior.
class Mapper180(nes: NES) : Mapper(nes) {
    override fun write(address: Int, value: Int) {
        // Writes to addresses other than MMC registers are handled by Mapper.
        if (address < 0x8000) {
            super.write(address, value)
            return
        } else {
            // This is a ROM bank select command.
            // Swap in the given ROM bank at 0xc000:
            loadRomBank(value, 0xc000)
        }
    }

    override fun loadROM() {
        if (!nes.rom!!.valid) {
            throw Error("Mapper 180: Invalid ROM! Unable to load.")
        }

        // Load PRG-ROM:
        loadRomBank(0, 0x8000)
        loadRomBank(0, 0xc000)

        // Load CHR-ROM:
        loadCHRROM()

        // Do Reset-Interrupt:
        nes.cpu.requestIrq(nes.cpu.IRQ_RESET)
    }
}
