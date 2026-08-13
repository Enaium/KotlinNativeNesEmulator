package cn.enaium.nes.core.papu

class ChannelSquare(val papu: PAPU, private val sqr1: Boolean) {
    val dutyLookup = intArrayOf(
        0, 1, 0, 0, 0, 0, 0, 0,
        0, 1, 1, 0, 0, 0, 0, 0,
        0, 1, 1, 1, 1, 0, 0, 0,
        1, 0, 0, 1, 1, 1, 1, 1,
    )
    val impLookup = intArrayOf(
        1, -1, 0, 0, 0, 0, 0, 0,
        1, 0, -1, 0, 0, 0, 0, 0,
        1, 0, 0, 0, -1, 0, 0, 0,
        -1, 0, 1, 0, 0, 0, 0, 0,
    )

    var progTimerCount = 0
    var progTimerMax = 0
    var lengthCounter = 0
    var squareCounter = 0
    var sweepCounter = 0
    var sweepCounterMax = 0
    var sweepMode = 0
    var sweepShiftAmount = 0
    var envDecayRate = 0
    var envDecayCounter = 0
    var envVolume = 0
    var masterVolume = 0
    var dutyMode = 0
    var vol = 0
    var isEnabled = false
    var lengthCounterEnable = false
    var sweepActive = false
    var sweepCarry = false
    var envDecayDisable = false
    var envDecayLoopEnable = false
    var envReset = false
    var updateSweepPeriod = false
    var sweepResult = 0
    var sampleValue = 0

    fun clockLengthCounter() {
        if (lengthCounterEnable && lengthCounter > 0) {
            lengthCounter -= 1
            if (lengthCounter == 0) {
                updateSampleValue()
            }
        }
    }

    fun clockEnvDecay() {
        if (envReset) {
            // Reset envelope:
            envReset = false
            envDecayCounter = envDecayRate + 1
            envVolume = 0xf
        } else {
            envDecayCounter -= 1
            if (envDecayCounter <= 0) {
                // Normal handling:
                envDecayCounter = envDecayRate + 1
                if (envVolume > 0) {
                    envVolume -= 1
                } else {
                    envVolume = if (envDecayLoopEnable) 0xf else 0
                }
            }
        }

        if (envDecayDisable) {
            masterVolume = envDecayRate
        } else {
            masterVolume = envVolume
        }
        updateSampleValue()
    }

    fun clockSweep() {
        sweepCounter -= 1
        if (sweepCounter <= 0) {
            sweepCounter = sweepCounterMax + 1
            if (sweepActive && sweepShiftAmount > 0 && progTimerMax > 7) {
                // Calculate result from shifter:
                sweepCarry = false
                if (sweepMode == 0) {
                    progTimerMax += progTimerMax shr sweepShiftAmount
                    if (progTimerMax > 0x7ff) {
                        progTimerMax = 4095
                        sweepCarry = true
                    }
                } else {
                    progTimerMax =
                        progTimerMax - ((progTimerMax shr sweepShiftAmount) + (if (sqr1) 1 else 0))
                }
            }
        }

        if (updateSweepPeriod) {
            updateSweepPeriod = false
            sweepCounter = sweepCounterMax + 1
        }
    }

    fun updateSampleValue() {
        if (isEnabled && lengthCounter > 0 && progTimerMax > 7) {
            if (sweepMode == 0 && progTimerMax + (progTimerMax shr sweepShiftAmount) > 0x7ff) {
                sampleValue = 0
            } else {
                sampleValue = masterVolume * dutyLookup[(dutyMode shl 3) + squareCounter]
            }
        } else {
            sampleValue = 0
        }
    }

    fun writeReg(address: Int, value: Int) {
        val addrAdd = if (sqr1) 0 else 4
        if (address == 0x4000 + addrAdd) {
            // Volume/Envelope decay:
            envDecayDisable = (value and 0x10) != 0
            envDecayRate = value and 0xf
            envDecayLoopEnable = (value and 0x20) != 0
            dutyMode = (value shr 6) and 0x3
            lengthCounterEnable = (value and 0x20) == 0
            if (envDecayDisable) {
                masterVolume = envDecayRate
            } else {
                masterVolume = envVolume
            }
            updateSampleValue()
        } else if (address == 0x4001 + addrAdd) {
            // Sweep:
            sweepActive = (value and 0x80) != 0
            sweepCounterMax = (value shr 4) and 7
            sweepMode = (value shr 3) and 1
            sweepShiftAmount = value and 7
            updateSweepPeriod = true
        } else if (address == 0x4002 + addrAdd) {
            // Programmable timer:
            progTimerMax = progTimerMax and 0x700
            progTimerMax = progTimerMax or value
        } else if (address == 0x4003 + addrAdd) {
            // Programmable timer, length counter
            progTimerMax = progTimerMax and 0xff
            progTimerMax = progTimerMax or ((value and 0x7) shl 8)

            if (isEnabled) {
                lengthCounter = papu.getLengthMax(value and 0xf8)
            }

            envReset = true
        }
    }

    fun setEnabledValue(value: Boolean) {
        isEnabled = value
        if (!value) {
            lengthCounter = 0
        }
        updateSampleValue()
    }

    fun getLengthStatus(): Int {
        return if (lengthCounter == 0 || !isEnabled) 0 else 1
    }
}
