package cn.enaium.nes.core.ppu

import cn.enaium.nes.core.CPU
import cn.enaium.nes.core.NES
import cn.enaium.nes.core.Tile

class PPU(val nes: NES) {
    companion object {
        // Status flags:
        const val STATUS_VRAMWRITE = 4
        const val STATUS_SLSPRITECOUNT = 5
        const val STATUS_SPRITE0HIT = 6
        const val STATUS_VBLANK = 7
    }

    // Rendering Options:
    var showSpr0Hit = false
    var clipToTvSize = true

    // Memory (zero-initialized)
    val vramMem = IntArray(0x8000)
    val spriteMem = IntArray(0x100)

    // VRAM I/O:
    var vramAddress = 0
    var vramTmpAddress = 0
    var vramBufferedReadValue = 0
    var firstWrite = true // VRAM/Scroll Hi/Lo latch
    var openBusLatch = 0
    var openBusDecayFrames = 0

    // SPR-RAM I/O:
    var sramAddress = 0 // 8-bit only.

    var currentMirroring = -1
    var nmiOutput = false // Current NMI output level
    var nmiSuppressed = false // Suppresses VBlank set when $2002 read at dot 0
    var vblankPending = false
    var frameEnded = false
    var dummyCycleToggle = false
    var validTileData = false
    var scanlineAlreadyRendered = false

    // Control Flags Register 1:
    var f_nmiOnVblank = 0 // NMI on VBlank. 0=disable, 1=enable
    var f_spriteSize = 0 // Sprite size. 0=8x8, 1=8x16
    var f_bgPatternTable = 0 // Background Pattern Table address. 0=0x0000,1=0x1000
    var f_spPatternTable = 0 // Sprite Pattern Table address. 0=0x0000,1=0x1000
    var f_addrInc = 0 // PPU Address Increment. 0=1,1=32
    var f_nTblAddress = 0 // Name Table Address. 0=0x2000,1=0x2400,2=0x2800,3=0x2C00

    // Control Flags Register 2:
    var f_color = 0 // Background color. 0=black, 1=blue, 2=green, 4=red
    var f_spVisibility = 0 // Sprite visibility. 0=not displayed,1=displayed
    var f_bgVisibility = 0 // Background visibility. 0=Not Displayed,1=displayed
    var f_spClipping = 0 // Sprite clipping. 0=Sprites invisible in left 8-pixel column,1=No clipping
    var f_bgClipping = 0 // Background clipping. 0=BG invisible in left 8-pixel column, 1=No clipping
    var f_dispType = 0 // Display type. 0=color, 1=monochrome

    // Counters:
    var cntFV = 0
    var cntV = 0
    var cntH = 0
    var cntVT = 0
    var cntHT = 0

    // Registers:
    var regFV = 0
    var regV = 0
    var regH = 0
    var regVT = 0
    var regHT = 0
    var regFH = 0
    var regS = 0

    // Temporary variables used in rendering and sound procedures.
    var curNt = 0

    // Variables used when rendering:
    val attrib = IntArray(32)
    val buffer = IntArray(256 * 240)
    val bgbuffer = IntArray(256 * 240)
    val pixrendered = IntArray(256 * 240)

    val scantile = arrayOfNulls<Tile>(32)

    // Initialize misc vars:
    var scanline = 0
    var lastRenderedScanline = -1
    var curX = 0

    // Sprite data (unpacked from primary OAM for quick access):
    val sprX = IntArray(64) // X coordinate
    val sprY = IntArray(64) // Y coordinate
    val sprTile = IntArray(64) // Tile Index (into pattern table)
    val sprCol = IntArray(64) // Upper two bits of color
    val vertFlip = IntArray(64) // Vertical Flip (0/1)
    val horiFlip = IntArray(64) // Horizontal Flip (0/1)
    val bgPriority = IntArray(64) // Background priority (0/1)
    var spr0HitX = 0 // Sprite #0 hit X coordinate
    var spr0HitY = 0 // Sprite #0 hit Y coordinate
    var hitSpr0 = false

    // Secondary OAM: 32 bytes (8 sprites x 4 bytes each).
    val secondaryOAM = IntArray(32)
    var spritesFound = 0
    var sprite0InSecondary = false

    // Per-scanline sprite evaluation results.
    val scanlineSpriteCount = IntArray(241) // +1 for buffer
    val scanlineSecondaryOAM = IntArray(241 * 32)
    val scanlineSprite0 = IntArray(241) // 1 if sprite 0 present

    // Palette data:
    val sprPalette = IntArray(16)
    val imgPalette = IntArray(16)

    // Pattern table tile buffers:
    val ptTile = Array(512) { Tile() }

    // Name table data:
    val ntable1 = IntArray(4)
    val nameTable = Array(4) { Nametable(32, 32, "Nt$it") }

    // Mirroring lookup table:
    val vramMirrorTable = IntArray(0x8000)

    val palTable = PaletteTable()

    private var _inRendering = false

    init {
        for (i in 0 until 0x8000) {
            vramMirrorTable[i] = i
        }

        secondaryOAM.fill(0xff) // $FF = no valid sprites

        palTable.loadNTSCPalette()

        updateControlReg1(0)
        updateControlReg2(0)
    }

    // Sets Nametable mirroring.
    fun setMirroring(mirroring: Int) {
        if (mirroring == currentMirroring) {
            return
        }

        currentMirroring = mirroring
        triggerRendering()

        // Reset mirroring lookup table to identity:
        for (i in 0 until 0x8000) {
            vramMirrorTable[i] = i
        }

        // Palette mirroring:
        defineMirrorRegion(0x3f20, 0x3f00, 0x20)
        defineMirrorRegion(0x3f40, 0x3f00, 0x20)
        defineMirrorRegion(0x3f80, 0x3f00, 0x20)
        defineMirrorRegion(0x3fc0, 0x3f00, 0x20)

        // Additional mirroring:
        defineMirrorRegion(0x3000, 0x2000, 0xf00)
        defineMirrorRegion(0x4000, 0x0000, 0x4000)

        if (mirroring == nes.rom!!.HORIZONTAL_MIRRORING) {
            // Horizontal mirroring.
            ntable1[0] = 0
            ntable1[1] = 0
            ntable1[2] = 1
            ntable1[3] = 1

            defineMirrorRegion(0x2400, 0x2000, 0x400)
            defineMirrorRegion(0x2c00, 0x2800, 0x400)
        } else if (mirroring == nes.rom!!.VERTICAL_MIRRORING) {
            // Vertical mirroring.
            ntable1[0] = 0
            ntable1[1] = 1
            ntable1[2] = 0
            ntable1[3] = 1

            defineMirrorRegion(0x2800, 0x2000, 0x400)
            defineMirrorRegion(0x2c00, 0x2400, 0x400)
        } else if (mirroring == nes.rom!!.SINGLESCREEN_MIRRORING) {
            // Single Screen mirroring
            ntable1[0] = 0
            ntable1[1] = 0
            ntable1[2] = 0
            ntable1[3] = 0

            defineMirrorRegion(0x2400, 0x2000, 0x400)
            defineMirrorRegion(0x2800, 0x2000, 0x400)
            defineMirrorRegion(0x2c00, 0x2000, 0x400)
        } else if (mirroring == nes.rom!!.SINGLESCREEN_MIRRORING2) {
            ntable1[0] = 1
            ntable1[1] = 1
            ntable1[2] = 1
            ntable1[3] = 1

            defineMirrorRegion(0x2400, 0x2400, 0x400)
            defineMirrorRegion(0x2800, 0x2400, 0x400)
            defineMirrorRegion(0x2c00, 0x2400, 0x400)
        } else {
            // Assume Four-screen mirroring.
            ntable1[0] = 0
            ntable1[1] = 1
            ntable1[2] = 2
            ntable1[3] = 3
        }
    }

