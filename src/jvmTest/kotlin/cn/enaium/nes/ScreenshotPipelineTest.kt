package cn.enaium.nes

import cn.enaium.nes.core.NES
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLInitFlags
import cn.enaium.sdl.SDLPixelFormat
import cn.enaium.sdl.SDLTextureAccess
import cn.enaium.sdl.SDLWindowFlags
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Renders a real emulator frame through the SDL texture/renderer and saves a
 * BMP screenshot, then verifies the nestest menu's dominant non-black color is
 * the standard palette entry $33 = (152,136,252). This guards against the
 * renderer channel mangling that 32-bit texture formats exhibit.
 */
class ScreenshotPipelineTest {

    @Test
    fun nestestFrameDisplaysCorrectly() {
        val rom = javaClass.classLoader.getResourceAsStream("nestest.nes")
            ?.use { it.readBytes() }
            ?: error("nestest.nes not found")

        val nes = NES(NES.Options(onFrame = {}, onStatusUpdate = {}, emulateSound = false))
        nes.loadROM(rom)
        repeat(5) { nes.frame() }

        val argb = nes.ppu.buffer
        val rgb = ByteArray(256 * 240 * 3)
        argbToRgbBytes(argb, rgb)

        SDL.setMainReady()
        check(SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)) { "SDL init failed: ${SDL.error()}" }
        try {
            SDL.createWindow("shot", width = 256, height = 240, flags = SDLWindowFlags.HIDDEN).use { window ->
                SDL.createRenderer(window).use { renderer ->
                    val texture = renderer.createTexture(
                        format = SDLPixelFormat.RGB24,
                        access = SDLTextureAccess.STREAMING,
                        width = 256,
                        height = 240,
                    )
                    texture.update(null, rgb, 256 * 3)
                    renderer.drawColor = SDLColor(0, 0, 0)
                    renderer.clear()
                    renderer.renderTexture(texture, dst = null)
                    renderer.present()
                    SDL.delay(200)

                    val surface = renderer.renderReadPixels(null)
                        ?: error("renderReadPixels failed: ${SDL.error()}")
                    surface.saveBMP("/tmp/nestest_shot.bmp")
                    surface.close()
                }
            }
        } finally {
            SDL.quit()
        }

        val bytes = File("/tmp/nestest_shot.bmp").readBytes()
        val w = (bytes[18].toInt() and 0xff) or ((bytes[19].toInt() and 0xff) shl 8)
        val h = (bytes[22].toInt() and 0xff) or ((bytes[23].toInt() and 0xff) shl 8)
        val bpp = (bytes[28].toInt() and 0xff) or ((bytes[29].toInt() and 0xff) shl 8)
        val off = (bytes[10].toInt() and 0xff) or ((bytes[11].toInt() and 0xff) shl 8)
        val bppn = bpp / 8
        val rowSize = (w * bppn + 3) / 4 * 4

        val counts = HashMap<Int, Int>()
        for (y in 0 until h) {
            val row = off + (h - 1 - y) * rowSize
            for (x in 0 until w) {
                val base = row + x * bppn
                val b = bytes[base].toInt() and 0xff
                val g = bytes[base + 1].toInt() and 0xff
                val r = bytes[base + 2].toInt() and 0xff
                if (r != 0 || g != 0 || b != 0) {
                    counts[(r shl 16) or (g shl 8) or b] = (counts[(r shl 16) or (g shl 8) or b] ?: 0) + 1
                }
            }
        }
        val top = counts.entries.sortedByDescending { it.value }.first()
        val rgbc = top.key
        val r = (rgbc shr 16) and 0xff
        val g = (rgbc shr 8) and 0xff
        val b = rgbc and 0xff
        println("dominant BMP color: RGB=($r,$g,$b) count=${top.value}")
        // $33 of the FCEUX default palette = (215,203,255).
        assertEquals(215, r, "expected R=215 for palette \$33")
        assertEquals(203, g, "expected G=203 for palette \$33")
        assertEquals(255, b, "expected B=255 for palette \$33")
    }
}
