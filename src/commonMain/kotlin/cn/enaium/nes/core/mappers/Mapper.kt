package cn.enaium.nes.core.mappers

import cn.enaium.nes.core.NES
import cn.enaium.nes.core.Tile
import cn.enaium.nes.core.copyArrayElements
import cn.enaium.nes.core.papu.ChannelDM

// NROM - the simplest NES cartridge board (NES-NROM-128/NROM-256)
// Used by games like Super Mario Bros., Donkey Kong, Excitebike.
// No bank switching at all: 16 or 32 KB PRG-ROM, 8 KB CHR-ROM, fixed mirroring.
// See https://www.nesdev.org/wiki/NROM
// This class also serves as the base class for all other mappers.
open class Mapper(val nes: NES) {

    var joy1StrobeState = 0
    var joy2StrobeState = 0
    var joypadLastWrite = 0
    // The effective OUT0 value visible to the controller shift register.
    // On the 2A03, OUT0-OUT2 are output latches that only update on APU
    // clock edges (every 2 CPU cycles). Writes to $4016 on "get" cycles
    // (odd CPU cycle count) update the internal register but NOT the output
    // latch until the next APU clock. This distinction matters for RMW
    // instructions like DEC $4016 that produce a 1-cycle strobe pulse:
    // the dummy write and final write land on consecutive CPU cycles, and
    // whether the pulse is visible depends on APU clock alignment.
    // See https://www.nesdev.org/wiki/CPU_pin_out_and_signal_timing
    var joypadOutputBit0 = 0
    // CPU cycle at which the last $4016 write occurred (-2 = never)
    var joypadLastWriteCycle = -2

    var zapperFired = false
    var zapperX: Int? = null
    var zapperY: Int? = null

    // Set to true by mappers that need per-tile BG override (e.g. MMC5
    // ExRAM mode 1). When true, the PPU calls getBgTileData() for each
    // background tile during rendering.
    var bgTileOverride = false

    open fun write(address: Int, value: Int) {
        if (address < 0x2000) {
            // Mirroring of RAM:
            nes.cpu.mem[address and 0x7ff] = value
        } else if (address >= 0x8000) {
            // ROM is not writable. Mappers may override this to handle bank switching.
        } else if (address >= 0x6000) {
            // Cartridge SRAM (0x6000-0x7FFF)
            nes.cpu.mem[address] = value
            nes.opts.onBatteryRamWrite(address, value)
        } else if (address > 0x4017) {
            // Cartridge expansion area (0x4018-0x5FFF)
            nes.cpu.mem[address] = value
        } else if (address > 0x2007 && address < 0x4000) {
            regWrite(0x2000 + (address and 0x7), value)
        } else {
            regWrite(address, value)
        }
    }

    fun writelow(address: Int, value: Int) {
        if (address < 0x2000) {
            // Mirroring of RAM:
            nes.cpu.mem[address and 0x7ff] = value
        } else if (address >= 0x8000) {
            // ROM is not writable
        } else if (address > 0x4017) {
            // Cartridge RAM/expansion area (0x4018-0x7FFF)
            nes.cpu.mem[address] = value
        } else if (address > 0x2007 && address < 0x4000) {
            regWrite(0x2000 + (address and 0x7), value)
        } else {
            regWrite(address, value)
        }
    }

    open fun load(address: Int): Int {
        // Wrap around:
        var addr = address and 0xffff

        // Check address range:
        if (addr > 0x4017) {
            if (addr < 0x6000) {
                // Open bus: $4018-$5FFF (unmapped expansion area)
                return nes.cpu.dataBus
            }
            // Cartridge RAM ($6000-$7FFF) and ROM ($8000-$FFFF):
            return nes.cpu.mem[addr]
        } else if (addr >= 0x2000) {
            // I/O Ports.
            return regLoad(addr)
        } else {
            // RAM (mirrored)
            return nes.cpu.mem[addr and 0x7ff]
        }
    }