    // Define a mirrored area in the address lookup table.
    // Assumes the regions don't overlap.
    // The 'to' region is the region that is physically in memory.
    fun defineMirrorRegion(fromStart: Int, toStart: Int, size: Int) {
        for (i in 0 until size) {
            vramMirrorTable[fromStart + i] = toStart + i
        }
    }

    fun startVBlank() {
        // PPU open bus latch decay: on real hardware each bit decays to 0
        // after ~600ms (~36 frames). We use a simple per-latch frame counter.
        if (openBusDecayFrames > 0) {
            openBusDecayFrames--
            if (openBusDecayFrames == 0) {
                openBusLatch = 0
            }
        }

        // Make sure everything is rendered:
        if (lastRenderedScanline < 239) {
            renderFramePartially(
                lastRenderedScanline + 1,
                240 - lastRenderedScanline,
            )
        }

        // End frame:
        endFrame()

        // Reset scanline counter:
        lastRenderedScanline = -1
    }

    // Fire the VBlank set event at dot 1 of scanline 0 (NES scanline 241).
    fun _fireVblankSet(cpu: CPU, dotsRemaining: Int) {
        vblankPending = false
        if (!nmiSuppressed) {
            setStatusFlag(STATUS_VBLANK, true)
            _updateNmiOutput()
            if (cpu.nmiRaised) {
                cpu.nmiDotsRemainingInStep = dotsRemaining
            }
        }
        nmiSuppressed = false
        startVBlank()
        frameEnded = true
    }

    // Fire the VBlank clear event at dot 1 of scanline 20 (NES scanline 261).
    fun _fireVblankClear(cpu: CPU, isLastDot: Boolean) {
        if (cpu.nmiRaised && isLastDot) {
            cpu.nmiPending = true
            cpu.nmiRaised = false
        }
        setStatusFlag(STATUS_VBLANK, false)
        setStatusFlag(STATUS_SPRITE0HIT, false)
        setStatusFlag(STATUS_SLSPRITECOUNT, false)
        hitSpr0 = false
        spr0HitX = -1
        spr0HitY = -1
        _updateNmiOutput()
    }

    // Advance the PPU by the given number of dots.
    fun advanceDots(dots: Int) {
        val finalCurX = curX + dots

        // Fast path: skip dot-by-dot when no per-dot events can fire.
        if (
            finalCurX < 341 &&
            !(
                scanline == 0 &&
                vblankPending &&
                curX <= 1 &&
                finalCurX >= 1
            ) &&
            !(scanline == 20 && curX <= 1 && finalCurX >= 1) &&
            (spr0HitX < curX || spr0HitX >= finalCurX)
        ) {
            curX = finalCurX
            return
        }

        // Slow path: advance dot-by-dot checking for events.
        val cpu = nes.cpu
        for (i in 0 until dots) {
            // VBlank set at dot 1 of scanline 0 (NES scanline 241).
            if (scanline == 0 && curX == 1 && vblankPending) {
                _fireVblankSet(cpu, dots - i)
                curX++
                continue
            }

            // VBlank clear at dot 1 of scanline 20 (NES scanline 261, pre-render).
            if (scanline == 20 && curX == 1) {
                _fireVblankClear(cpu, i == dots - 1)
            }

            // Sprite 0 hit check.
            if (
                curX == spr0HitX &&
                f_bgVisibility == 1 &&
                f_spVisibility == 1 &&
                scanline - 21 == spr0HitY
            ) {
                setStatusFlag(STATUS_SPRITE0HIT, true)
            }

            curX++
            if (curX == 341) {
                curX = 0
                endScanline()
            }
        }

        // Post-loop boundary checks.
        if (scanline == 0 && curX == 1 && vblankPending) {
            _fireVblankSet(cpu, 0)
        }
        if (scanline == 20 && curX == 1) {
            _fireVblankClear(cpu, true)
        }
    }

    fun endScanline() {
        when (scanline) {
            19 -> {
                // Dummy scanline.
                // May be variable length:
                if (dummyCycleToggle) {
                    // Remove dead cycle at end of scanline,
                    // for next scanline:
                    curX = 1
                    dummyCycleToggle = !dummyCycleToggle
                }
            }

            20 -> {
                // Pre-render scanline (NES scanline 261). VBlank and sprite 0 hit
                // flags are cleared at dot 1, handled by the frame loop and catch-up
                // loop for cycle-accurate timing.

                // OAM corruption (2C02G/H hardware bug).
                performOAMCorruption()

                if (f_bgVisibility == 1 || f_spVisibility == 1) {
                    // Update counters:
                    cntFV = regFV
                    cntV = regV
                    cntH = regH
                    cntVT = regVT
                    cntHT = regHT

                    if (f_bgVisibility == 1 || f_spVisibility == 1) {
                        // Render dummy scanline:
                        renderBgScanline(false, 0)
                    }

                    // Buffer row 0 is the pre-render dummy row (no sprites).
                    scanlineSpriteCount[0] = 0
                    scanlineSprite0[0] = 0
                    for (i in 0 until 32) {
                        scanlineSecondaryOAM[i] = 0xff
                    }

                    // Buffer row 1 = NES scanline 0. Copy stale secondary OAM data
                    // from the last evaluation.
                    val scanline0Base = 1 * 32
                    for (i in 0 until 32) {
                        scanlineSecondaryOAM[scanline0Base + i] = secondaryOAM[i]
                    }
                    scanlineSpriteCount[1] = spritesFound
                    scanlineSprite0[1] = if (sprite0InSecondary) 1 else 0

                    // OAMADDR is reset to 0 during sprite tile loading (cycles 257-320).
                    sramAddress = 0
                }

                if (f_bgVisibility == 1 && f_spVisibility == 1) {
                    // Check sprite 0 hit for dummy scanline (buffer row 0).
                    checkSprite0(0)
                }

                // Pre-compute sprite 0 hit for the first visible scanline (buffer row 1).
                if (
                    !hitSpr0 &&
                    f_bgVisibility == 1 &&
                    f_spVisibility == 1
                ) {
                    if (_precomputeSprite0Hit(1)) {
                        hitSpr0 = true
                    }
                }

                if (f_bgVisibility == 1 || f_spVisibility == 1) {
                    // Clock mapper IRQ Counter:
                    nes.mmap!!.clockIrqCounter()
                }
            }

            261 -> {
                // Post-render scanline (NES scanline 240), no rendering.
                vblankPending = true

                // Wrap around:
                scanline = -1 // will be incremented to 0
            }

            else -> {
                if (scanline >= 21 && scanline <= 260) {
                    // NES visible scanline index (0-239).
                    val bufferScan = scanline + 1 - 21

                    // OAM corruption at the start of each visible scanline.
                    performOAMCorruption()

                    if (f_bgVisibility == 1 || f_spVisibility == 1) {
                        if (!scanlineAlreadyRendered) {
                            // update scroll:
                            cntHT = regHT
                            cntH = regH
                            renderBgScanline(true, bufferScan)
                        }
                        scanlineAlreadyRendered = false

                        // Check for sprite 0 hit on this scanline.
                        if (
                            !hitSpr0 &&
                            f_bgVisibility == 1 &&
                            f_spVisibility == 1 &&
                            scanlineSprite0[bufferScan] != 0
                        ) {
                            if (checkSprite0(bufferScan)) {
                                hitSpr0 = true
                            }
                        }
                    }

                    // Evaluate sprites for the NEXT scanline.
                    if (bufferScan < 240) {
                        evaluateSprites(bufferScan + 1)
                    }

                    // Pre-compute sprite 0 hit for the NEXT visible scanline.
                    if (
                        !hitSpr0 &&
                        f_bgVisibility == 1 &&
                        f_spVisibility == 1
                    ) {
                        _precomputeSprite0Hit(bufferScan + 1)
                        if (spr0HitX != -1) {
                            hitSpr0 = true
                        }
                    }

                    if (f_bgVisibility == 1 || f_spVisibility == 1) {
                        // Clock mapper IRQ Counter:
                        nes.mmap!!.clockIrqCounter()
                    }
                }
            }
        }

        scanline++
        regsToAddress()
        cntsToAddress()
    }

