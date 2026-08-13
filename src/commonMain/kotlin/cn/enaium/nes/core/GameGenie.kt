package cn.enaium.nes.core

class GameGenie {
    class Patch(
        val value: Int,
        val addr: Int,
        val wantskey: Boolean,
        val key: Int?,
    )

    var patches: List<Patch> = emptyList()
    var enabled = true
    // Callback invoked when patches or enabled state change, so the CPU
    // can swap its loadFromCartridge function pointer.
    var onChange: (() -> Unit)? = null

    fun setEnabledValue(enabled: Boolean) {
        this.enabled = enabled
        onChange?.invoke()
    }

    fun addCode(code: String) {
        val patch = decode(code)
            ?: throw IllegalArgumentException("Invalid Game Genie code: $code")
        patches = patches + patch
        onChange?.invoke()
    }

    fun addPatch(addr: Int, value: Int, key: Int?) {
        patches = patches + Patch(value, addr, false, key)
        onChange?.invoke()
    }

    fun removeAllCodes() {
        patches = emptyList()
        onChange?.invoke()
    }

    // Apply Game Genie patches to a value being read from the given address.
    // Game Genie works by intercepting ROM reads and substituting values.
    // The address is masked to 15 bits because Game Genie ignores the
    // highest bit (ROM is mirrored in $8000-$FFFF).
    fun applyCodes(addr: Int, value: Int): Int {
        if (!enabled) return value
        for (i in patches.indices) {
            if (patches[i].addr == (addr and 0x7fff)) {
                if (patches[i].key == null || patches[i].key == value) {
                    return patches[i].value
                }
            }
        }
        return value
    }

    fun decode(code: String): Patch? {
        if (code.contains(":")) return decodeHex(code)

        val digits = code.uppercase().map { toDigit(it) }
        if (digits.any { it < 0 }) return null

        var value = ((digits[0] and 8) shl 4) + ((digits[1] and 7) shl 4) + (digits[0] and 7)
        val addr =
            ((digits[3] and 7) shl 12) +
                ((digits[4] and 8) shl 8) +
                ((digits[5] and 7) shl 8) +
                ((digits[1] and 8) shl 4) +
                ((digits[2] and 7) shl 4) +
                (digits[3] and 8) +
                (digits[4] and 7)
        var key: Int? = null

        if (digits.size == 8) {
            value += digits[7] and 8
            key =
                ((digits[6] and 8) shl 4) +
                    ((digits[7] and 7) shl 4) +
                    (digits[5] and 8) +
                    (digits[6] and 7)
        } else {
            value += digits[5] and 8
        }

        val wantskey = (digits[2] shr 3) != 0

        return Patch(value, addr, wantskey, key)
    }

    private fun decodeHex(s: String): Patch? {
        val match = HEX_REGEX.matchEntire(s) ?: return null
        val addr = match.groupValues[1].toInt(16)
        val value = match.groupValues[2].toInt(16)
        val suffix = match.groupValues[3]
        val wantskey = suffix.isNotEmpty()
        val key = if (suffix.isNotEmpty() && suffix.length > 1) suffix.substring(1).toInt(16) else null
        return Patch(value, addr, wantskey, key)
    }

    private fun toDigit(letter: Char): Int {
        return LETTER_VALUES.indexOf(letter)
    }

    companion object {
        private const val LETTER_VALUES = "APZLGITYEOXUKSVN"
        private val HEX_REGEX = Regex("([0-9a-fA-F]+):([0-9a-fA-F]+)(\\?[0-9a-fA-F]*)?")
    }
}