    open fun regLoad(address: Int): Int {
        when (address shr 12) { // use fourth nibble (0xF000)
            0, 1 -> {
            }

            2, 3 -> {
                // PPU Registers
                when (address and 0x7) {
                    0x0 -> {
                        // 0x2000: PPU Control Register 1 (write-only, returns open bus)
                        return nes.ppu.openBusLatch
                    }
                    0x1 -> {
                        // 0x2001: PPU Control Register 2 (write-only, returns open bus)
                        return nes.ppu.openBusLatch
                    }
                    0x2 -> {
                        // 0x2002: PPU Status Register (bits 7-5 from status, 4-0 from open bus)
                        return nes.ppu.readStatusRegister()
                    }
                    0x3 -> {
                        // 0x2003: OAM Address (write-only, returns open bus)
                        return nes.ppu.openBusLatch
                    }
                    0x4 -> {
                        // 0x2004: Sprite Memory read
                        return nes.ppu.sramLoad()
                    }
                    0x5 -> {
                        // 0x2005: Scroll (write-only, returns open bus)
                        return nes.ppu.openBusLatch
                    }
                    0x6 -> {
                        // 0x2006: VRAM Address (write-only, returns open bus)
                        return nes.ppu.openBusLatch
                    }
                    0x7 -> {
                        // 0x2007: VRAM read
                        return nes.ppu.vramLoad()
                    }
                }
            }

            4 -> {
                // Sound+Joypad registers
                when (address - 0x4015) {
                    0 -> {
                        // 0x4015:
                        // Sound channel enable, DMC Status
                        return nes.papu.readReg(address)
                    }
                    1 -> {
                        // 0x4016:
                        // Joystick 1 + Strobe
                        // Bits 0-4 from controller, bits 5-7 are open bus (data bus)
                        // See https://www.nesdev.org/wiki/Open_bus_behavior
                        return (joy1Read() and 0x1f) or (nes.cpu.dataBus and 0xe0)
                    }
                    2 -> {
                        // 0x4017:
                        // Joystick 2 + Strobe
                        // https://wiki.nesdev.com/w/index.php/Zapper
                        // Bits 0-4 from controller/zapper, bits 5-7 are open bus (data bus)
                        var w = 0

                        if (zapperX != null && zapperY != null) {
                            // Zapper connected: bit 3 = light not detected
                            if (!nes.ppu.isPixelWhite(zapperX!!, zapperY!!)) {
                                w = 0x1 shl 3
                            }
                        }

                        if (zapperFired) {
                            w = w or (0x1 shl 4)
                        }
                        return ((joy2Read() or w) and 0x1f) or (nes.cpu.dataBus and 0xe0)
                    }
                }
            }
        }
        // Write-only registers (APU $4000-$4014, etc.) are open bus.
        // On real hardware, if a DMC DMA fetch coincides with this read cycle,
        // the DMA steals the CPU bus cycle and the fetched sample byte appears
        // on the data bus instead of the open bus value. This is how the ROM's
        // DMA sync loops (LDA $4000; BNE) detect DMC activity.
        // See https://www.nesdev.org/wiki/APU_DMC#Memory_reader
        val cpu = nes.cpu
        if (cpu._dmcFetchCycles > 0 && cpu._dmcFetchCycles == cpu.instrBusCycles + 1) {
            val dmc = nes.papu.dmc
            if (dmc != null && dmc.isEnabled) {
                return dmc.lastFetchedByte
            }
        }
        return cpu.dataBus
    }

