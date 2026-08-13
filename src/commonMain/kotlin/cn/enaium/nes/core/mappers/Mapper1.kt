package cn.enaium.nes.core.mappers

import cn.enaium.nes.core.NES

// MMC1 / SxROM (SKROM, SLROM, SNROM, etc.)
// Writes use a 5-bit serial shift register (5 consecutive writes to load a value).
// Provides switchable 16 KB PRG-ROM banks, 4 KB or 8 KB CHR banks,
// and software-controlled nametable mirroring.
class Mapper1(nes: NES) : Mapper(nes) {
    // 5-bit buffer:
    var regBuffer = 0
    var regBufferCounter = 0

    // Register 0:
    var mirroring = 0
    var oneScreenMirroring = 0
    var prgSwitchingArea = 1
    var prgSwitchingSize = 1
    var vromSwitchingSize = 0

    // Register 1:
    var romSelectionReg0 = 0

    // Register 2:
    var romSelectionReg1 = 0

    // Register 3:
    var romBankSelect = 0

    override fun write(address: Int, value: Int) {
        // Writes to addresses other than MMC registers are handled by Mapper.
        if (address < 0x8000) {
            super.write(address, value)
            return
        }

        // See what should be done with the written value:
        if ((value and 128) != 0) {
            // Reset buffering:
            regBufferCounter = 0
            regBuffer = 0

            // Reset register:
            if (getRegNumber(address) == 0) {
                prgSwitchingArea = 1
                prgSwitchingSize = 1
            }
        } else {
            // Continue buffering:
            regBuffer =
                (regBuffer and (0xff - (1 shl regBufferCounter))) or
                    ((value and 1) shl regBufferCounter)
            regBufferCounter++

            if (regBufferCounter == 5) {
                // Use the buffered value:
                setReg(getRegNumber(address), regBuffer)

                // Reset buffer:
                regBuffer = 0
                regBufferCounter = 0
            }
        }
    }

    private fun setReg(reg: Int, value: Int) {
        when (reg) {
            0 -> {
                // Mirroring:
                val tmp = value and 3
                if (tmp != mirroring) {
                    // Set mirroring:
                    mirroring = tmp
                    if ((mirroring and 2) == 0) {
                        // SingleScreen mirroring overrides the other setting:
                        nes.ppu.setMirroring(nes.rom!!.SINGLESCREEN_MIRRORING)
                    } else if ((mirroring and 1) != 0) {
                        // Not overridden by SingleScreen mirroring.
                        nes.ppu.setMirroring(nes.rom!!.HORIZONTAL_MIRRORING)
                    } else {
                        nes.ppu.setMirroring(nes.rom!!.VERTICAL_MIRRORING)
                    }
                }

                // PRG Switching Area;
                prgSwitchingArea = (value shr 2) and 1

                // PRG Switching Size:
                prgSwitchingSize = (value shr 3) and 1

                // VROM Switching Size:
                vromSwitchingSize = (value shr 4) and 1
            }

            1 -> {
                // ROM selection:
                romSelectionReg0 = (value shr 4) and 1

                // Check whether the cart has VROM:
                if (nes.rom!!.vromCount > 0) {
                    // Select VROM bank at 0x0000:
                    if (vromSwitchingSize == 0) {
                        // Swap 8kB VROM:
                        if (romSelectionReg0 == 0) {
                            load8kVromBank(value and 0xf, 0x0000)
                        } else {
                            load8kVromBank(nes.rom!!.vromCount / 2 + (value and 0xf), 0x0000)
                        }
                    } else {
                        // Swap 4kB VROM:
                        if (romSelectionReg0 == 0) {
                            loadVromBank(value and 0xf, 0x0000)
                        } else {
                            loadVromBank(nes.rom!!.vromCount / 2 + (value and 0xf), 0x0000)
                        }
                    }
                }
            }

            2 -> {
                // ROM selection:
                romSelectionReg1 = (value shr 4) and 1

                // Check whether the cart has VROM:
                if (nes.rom!!.vromCount > 0) {
                    // Select VROM bank at 0x1000:
                    if (vromSwitchingSize == 1) {
                        // Swap 4kB of VROM:
                        if (romSelectionReg1 == 0) {
                            loadVromBank(value and 0xf, 0x1000)
                        } else {
                            loadVromBank(nes.rom!!.vromCount / 2 + (value and 0xf), 0x1000)
                        }
                    }
                }
            }

            else -> {
                // Select ROM bank:
                var baseBank = 0

                if (nes.rom!!.romCount >= 32) {
                    // 1024 kB cart
                    if (vromSwitchingSize == 0) {
                        if (romSelectionReg0 == 1) {
                            baseBank = 16
                        }
                    } else {
                        baseBank = (romSelectionReg0 or (romSelectionReg1 shl 1)) shl 3
                    }
                } else if (nes.rom!!.romCount >= 16) {
                    // 512 kB cart
                    if (romSelectionReg0 == 1) {
                        baseBank = 8
                    }
                }

                val bank: Int
                if (prgSwitchingSize == 0) {
                    // 32kB
                    bank = baseBank + (value and 0xf)
                    load32kRomBank(bank, 0x8000)
                } else {
                    // 16kB
                    bank = baseBank * 2 + (value and 0xf)
                    if (prgSwitchingArea == 0) {
                        loadRomBank(bank, 0xc000)
                    } else {
                        loadRomBank(bank, 0x8000)
                    }
                }
            }
        }
    }

    // Returns the register number from the address written to:
    private fun getRegNumber(address: Int): Int {
        return when {
            address >= 0x8000 && address <= 0x9fff -> 0
            address >= 0xa000 && address <= 0xbfff -> 1
            address >= 0xc000 && address <= 0xdfff -> 2
            else -> 3
        }
    }

    override fun loadROM() {
        if (!nes.rom!!.valid) {
            throw Error("MMC1: Invalid ROM! Unable to load.")
        }

        // Load PRG-ROM:
        loadRomBank(0, 0x8000) // First ROM bank..
        loadRomBank(nes.rom!!.romCount - 1, 0xc000) // ..and last ROM bank.

        // Load CHR-ROM:
        loadCHRROM()

        // Load Battery RAM (if present):
        loadBatteryRam()

        // Do Reset-Interrupt:
        nes.cpu.requestIrq(nes.cpu.IRQ_RESET)
    }

    fun switchLowHighPrgRom(oldSetting: Int) {
        // not yet.
    }

    fun switch16to32() {
        // not yet.
    }

    fun switch32to16() {
        // not yet.
    }
}
