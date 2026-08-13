package cn.enaium.nes

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.readBytes

/**
 * FileKit file picker for native targets that ship dialogs:
 * Windows (mingwX64), macOS arm64 (macosArm64) and iOS.
 */
actual suspend fun pickRomFile(): ByteArray? {
    val file: PlatformFile? = FileKit.openFilePicker(type = FileKitType.File("nes"))
    return file?.readBytes()
}
