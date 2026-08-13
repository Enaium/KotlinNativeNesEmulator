package cn.enaium.nes

import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * Verifies the frame buffer byte order used by the renderer.
 *
 * The PPU buffer holds standard 0xRRGGBB colors (R in the high byte) and is
 * converted to RGB888 (3 bytes per pixel). Buffer 0x00D7CBFF ($33 of the
 * FCEUX default palette) must produce bytes [D7, CB, FF].
 */
class FrameBufferByteOrderTest {

    @Test
    fun rgbLayoutProducesRgbBytes() {
        val argb = intArrayOf(0x00d7cbff, 0x00000000)
        val rgb = ByteArray(6)
        argbToRgbBytes(argb, rgb)

        val expected = byteArrayOf(
            0xd7.toByte(), 0xcb.toByte(), 0xff.toByte(),
            0x00, 0x00, 0x00,
        )
        assertContentEquals(expected, rgb)
    }
}
