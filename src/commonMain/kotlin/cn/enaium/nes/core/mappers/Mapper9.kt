package cn.enaium.nes.core.mappers

import cn.enaium.nes.core.NES

// MMC2 (PNROM / PEEOROM)
// Tile-triggered CHR bank switching: two independent 4 KB CHR latches
// automatically swap between two banks when the PPU fetches specific tiles
// ($FD/$FE). PRG: 8 KB switchable at $8000, three 8 KB fixed banks at
// $A000-$FFFF.
class Mapper9(nes: NES) : Mapper(nes) {
    // PRG bank register ($A000-$AFFF): selects 8 KB bank at $8000
    var prgBank = 0

    // CHR bank registers: each pattern table half has two possible banks,
    // selected by the corresponding latch state ($FD or $FE).
    var chrBankFD0 = 0 // $B000: CHR bank for $0000 when latch0 = $FD
    var chrBankFE0 = 0 // $C000: CHR bank for $0000 when latch0 = $FE
    var chrBankFD1 = 0 // $D000: CHR bank for $1000 when latch1 = $FD
    var chrBankFE1 = 0 // $E000: CHR bank for $1000 when latch1 = $FE

    // Latch states: $FD or $FE, one per pattern table half.
    // Both initialize to $FE on power-up.
    var latch0 = 0xfe
    var latch1 = 0xfe

    override fun write(address: Int, value: Int) {
        if (address < 0x8000) {
            super.write(address, value)
            return
        }

        // Only the top nibble matters for register selection
        when (address and 0xf000) {
            0xa000 ->
                // $A000-$AFFF: PRG bank select (bits 3-0 select 8 KB bank at $8000)
                {
                    prgBank = value and 0x0f
                    load8kRomBank(prgBank, 0x8000)
                }

            0xb000 ->
                // $B000-$BFFF: CHR bank for $0000 when latch0 = $FD
                {
                    chrBankFD0 = value and 0x1f
                    _updateChr0()
                }

            0xc000 ->
                // $C000-$CFFF: CHR bank for $0000 when latch0 = $FE
                {
                    chrBankFE0 = value and 0x1f
                    _updateChr0()
                }

            0xd000 ->
                // $D000-$DFFF: CHR bank for $1000 when latch1 = $FD
                {
                    chrBankFD1 = value and 0x1f
                    _updateChr1()
                }

            0xe000 ->
                // $E000-$EFFF: CHR bank for $1000 when latch1 = $FE
                {
                    chrBankFE1 = value and 0x1f
                    _updateChr1()
                }

            0xf000 ->
                // $F000-$FFFF: Mirroring (bit 0: 0=vertical, 1=horizontal)
                if ((value and 0x01) != 0) {
                    nes.ppu.setMirroring(nes.rom!!.HORIZONTAL_MIRRORING)
                } else {
                    nes.ppu.setMirroring(nes.rom!!.VERTICAL_MIRRORING)
                }
        }
    }

    // Load the correct CHR bank into $0000 based on latch0 state.
    private fun _updateChr0() {
        val bank = if (latch0 == 0xfd) chrBankFD0 else chrBankFE0
        loadVromBank(bank, 0x0000)
    }

    // Load the correct CHR bank into $1000 based on latch1 state.
    private fun _updateChr1() {
        val bank = if (latch1 == 0xfd) chrBankFD1 else chrBankFE1
        loadVromBank(bank, 0x1000)
    }

    // Called by the PPU when pattern table memory is accessed.
    // Updates the CHR latches based on the tile being fetched.
    // The latch switches AFTER the data has been read, so the tile at $FD/$FE
    // itself is rendered with the old bank.
    override fun latchAccess(address: Int) {
        // Only reload CHR banks when the latch state actually changes.
        if (address == 0x0fd8) {
            // Latch 0 triggers on exactly $0FD8
            if (latch0 != 0xfd) {
                latch0 = 0xfd
                _updateChr0()
            }
        } else if (address == 0x0fe8) {
            // Latch 0 triggers on exactly $0FE8
            if (latch0 != 0xfe) {
                latch0 = 0xfe
                _updateChr0()
            }
        } else if (address >= 0x1fd8 && address <= 0x1fdf) {
            // Latch 1 triggers on $1FD8-$1FDF
            if (latch1 != 0xfd) {
                latch1 = 0xfd
                _updateChr1()
            }
        } else if (address >= 0x1fe8 && address <= 0x1fef) {
            // Latch 1 triggers on $1FE8-$1FEF
            if (latch1 != 0xfe) {
                latch1 = 0xfe
                _updateChr1()
            }
        }
    }

    override fun loadROM() {
        if (!nes.rom!!.valid) {
            throw Error("MMC2: Invalid ROM! Unable to load.")
        }

        // Load first switchable 8 KB PRG bank at $8000
        load8kRomBank(0, 0x8000)

        // Load the last three 8 KB PRG banks fixed at $A000-$FFFF
        val lastBank8k = (nes.rom!!.romCount - 1) * 2 + 1
        load8kRomBank(lastBank8k - 2, 0xa000)
        load8kRomBank(lastBank8k - 1, 0xc000)
        load8kRomBank(lastBank8k, 0xe000)

        // Load CHR-ROM
        loadCHRROM()

        // Load Battery RAM (if present)
        loadBatteryRam()

        nes.cpu.requestIrq(nes.cpu.IRQ_RESET)
    }
}
