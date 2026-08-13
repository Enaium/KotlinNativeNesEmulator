package cn.enaium.nes.core.papu

import cn.enaium.nes.core.NES
import kotlin.math.floor

private const val CPU_FREQ_NTSC = 1789772.5

// Frame counter step timing tables (in CPU cycles).
// The APU frame counter fires at these specific cycle positions within each
// sequence. On real hardware, the APU clock is half the CPU clock, so these
// correspond to APU cycles 3728.5, 7456.5, 11185.5, 14914, 14914.5 etc.
// In 4-step mode, the IRQ flag is set 1 CPU cycle before the clock event
// (at 29828 vs 29829), so step 3 is split into two sub-steps.
// See https://www.nesdev.org/wiki/APU_Frame_Counter
private val FRAME_STEPS_4 = intArrayOf(7457, 14913, 22371, 29828, 29829)
// 5-step mode step 3 fires at 29829 per the nesdev wiki, not 29828. This is
// fine because fireFrameStep step 3 in 5-step mode is a no-op (no clock or IRQ).
private val FRAME_STEPS_5 = intArrayOf(7457, 14913, 22371, 29829, 37281)
private const val FRAME_PERIOD_4 = 29830 // Total CPU cycles for 4-step sequence
private const val FRAME_PERIOD_5 = 37282 // Total CPU cycles for 5-step sequence

class PAPU(val nes: NES) {
    val square1: ChannelSquare
    val square2: ChannelSquare
    val triangle: ChannelTriangle
    val noise: ChannelNoise
    val dmc: ChannelDM

    var startedPlaying = false
    var recordOutput = false
    var triValue = 0

    // DC removal vars:
    var prevSampleL = 0.0
    var prevSampleR = 0.0
    var smpAccumL = 0.0
    var smpAccumR = 0.0

    // DAC range:
    var dacRange = 0
    var dcValue = 0.0

    // Master volume:
    var masterVolume = 256

    // Panning:
    var panning = intArrayOf(80, 170, 100, 150, 128)

    var sampleRate: Int
    var sampleTimerMax: Int
    var sampleTimer = 0
    var frameCycleCounter = 0
    var frameStep = 0
    var countSequence = 0
    var sampleCount = 0
    var frameIrqEnabled = false
    var frameIrqActive = false
    // Deferred clearing of the frame IRQ flag: on real hardware, reading $4015
    // doesn't clear bit 6 immediately. The clear takes effect at the next APU
    // "get" cycle (the APU clock runs at half the CPU rate, so get/put phases
    // alternate every CPU cycle).
    var frameIrqClearPending = false
    // APU cycle parity tracks the CPU cycle count modulo 2, determining which
    // APU half-cycle phase we're on (get or put).
    var apuCycleParity = 0
    var accCount = 0
    var smpSquare1 = 0
    var smpSquare2 = 0
    var smpTriangle = 0
    var smpDmc = 0
    var channelEnableValue = 0xff
    var extraCycles = 0
    var maxSample = -500000.0
    var minSample = 500000.0

    var stereoPosLSquare1 = 0
    var stereoPosLSquare2 = 0
    var stereoPosLTriangle = 0
    var stereoPosLNoise = 0
    var stereoPosLDMC = 0
    var stereoPosRSquare1 = 0
    var stereoPosRSquare2 = 0
    var stereoPosRTriangle = 0
    var stereoPosRNoise = 0
    var stereoPosRDMC = 0

    lateinit var lengthLookup: IntArray
    lateinit var dmcFreqLookup: IntArray
    lateinit var noiseWavelengthLookup: IntArray
    lateinit var square_table: IntArray
    lateinit var tnd_table: IntArray

    init {
        square1 = ChannelSquare(this, true)
        square2 = ChannelSquare(this, false)
        triangle = ChannelTriangle(this)
        noise = ChannelNoise(this)
        dmc = ChannelDM(this)

        setPanningValues(panning)

        // Initialize lookup tables:
        initLengthLookup()
        initDmcFrequencyLookup()
        initNoiseWavelengthLookup()
        initDACtables()

        // Init sound registers:
        for (i in 0 until 0x14) {
            if (i == 0x10) {
                writeReg(0x4010, 0x10)
            } else {
                writeReg(0x4000 + i, 0)
            }
        }

        sampleRate = nes.opts.sampleRate
        sampleTimerMax = floor((1024.0 * CPU_FREQ_NTSC) / sampleRate).toInt()
        sampleTimer = 0
        updateChannelEnable(0)
        frameCycleCounter = 0
        frameStep = 0
        countSequence = 0
        sampleCount = 0
        frameIrqEnabled = false
        frameIrqActive = false
        frameIrqClearPending = false
        apuCycleParity = 0
        accCount = 0
        smpSquare1 = 0
        smpSquare2 = 0
        smpTriangle = 0
        smpDmc = 0
        channelEnableValue = 0xff
        extraCycles = 0
        maxSample = -500000.0
        minSample = 500000.0
    }