    open fun regWrite(address: Int, value: Int) {
        // All PPU register writes update the open bus latch
        if (address >= 0x2000 && address <= 0x3fff) {
            nes.ppu.openBusLatch = value
            nes.ppu.openBusDecayFrames = 36 // ~600ms at 60fps
        }

        when (address) {
            0x2000 -> {
                // PPU Control register 1
                nes.cpu.mem[address] = value
                nes.ppu.updateControlReg1(value)
            }
            0x2001 -> {
                // PPU Control register 2
                nes.cpu.mem[address] = value
                nes.ppu.updateControlReg2(value)
            }
            0x2003 -> {
                // Set Sprite RAM address:
                nes.ppu.writeSRAMAddress(value)
            }
            0x2004 -> {
                // Write to Sprite RAM:
                nes.ppu.sramWrite(value)
            }
            0x2005 -> {
                // Screen Scroll offsets:
                nes.ppu.scrollWrite(value)
            }
            0x2006 -> {
                // Set VRAM address:
                nes.ppu.writeVRAMAddress(value)
            }
            0x2007 -> {
                // Write to VRAM:
                nes.ppu.vramWrite(value)
            }
            0x4014 -> {
                // Sprite Memory DMA Access
                nes.ppu.sramDMA(value)
            }
            0x4015 -> {
                // Sound Channel Switch, DMC Status
                nes.papu.writeReg(address, value)
            }
            0x4016 -> {
                // Joystick 1 + Strobe
                // The 2A03 output ports (OUT0-OUT2) only update on APU clock
                // edges, which happen every 2 CPU cycles. A write to $4016
                // always updates the internal register immediately, but the
                // effective output (joypadOutputBit0) only changes on
                // odd-parity CPU cycles.
                // This matters for RMW instructions like DEC $4016: the dummy
                // write (original value) and real write (modified value) happen
                // on consecutive cycles. If the dummy write lands on an APU tick
                // (even) but the real write lands on a non-tick (odd), only the
                // dummy write's value reaches OUT0. The AccuracyCoin controller
                // strobe test verifies this behavior.
                val cpu = nes.cpu
                val currentCycle = cpu._cpuCycleBase + cpu.instrBusCycles

                // If previous write(s) haven't been applied to the output yet
                // (because they landed on odd cycles), sync them now if at least
                // one APU tick has passed since then.
                if (currentCycle - joypadLastWriteCycle > 1) {
                    val prevBit = joypadLastWrite and 1
                    if (prevBit != joypadOutputBit0) {
                        if (joypadOutputBit0 == 1 && prevBit == 0) {
                            joy1StrobeState = 0
                            joy2StrobeState = 0
                        }
                        joypadOutputBit0 = prevBit
                    }
                }

                joypadLastWrite = value
                joypadLastWriteCycle = currentCycle

                // Apply to effective output only on APU tick ("put") cycles.
                // After OAM DMA sync, _cpuCycleBase is always odd, so the first
                // instruction cycle (_cpuCycleBase + 1) is even = "get". The 5th
                // cycle of a 6-cycle RMW (dummy write) is _cpuCycleBase + 4 = odd
                // = "put" = APU tick. This matches real hardware where OUT0
                // updates on "put" cycles.
                if (currentCycle % 2 == 1) {
                    val newBit = value and 1
                    if (joypadOutputBit0 == 1 && newBit == 0) {
                        joy1StrobeState = 0
                        joy2StrobeState = 0
                    }
                    joypadOutputBit0 = newBit
                }
            }
            0x4017 -> {
                // Sound channel frame sequencer:
                nes.papu.writeReg(address, value)
            }
            else -> {
                // Sound registers
                if (address >= 0x4000 && address <= 0x4017) {
                    nes.papu.writeReg(address, value)
                }
            }
        }
    }

    // Sync any pending $4016 output that was deferred from odd-cycle writes.
    // Called before reads from $4016/$4017, since reads happen on a later
    // cycle and the APU clock will have ticked by then.
    fun syncJoypadOutput() {
        val newBit = joypadLastWrite and 1
        if (newBit != joypadOutputBit0) {
            if (joypadOutputBit0 == 1 && newBit == 0) {
                joy1StrobeState = 0
                joy2StrobeState = 0
            }
            joypadOutputBit0 = newBit
        }
    }

