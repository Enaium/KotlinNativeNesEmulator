@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.nes

import kotlinx.cinterop.refTo
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

/**
 * Native desktop/mobile file reading via POSIX. All native targets here are
 * 64-bit, so `size_t` maps to `ULong`.
 *
 * The ROM picker itself is provided by the `nativeDialogsMain` source set on
 * targets that ship FileKit dialogs (Windows/macOS arm64/iOS); the remaining
 * targets pass the ROM path as a command line argument.
 */
actual suspend fun readRomFile(path: String): ByteArray? = readRomFileSync(path)

internal fun readRomFileSync(path: String): ByteArray? {
    val file = fopen(path, "rb") ?: return null
    return try {
        fseek(file, 0, SEEK_END)
        val size = ftell(file)
        fseek(file, 0, SEEK_SET)
        if (size <= 0) return null
        val buffer = ByteArray(size.toInt())
        val read = fread(buffer.refTo(0), 1u, size.toULong(), file)
        if (read < size.toULong()) return null
        buffer
    } finally {
        fclose(file)
    }
}