    fun readReg(address: Int): Int {
        // Read 0x4015:
        var tmp = 0
        tmp = tmp or square1.getLengthStatus()
        tmp = tmp or (square2.getLengthStatus() shl 1)
        tmp = tmp or (triangle.getLengthStatus() shl 2)
        tmp = tmp or (noise.getLengthStatus() shl 3)
        tmp = tmp or (dmc.getLengthStatus() shl 4)
        // Bit 5 is open bus (not driven by APU), comes from CPU data bus
        tmp = tmp or (nes.cpu.dataBus and 0x20)
        // Frame interrupt flag: reflects whether the flag is set, regardless of
        // the IRQ inhibit bit in $4017.
        tmp = tmp or ((if (frameIrqActive) 1 else 0) shl 6)
        tmp = tmp or (dmc.getIrqStatus() shl 7)

        // Reading $4015 schedules the frame interrupt flag for clearing, but
        // the actual clear is deferred to the next APU "get" cycle.
        if (frameIrqActive) {
            frameIrqClearPending = true
        }

        return tmp and 0xff
    }

    fun writeReg(address: Int, value: Int) {
        if (address >= 0x4000 && address < 0x4004) {
            // Square Wave 1 Control
            square1.writeReg(address, value)
        } else if (address >= 0x4004 && address < 0x4008) {
            // Square 2 Control
            square2.writeReg(address, value)
        } else if (address >= 0x4008 && address < 0x400c) {
            // Triangle Control
            triangle.writeReg(address, value)
        } else if (address >= 0x400c && address <= 0x400f) {
            // Noise Control
            noise.writeReg(address, value)
        } else if (address == 0x4010) {
            // DMC Play mode & DMA frequency
            dmc.writeReg(address, value)
        } else if (address == 0x4011) {
            // DMC Delta Counter
            dmc.writeReg(address, value)
        } else if (address == 0x4012) {
            // DMC Play code starting address
            dmc.writeReg(address, value)
        } else if (address == 0x4013) {
            // DMC Play code length
            dmc.writeReg(address, value)
        } else if (address == 0x4015) {
            // Channel enable
            updateChannelEnable(value)

            // DMC/IRQ Status
            dmc.writeReg(address, value)
        } else if (address == 0x4017) {
            // Frame counter control
            // Bit 7: sequence mode (0=4-step, 1=5-step)
            // Bit 6: IRQ inhibit (0=IRQs enabled, 1=IRQs disabled)
            countSequence = (value shr 7) and 1
            // Writing $4017 resets the frame counter's internal divider, but on
            // real hardware the reset is delayed after the write cycle. Since
            // the emulator clocks the full STA instruction's cycles (4 for STA
            // absolute) after writeReg, we compensate by starting the counter
            // negative so it reaches 0 at the true reset point.
            val cpu = nes.cpu
            val pendingCycles = cpu.instrBusCycles + 1 - cpu.apuCatchupCycles
            val writeParity = (apuCycleParity + pendingCycles) and 1
            // "get" phase (odd): -6 -> after STA (4 cycles) -> -2, after 2 cycles -> 0
            // "put" phase (even): -7 -> after STA (4 cycles) -> -3, after 3 cycles -> 0
            frameCycleCounter = -7 + writeParity
            frameStep = 0

            if (value and 0x40 != 0) {
                // IRQ inhibit set: clear the frame interrupt flag and prevent
                // future frame IRQs from firing
                frameIrqEnabled = false
                frameIrqActive = false
                frameIrqClearPending = false
            } else {
                // IRQ inhibit clear: enable frame IRQs (flag is not affected)
                frameIrqEnabled = true
            }

            if (countSequence == 1) {
                // 5-step mode: immediately clock all quarter-frame and half-frame
                // units on the write cycle
                clockQuarterFrame()
                clockHalfFrame()
            }
        }
    }

