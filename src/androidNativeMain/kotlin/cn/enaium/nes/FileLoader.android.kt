package cn.enaium.nes

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

actual suspend fun pickRomFile(): ByteArray? {
    // The androidNative targets cannot use FileKit; a ROM is picked in the
    // Compose launcher activity and passed to SDL_main as an argument instead.
    return null
}

actual suspend fun readRomFile(path: String): ByteArray? = readRomFileSync(path)

internal fun readRomFileSync(path: String): ByteArray? {
    return try {
        SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray() }
    } catch (e: Throwable) {
        null
    }
}
