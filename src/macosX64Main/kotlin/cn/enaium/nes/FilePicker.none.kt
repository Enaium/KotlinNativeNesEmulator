package cn.enaium.nes

// FileKit dialogs are not available on this target; pass the ROM path as a
// command line argument instead.
actual suspend fun pickRomFile(): ByteArray? = null
