package cn.enaium.nes.core.mappers

import cn.enaium.nes.core.NES

object Mappers {
    fun isSupported(type: Int): Boolean = when (type) {
        0, 1, 2, 3, 4, 5, 7, 9, 11, 34, 38, 66, 71, 79, 94, 118, 119, 140, 180, 240, 241 -> true
        else -> false
    }

    fun create(nes: NES, type: Int): Mapper = when (type) {
        0 -> Mapper0(nes)
        1 -> Mapper1(nes)
        2 -> Mapper2(nes)
        3 -> Mapper3(nes)
        4 -> Mapper4(nes)
        5 -> Mapper5(nes)
        7 -> Mapper7(nes)
        9 -> Mapper9(nes)
        11 -> Mapper11(nes)
        34 -> Mapper34(nes)
        38 -> Mapper38(nes)
        66 -> Mapper66(nes)
        71 -> Mapper71(nes)
        79 -> Mapper79(nes)
        94 -> Mapper94(nes)
        118 -> Mapper118(nes)
        119 -> Mapper119(nes)
        140 -> Mapper140(nes)
        180 -> Mapper180(nes)
        240 -> Mapper240(nes)
        241 -> Mapper241(nes)
        else -> throw Error("Unsupported mapper: $type")
    }
}
