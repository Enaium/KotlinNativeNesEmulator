package cn.enaium.nes.core.mappers

import cn.enaium.nes.core.NES

// MMC3 / TxROM (TSROM, TLSROM, TQROM, etc.)
// Fine-grained bank switching: two 8 KB switchable PRG-ROM banks, two 2 KB +
// four 1 KB CHR banks. Provides a scanline-counting IRQ for split-screen
// effects and software-switchable H/V nametable mirroring.
open class Mapper4(nes: NES) : Mapper(nes) {
    var command = 0
    var prgAddressSelect = 0
    var chrAddressSelect = 0
    var pageNumber = 0
    var irqCounter = 0
    var irqLatchValue = 0
    var irqEnable = 0
    var prgAddressChanged = false

    override fun write(address: Int, value: Int) {
        // Writes to addresses other than MMC registers are handled by Mapper.
        if (address < 0x8000) {
            super.write(address, value)
            return
        }

        when (address and 0xe001) {
            0x8000 -> {
                // Command/Address Select register
                command = value and 7
                val tmp = (value shr 6) and 1
                if (tmp != prgAddressSelect) {
                    prgAddressChanged = true
                }
                prgAddressSelect = tmp
                chrAddressSelect = (value shr 7) and 1
            }

            0x8001 ->
                // Page number for command
                executeCommand(command, value)

            0xa000 -> {
                // Mirroring select
                if ((value and 1) != 0) {
                    nes.ppu.setMirroring(nes.rom!!.HORIZONTAL_MIRRORING)
                } else {
                    nes.ppu.setMirroring(nes.rom!!.VERTICAL_MIRRORING)
                }
            }

            0xa001 -> {
                // SaveRAM Toggle
                // TODO
                //nes.getRom().setSaveState((value&1)!=0);
            }

            0xc000 ->
                // IRQ Counter register
                irqCounter = value

            0xc001 ->
                // IRQ Latch register
                irqLatchValue = value

            0xe000 ->
                // IRQ Control Reg 0 (disable)
                //irqCounter = irqLatchValue;
                irqEnable = 0

            0xe001 ->
                // IRQ Control Reg 1 (enable)
                irqEnable = 1

            // No default needed: the 0xE001 mask maps every address >= $8000
            // to one of the eight cases above.
        }
    }

    open fun executeCommand(cmd: Int, arg: Int) {
        when (cmd) {
            Mapper4.CMD_SEL_2_1K_VROM_0000 ->
                // Select 2 1KB VROM pages at 0x0000:
                if (chrAddressSelect == 0) {
                    load1kVromBank(arg, 0x0000)
                    load1kVromBank(arg + 1, 0x0400)
                } else {
                    load1kVromBank(arg, 0x1000)
                    load1kVromBank(arg + 1, 0x1400)
                }

            Mapper4.CMD_SEL_2_1K_VROM_0800 ->
                // Select 2 1KB VROM pages at 0x0800:
                if (chrAddressSelect == 0) {
                    load1kVromBank(arg, 0x0800)
                    load1kVromBank(arg + 1, 0x0c00)
                } else {
                    load1kVromBank(arg, 0x1800)
                    load1kVromBank(arg + 1, 0x1c00)
                }

            Mapper4.CMD_SEL_1K_VROM_1000 ->
                // Select 1K VROM Page at 0x1000:
                if (chrAddressSelect == 0) {
                    load1kVromBank(arg, 0x1000)
                } else {
                    load1kVromBank(arg, 0x0000)
                }

            Mapper4.CMD_SEL_1K_VROM_1400 ->
                // Select 1K VROM Page at 0x1400:
                if (chrAddressSelect == 0) {
                    load1kVromBank(arg, 0x1400)
                } else {
                    load1kVromBank(arg, 0x0400)
                }

            Mapper4.CMD_SEL_1K_VROM_1800 ->
                // Select 1K VROM Page at 0x1800:
                if (chrAddressSelect == 0) {
                    load1kVromBank(arg, 0x1800)
                } else {
                    load1kVromBank(arg, 0x0800)
                }

            Mapper4.CMD_SEL_1K_VROM_1C00 ->
                // Select 1K VROM Page at 0x1C00:
                if (chrAddressSelect == 0) {
                    load1kVromBank(arg, 0x1c00)
                } else {
                    load1kVromBank(arg, 0x0c00)
                }

            Mapper4.CMD_SEL_ROM_PAGE1 -> {
                if (prgAddressChanged) {
                    // Load the two hardwired banks:
                    if (prgAddressSelect == 0) {
                        load8kRomBank((nes.rom!!.romCount - 1) * 2, 0xc000)
                    } else {
                        load8kRomBank((nes.rom!!.romCount - 1) * 2, 0x8000)
                    }
                    prgAddressChanged = false
                }

                // Select first switchable ROM page:
                if (prgAddressSelect == 0) {
                    load8kRomBank(arg, 0x8000)
                } else {
                    load8kRomBank(arg, 0xc000)
                }
            }

            Mapper4.CMD_SEL_ROM_PAGE2 -> {
                // Select second switchable ROM page:
                load8kRomBank(arg, 0xa000)

                // hardwire appropriate bank:
                if (prgAddressChanged) {
                    // Load the two hardwired banks:
                    if (prgAddressSelect == 0) {
                        load8kRomBank((nes.rom!!.romCount - 1) * 2, 0xc000)
                    } else {
                        load8kRomBank((nes.rom!!.romCount - 1) * 2, 0x8000)
                    }
                    prgAddressChanged = false
                }
            }
        }
    }

    override fun loadROM() {
        if (!nes.rom!!.valid) {
            throw Error("MMC3: Invalid ROM! Unable to load.")
        }

        // Load hardwired PRG banks (0xC000 and 0xE000):
        load8kRomBank((nes.rom!!.romCount - 1) * 2, 0xc000)
        load8kRomBank((nes.rom!!.romCount - 1) * 2 + 1, 0xe000)

        // Load swappable PRG banks (0x8000 and 0xA000):
        load8kRomBank(0, 0x8000)
        load8kRomBank(1, 0xa000)

        // Load CHR-ROM:
        loadCHRROM()

        // Load Battery RAM (if present):
        loadBatteryRam()

        // Do Reset-Interrupt:
        nes.cpu.requestIrq(nes.cpu.IRQ_RESET)
    }

    override fun clockIrqCounter() {
        if (irqEnable == 1) {
            irqCounter--
            if (irqCounter < 0) {
                // Trigger IRQ:
                //nes.getCpu().doIrq();
                nes.cpu.requestIrq(nes.cpu.IRQ_NORMAL)
                irqCounter = irqLatchValue
            }
        }
    }

    companion object {
        const val CMD_SEL_2_1K_VROM_0000 = 0
        const val CMD_SEL_2_1K_VROM_0800 = 1
        const val CMD_SEL_1K_VROM_1000 = 2
        const val CMD_SEL_1K_VROM_1400 = 3
        const val CMD_SEL_1K_VROM_1800 = 4
        const val CMD_SEL_1K_VROM_1C00 = 5
        const val CMD_SEL_ROM_PAGE1 = 6
        const val CMD_SEL_ROM_PAGE2 = 7
    }
}
