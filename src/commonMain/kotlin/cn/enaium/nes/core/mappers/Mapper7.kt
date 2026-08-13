package cn.enaium.nes.core.mappers

import cn.enaium.nes.core.NES

// AxROM (NES-AMROM, NES-ANROM, NES-AOROM)
// 32 KB switchable PRG-ROM bank (bits 0-2) with single-screen nametable
// mirroring select (bit 4). Uses CHR-RAM, no CHR bank switching.
class Mapper7(nes: NES) : Mapper(nes) {
    override fun write(address: Int, value: Int) {
        // Writes to addresses other than MMC registers are handled by Mapper.
        if (address < 0x8000) {
            super.write(address, value)
        } else {
            load32kRomBank(value and 0x7, 0x8000)
            if ((value and 0x10) != 0) {
                nes.ppu.setMirroring(nes.rom!!.SINGLESCREEN_MIRRORING2)
            } else {
                nes.ppu.setMirroring(nes.rom!!.SINGLESCREEN_MIRRORING)
            }
        }
    }

    override fun loadROM() {
        if (!nes.rom!!.valid) {
            throw Error("AOROM: Invalid ROM! Unable to load.")
        }

        // Load PRG-ROM:
        loadPRGROM()

        // Load CHR-ROM:
        loadCHRROM()

        // Do Reset-Interrupt:
        nes.cpu.requestIrq(nes.cpu.IRQ_RESET)
    }
}
