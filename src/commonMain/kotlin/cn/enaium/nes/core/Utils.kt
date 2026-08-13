package cn.enaium.nes.core

internal fun copyArrayElements(src: IntArray, srcPos: Int, dest: IntArray, destPos: Int, length: Int) {
    for (i in 0 until length) {
        dest[destPos + i] = src[srcPos + i]
    }
}

internal fun copyArrayElements(src: Array<Tile>, srcPos: Int, dest: Array<Tile>, destPos: Int, length: Int) {
    for (i in 0 until length) {
        dest[destPos + i] = src[srcPos + i]
    }
}
