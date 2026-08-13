package cn.enaium.nes

import cn.enaium.nes.core.NES
import kotlin.test.Test

class RenderSanityTest {

    @Test
    fun ppuProducesNonBlackFrames() {
        val rom = javaClass.classLoader.getResourceAsStream("nestest.nes")
            ?.use { it.readBytes() }
            ?: error("nestest.nes not found in test resources")

        val nes = NES(
            NES.Options(
                onFrame = {},
                onStatusUpdate = {},
                emulateSound = false,
                sampleRate = 44100,
            ),
        )
        nes.loadROM(rom)

        // Run a few frames from the reset vector (the nestest menu renders text).
        var nonBlack = 0
        repeat(5) {
            nes.frame()
            val buf = nes.ppu.buffer
            nonBlack = maxOf(nonBlack, buf.count { it != 0 })
        }
        println("render sanity: max non-black pixels in one frame = $nonBlack")
        kotlin.test.assertTrue(nonBlack > 0, "PPU produced only black frames")
    }
}