    fun startFrame() {
        // Clear per-scanline sprite evaluation data from the previous frame.
        scanlineSpriteCount.fill(0)
        scanlineSprite0.fill(0)

        // Set background color:
        val bgColor: Int

        if (f_dispType == 0) {
            // Color display.
            // f_color determines color emphasis.
            // Use first entry of image palette as BG color.
            bgColor = imgPalette[0]
        } else {
            // Monochrome display.
            // f_color determines the bg color.
            bgColor = when (f_color) {
                0 -> 0x00000 // Black
                1 -> 0x00ff00 // Green
                2 -> 0x0000ff // Blue
                3 -> 0x000000 // Invalid. Use black.
                4 -> 0xff0000 // Red
                else -> 0x0 // Invalid. Use black.
            }
        }

        buffer.fill(bgColor)
        pixrendered.fill(65)
    }

    fun endFrame() {
        val buffer = this.buffer

        // Draw spr#0 hit coordinates:
        if (showSpr0Hit) {
            // Spr 0 position:
            if (
                sprX[0] >= 0 &&
                sprX[0] < 256 &&
                sprY[0] >= 0 &&
                sprY[0] < 240
            ) {
                for (i in 0 until 256) {
                    buffer[(sprY[0] shl 8) + i] = 0xff5555
                }
                for (i in 0 until 240) {
                    buffer[(i shl 8) + sprX[0]] = 0xff5555
                }
            }
            // Hit position:
            if (
                spr0HitX >= 0 &&
                spr0HitX < 256 &&
                spr0HitY >= 0 &&
                spr0HitY < 240
            ) {
                for (i in 0 until 256) {
                    buffer[(spr0HitY shl 8) + i] = 0x55ff55
                }
                for (i in 0 until 240) {
                    buffer[(i shl 8) + spr0HitX] = 0x55ff55
                }
            }
        }

        // Clip left 8-pixels column:
        if (
            clipToTvSize ||
            f_bgClipping == 0 ||
            f_spClipping == 0
        ) {
            for (y in 0 until 240) {
                buffer.fill(0, y shl 8, (y shl 8) + 8)
            }
        }

        if (clipToTvSize) {
            // Clip right 8-pixels column too:
            for (y in 0 until 240) {
                buffer.fill(0, (y shl 8) + 248, (y shl 8) + 256)
            }

            // Clip top and bottom 8 pixels:
            buffer.fill(0, 0, 8 shl 8)
            buffer.fill(0, 232 shl 8, 240 shl 8)
        }

        nes.ui.writeFrame(buffer)
    }

    fun updateControlReg1(value: Int) {
        triggerRendering()

        f_nmiOnVblank = (value shr 7) and 1
        f_spriteSize = (value shr 5) and 1
        f_bgPatternTable = (value shr 4) and 1
        f_spPatternTable = (value shr 3) and 1
        f_addrInc = (value shr 2) and 1
        f_nTblAddress = value and 3

        regV = (value shr 1) and 1
        regH = value and 1
        regS = (value shr 4) and 1

        _updateNmiOutput()
    }

    // Recomputes the NMI output level from (vblankFlag AND nmiEnabled).
    fun _updateNmiOutput() {
        val vblank = (nes.cpu.mem[0x2002] and 0x80) != 0
        val newOutput = f_nmiOnVblank != 0 && vblank
        if (newOutput && !nmiOutput) {
            // Rising edge: set nmiRaised.
            nes.cpu.nmiRaised = true
            nes.cpu.nmiRaisedAtCycle = nes.cpu.instrBusCycles
        } else if (!newOutput && nmiOutput) {
            // Falling edge: cancel nmiRaised only if it hasn't been latched yet.
            if (nes.cpu.nmiRaised) {
                val busCycleDiff =
                    nes.cpu.instrBusCycles - nes.cpu.nmiRaisedAtCycle
                if (
                    busCycleDiff == 0 ||
                    (busCycleDiff == 1 && nes.cpu.nmiDotsRemainingInStep == 0)
                ) {
                    nes.cpu.nmiRaised = false
                }
            }
        }
        nmiOutput = newOutput
    }

    fun updateControlReg2(value: Int) {
        triggerRendering()

        f_color = (value shr 5) and 7
        f_spVisibility = (value shr 4) and 1
        f_bgVisibility = (value shr 3) and 1
        f_spClipping = (value shr 2) and 1
        f_bgClipping = (value shr 1) and 1
        f_dispType = value and 1

        // When both BG and sprite rendering become enabled mid-scanline,
        // re-check sprite 0 hit.
        if (
            !hitSpr0 &&
            f_bgVisibility == 1 &&
            f_spVisibility == 1 &&
            scanline >= 21 &&
            scanline <= 260
        ) {
            val bufferScan = scanline + 1 - 21
            if (scanlineSprite0[bufferScan] != 0) {
                if (checkSprite0(bufferScan)) {
                    hitSpr0 = true
                }
            }
        }

        if (f_dispType == 0) {
            palTable.setEmphasis(f_color)
        }
        updatePalettes()
    }

    fun setStatusFlag(flag: Int, value: Boolean) {
        val n = 1 shl flag
        nes.cpu.mem[0x2002] =
            (nes.cpu.mem[0x2002] and (255 - n)) or (if (value) n else 0)
    }