    // Updates channel enable status.
    // This is done on writes to the channel enable register (0x4015).
    fun updateChannelEnable(value: Int) {
        channelEnableValue = value and 0xffff
        square1.setEnabledValue((value and 1) != 0)
        square2.setEnabledValue((value and 2) != 0)
        triangle.setEnabledValue((value and 4) != 0)
        noise.setEnabledValue((value and 8) != 0)
        dmc.setEnabledValue((value and 16) != 0)
    }

    // Clocks all APU channel timers and the frame counter by nCycles CPU cycles.
    // Called once per instruction from the frame loop with the total cycle count.
    // frameCounterAlreadyAdvanced is the number of frame counter cycles already
    // advanced mid-instruction by APU catch-up (advanceFrameCounter).
    fun clockFrameCounter(nCycles: Int, frameCounterAlreadyAdvanced: Int = 0) {
        var nCycles = nCycles
        val frameCounterCycles = nCycles - frameCounterAlreadyAdvanced

        // Process deferred frame IRQ clear and update APU cycle parity for
        // the remaining cycles not yet advanced by advanceFrameCounter.
        processFrameIrqClear(frameCounterCycles)
        apuCycleParity = (apuCycleParity + frameCounterCycles) and 1

        // Don't process channel ticks beyond next sampling:
        nCycles += extraCycles
        val maxCycles = sampleTimerMax - sampleTimer
        if ((nCycles shl 10) > maxCycles) {
            extraCycles = ((nCycles shl 10) - maxCycles) shr 10
            nCycles -= extraCycles
        } else {
            extraCycles = 0
        }

        val dmc = this.dmc
        val triangle = this.triangle
        val square1 = this.square1
        val square2 = this.square2
        val noise = this.noise

        // Clock DMC:
        if (dmc.isEnabled) {
            dmc.shiftCounter -= (nCycles shl 3)
            while (dmc.shiftCounter <= 0 && dmc.dmaFrequency > 0) {
                dmc.shiftCounter += dmc.dmaFrequency
                dmc.clockDmc()
            }
        }

        // Clock Triangle channel Prog timer:
        if (triangle.progTimerMax > 0) {
            triangle.progTimerCount -= nCycles
            while (triangle.progTimerCount <= 0) {
                triangle.progTimerCount += triangle.progTimerMax + 1
                if (triangle.linearCounter > 0 && triangle.lengthCounter > 0) {
                    triangle.triangleCounter += 1
                    triangle.triangleCounter = triangle.triangleCounter and 0x1f

                    if (triangle.isEnabled) {
                        if (triangle.triangleCounter >= 0x10) {
                            // Normal value.
                            triangle.sampleValue = triangle.triangleCounter and 0xf
                        } else {
                            // Inverted value.
                            triangle.sampleValue = 0xf - (triangle.triangleCounter and 0xf)
                        }
                        triangle.sampleValue = triangle.sampleValue shl 4
                    }
                }
            }
        }

        // Clock Square channel 1 Prog timer:
        square1.progTimerCount -= nCycles
        if (square1.progTimerCount <= 0) {
            square1.progTimerCount += (square1.progTimerMax + 1) shl 1

            square1.squareCounter += 1
            square1.squareCounter = square1.squareCounter and 0x7
            square1.updateSampleValue()
        }

        // Clock Square channel 2 Prog timer:
        square2.progTimerCount -= nCycles
        if (square2.progTimerCount <= 0) {
            square2.progTimerCount += (square2.progTimerMax + 1) shl 1

            square2.squareCounter += 1
            square2.squareCounter = square2.squareCounter and 0x7
            square2.updateSampleValue()
        }

        // Clock noise channel Prog timer:
        var acc_c = nCycles
        if (noise.progTimerCount - acc_c > 0) {
            // Do all cycles at once:
            noise.progTimerCount -= acc_c
            noise.accCount += acc_c
            noise.accValue += acc_c * noise.sampleValue
        } else {
            // Slow-step:
            while (acc_c > 0) {
                acc_c -= 1
                noise.progTimerCount -= 1
                if (noise.progTimerCount <= 0 && noise.progTimerMax > 0) {
                    // Update noise shift register:
                    noise.shiftReg = noise.shiftReg shl 1
                    noise.tmp =
                        ((noise.shiftReg shl (if (noise.randomMode == 0) 1 else 6)) xor noise.shiftReg) and
                            0x8000
                    if (noise.tmp != 0) {
                        // Sample value must be 0.
                        noise.shiftReg = noise.shiftReg or 0x01
                        noise.randomBit = 0
                        noise.sampleValue = 0
                    } else {
                        // Find sample value:
                        noise.randomBit = 1
                        if (noise.isEnabled && noise.lengthCounter > 0) {
                            noise.sampleValue = noise.masterVolume
                        } else {
                            noise.sampleValue = 0
                        }
                    }

                    noise.progTimerCount += noise.progTimerMax
                }

                noise.accValue += noise.sampleValue
                noise.accCount += 1
            }
        }

        // Frame IRQ handling:
        if (frameIrqEnabled && frameIrqActive) {
            nes.cpu.requestIrq(nes.cpu.IRQ_NORMAL)
        }

        // Clock frame counter: fire steps at the correct CPU cycle positions.
        // Uses the uncapped cycle count to maintain accurate timing.
        _advanceFrameSteps(frameCounterCycles)

        // Accumulate sample value:
        accSample(nCycles)

        // Clock sample timer:
        sampleTimer += (nCycles shl 10)
        if (sampleTimer >= sampleTimerMax) {
            // Sample channels:
            sample()
            sampleTimer -= sampleTimerMax
        }
    }

