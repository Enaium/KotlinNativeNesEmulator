package cn.enaium.nes.core.papu

class ChannelDM(val papu: PAPU) {
    companion object {
        const val MODE_NORMAL = 0
        const val MODE_LOOP = 1
        const val MODE_IRQ = 2
    }

    var isEnabled = false
    var hasSample = false
    var irqGenerated = false
    var playMode = MODE_NORMAL
    var dmaFrequency = 0
    var dmaCounter = 0
    var deltaCounter = 0
    var playStartAddress = 0
    var playAddress = 0
    var playLength = 0
    var playLengthCounter = 0
    var sample = 0
    var dacLsb = 0
    var shiftCounter = 0
    var reg4012 = 0
    var reg4013 = 0
    var data = 0
    var lastFetchedByte = 0

    fun clockDmc() {
        // Only alter DAC value if the sample buffer has data:
        if (hasSample) {
            if ((data and 1) == 0) {
                // Decrement delta:
                if (deltaCounter > 0) {
                    deltaCounter -= 1
                }
            } else {
                // Increment delta:
                if (deltaCounter < 63) {
                    deltaCounter += 1
                }
            }

            // Update sample value:
            sample = if (isEnabled) (deltaCounter shl 1) + dacLsb else 0

            // Update shift register:
            data = data shr 1
        }

        dmaCounter -= 1
        if (dmaCounter <= 0) {
            // No more sample bits.
            hasSample = false
            endOfSample()
            dmaCounter = 8
        }

        if (irqGenerated) {
            papu.nes.cpu.requestIrq(papu.nes.cpu.IRQ_NORMAL)
        }
    }

    fun endOfSample() {
        if (playLengthCounter == 0 && playMode == MODE_LOOP) {
            // Start from beginning of sample:
            playAddress = playStartAddress
            playLengthCounter = playLength
        }

        if (playLengthCounter > 0) {
            // Fetch next sample:
            nextSample()

            if (playLengthCounter == 0) {
                // Last byte of sample fetched, generate IRQ:
                if (playMode == MODE_IRQ) {
                    // Generate IRQ:
                    irqGenerated = true
                }
            }
        }
    }

    fun nextSample() {
        // Fetch byte:
        data = papu.nes.mmap?.load(playAddress) ?: 0
        // On real hardware, the DMA fetch puts this byte on the CPU data bus.
        // Store it so cpu.load() can detect DMA bus hijacking mid-instruction.
        lastFetchedByte = data
        papu.nes.cpu.haltCycles(4)

        playLengthCounter -= 1
        playAddress += 1
        if (playAddress > 0xffff) {
            playAddress = 0x8000
        }

        hasSample = true
    }

    fun writeReg(address: Int, value: Int) {
        if (address == 0x4010) {
            // Play mode, DMA Frequency
            if (value shr 6 == 0) {
                playMode = MODE_NORMAL
            } else if (((value shr 6) and 1) == 1) {
                playMode = MODE_LOOP
            } else if (value shr 6 == 2) {
                playMode = MODE_IRQ
            }

            if ((value and 0x80) == 0) {
                irqGenerated = false
            }

            dmaFrequency = papu.getDmcFrequency(value and 0xf)
        } else if (address == 0x4011) {
            // Delta counter load register:
            deltaCounter = (value shr 1) and 63
            dacLsb = value and 1
            sample = (deltaCounter shl 1) + dacLsb // update sample value
        } else if (address == 0x4012) {
            // DMA address load register.
            // Only updates the start address register - the active playAddress is
            // loaded from playStartAddress when a sample restart occurs (via $4015).
            playStartAddress = (value shl 6) or 0x0c000
            reg4012 = value
        } else if (address == 0x4013) {
            // Length of play code.
            // Only updates the length register - the active playLengthCounter is
            // loaded from playLength when a sample restart occurs (via $4015 or loop).
            playLength = (value shl 4) + 1
            reg4013 = value
        } else if (address == 0x4015) {
            // DMC/IRQ Status
            // Writing $4015 always clears the DMC IRQ flag first, before any
            // other effects.
            irqGenerated = false

            if (((value shr 4) and 1) == 0) {
                // Disable: set bytes remaining to 0.
                playLengthCounter = 0
            } else {
                // Enable: only restart the sample if bytes remaining is 0.
                if (playLengthCounter == 0) {
                    playAddress = playStartAddress
                    playLengthCounter = playLength
                    // On real hardware, when DMC is enabled and the sample buffer
                    // is empty, a DMA fetch fires within a few CPU cycles. Trigger
                    // it immediately so the DMASync loop in test ROMs can detect
                    // the first fetch.
                    if (!hasSample && playLengthCounter > 0) {
                        nextSample()
                        dmaCounter = 8
                        shiftCounter = dmaFrequency
                        // If the immediate DMA fetch consumed the last byte (e.g. a
                        // 1-byte sample), set the IRQ flag just like endOfSample does.
                        if (playLengthCounter == 0 && playMode == MODE_IRQ) {
                            irqGenerated = true
                        }
                    }
                }
            }
        }
    }

    fun setEnabledValue(value: Boolean) {
        // Just track the enable flag. The restart logic (reloading address and
        // length counter) is handled in writeReg for $4015, which is always
        // called after setEnabled in the $4015 write path.
        isEnabled = value
    }

    fun getLengthStatus(): Int {
        return if (playLengthCounter == 0 || !isEnabled) 0 else 1
    }

    fun getIrqStatus(): Int {
        return if (irqGenerated) 1 else 0
    }
}