    // CPU Register $2002:
    fun readStatusRegister(): Int {
        var tmp = nes.cpu.mem[0x2002]

        // Reset scroll & VRAM Address toggle:
        firstWrite = true

        // NMI suppression: reading $2002 one PPU dot BEFORE VBlank is set.
        if (scanline == 0 && curX == 0) {
            nmiSuppressed = true
        }

        // Clear VBlank flag:
        setStatusFlag(STATUS_VBLANK, false)

        // Clearing VBlank may cause a falling edge on NMI output.
        _updateNmiOutput()

        // Only bits 7-5 come from the status register; bits 4-0 are open bus.
        tmp = (tmp and 0xe0) or (openBusLatch and 0x1f)
        openBusLatch = tmp
        openBusDecayFrames = 36 // ~600ms at 60fps

        // Fetch status data:
        return tmp
    }

    // CPU Register $2003:
    fun writeSRAMAddress(address: Int) {
        sramAddress = address
    }

    // CPU Register $2004 (R):
    fun sramLoad(): Int {
        val renderingEnabled =
            f_spVisibility == 1 || f_bgVisibility == 1

        if (renderingEnabled && scanline >= 20 && scanline <= 260) {
            val dot = curX
            if (dot <= 64) {
                // Dots 0-64: secondary OAM clear phase.
                return 0xff
            } else if (dot <= 256) {
                // Dots 65-256: sprite evaluation phase.
                var val_ = spriteMem[sramAddress]
                if ((sramAddress and 3) == 2) {
                    val_ = val_ and 0xe3
                }
                return val_
            } else {
                // Dots 257-340: sprite tile loading and background prefetch.
                return 0xff
            }
        }

        // Normal read during VBlank or rendering disabled.
        var value = spriteMem[sramAddress]
        if ((sramAddress and 3) == 2) {
            value = value and 0xe3
        }
        return value
    }

    // CPU Register $2004 (W):
    fun sramWrite(value: Int) {
        val renderingEnabled =
            f_spVisibility == 1 || f_bgVisibility == 1

        if (renderingEnabled && scanline >= 20 && scanline <= 260) {
            // During rendering on visible/pre-render scanlines, writes to $2004
            // are suppressed.
            sramAddress = (sramAddress + 4) and 0xfc
        } else {
            // Normal write during VBlank or rendering disabled
            spriteMem[sramAddress] = value
            spriteRamWriteUpdate(sramAddress, value)
            sramAddress++
            sramAddress %= 0x100
        }
    }

    // CPU Register $2005:
    fun scrollWrite(value: Int) {
        triggerRendering()

        if (firstWrite) {
            // First write, horizontal scroll:
            regHT = (value shr 3) and 31
            regFH = value and 7
        } else {
            // Second write, vertical scroll:
            regFV = value and 7
            regVT = (value shr 3) and 31
        }
        firstWrite = !firstWrite
    }

    // CPU Register $2006:
    fun writeVRAMAddress(address: Int) {
        if (firstWrite) {
            regFV = (address shr 4) and 3
            regV = (address shr 3) and 1
            regH = (address shr 2) and 1
            regVT = (regVT and 7) or ((address and 3) shl 3)
        } else {
            triggerRendering()

            regVT = (regVT and 24) or ((address shr 5) and 7)
            regHT = address and 31

            cntFV = regFV
            cntV = regV
            cntH = regH
            cntVT = regVT
            cntHT = regHT

            checkSprite0(scanline + 1 - 21)
        }

        firstWrite = !firstWrite

        // Invoke mapper latch:
        cntsToAddress()
        if (vramAddress < 0x2000) {
            nes.mmap!!.latchAccess(vramAddress)
        }
    }

    // CPU Register $2007(R):
    fun vramLoad(): Int {
        var tmp: Int

        cntsToAddress()
        regsToAddress()

        // If address is in range 0x0000-0x3EFF, return buffered values:
        if (vramAddress <= 0x3eff) {
            tmp = vramBufferedReadValue

            // Update buffered value:
            if (vramAddress < 0x2000) {
                vramBufferedReadValue = vramMem[vramAddress]
            } else {
                vramBufferedReadValue = mirroredLoad(vramAddress)
            }

            // Mapper latch access:
            if (vramAddress < 0x2000) {
                nes.mmap!!.latchAccess(vramAddress)
            }

            _incrementVramAddress()

            cntsFromAddress()
            regsFromAddress()

            return tmp // Return the previous buffered value.
        }

        // Palette RAM ($3F00-$3FFF): value is returned directly (no buffer delay).
        var palIdx = vramAddress and 0x1f
        if ((palIdx and 0x13) == 0x10) {
            palIdx = palIdx and 0x0f // backdrop mirror
        }
        tmp = (vramMem[0x3f00 + palIdx] and 0x3f) or (openBusLatch and 0xc0)

        // Update buffer with nametable data behind the palette
        vramBufferedReadValue = mirroredLoad(vramAddress and 0x2fff)

        _incrementVramAddress()

        cntsFromAddress()
        regsFromAddress()

        return tmp
    }

    // CPU Register $2007(W):
    fun vramWrite(value: Int) {
        triggerRendering()
        cntsToAddress()
        regsToAddress()

        if (vramAddress >= 0x2000) {
            // Mirroring is used.
            mirroredWrite(vramAddress, value)
        } else {
            // Pattern table ($0000-$1FFF): writable if CHR RAM is mapped here.
            if (nes.mmap!!.canWriteChr(vramAddress)) {
                writeMem(vramAddress, value)
            }

            // Invoke mapper latch:
            nes.mmap!!.latchAccess(vramAddress)
        }

        _incrementVramAddress()
        regsFromAddress()
        cntsFromAddress()
    }

    // CPU Register $4014:
    fun sramDMA(value: Int) {
        val baseAddress = value * 0x100
        var data: Int
        for (i in 0 until 256) {
            data = nes.cpu.mem[baseAddress + i]
            val oamAddr = (sramAddress + i) and 0xff
            spriteMem[oamAddr] = data
            spriteRamWriteUpdate(oamAddr, data)
        }

        // OAM DMA takes 513 CPU cycles (plus alignment cycle if odd).
        val cpu = nes.cpu
        val currentCycle = cpu._cpuCycleBase + cpu.instrBusCycles
        val cycles = if (currentCycle % 2 == 0) 514 else 513
        cpu.haltCycles(cycles)
    }

    // Updates the scroll registers from a new VRAM address.
    fun regsFromAddress() {
        var address = (vramTmpAddress shr 8) and 0xff
        regFV = (address shr 4) and 7
        regV = (address shr 3) and 1
        regH = (address shr 2) and 1
        regVT = (regVT and 7) or ((address and 3) shl 3)

        address = vramTmpAddress and 0xff
        regVT = (regVT and 24) or ((address shr 5) and 7)
        regHT = address and 31
    }