    // Process the deferred frame IRQ flag clear. On real hardware, reading
    // $4015 schedules the clear for the next APU "get" cycle (which happens
    // every 2 CPU cycles). This must be called BEFORE updating apuCycleParity
    // for the current advance, so it sees the parity at the start of the period.
    // See https://www.nesdev.org/wiki/APU_Frame_Counter
    fun processFrameIrqClear(nCycles: Int) {
        if (!frameIrqClearPending || nCycles <= 0) return
        // Determine how many CPU cycles until the next APU "get" boundary.
        val cyclesToNextGet = if ((apuCycleParity and 1) == 0) 1 else 2
        if (nCycles >= cyclesToNextGet) {
            frameIrqActive = false
            frameIrqClearPending = false
        }
    }

    // Advance only the frame counter steps without clocking channel timers,
    // DMC, or audio sampling. Used by CPU APU catch-up to update frame counter
    // state (length counters, envelopes) before $4015 reads, without disturbing
    // DMC DMA timing or audio generation.
    fun advanceFrameCounter(nCycles: Int) {
        processFrameIrqClear(nCycles)
        apuCycleParity = (apuCycleParity + nCycles) and 1
        _advanceFrameSteps(nCycles)
    }

    // Advance frame counter steps and handle period wrap. Shared by both
    // clockFrameCounter (full APU tick) and advanceFrameCounter (catch-up only).
    // The step loop and period wrap are separated: steps fire when the counter
    // reaches each step's cycle position, and the period wrap only occurs when
    // the counter reaches the full period length (not immediately after the
    // last step). This matters because in 4-step mode, the last step fires at
    // 29829 but the period wrap (and 3rd IRQ assertion) occurs at 29830.
    // See https://www.nesdev.org/wiki/APU_Frame_Counter
    fun _advanceFrameSteps(frameCounterCycles: Int) {
        frameCycleCounter += frameCounterCycles
        val steps = if (countSequence == 0) FRAME_STEPS_4 else FRAME_STEPS_5
        val period = if (countSequence == 0) FRAME_PERIOD_4 else FRAME_PERIOD_5
        while (true) {
            if (frameStep < steps.size && frameCycleCounter >= steps[frameStep]) {
                fireFrameStep(frameStep)
                frameStep += 1
            } else if (frameStep >= steps.size && frameCycleCounter >= period) {
                // Period wrap: reset the frame counter for the next sequence.
                frameStep = 0
                frameCycleCounter -= period
                // In 4-step mode, the IRQ flag is asserted for 3 consecutive CPU
                // cycles: at 29828 (step 3), 29829 (step 4), and 29830 (period wrap).
                // On the 3rd cycle (period wrap), the flag is set only if the IRQ
                // inhibit flag is clear. If inhibit is set, the flag is actively
                // cleared (it was unconditionally set on cycles 29828-29829).
                // See https://www.nesdev.org/wiki/APU_Frame_Counter
                if (countSequence == 0) {
                    frameIrqActive = frameIrqEnabled
                    frameIrqClearPending = false
                }
            } else {
                break
            }
        }
    }

