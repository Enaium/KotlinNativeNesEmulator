package cn.enaium.nes

/**
 * Platform-specific ROM loading.
 *
 * - JVM desktop: FileKit native file picker dialog + file path reading.
 * - Native Windows (mingwX64), macOS arm64 (macosArm64) and iOS: FileKit
 *   pickers are available and used.
 * - Native Linux / macOS x64 / tvOS: no FileKit dialogs; the ROM path is
 *   passed as a command-line argument and read via POSIX.
 * - Android (androidNative*): the ROM is picked in the Compose launcher and
 *   passed to SDL_main as an argument; read via kotlinx-io.
 */
expect suspend fun pickRomFile(): ByteArray?

expect suspend fun readRomFile(path: String): ByteArray?
