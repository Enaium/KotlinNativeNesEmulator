package cn.enaium.nes.core

class Tile {
    // Tile data: color indices 0-3
    val pix = IntArray(64)

    var initialized = false
    val opaque = IntArray(8)

    fun setBuffer(scanline: IntArray) {
        for (y in 0 until 8) {
            setScanline(y, scanline[y], scanline[y + 8])
        }
    }

    fun setScanline(sline: Int, b1: Int, b2: Int) {
        initialized = true
        var tIndex = sline shl 3
        for (x in 0 until 8) {
            pix[tIndex + x] = ((b1 shr (7 - x)) and 1) + (((b2 shr (7 - x)) and 1) shl 1)
            if (pix[tIndex + x] == 0) {
                opaque[sline] = 0
            }
        }
    }

    fun render(
        buffer: IntArray,
        srcx1: Int,
        srcy1: Int,
        srcx2: Int,
        srcy2: Int,
        dx: Int,
        dy: Int,
        palAdd: Int,
        palette: IntArray,
        flipHorizontal: Boolean,
        flipVertical: Boolean,
        pri: Int,
        priTable: IntArray,
    ) {
        if (dx < -7 || dx >= 256 || dy < -7 || dy >= 240) {
            return
        }

        var srcx1v = srcx1
        var srcx2v = srcx2
        var srcy1v = srcy1
        var srcy2v = srcy2

        if (dx < 0) {
            srcx1v -= dx
        }
        if (dx + srcx2v >= 256) {
            srcx2v = 256 - dx
        }

        if (dy < 0) {
            srcy1v -= dy
        }
        if (dy + srcy2v >= 240) {
            srcy2v = 240 - dy
        }

        var fbIndex: Int
        var tIndex: Int
        var palIndex: Int
        var tpri: Int

        if (!flipHorizontal && !flipVertical) {
            fbIndex = (dy shl 8) + dx
            tIndex = 0
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    if (x >= srcx1v && x < srcx2v && y >= srcy1v && y < srcy2v) {
                        palIndex = pix[tIndex]
                        tpri = priTable[fbIndex]
                        if (palIndex != 0 && pri <= (tpri and 0xff)) {
                            buffer[fbIndex] = palette[palIndex + palAdd]
                            tpri = (tpri and 0xf00) or pri
                            priTable[fbIndex] = tpri
                        }
                    }
                    fbIndex++
                    tIndex++
                }
                fbIndex -= 8
                fbIndex += 256
            }
        } else if (flipHorizontal && !flipVertical) {
            fbIndex = (dy shl 8) + dx
            tIndex = 7
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    if (x >= srcx1v && x < srcx2v && y >= srcy1v && y < srcy2v) {
                        palIndex = pix[tIndex]
                        tpri = priTable[fbIndex]
                        if (palIndex != 0 && pri <= (tpri and 0xff)) {
                            buffer[fbIndex] = palette[palIndex + palAdd]
                            tpri = (tpri and 0xf00) or pri
                            priTable[fbIndex] = tpri
                        }
                    }
                    fbIndex++
                    tIndex--
                }
                fbIndex -= 8
                fbIndex += 256
                tIndex += 16
            }
        } else if (flipVertical && !flipHorizontal) {
            fbIndex = (dy shl 8) + dx
            tIndex = 56
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    if (x >= srcx1v && x < srcx2v && y >= srcy1v && y < srcy2v) {
                        palIndex = pix[tIndex]
                        tpri = priTable[fbIndex]
                        if (palIndex != 0 && pri <= (tpri and 0xff)) {
                            buffer[fbIndex] = palette[palIndex + palAdd]
                            tpri = (tpri and 0xf00) or pri
                            priTable[fbIndex] = tpri
                        }
                    }
                    fbIndex++
                    tIndex++
                }
                fbIndex -= 8
                fbIndex += 256
                tIndex -= 16
            }
        } else {
            fbIndex = (dy shl 8) + dx
            tIndex = 63
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    if (x >= srcx1v && x < srcx2v && y >= srcy1v && y < srcy2v) {
                        palIndex = pix[tIndex]
                        tpri = priTable[fbIndex]
                        if (palIndex != 0 && pri <= (tpri and 0xff)) {
                            buffer[fbIndex] = palette[palIndex + palAdd]
                            tpri = (tpri and 0xf00) or pri
                            priTable[fbIndex] = tpri
                        }
                    }
                    fbIndex++
                    tIndex--
                }
                fbIndex -= 8
                fbIndex += 256
            }
        }
    }

    fun isTransparent(x: Int, y: Int): Boolean {
        return pix[(y shl 3) + x] == 0
    }
}