    fun accSample(cycles: Int) {
        // Special treatment for triangle channel - need to interpolate.
        if (triangle.sampleCondition) {
            triValue = floor(
                (triangle.progTimerCount shl 4).toDouble() / (triangle.progTimerMax + 1).toDouble(),
            ).toInt()
            if (triValue > 16) {
                triValue = 16
            }
            if (triangle.triangleCounter >= 16) {
                triValue = 16 - triValue
            }

            // Add non-interpolated sample value:
            triValue += triangle.sampleValue
        }

        // Now sample normally:
        if (cycles == 2) {
            smpTriangle += triValue shl 1
            smpDmc += dmc.sample shl 1
            smpSquare1 += square1.sampleValue shl 1
            smpSquare2 += square2.sampleValue shl 1
            accCount += 2
        } else if (cycles == 4) {
            smpTriangle += triValue shl 2
            smpDmc += dmc.sample shl 2
            smpSquare1 += square1.sampleValue shl 2
            smpSquare2 += square2.sampleValue shl 2
            accCount += 4
        } else {
            smpTriangle += cycles * triValue
            smpDmc += cycles * dmc.sample
            smpSquare1 += cycles * square1.sampleValue
            smpSquare2 += cycles * square2.sampleValue
            accCount += cycles
        }
    }

    // Fire a frame counter step. Each step clocks different APU units depending
    // on the mode and step number.
    // See https://www.nesdev.org/wiki/APU_Frame_Counter
    fun fireFrameStep(step: Int) {
        if (countSequence == 0) {
            // Mode 0 (4-step):
            //   Step 0 (7457): quarter frame (envelope + linear counter)
            //   Step 1 (14913): half frame (quarter + length counter + sweep)
            //   Step 2 (22371): quarter frame
            //   Step 3 (29828): set frame IRQ flag only (1 cycle before clock)
            //   Step 4 (29829): half frame + set frame IRQ flag
            // On real hardware, the IRQ flag is asserted 1 CPU cycle before the
            // clock event at the end of the 4-step sequence. This is why step 3
            // is split from step 4.
            // See https://www.nesdev.org/wiki/APU_Frame_Counter
            when (step) {
                0 -> clockQuarterFrame()
                1 -> {
                    clockQuarterFrame()
                    clockHalfFrame()
                }
                2 -> clockQuarterFrame()
                3 -> {
                    // IRQ flag is UNCONDITIONALLY set 1 CPU cycle before the half-frame
                    // clock, regardless of the IRQ inhibit flag ($4017 bit 6).
                    frameIrqActive = true
                    frameIrqClearPending = false
                }
                4 -> {
                    clockQuarterFrame()
                    clockHalfFrame()
                    // IRQ flag continues to be unconditionally asserted on this cycle.
                    frameIrqActive = true
                    frameIrqClearPending = false
                }
            }
        } else {
            // Mode 1 (5-step):
            //   Step 0: quarter frame
            //   Step 1: half frame
            //   Step 2: quarter frame
            //   Step 3: nothing (no clocking, no IRQ)
            //   Step 4: half frame
            when (step) {
                0 -> clockQuarterFrame()
                1 -> {
                    clockQuarterFrame()
                    clockHalfFrame()
                }
                2 -> clockQuarterFrame()
                3 -> {
                    // Nothing happens at step 3 in 5-step mode
                }
                4 -> {
                    clockQuarterFrame()
                    clockHalfFrame()
                }
            }
        }
    }

    // Quarter frame: clock envelopes and triangle linear counter (~240Hz)
    fun clockQuarterFrame() {
        square1.clockEnvDecay()
        square2.clockEnvDecay()
        noise.clockEnvDecay()
        triangle.clockLinearCounter()
    }

    // Half frame: clock length counters and sweep units (~120Hz)
    fun clockHalfFrame() {
        triangle.clockLengthCounter()
        square1.clockLengthCounter()
        square2.clockLengthCounter()
        noise.clockLengthCounter()
        square1.clockSweep()
        square2.clockSweep()
    }