    fun joy1Read(): Int {
        // Sync deferred output before checking strobe state
        syncJoypadOutput()

        // While strobe is active ($4016 bit 0 = 1), the shift register is
        // continuously reloaded, so reads always return button A's state.
        // See https://www.nesdev.org/wiki/Standard_controller
        if (joypadOutputBit0 != 0) {
            return nes.controllers[1].state[0]
        }

        var ret: Int
        if (joy1StrobeState < 8) {
            ret = nes.controllers[1].state[joy1StrobeState]
        } else {
            // After 8 reads, the shift register is empty and the serial data
            // line floats high, returning 1 on a standard NES controller.
            ret = 1
        }

        joy1StrobeState++
        if (joy1StrobeState == 24) {
            joy1StrobeState = 0
        }

        return ret
    }

    fun joy2Read(): Int {
        // Sync deferred output before checking strobe state
        syncJoypadOutput()

        // While strobe is active, always return button A's state.
        if (joypadOutputBit0 != 0) {
            return nes.controllers[2].state[0]
        }

        var ret: Int
        if (joy2StrobeState < 8) {
            ret = nes.controllers[2].state[joy2StrobeState]
        } else {
            // After 8 reads, the shift register is empty -> returns 1.
            ret = 1
        }

        joy2StrobeState++
        if (joy2StrobeState == 24) {
            joy2StrobeState = 0
        }

        return ret
    }

    open fun loadROM() {
        if (!nes.rom!!.valid || nes.rom!!.romCount < 1) {
            throw Error("NoMapper: Invalid ROM! Unable to load.")
        }

        // Load ROM into memory:
        loadPRGROM()

        // Load CHR-ROM:
        loadCHRROM()

        // Load Battery RAM (if present):
        loadBatteryRam()

        // Reset IRQ:
        nes.cpu.requestIrq(nes.cpu.IRQ_RESET)
    }

    open fun loadPRGROM() {
        if (nes.rom!!.romCount > 1) {
            // Load the two first banks into memory.
            loadRomBank(0, 0x8000)
            loadRomBank(1, 0xc000)
        } else {
            // Load the one bank into both memory locations:
            loadRomBank(0, 0x8000)
            loadRomBank(0, 0xc000)
        }
    }

    open fun loadCHRROM() {
        // console.log("Loading CHR ROM..");
        if (nes.rom!!.vromCount > 0) {
            if (nes.rom!!.vromCount == 1) {
                loadVromBank(0, 0x0000)
                loadVromBank(0, 0x1000)
            } else {
                loadVromBank(0, 0x0000)
                loadVromBank(1, 0x1000)
            }
        } else {
            //System.out.println("There aren't any CHR-ROM banks..");
        }
    }

    open fun loadBatteryRam() {
        if (nes.rom!!.batteryRam) {
            // Battery RAM persistence is not implemented (see ROM.load TODO).
        }
    }

    fun loadRomBank(bank: Int, address: Int) {
        // Loads a ROM bank into the specified address.
        val b = bank % nes.rom!!.romCount
        copyArrayElements(
            nes.rom!!.rom[b],
            0,
            nes.cpu.mem,
            address,
            16384,
        )
    }

    fun loadVromBank(bank: Int, address: Int) {
        if (nes.rom!!.vromCount == 0) {
            return
        }
        nes.ppu.triggerRendering()

        copyArrayElements(
            nes.rom!!.vrom[bank % nes.rom!!.vromCount],
            0,
            nes.ppu.vramMem,
            address,
            4096,
        )

        val vromTile = nes.rom!!.vromTile[bank % nes.rom!!.vromCount]
        copyArrayElements(vromTile, 0, nes.ppu.ptTile, address shr 4, 256)
    }

    fun load32kRomBank(bank: Int, address: Int) {
        loadRomBank((bank * 2) % nes.rom!!.romCount, address)
        loadRomBank((bank * 2 + 1) % nes.rom!!.romCount, address + 16384)
    }

    fun load8kVromBank(bank4kStart: Int, address: Int) {
        if (nes.rom!!.vromCount == 0) {
            return
        }
        nes.ppu.triggerRendering()

        loadVromBank(bank4kStart % nes.rom!!.vromCount, address)
        loadVromBank(
            (bank4kStart + 1) % nes.rom!!.vromCount,
            address + 4096,
        )
    }

