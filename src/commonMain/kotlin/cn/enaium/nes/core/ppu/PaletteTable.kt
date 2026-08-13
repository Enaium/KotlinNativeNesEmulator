package cn.enaium.nes.core.ppu

import kotlin.math.floor

class PaletteTable {
    var curTable = IntArray(64)
    var emphTable = arrayOfNulls<IntArray>(8)
    var currentEmph = -1

    fun loadNTSCPalette() {
        // FCEUX default palette (0xRRGGBB), the classic bright/vivid NES look
        // (e.g. $22 sky blue = (93,150,255)). It is already saturated, so no
        // extra boost is needed — unlike the raw 2C02 signal which looks
        // washed out on LCDs.
        curTable = intArrayOf(
            0x757575, 0x24188E, 0x0000AA, 0x45009E, 0x8E0075, 0xAA0010, 0xA60000, 0x7D0800,
            0x412C00, 0x004500, 0x005100, 0x003C14, 0x183C5D, 0x000000, 0x000000, 0x000000,
            0xBEBEBE, 0x0071EF, 0x2038EF, 0x8200F3, 0xBE00BE, 0xE70059, 0xDB2800, 0xCB4D0C,
            0x8A7100, 0x009600, 0x00AA00, 0x009238, 0x00828A, 0x000000, 0x000000, 0x000000,
            0xFFFFFF, 0x3CBEFF, 0x5D96FF, 0xCF8AFF, 0xF779FF, 0xFF75B6, 0xFF7561, 0xFF9A38,
            0xF3BE3C, 0x82D310, 0x4DDF49, 0x59FB9A, 0x00EBDB, 0x797979, 0x000000, 0x000000,
            0xFFFFFF, 0xAAE7FF, 0xC7D7FF, 0xD7CBFF, 0xFFC7FF, 0xFFC7DB, 0xFFBEB2, 0xFFDBAA,
            0xFFE7A2, 0xE3FFA2, 0xAAF3BE, 0xB2FFCF, 0x9EFFF3, 0xC7C7C7, 0x000000, 0x000000,
        )
        makeTables()
        setEmphasis(0)
    }

    fun loadPALPalette() {
        curTable = intArrayOf(
            0x525252, 0xB40000, 0xA00000, 0xB1003D, 0x740069, 0x00005B, 0x00005F, 0x001840, 0x002F10, 0x084A08, 0x006700, 0x124200, 0x6D2800, 0x000000, 0x000000, 0x000000,
            0xC4D5E7, 0xFF4000, 0xDC0E22, 0xFF476B, 0xD7009F, 0x680AD7, 0x0019BC, 0x0054B1, 0x006A5B, 0x008C03, 0x00AB00, 0x2C8800, 0xA47200, 0x000000, 0x000000, 0x000000,
            0xF8F8F8, 0xFFAB3C, 0xFF7981, 0xFF5BC5, 0xFF48F2, 0xDF49FF, 0x476DFF, 0x00B4F7, 0x00E0FF, 0x00E375, 0x03F42B, 0x78B82E, 0xE5E218, 0x787878, 0x000000, 0x000000,
            0xFFFFFF, 0xFFF2BE, 0xF8B8B8, 0xF8B8D8, 0xFFB6FF, 0xFFC3FF, 0xC7D1FF, 0x9ADAFF, 0x88EDF8, 0x83FFDD, 0xB8F8B8, 0xF5F8AC, 0xFFFFB0, 0xF8D8F8, 0x000000, 0x000000,
        )
        makeTables()
        setEmphasis(0)
    }

    fun makeTables() {
        var r: Double
        var g: Double
        var b: Double
        var col: Int
        var rFactor: Double
        var gFactor: Double
        var bFactor: Double

        // Calculate a table for each possible emphasis setting:
        for (emph in 0 until 8) {
            // Determine color component factors:
            rFactor = 1.0
            gFactor = 1.0
            bFactor = 1.0

            // NTSC emphasis bits from $2001:
            // Bit 5 (emph & 1): Emphasize Red -> darken Green + Blue
            // Bit 6 (emph & 2): Emphasize Green -> darken Red + Blue
            // Bit 7 (emph & 4): Emphasize Blue -> darken Red + Green
            if ((emph and 1) != 0) {
                gFactor = 0.75
                bFactor = 0.75
            }
            if ((emph and 2) != 0) {
                rFactor = 0.75
                bFactor = 0.75
            }
            if ((emph and 4) != 0) {
                rFactor = 0.75
                gFactor = 0.75
            }

            val table = IntArray(64)
            emphTable[emph] = table

            // Calculate table:
            for (i in 0 until 64) {
                col = curTable[i]
                r = floor(getRed(col) * rFactor)
                g = floor(getGreen(col) * gFactor)
                b = floor(getBlue(col) * bFactor)
                table[i] = getRgb(r.toInt(), g.toInt(), b.toInt())
            }
        }
    }