    // Samples the channels, mixes the output together, then writes to buffer.
    fun sample() {
        var sq_index: Int
        var tnd_index: Int

        if (accCount > 0) {
            smpSquare1 = smpSquare1 shl 4
            smpSquare1 = floor(smpSquare1.toDouble() / accCount.toDouble()).toInt()

            smpSquare2 = smpSquare2 shl 4
            smpSquare2 = floor(smpSquare2.toDouble() / accCount.toDouble()).toInt()

            smpTriangle = floor(smpTriangle.toDouble() / accCount.toDouble()).toInt()

            smpDmc = smpDmc shl 4
            smpDmc = floor(smpDmc.toDouble() / accCount.toDouble()).toInt()

            accCount = 0
        } else {
            smpSquare1 = square1.sampleValue shl 4
            smpSquare2 = square2.sampleValue shl 4
            smpTriangle = triangle.sampleValue
            smpDmc = dmc.sample shl 4
        }

        val smpNoise = floor((noise.accValue shl 4).toDouble() / noise.accCount.toDouble()).toInt()
        noise.accValue = smpNoise shr 4
        noise.accCount = 1

        // Stereo sound.

        // Left channel:
        sq_index = (smpSquare1 * stereoPosLSquare1 + smpSquare2 * stereoPosLSquare2) shr 8
        tnd_index =
            (3 * smpTriangle * stereoPosLTriangle +
                (smpNoise shl 1) * stereoPosLNoise +
                smpDmc * stereoPosLDMC) shr 8
        if (sq_index >= square_table.size) {
            sq_index = square_table.size - 1
        }
        if (tnd_index >= tnd_table.size) {
            tnd_index = tnd_table.size - 1
        }
        var sampleValueL = square_table[sq_index] + tnd_table[tnd_index] - dcValue

        // Right channel:
        sq_index = (smpSquare1 * stereoPosRSquare1 + smpSquare2 * stereoPosRSquare2) shr 8
        tnd_index =
            (3 * smpTriangle * stereoPosRTriangle +
                (smpNoise shl 1) * stereoPosRNoise +
                smpDmc * stereoPosRDMC) shr 8
        if (sq_index >= square_table.size) {
            sq_index = square_table.size - 1
        }
        if (tnd_index >= tnd_table.size) {
            tnd_index = tnd_table.size - 1
        }
        var sampleValueR = square_table[sq_index] + tnd_table[tnd_index] - dcValue

        // Remove DC from left channel:
        val smpDiffL = sampleValueL - prevSampleL
        prevSampleL += smpDiffL
        smpAccumL += smpDiffL - (smpAccumL.toInt() shr 10)
        sampleValueL = smpAccumL

        // Remove DC from right channel:
        val smpDiffR = sampleValueR - prevSampleR
        prevSampleR += smpDiffR
        smpAccumR += smpDiffR - (smpAccumR.toInt() shr 10)
        sampleValueR = smpAccumR

        // Write:
        if (sampleValueL > maxSample) {
            maxSample = sampleValueL
        }
        if (sampleValueL < minSample) {
            minSample = sampleValueL
        }

        if (nes.opts.onAudioSample != null) {
            nes.opts.onAudioSample?.invoke(
                (sampleValueL / 32768.0).toFloat(),
                (sampleValueR / 32768.0).toFloat(),
            )
        }

        // Reset sampled values:
        smpSquare1 = 0
        smpSquare2 = 0
        smpTriangle = 0
        smpDmc = 0
    }

    fun getLengthMax(value: Int): Int {
        return lengthLookup[value shr 3]
    }

    fun getDmcFrequency(value: Int): Int {
        if (value >= 0 && value < 0x10) {
            return dmcFreqLookup[value]
        }
        return 0
    }

    fun getNoiseWaveLength(value: Int): Int {
        if (value >= 0 && value < 0x10) {
            return noiseWavelengthLookup[value]
        }
        return 0
    }

    // Recalculate the sample timer for a non-standard host frame rate.
    // At 60fps the timer fires once per (CPU_FREQ / sampleRate) cycles. If the
    // host calls frame() at a different rate, scale proportionally so the total
    // audio output per second stays constant.
    fun setFrameRate(rate: Int) {
        sampleTimerMax = floor((1024.0 * CPU_FREQ_NTSC * rate) / (sampleRate * 60.0)).toInt()
    }

    fun setPanningValues(pos: IntArray) {
        for (i in 0 until 5) {
            panning[i] = pos[i]
        }
        updateStereoPos()
    }

    fun setMasterVolumeValue(value: Int) {
        var value = value
        if (value < 0) {
            value = 0
        }
        if (value > 256) {
            value = 256
        }
        masterVolume = value
        updateStereoPos()
    }

