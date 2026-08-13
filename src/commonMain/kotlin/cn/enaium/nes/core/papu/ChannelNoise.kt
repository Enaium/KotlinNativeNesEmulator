package cn.enaium.nes.core.papu

class ChannelNoise(val papu: PAPU) {
    var progTimerCount = 0
    var progTimerMax = 0
    var isEnabled = false
    var lengthCounter = 0
    var lengthCounterEnable = false
    var envDecayDisable = false
    var envDecayLoopEnable = false
    var envReset = false
    var shiftNow = false
    var envDecayRate = 0
    var envDecayCounter = 0
    var envVolume = 0
    var masterVolume = 0
    var shiftReg = 1
    var randomBit = 0
    var randomMode = 0
    var sampleValue = 0
    var tmp = 0
    var accValue = 0
    var accCount = 1

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

    fun updateSampleValue() {
        if (isEnabled && lengthCounter > 0) {
            sampleValue = randomBit * masterVolume
        }
    }

    fun writeReg(address: Int, value: Int) {
        if (address == 0x400c) {
            // Volume/Envelope decay:
            envDecayDisable = (value and 0x10) != 0
            envDecayRate = value and 0xf
            envDecayLoopEnable = (value and 0x20) != 0
            lengthCounterEnable = (value and 0x20) == 0
            if (envDecayDisable) {
                masterVolume = envDecayRate
            } else {
                masterVolume = envVolume
            }
        } else if (address == 0x400e) {
            // Programmable timer:
            progTimerMax = papu.getNoiseWaveLength(value and 0xf)
            randomMode = value shr 7
        } else if (address == 0x400f) {
            // Length counter - only loaded when the channel is enabled via $4015.
            if (isEnabled) {
                lengthCounter = papu.getLengthMax(value and 248)
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