    fun load1kVromBank(bank1k: Int, address: Int) {
        if (nes.rom!!.vromCount == 0) {
            return
        }
        nes.ppu.triggerRendering()

        val bank4k = (bank1k / 4) % nes.rom!!.vromCount
        val bankoffset = (bank1k % 4) * 1024
        copyArrayElements(
            nes.rom!!.vrom[bank4k],
            bankoffset,
            nes.ppu.vramMem,
            address,
            1024,
        )

        // Update tiles:
        val vromTile = nes.rom!!.vromTile[bank4k]
        val baseIndex = address shr 4
        for (i in 0 until 64) {
            nes.ppu.ptTile[baseIndex + i] = vromTile[((bank1k % 4) shl 6) + i]
        }
    }

    fun load2kVromBank(bank2k: Int, address: Int) {
        if (nes.rom!!.vromCount == 0) {
            return
        }
        nes.ppu.triggerRendering()

        val bank4k = (bank2k / 2) % nes.rom!!.vromCount
        val bankoffset = (bank2k % 2) * 2048
        copyArrayElements(
            nes.rom!!.vrom[bank4k],
            bankoffset,
            nes.ppu.vramMem,
            address,
            2048,
        )

        // Update tiles:
        val vromTile = nes.rom!!.vromTile[bank4k]
        val baseIndex = address shr 4
        for (i in 0 until 128) {
            nes.ppu.ptTile[baseIndex + i] = vromTile[((bank2k % 2) shl 7) + i]
        }
    }

    fun load8kRomBank(bank8k: Int, address: Int) {
        val bank16k = (bank8k / 2) % nes.rom!!.romCount
        val offset = (bank8k % 2) * 8192

        //this.nes.cpu.mem.write(address,this.nes.rom.rom[bank16k],offset,8192);
        copyArrayElements(
            nes.rom!!.rom[bank16k],
            offset,
            nes.cpu.mem,
            address,
            8192,
        )
    }

    // Returns true if the PPU can write to the given pattern table address.
    // Most mappers only allow writes when there's no CHR ROM (pure CHR RAM).
    // Mappers with mixed CHR ROM/RAM (e.g. TQROM) override this.
    open fun canWriteChr(address: Int): Boolean {
        return nes.rom!!.vromCount == 0
    }

    open fun clockIrqCounter() {
        // Does nothing. This is used by the MMC3 mapper.
    }

    open fun latchAccess(address: Int) {
        // Does nothing. This is used by MMC2.
    }

    // Called by the PPU before rendering background tiles for a scanline.
    // Override in mappers that need per-phase CHR bank switching (e.g. MMC5,
    // which uses separate CHR bank sets for sprites vs backgrounds).
    open fun onBgRender() {}

    // Called by the PPU before rendering sprites.
    // Override in mappers that need per-phase CHR bank switching.
    open fun onSpriteRender() {}

    // Called per-tile during BG rendering when bgTileOverride is true.
    // Returns a BgTileData to override the tile and attribute for a
    // background tile, or null to use the default lookup.
    // Used by MMC5 ExRAM mode 1 for per-tile CHR bank selection.
    open fun getBgTileData(baseTile: Int, tileIndex: Int, ht: Int, vt: Int): BgTileData? {
        return null
    }

    // Look up a sprite pattern tile by ptTile index (0-511).
    // Default: return from the PPU's current ptTile cache.
    // MMC5 overrides this to look up from Set A's VROM banks directly,
    // since ptTile may have BG data (Set B) loaded during BG rendering.
    // This avoids calling load*VromBank (which triggers triggerRendering).
    open fun getSpritePatternTile(index: Int): Tile {
        return nes.ppu.ptTile[index]
    }

    class BgTileData(
        val tile: Tile,
        val attrib: Int,
    )
}

class Mapper0(nes: NES) : Mapper(nes)