    // Increments the VRAM address after a $2007 read or write.
    fun _incrementVramAddress() {
        val renderingEnabled =
            f_spVisibility == 1 || f_bgVisibility == 1
        // jsnes scanlines 20-260 = NES pre-render + visible scanlines
        val onRenderingScanline = scanline >= 20 && scanline <= 260

        if (renderingEnabled && onRenderingScanline) {
            // Coarse X increment (with horizontal nametable toggle on overflow)
            if ((vramAddress and 0x001f) == 31) {
                vramAddress = vramAddress and 0x001f.inv() // coarse X = 0
                vramAddress = vramAddress xor 0x0400 // toggle horizontal nametable
            } else {
                vramAddress += 1
            }

            // Y increment: fine Y first, then coarse Y on overflow
            if ((vramAddress and 0x7000) != 0x7000) {
                vramAddress += 0x1000 // fine Y += 1
            } else {
                vramAddress = vramAddress and 0x7000.inv() // fine Y = 0
                var coarseY = (vramAddress shr 5) and 0x1f
                if (coarseY == 29) {
                    coarseY = 0
                    vramAddress = vramAddress xor 0x0800 // toggle vertical nametable
                } else if (coarseY == 31) {
                    coarseY = 0 // wrap without nametable toggle
                } else {
                    coarseY += 1
                }
                vramAddress = (vramAddress and 0x03e0.inv()) or (coarseY shl 5)
            }
        } else {
            // Normal linear increment outside rendering
            vramAddress += if (f_addrInc == 1) 32 else 1
        }
    }

    // Updates the scroll registers from a new VRAM address.
    fun cntsFromAddress() {
        var address = (vramAddress shr 8) and 0xff
        cntFV = (address shr 4) and 3
        cntV = (address shr 3) and 1
        cntH = (address shr 2) and 1
        cntVT = (cntVT and 7) or ((address and 3) shl 3)

        address = vramAddress and 0xff
        cntVT = (cntVT and 24) or ((address shr 5) and 7)
        cntHT = address and 31
    }

    fun regsToAddress() {
        var b1 = (regFV and 7) shl 4
        b1 = b1 or ((regV and 1) shl 3)
        b1 = b1 or ((regH and 1) shl 2)
        b1 = b1 or ((regVT shr 3) and 3)

        val b2 = ((regVT and 7) shl 5) or (regHT and 31)

        vramTmpAddress = ((b1 shl 8) or b2) and 0x7fff
    }

    fun cntsToAddress() {
        var b1 = (cntFV and 7) shl 4
        b1 = b1 or ((cntV and 1) shl 3)
        b1 = b1 or ((cntH and 1) shl 2)
        b1 = b1 or ((cntVT shr 3) and 3)

        val b2 = ((cntVT and 7) shl 5) or (cntHT and 31)

        vramAddress = ((b1 shl 8) or b2) and 0x7fff
    }

    fun incTileCounter(count: Int) {
        var i = count
        while (i != 0) {
            cntHT++
            if (cntHT == 32) {
                cntHT = 0
                cntVT++
                if (cntVT >= 30) {
                    cntH++
                    if (cntH == 2) {
                        cntH = 0
                        cntV++
                        if (cntV == 2) {
                            cntV = 0
                            cntFV++
                            cntFV = cntFV and 0x7
                        }
                    }
                }
            }
            i--
        }
    }

    // Reads from memory, taking into account mirroring/mapping of address ranges.
    fun mirroredLoad(address: Int): Int {
        return vramMem[vramMirrorTable[address]]
    }

    // Writes to memory, taking into account mirroring/mapping of address ranges.
    fun mirroredWrite(address: Int, value: Int) {
        if (address >= 0x3f00 && address < 0x3f20) {
            // Palette write mirroring.
            when (address) {
                0x3f00, 0x3f10 -> {
                    writeMem(0x3f00, value)
                    writeMem(0x3f10, value)
                }
                0x3f04, 0x3f14 -> {
                    writeMem(0x3f04, value)
                    writeMem(0x3f14, value)
                }
                0x3f08, 0x3f18 -> {
                    writeMem(0x3f08, value)
                    writeMem(0x3f18, value)
                }
                0x3f0c, 0x3f1c -> {
                    writeMem(0x3f0c, value)
                    writeMem(0x3f1c, value)
                }
                else -> writeMem(address, value)
            }
        } else {
            // Use lookup table for mirrored address:
            if (address < vramMirrorTable.size) {
                writeMem(vramMirrorTable[address], value)
            } else {
                throw Error("Invalid VRAM address: " + address.toString(16))
            }
        }
    }

    fun triggerRendering() {
        // Guard against recursion from mapper latch bank switches during rendering.
        if (_inRendering) return
        if (scanline >= 21 && scanline <= 260) {
            // Render sprites, and combine:
            renderFramePartially(
                lastRenderedScanline + 1,
                scanline - 21 - lastRenderedScanline,
            )

            // Set last rendered scanline:
            lastRenderedScanline = scanline - 21
        }
    }

    fun renderFramePartially(startScan: Int, scanCount: Int) {
        _inRendering = true

        // Let the mapper swap CHR banks for sprite rendering.
        nes.mmap!!.onSpriteRender()

        if (f_spVisibility == 1) {
            renderSpritesPartially(startScan, scanCount, 1)
        }

        if (f_bgVisibility == 1) {
            var si = startScan shl 8
            var ei = (startScan + scanCount) shl 8
            if (ei > 0xf000) {
                ei = 0xf000
            }
            val buffer = this.buffer
            val bgbuffer = this.bgbuffer
            val pixrendered = this.pixrendered
            var destIndex = si
            while (destIndex < ei) {
                if (pixrendered[destIndex] > 0xff) {
                    buffer[destIndex] = bgbuffer[destIndex]
                }
                destIndex++
            }
        }

        if (f_spVisibility == 1) {
            renderSpritesPartially(startScan, scanCount, 0)
        }

        // Restore BG CHR banks for subsequent background scanline rendering.
        nes.mmap!!.onBgRender()

        _inRendering = false
        validTileData = false
    }

