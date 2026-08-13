package cn.enaium.nes.core.papu

class ChannelTriangle(val papu: PAPU) {
    var progTimerCount = 0
    var progTimerMax = 0
    var triangleCounter = 0
    var isEnabled = false
    var sampleCondition = false
    var lengthCounter = 0
    var lengthCounterEnable = false
    var linearCounter = 0
    var lcLoadValue = 0
    var lcHalt = true
    var lcControl = false
    var tmp = 0
    var sampleValue = 0xf

    fun clockLengthCounter() {
        if (lengthCounterEnable && lengthCounter > 0) {
            lengthCounter -= 1
            if (lengthCounter == 0) {
                updateSampleCondition()
            }
        }
    }

    fun clockLinearCounter() {
        if (lcHalt) {
            // Load:
            linearCounter = lcLoadValue
            updateSampleCondition()
        } else if (linearCounter > 0) {
            // Decrement:
            linearCounter -= 1
            updateSampleCondition()
        }
        if (!lcControl) {
            // Clear halt flag:
            lcHalt = false
        }
    }

    fun getLengthStatus(): Int {
        return if (lengthCounter == 0 || !isEnabled) 0 else 1
    }

    fun readReg(address: Int): Int {
        return 0
    }

    fun writeReg(address: Int, value: Int) {
        if (address == 0x4008) {
            // New values for linear counter:
            lcControl = (value and 0x80) != 0
            lcLoadValue = value and 0x7f

            // Length counter enable:
            lengthCounterEnable = !lcControl
        } else if (address == 0x400a) {
            // Programmable timer:
            progTimerMax = progTimerMax and 0x700
            progTimerMax = progTimerMax or value
        } else if (address == 0x400b) {
            // Programmable timer, length counter
            progTimerMax = progTimerMax and 0xff
            progTimerMax = progTimerMax or ((value and 0x07) shl 8)
            // Length counter is only loaded when the channel is enabled via $4015.
            if (isEnabled) {
                lengthCounter = papu.getLengthMax(value and 0xf8)
            }
            lcHalt = true
        }

        updateSampleCondition()
    }

    fun clockProgrammableTimer(nCycles: Int) {
        if (progTimerMax > 0) {
            progTimerCount += nCycles
            while (progTimerMax > 0 && progTimerCount >= progTimerMax) {
                progTimerCount -= progTimerMax
                if (isEnabled && lengthCounter > 0 && linearCounter > 0) {
                    clockTriangleGenerator()
                }
            }
        }
    }

    fun clockTriangleGenerator() {
        triangleCounter += 1
        triangleCounter = triangleCounter and 0x1f
    }

    fun setEnabledValue(value: Boolean) {
        isEnabled = value
        if (!value) {
            lengthCounter = 0
        }
        updateSampleCondition()
    }

    fun updateSampleCondition() {
        sampleCondition = isEnabled && progTimerMax > 7 && linearCounter > 0 && lengthCounter > 0
    }
}