    fun setEmphasis(emph: Int) {
        if (emph != currentEmph) {
            currentEmph = emph
            val table = emphTable[emph]!!
            for (i in 0 until 64) {
                curTable[i] = table[i]
            }
        }
    }

    fun getEntry(yiq: Int): Int {
        return curTable[yiq]
    }

    fun getRed(rgb: Int): Int {
        return (rgb shr 16) and 0xff
    }

    fun getGreen(rgb: Int): Int {
        return (rgb shr 8) and 0xff
    }

    fun getBlue(rgb: Int): Int {
        return rgb and 0xff
    }

    fun getRgb(r: Int, g: Int, b: Int): Int {
        return (r shl 16) or (g shl 8) or b
    }

    fun loadDefaultPalette() {
        curTable[0] = getRgb(117, 117, 117)
        curTable[1] = getRgb(39, 27, 143)
        curTable[2] = getRgb(0, 0, 171)
        curTable[3] = getRgb(71, 0, 159)
        curTable[4] = getRgb(143, 0, 119)
        curTable[5] = getRgb(171, 0, 19)
        curTable[6] = getRgb(167, 0, 0)
        curTable[7] = getRgb(127, 11, 0)
        curTable[8] = getRgb(67, 47, 0)
        curTable[9] = getRgb(0, 71, 0)
        curTable[10] = getRgb(0, 81, 0)
        curTable[11] = getRgb(0, 63, 23)
        curTable[12] = getRgb(27, 63, 95)
        curTable[13] = getRgb(0, 0, 0)
        curTable[14] = getRgb(0, 0, 0)
        curTable[15] = getRgb(0, 0, 0)
        curTable[16] = getRgb(188, 188, 188)
        curTable[17] = getRgb(0, 115, 239)
        curTable[18] = getRgb(35, 59, 239)
        curTable[19] = getRgb(131, 0, 243)
        curTable[20] = getRgb(191, 0, 191)
        curTable[21] = getRgb(231, 0, 91)
        curTable[22] = getRgb(219, 43, 0)
        curTable[23] = getRgb(203, 79, 15)
        curTable[24] = getRgb(139, 115, 0)
        curTable[25] = getRgb(0, 151, 0)
        curTable[26] = getRgb(0, 171, 0)
        curTable[27] = getRgb(0, 147, 59)
        curTable[28] = getRgb(0, 131, 139)
        curTable[29] = getRgb(0, 0, 0)
        curTable[30] = getRgb(0, 0, 0)
        curTable[31] = getRgb(0, 0, 0)
        curTable[32] = getRgb(255, 255, 255)
        curTable[33] = getRgb(63, 191, 255)
        curTable[34] = getRgb(95, 151, 255)
        curTable[35] = getRgb(167, 139, 253)
        curTable[36] = getRgb(247, 123, 255)
        curTable[37] = getRgb(255, 119, 183)
        curTable[38] = getRgb(255, 119, 99)
        curTable[39] = getRgb(255, 155, 59)
        curTable[40] = getRgb(243, 191, 63)
        curTable[41] = getRgb(131, 211, 19)
        curTable[42] = getRgb(79, 223, 75)
        curTable[43] = getRgb(88, 248, 152)
        curTable[44] = getRgb(0, 235, 219)
        curTable[45] = getRgb(0, 0, 0)
        curTable[46] = getRgb(0, 0, 0)
        curTable[47] = getRgb(0, 0, 0)
        curTable[48] = getRgb(255, 255, 255)
        curTable[49] = getRgb(171, 231, 255)
        curTable[50] = getRgb(199, 215, 255)
        curTable[51] = getRgb(215, 203, 255)
        curTable[52] = getRgb(255, 199, 255)
        curTable[53] = getRgb(255, 199, 219)
        curTable[54] = getRgb(255, 191, 179)
        curTable[55] = getRgb(255, 219, 171)
        curTable[56] = getRgb(255, 231, 163)
        curTable[57] = getRgb(227, 255, 163)
        curTable[58] = getRgb(171, 243, 191)
        curTable[59] = getRgb(179, 255, 207)
        curTable[60] = getRgb(159, 255, 243)
        curTable[61] = getRgb(0, 0, 0)
        curTable[62] = getRgb(0, 0, 0)
        curTable[63] = getRgb(0, 0, 0)

        makeTables()
        setEmphasis(0)
    }
}