    fun renderBgScanline(bgbuffer: Boolean, scan: Int) {
        val baseTile = if (regS == 0) 0 else 256
        // Base address for pattern table fetches (used for mapper latch triggers).
        val baseAddr = if (regS == 0) 0x0000 else 0x1000
        var destIndex = (scan shl 8) - regFH

        curNt = ntable1[(cntV shl 1) + cntH]

        cntHT = regHT
        cntH = regH
        curNt = ntable1[(cntV shl 1) + cntH]

        if (scan < 240 && scan - cntFV >= 0) {
            val tscanoffset = cntFV shl 3
            val scantile = this.scantile
            val attrib = this.attrib
            val ptTile = this.ptTile
            val nameTable = this.nameTable
            val imgPalette = this.imgPalette
            val pixrendered = this.pixrendered
            val targetBuffer = if (bgbuffer) this.bgbuffer else this.buffer
            val mmap = nes.mmap!!

            var t: Tile?
            var tpix: IntArray
            var att: Int
            var col: Int

            _inRendering = true

            // Let the mapper swap CHR banks for background rendering.
            nes.mmap!!.onBgRender()

            // Simulate unused sprite slot dummy fetches from the previous scanline.
            if (f_spriteSize == 1) {
                mmap.latchAccess(0x1fe8)
            }

            for (tile in 0 until 32) {
                if (scan >= 0) {
                    // Look up nametable tile index.
                    val tileIndex = nameTable[curNt].getTileIndex(
                        cntHT,
                        cntVT,
                    )

                    // Fetch tile & attrib data:
                    if (validTileData) {
                        // Get data from array:
                        t = scantile[tile]
                        if (t == null) {
                            continue
                        }
                        tpix = t.pix
                        att = attrib[tile]
                    } else {
                        // Fetch data:
                        t = ptTile[baseTile + tileIndex]
                        tpix = t.pix
                        att = nameTable[curNt].getAttrib(cntHT, cntVT)

                        // MMC5 ExRAM mode 1: per-tile CHR bank and attribute override.
                        if (mmap.bgTileOverride) {
                            val override = mmap.getBgTileData(
                                baseTile,
                                tileIndex,
                                cntHT,
                                cntVT,
                            )
                            if (override != null) {
                                t = override.tile
                                tpix = t.pix
                                att = override.attrib
                            }
                        }

                        scantile[tile] = t
                        attrib[tile] = att
                    }

                    // Render tile scanline:
                    val tt = t!!
                    var sx = 0
                    var x = (tile shl 3) - regFH

                    if (x > -8) {
                        if (x < 0) {
                            destIndex -= x
                            sx = -x
                        }
                        if (tt.opaque[cntFV] != 0) {
                            while (sx < 8) {
                                targetBuffer[destIndex] =
                                    imgPalette[tpix[tscanoffset + sx] + att]
                                pixrendered[destIndex] = pixrendered[destIndex] or 256
                                destIndex++
                                sx++
                            }
                        } else {
                            while (sx < 8) {
                                col = tpix[tscanoffset + sx]
                                if (col != 0) {
                                    targetBuffer[destIndex] = imgPalette[col + att]
                                    pixrendered[destIndex] = pixrendered[destIndex] or 256
                                }
                                destIndex++
                                sx++
                            }
                        }
                    }

                    // Mapper latch access: simulate the PPU's pattern table high
                    // byte fetch.
                    mmap.latchAccess(baseAddr + tileIndex * 16 + cntFV + 8)
                }

                // Increase Horizontal Tile Counter:
                cntHT++
                if (cntHT == 32) {
                    cntHT = 0
                    cntH++
                    cntH %= 2
                    curNt = ntable1[(cntV shl 1) + cntH]
                }
            }
            _inRendering = false

            // Tile data for one row should now have been fetched,
            // so the data in the array is valid.
            validTileData = true
        }

        // update vertical scroll:
        cntFV++
        if (cntFV == 8) {
            cntFV = 0
            cntVT++
            if (cntVT == 30) {
                cntVT = 0
                cntV++
                cntV %= 2
                curNt = ntable1[(cntV shl 1) + cntH]
            } else if (cntVT == 32) {
                cntVT = 0
            }

            // Invalidate fetched data:
            validTileData = false
        }
    }

    // OAM corruption (2C02G/H hardware bug).
    fun performOAMCorruption() {
        val renderingEnabled =
            f_spVisibility == 1 || f_bgVisibility == 1
        if (!renderingEnabled) return
        if (sramAddress == 0) return

        val srcBase = sramAddress and 0xf8
        for (i in 0 until 8) {
            spriteMem[i] = spriteMem[(srcBase + i) and 0xff]
        }
        // Update unpacked sprite data for the corrupted entries
        for (i in 0 until 8) {
            spriteRamWriteUpdate(i, spriteMem[i])
        }
    }

    // Evaluate sprites for the given scanline, populating secondary OAM.
    fun evaluateSprites(targetScanline: Int) {
        val renderingEnabled =
            f_spVisibility == 1 || f_bgVisibility == 1

        if (!renderingEnabled) return

        // Phase 1: Clear secondary OAM to $FF (cycles 1-64)
        val oamBase = targetScanline * 32
        for (i in 0 until 32) {
            scanlineSecondaryOAM[oamBase + i] = 0xff
        }
        scanlineSpriteCount[targetScanline] = 0
        scanlineSprite0[targetScanline] = 0

        val spriteHeight = if (f_spriteSize == 0) 8 else 16
        var spritesFound = 0
        var secondaryIndex = 0 // Write pointer into secondary OAM (0-31)

        // Phase 2: Sprite evaluation (cycles 65-256)
        val startN = (sramAddress shr 2) and 0x3f
        val startM = sramAddress and 0x03
        var overflowM = 0 // m counter for overflow bug

        var n = startN
        var firstSprite = true // First sprite may have misaligned m

        var evaluated = 0
        do {
            val m: Int
            if (spritesFound >= 8) {
                // In overflow detection mode: use the buggy m counter
                m = overflowM
            } else if (firstSprite) {
                // First sprite: m may be non-zero (misaligned OAMADDR)
                m = startM
            } else {
                m = 0
            }
            firstSprite = false

            val yByte = spriteMem[(n * 4 + m) and 0xff]

            // Check if sprite is in range for the target buffer row.
            if (targetScanline > yByte && targetScanline <= yByte + spriteHeight) {
                if (spritesFound < 8) {
                    // Copy 4 bytes to secondary OAM.
                    for (b in 0 until 4) {
                        scanlineSecondaryOAM[oamBase + secondaryIndex + b] =
                            spriteMem[(n * 4 + m + b) and 0xff]
                    }
                    // The first sprite in evaluation order triggers sprite 0 hit.
                    if (evaluated == 0) {
                        scanlineSprite0[targetScanline] = 1
                    }
                    spritesFound++
                    secondaryIndex += 4
                } else {
                    // 9th in-range sprite found: set sprite overflow flag.
                    setStatusFlag(STATUS_SLSPRITECOUNT, true)
                    break // After overflow is found, evaluation enters idle
                }
            } else if (spritesFound >= 8) {
                // Sprite overflow bug: both n and m are incremented.
                overflowM = (overflowM + 1) and 0x03
            }

            n = (n + 1) and 0x3f
            evaluated++
        } while (n != 0)

        scanlineSpriteCount[targetScanline] = spritesFound

        // Also save to the hardware secondary OAM buffer.
        for (i in 0 until 32) {
            secondaryOAM[i] = scanlineSecondaryOAM[oamBase + i]
        }
        this.spritesFound = spritesFound
        sprite0InSecondary = scanlineSprite0[targetScanline] == 1

        // OAMADDR is set to 0 during sprite tile loading (cycles 257-320).
        sramAddress = 0
    }

