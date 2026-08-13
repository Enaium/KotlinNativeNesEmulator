@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package cn.enaium.nes

import cn.enaium.sdl.SDL
import kotlin.native.CName
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.get
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking

/**
 * SDL3 Android entry point.
 *
 * `EmulatorActivity` (a SDLActivity subclass) loads `libmain.so` and calls the
 * exported `SDL_main` symbol on a dedicated SDL thread. SDL itself was already
 * initialized by the Java activity. The ROM path picked in the Compose
 * launcher activity is passed as the first command line argument.
 */
@CName("SDL_main")
fun sdlMain(argc: Int, argv: CPointer<CPointerVar<ByteVar>>?): Int {
    var rom: ByteArray? = null
    if (argc > 1 && argv != null) {
        val path = argv[1]?.toKString()
        if (path != null && path.isNotEmpty()) {
            rom = runBlocking { readRomFile(path) }
        }
    }
    if (rom == null) {
        println("No ROM passed to the emulator.")
    }
    // desktop=false: the OS controls the window size on Android (the frame is
    // still scaled proportionally and centered by the renderer).
    NesApp(rom, desktop = false).run()
    SDL.quit()
    return 0
}
