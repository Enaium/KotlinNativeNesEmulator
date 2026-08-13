import cn.enaium.nes.NesApp
import cn.enaium.nes.readRomFile
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val path = args.firstOrNull()
    var rom: ByteArray? = null
    if (path != null) {
        rom = runBlocking { readRomFile(path) }
        if (rom == null) {
            println("Could not read ROM file: $path")
        }
    }
    NesApp(rom).run()
}