    // Render sprites for a range of scanlines using per-scanline secondary OAM data.
    fun renderSpritesPartially(startscan: Int, scancount: Int, bgPri: Int) {
        if (f_spVisibility != 1) return

        val mmap = nes.mmap!!
        val ptTile = this.ptTile
        val buffer = this.buffer
        val sprPalette = this.sprPalette
        val pixrendered = this.pixrendered

        for (scan in startscan until startscan + scancount) {
            if (scan < 0 || scan >= 240) continue

            val count = scanlineSpriteCount[scan]
            val oamBase = scan * 32

            for (i in 0 until count) {
                val sprY = scanlineSecondaryOAM[oamBase + i * 4 + 0]
                val sprTile = scanlineSecondaryOAM[oamBase + i * 4 + 1]
                val sprAttr = scanlineSecondaryOAM[oamBase + i * 4 + 2]
                val sprX = scanlineSecondaryOAM[oamBase + i * 4 + 3]

                val vertFlip = (sprAttr shr 7) and 1
                val horiFlip = (sprAttr shr 6) and 1
                val priority = (sprAttr shr 5) and 1
                val palAdd = (sprAttr and 3) shl 2

                if (priority != bgPri) continue
                if (f_spriteSize == 0) {
                    // 8x8 sprites
                    val tileIndex = if (f_spPatternTable == 0) sprTile else sprTile + 256
                    val sprBaseAddr = if (f_spPatternTable == 0) 0x0000 else 0x1000

                    // Render only the one scanline row that falls on 'scan'
                    val dy = sprY + 1 // +1 because sprite Y in OAM is display line - 1
                    val fineY = scan - dy
                    if (fineY < 0 || fineY >= 8) continue

                    ptTile[tileIndex].render(
                        buffer,
                        0,
                        fineY,
                        8,
                        fineY + 1,
                        sprX,
                        dy,
                        palAdd,
                        sprPalette,
                        horiFlip != 0,
                        vertFlip != 0,
                        i, // priority: lower index in secondary OAM = higher priority
                        pixrendered,
                    )

                    // Mapper latch: simulate PPU's sprite pattern table fetch.
                    mmap.latchAccess(sprBaseAddr + sprTile * 16 + 8)
                } else {
                    // 8x16 sprites: tile index bit 0 selects pattern table.
                    val sprBaseAddr = if ((sprTile and 1) != 0) 0x1000 else 0x0000
                    val topTileNum = sprTile and 0xfe
                    val top = if ((sprTile and 1) != 0) topTileNum - 1 + 256 else topTileNum

                    val dy = sprY + 1
                    val fineY = scan - dy
                    if (fineY < 0 || fineY >= 16) continue

                    // Determine which half (top/bottom) this scanline falls in
                    val tileOffset: Int
                    val tileFineY: Int
                    if (fineY < 8) {
                        tileOffset = if (vertFlip != 0) 1 else 0
                        tileFineY = fineY
                    } else {
                        tileOffset = if (vertFlip != 0) 0 else 1
                        tileFineY = fineY - 8
                    }

                    ptTile[top + tileOffset].render(
                        buffer,
                        0,
                        tileFineY,
                        8,
                        tileFineY + 1,
                        sprX,
                        dy + if (fineY < 8) 0 else 8,
                        palAdd,
                        sprPalette,
                        horiFlip != 0,
                        vertFlip != 0,
                        i,
                        pixrendered,
                    )

                    // Mapper latch: simulate fetches for both halves of 8x16 sprite.
                    mmap.latchAccess(sprBaseAddr + topTileNum * 16 + 8)
                    mmap.latchAccess(sprBaseAddr + (topTileNum + 1) * 16 + 8)
                }
            }
        }
    }

    // Check if sprite 0 overlaps with a background tile pixel on this scanline.
    fun checkSprite0(scan: Int): Boolean {
        spr0HitX = -1
        spr0HitY = -1

        if (scan < 0 || scan >= 240) return false
        if (scanlineSprite0[scan] == 0) return false
        if (scanlineSpriteCount[scan] == 0) return false

        // Read sprite 0's data from secondary OAM (first entry, slot 0).
        val oamBase = scan * 32
        val sprY = scanlineSecondaryOAM[oamBase + 0]
        val sprTile = scanlineSecondaryOAM[oamBase + 1]
        val sprAttr = scanlineSecondaryOAM[oamBase + 2]
        val x = scanlineSecondaryOAM[oamBase + 3]
        val y = sprY + 1 // +1 because sprite Y in OAM is display line - 1

        val vertFlip = (sprAttr shr 7) and 1
        val horiFlip = (sprAttr shr 6) and 1

        val leftClip = f_spClipping == 0 || f_bgClipping == 0

        var toffset: Int
        var t: Tile

        // Use the mapper's getSpritePatternTile() instead of ptTile directly.
        val mmap = nes.mmap

        if (f_spriteSize == 0) {
            // 8x8 sprites.
            val tIndexAdd = if (f_spPatternTable == 0) 0 else 256
            if (y <= scan && y + 8 > scan && x < 256) {
                t = mmap!!.getSpritePatternTile(sprTile + tIndexAdd)
                toffset = if (vertFlip != 0) 7 - (scan - y) else scan - y
                toffset *= 8
                return _checkSpr0Pixels(t, toffset, x, horiFlip != 0, scan, leftClip)
            }
        } else {
            // 8x16 sprites: tile index bit 0 selects pattern table.
            if (y <= scan && y + 16 > scan && x < 256) {
                toffset = if (vertFlip != 0) 15 - (scan - y) else scan - y

                if (toffset < 8) {
                    t = mmap!!.getSpritePatternTile(
                        sprTile + (if (vertFlip != 0) 1 else 0) + (if ((sprTile and 1) != 0) 255 else 0),
                    )
                } else {
                    t = mmap!!.getSpritePatternTile(
                        sprTile + (if (vertFlip != 0) 0 else 1) + (if ((sprTile and 1) != 0) 255 else 0),
                    )
                    toffset = if (vertFlip != 0) 15 - toffset else toffset - 8
                }
                toffset *= 8
                return _checkSpr0Pixels(t, toffset, x, horiFlip != 0, scan, leftClip)
            }
        }

        return false
    }

    // Helper: scan 8 pixels of sprite 0's tile row for overlap with background.
    fun _checkSpr0Pixels(tile: Tile, toffset: Int, startX: Int, horiFlip: Boolean, scan: Int, leftClip: Boolean): Boolean {
        var bufferIndex = scan * 256 + startX

        for (px in 0 until 8) {
            val tileIdx = if (horiFlip) 7 - px else px
            val pixelX = startX + px

            if (pixelX >= 0 && pixelX < 255) {
                // Skip left 8 pixels when clipping is enabled
                if (leftClip && pixelX < 8) {
                    bufferIndex++
                    continue
                }

                if (
                    bufferIndex >= 0 &&
                    bufferIndex < 61440 &&
                    pixrendered[bufferIndex] > 0xff &&
                    tile.pix[toffset + tileIdx] != 0
                ) {
                    spr0HitX = pixelX
                    spr0HitY = scan
                    return true
                }
            }
            bufferIndex++
        }
        return false
    }

