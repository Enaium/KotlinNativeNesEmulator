package cn.enaium.nes.android

import org.libsdl.app.SDLActivity

/**
 * Second activity: hosts the native emulator. SDLActivity loads libmain.so
 * (which statically links SDL3 and exports SDL_main) and calls SDL_main on a
 * dedicated thread, passing the arguments returned by [getArguments].
 */
class EmulatorActivity : SDLActivity() {

    override fun getLibraries(): Array<String> = arrayOf("main")

    override fun getArguments(): Array<String> {
        val path = intent?.getStringExtra("rom_path")
        return if (path != null) arrayOf(path) else emptyArray()
    }
}
