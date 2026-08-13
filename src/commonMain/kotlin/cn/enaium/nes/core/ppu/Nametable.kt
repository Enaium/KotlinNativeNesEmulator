package cn.enaium.nes.core.ppu

class Nametable(val width: Int, val height: Int, val name: String) {
    val tile = IntArray(width * height)
    val attrib = IntArray(width * height)

    fun getTileIndex(x: Int, y: Int): Int {
        return tile[y * width + x]
    }

    fun getAttrib(x: Int, y: Int): Int {
        return attrib[y * width + x]
    }

    fun writeAttrib(index: Int, value: Int) {
        val basex = (index % 8) * 4
        val basey = (index / 8) * 4
        var add: Int
        var tx: Int
        var ty: Int
        var attindex: Int

        for (sqy in 0 until 2) {
            for (sqx in 0 until 2) {
                add = (value shr (2 * (sqy * 2 + sqx))) and 3
                for (y in 0 until 2) {
                    for (x in 0 until 2) {
                        tx = basex + sqx * 2 + x
                        ty = basey + sqy * 2 + y
                        attindex = ty * width + tx
                        attrib[attindex] = (add shl 2) and 12
                    }
                }
            }
        }
    }
}
