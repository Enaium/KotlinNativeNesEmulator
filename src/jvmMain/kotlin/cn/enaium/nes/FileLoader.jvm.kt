package cn.enaium.nes

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.readBytes
import java.io.File

actual suspend fun pickRomFile(): ByteArray? {
    val file: PlatformFile? = FileKit.openFilePicker(type = FileKitType.File("nes"))
    return file?.readBytes()
}

actual suspend fun readRomFile(path: String): ByteArray? {
    val file = File(path)
    return if (file.exists()) file.readBytes() else null
}
