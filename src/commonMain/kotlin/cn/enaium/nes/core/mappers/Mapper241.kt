package cn.enaium.nes.core.mappers

import cn.enaium.nes.core.NES

// BxROM variant (Hengge Technology)
// BxROM-like 32 KB PRG bank switching via writes to $8000-$FFFF,
// with optional battery-backed WRAM at $6000-$7FFF.
class Mapper241(nes: NES) : Mapper(nes) {
    override fun write(address: Int, value: Int) {
        if (address < 0x8000) {
            super.write(address, value)
            return
        } else {
            load32kRomBank(value, 0x8000)
        }
    }
}