    fun updateStereoPos() {
        stereoPosLSquare1 = (panning[0] * masterVolume) shr 8
        stereoPosLSquare2 = (panning[1] * masterVolume) shr 8
        stereoPosLTriangle = (panning[2] * masterVolume) shr 8
        stereoPosLNoise = (panning[3] * masterVolume) shr 8
        stereoPosLDMC = (panning[4] * masterVolume) shr 8

        stereoPosRSquare1 = masterVolume - stereoPosLSquare1
        stereoPosRSquare2 = masterVolume - stereoPosLSquare2
        stereoPosRTriangle = masterVolume - stereoPosLTriangle
        stereoPosRNoise = masterVolume - stereoPosLNoise
        stereoPosRDMC = masterVolume - stereoPosLDMC
    }

    fun initLengthLookup() {
        lengthLookup = intArrayOf(
            0x0A, 0xFE,
            0x14, 0x02,
            0x28, 0x04,
            0x50, 0x06,
            0xA0, 0x08,
            0x3C, 0x0A,
            0x0E, 0x0C,
            0x1A, 0x0E,
            0x0C, 0x10,
            0x18, 0x12,
            0x30, 0x14,
            0x60, 0x16,
            0xC0, 0x18,
            0x48, 0x1A,
            0x10, 0x1C,
            0x20, 0x1E,
        )
    }

    fun initDmcFrequencyLookup() {
        dmcFreqLookup = IntArray(16)

        dmcFreqLookup[0x0] = 0xd60
        dmcFreqLookup[0x1] = 0xbe0
        dmcFreqLookup[0x2] = 0xaa0
        dmcFreqLookup[0x3] = 0xa00
        dmcFreqLookup[0x4] = 0x8f0
        dmcFreqLookup[0x5] = 0x7f0
        dmcFreqLookup[0x6] = 0x710
        dmcFreqLookup[0x7] = 0x6b0
        dmcFreqLookup[0x8] = 0x5f0
        dmcFreqLookup[0x9] = 0x500
        dmcFreqLookup[0xa] = 0x470
        dmcFreqLookup[0xb] = 0x400
        dmcFreqLookup[0xc] = 0x350
        dmcFreqLookup[0xd] = 0x2a0
        dmcFreqLookup[0xe] = 0x240
        dmcFreqLookup[0xf] = 0x1b0
    }

    fun initNoiseWavelengthLookup() {
        noiseWavelengthLookup = IntArray(16)

        noiseWavelengthLookup[0x0] = 0x004
        noiseWavelengthLookup[0x1] = 0x008
        noiseWavelengthLookup[0x2] = 0x010
        noiseWavelengthLookup[0x3] = 0x020
        noiseWavelengthLookup[0x4] = 0x040
        noiseWavelengthLookup[0x5] = 0x060
        noiseWavelengthLookup[0x6] = 0x080
        noiseWavelengthLookup[0x7] = 0x0a0
        noiseWavelengthLookup[0x8] = 0x0ca
        noiseWavelengthLookup[0x9] = 0x0fe
        noiseWavelengthLookup[0xa] = 0x17c
        noiseWavelengthLookup[0xb] = 0x1fc
        noiseWavelengthLookup[0xc] = 0x2fa
        noiseWavelengthLookup[0xd] = 0x3f8
        noiseWavelengthLookup[0xe] = 0x7f2
        noiseWavelengthLookup[0xf] = 0xfe4
    }

    fun initDACtables() {
        var max_sqr = 0
        var max_tnd = 0

        square_table = IntArray(32 * 16)
        tnd_table = IntArray(204 * 16)

        for (i in 0 until 32 * 16) {
            var value = 95.52 / (8128.0 / (i / 16.0) + 100.0)
            value *= 0.98411
            value *= 50000.0
            val ival = floor(value).toInt()

            square_table[i] = ival
            if (ival > max_sqr) {
                max_sqr = ival
            }
        }

        for (i in 0 until 204 * 16) {
            var value = 163.67 / (24329.0 / (i / 16.0) + 100.0)
            value *= 0.98411
            value *= 50000.0
            val ival = floor(value).toInt()

            tnd_table[i] = ival
            if (ival > max_tnd) {
                max_tnd = ival
            }
        }

        dacRange = max_sqr + max_tnd
        dcValue = dacRange / 2.0
    }
}
