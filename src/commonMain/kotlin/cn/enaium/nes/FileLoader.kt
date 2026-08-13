package cn.enaium.nes

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * Reads the ROM file at [path].
 *
 * Uses kotlinx-io's SystemFileSystem — the same backing store FileKit uses —
 * which is available on every target (JVM, native desktop, Android native).
 *
 * Returns null if the file does not exist or cannot be read.
 */
suspend fun readRomFile(path: String): ByteArray? {
    return try {
        val file = Path(path)
        if (SystemFileSystem.exists(file)) {
            SystemFileSystem.source(file).buffered().use { it.readByteArray() }
        } else {
            null
        }
    } catch (e: Throwable) {
        null
    }
}
