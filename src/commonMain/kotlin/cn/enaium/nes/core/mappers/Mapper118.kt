package cn.enaium.nes.core.mappers

import cn.enaium.nes.core.NES

// TxSROM - MMC3 variant with CHR-controlled nametable mirroring
// Identical to standard MMC3 except: the $A000 mirroring register is bypassed,
// and bit 7 of CHR bank register values controls CIRAM A10 (nametable page
// select) instead of being used for CHR addressing.
class Mapper118(nes: NES) : Mapper4(nes) {
    // Raw CHR register values (R0-R5) — bit 7 is used for nametable control
    var chrRegs = IntArray(6)

    override fun write(address: Int, value: Int) {
        if (address == 0xa000) {
            // The standard MMC3 mirroring register is bypassed on TxSROM.
            // Nametable mirroring is instead controlled by bit 7 of CHR bank values.
            return
        }
        super.write(address, value)
        if (address == 0x8000) {
            // chrAddressSelect may have changed, which affects which CHR registers
            // control which nametables
            updateNametableMirroring()
        }
    }

    override fun executeCommand(cmd: Int, arg: Int) {
        if (cmd <= 5) {
            // CHR bank command: store the raw value, then mask bit 7 before passing
            // to the parent for CHR banking (bit 7 goes to CIRAM A10, not CHR A17)
            chrRegs[cmd] = arg
            super.executeCommand(cmd, arg and 0x7f)
            updateNametableMirroring()
        } else {
            // PRG bank commands pass through unchanged
            super.executeCommand(cmd, arg)
        }
    }

    // Update nametable mirroring based on bit 7 of CHR register values.
    // When chrAddressSelect=0: R0/R1 (2KB banks) are at $0000-$0FFF, so they
    //   control nametables: R0 bit 7 -> NT0+NT1, R1 bit 7 -> NT2+NT3
    // When chrAddressSelect=1: R2-R5 (1KB banks) are at $0000-$0FFF, so they
    //   control individual nametables: R2->NT0, R3->NT1, R4->NT2, R5->NT3
    private fun updateNametableMirroring() {
        val ppu = nes.ppu

        if (chrAddressSelect == 0) {
            val nt01 = (chrRegs[0] shr 7) and 1
            val nt23 = (chrRegs[1] shr 7) and 1
            ppu.ntable1[0] = nt01
            ppu.ntable1[1] = nt01
            ppu.ntable1[2] = nt23
            ppu.ntable1[3] = nt23
        } else {
            ppu.ntable1[0] = (chrRegs[2] shr 7) and 1
            ppu.ntable1[1] = (chrRegs[3] shr 7) and 1
            ppu.ntable1[2] = (chrRegs[4] shr 7) and 1
            ppu.ntable1[3] = (chrRegs[5] shr 7) and 1
        }

        // Update VRAM mirror table to match ntable1 settings
        for (i in 0 until 4) {
            val source = 0x2000 + i * 0x400
            val target = 0x2000 + ppu.ntable1[i] * 0x400
            ppu.defineMirrorRegion(source, target, 0x400)
        }

        // Invalidate the PPU's mirroring cache so setMirroring() won't skip
        // updates if called later
        ppu.currentMirroring = -1
    }

    override fun loadROM() {
        super.loadROM()
        updateNametableMirroring()
    }
}