    // Pre-computes sprite 0 hit for the NEXT scanline by checking BG tile data directly.
    fun _precomputeSprite0Hit(nextBufferScan: Int): Boolean {
        if (nextBufferScan < 1 || nextBufferScan > 239) return false
        if (scanlineSprite0[nextBufferScan] == 0) return false
        if (scanlineSpriteCount[nextBufferScan] == 0) return false

        // Read sprite 0 from secondary OAM for the next scanline.
        val oamBase = nextBufferScan * 32
        val sprY = scanlineSecondaryOAM[oamBase + 0]
        val sprTile = scanlineSecondaryOAM[oamBase + 1]
        val sprAttr = scanlineSecondaryOAM[oamBase + 2]
        val sprX = scanlineSecondaryOAM[oamBase + 3]
        val y = sprY + 1 // +1 because sprite Y in OAM is display line - 1

        val vertFlip = (sprAttr shr 7) and 1
        val horiFlip = (sprAttr shr 6) and 1
        val leftClip = f_spClipping == 0 || f_bgClipping == 0

        // Check if sprite 0 overlaps the next scanline.
        val spriteHeight = if (f_spriteSize == 0) 8 else 16
        if (!(y <= nextBufferScan && y + spriteHeight > nextBufferScan))
            return false
        if (sprX >= 256) return false

        // Compute sprite tile row for this scanline.
        val sprRow = if (vertFlip != 0)
            spriteHeight - 1 - (nextBufferScan - y)
        else
            nextBufferScan - y
        val sprTileObj: Tile
        val toffset: Int

        if (f_spriteSize == 0) {
            // 8x8 sprites.
            val tIndexAdd = if (f_spPatternTable == 0) 0 else 256
            sprTileObj = ptTile[sprTile + tIndexAdd]
            toffset = sprRow * 8
        } else {
            // 8x16 sprites: tile index bit 0 selects pattern table.
            val patternBase = if ((sprTile and 1) != 0) 256 else 0
            val baseTileIdx = sprTile and 1.inv()
            if (sprRow < 8) {
                sprTileObj =
                    ptTile[baseTileIdx + patternBase + (if (vertFlip != 0) 1 else 0)]
                toffset = sprRow * 8
            } else {
                sprTileObj =
                    ptTile[baseTileIdx + patternBase + (if (vertFlip != 0) 0 else 1)]
                toffset = (sprRow - 8) * 8
            }
        }

        // BG vertical position: cntFV/cntVT/cntV have already been advanced to
        // the next row by renderBgScanline's scroll update.
        val bgFineY = cntFV
        val bgCoarseY = cntVT
        val bgNtV = cntV
        val baseBgTile = if (regS == 0) 0 else 256

        // Check each sprite pixel against the BG tile at that position.
        for (px in 0 until 8) {
            val screenX = sprX + px
            if (screenX >= 255) continue // no hit at x=255
            if (leftClip && screenX < 8) continue

            // Check sprite pixel non-transparent.
            val tileIdx = if (horiFlip != 0) 7 - px else px
            if (sprTileObj.pix[toffset + tileIdx] == 0) continue

            // Compute which BG tile covers this screen X using the horizontal
            // scroll registers.
            val tileOffset = (screenX + regFH) shr 3
            var absCol = regHT + tileOffset
            var bgNtH = regH
            if (absCol >= 32) {
                absCol -= 32
                bgNtH = bgNtH xor 1 // toggle horizontal nametable
            }

            // Look up the BG tile from the nametable.
            val ntIdx = ntable1[(bgNtV shl 1) + bgNtH]
            val bgTileIndex = nameTable[ntIdx].getTileIndex(absCol, bgCoarseY)
            val bgTile = ptTile[baseBgTile + bgTileIndex]

            // Check BG pixel non-transparent at (fineX, fineY).
            val bgPixelX = (screenX + regFH) and 7
            if (bgTile.pix[bgFineY * 8 + bgPixelX] != 0) {
                // Hit found! Store in NES scanline coordinates for step() matching.
                spr0HitX = screenX
                spr0HitY = nextBufferScan - 1
                return true
            }
        }
        return false
    }

    // This will write to PPU memory, and update internally buffered data appropriately.
    fun writeMem(address: Int, value: Int) {
        vramMem[address] = value

        // Update internally buffered data:
        if (address < 0x2000) {
            vramMem[address] = value
            patternWrite(address, value)
        } else if (address >= 0x2000 && address < 0x23c0) {
            nameTableWrite(ntable1[0], address - 0x2000, value)
        } else if (address >= 0x23c0 && address < 0x2400) {
            attribTableWrite(ntable1[0], address - 0x23c0, value)
        } else if (address >= 0x2400 && address < 0x27c0) {
            nameTableWrite(ntable1[1], address - 0x2400, value)
        } else if (address >= 0x27c0 && address < 0x2800) {
            attribTableWrite(ntable1[1], address - 0x27c0, value)
        } else if (address >= 0x2800 && address < 0x2bc0) {
            nameTableWrite(ntable1[2], address - 0x2800, value)
        } else if (address >= 0x2bc0 && address < 0x2c00) {
            attribTableWrite(ntable1[2], address - 0x2bc0, value)
        } else if (address >= 0x2c00 && address < 0x2fc0) {
            nameTableWrite(ntable1[3], address - 0x2c00, value)
        } else if (address >= 0x2fc0 && address < 0x3000) {
            attribTableWrite(ntable1[3], address - 0x2fc0, value)
        } else if (address >= 0x3f00 && address < 0x3f20) {
            updatePalettes()
        }
    }

    // Reads data from $3f00 to $3f1f into the two buffered palettes.
    fun updatePalettes() {
        for (i in 0 until 16) {
            if (f_dispType == 0) {
                imgPalette[i] = palTable.getEntry(
                    vramMem[0x3f00 + i] and 63,
                )
            } else {
                imgPalette[i] = palTable.getEntry(
                    vramMem[0x3f00 + i] and 0x30,
                )
            }
        }
        for (i in 0 until 16) {
            if (f_dispType == 0) {
                sprPalette[i] = palTable.getEntry(
                    vramMem[0x3f10 + i] and 63,
                )
            } else {
                sprPalette[i] = palTable.getEntry(
                    vramMem[0x3f10 + i] and 0x30,
                )
            }
        }
    }

    // Updates the internal pattern table buffers with this new byte.
    fun patternWrite(address: Int, value: Int) {
        val tileIndex = address shr 4
        val leftOver = address and 15
        if (leftOver < 8) {
            ptTile[tileIndex].setScanline(
                leftOver,
                value,
                vramMem[address + 8],
            )
        } else {
            ptTile[tileIndex].setScanline(
                leftOver - 8,
                vramMem[address - 8],
                value,
            )
        }
    }

    // Updates the internal name table buffers with this new byte.
    fun nameTableWrite(index: Int, address: Int, value: Int) {
        nameTable[index].tile[address] = value

        // Update Sprite #0 hit:
        val bufferScan = scanline + 1 - 21
        checkSprite0(bufferScan)
    }

    // Updates the internal pattern table buffers with this new attribute byte.
    fun attribTableWrite(index: Int, address: Int, value: Int) {
        nameTable[index].writeAttrib(address, value)
        // Also store the raw attribute byte in the tile array at offset 0x3C0
        // (= 960 = 30*32). This is the "attributes as tiles" quirk.
        nameTable[index].tile[0x3c0 + address] = value
    }

    // Updates the internally buffered sprite data with this new byte of info.
    fun spriteRamWriteUpdate(address: Int, value: Int) {
        val tIndex = address shr 2

        if (tIndex == 0) {
            val bufferScan = scanline + 1 - 21
            checkSprite0(bufferScan)
        }

        when (address and 3) {
            0 -> {
                // Y coordinate
                sprY[tIndex] = value
            }
            1 -> {
                // Tile index
                sprTile[tIndex] = value
            }
            2 -> {
                // Attributes
                vertFlip[tIndex] = (value shr 7) and 1
                horiFlip[tIndex] = (value shr 6) and 1
                bgPriority[tIndex] = (value shr 5) and 1
                sprCol[tIndex] = (value and 3) shl 2
            }
            3 -> {
                // X coordinate
                sprX[tIndex] = value
            }
        }
    }

    fun isPixelWhite(x: Int, y: Int): Boolean {
        triggerRendering()
        return buffer[(y shl 8) + x] == 0xffffff
    }
}
