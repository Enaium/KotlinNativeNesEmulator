package cn.enaium.nes.core

class Controller {
    companion object {
        const val BUTTON_A = 0
        const val BUTTON_B = 1
        const val BUTTON_SELECT = 2
        const val BUTTON_START = 3
        const val BUTTON_UP = 4
        const val BUTTON_DOWN = 5
        const val BUTTON_LEFT = 6
        const val BUTTON_RIGHT = 7
        // Turbo buttons rapidly toggle A/B each frame while held, simulating the
        // extra buttons on the NES Advantage and dogbone controllers.
        const val BUTTON_TURBO_A = 8
        const val BUTTON_TURBO_B = 9
    }

    val state = IntArray(8) { 0x40 }
    // Track the non-turbo ("base") state of A and B so we can restore them
    // when turbo is released while the regular button is still held.
    var baseA = 0x40
    var baseB = 0x40
    var turboA = false
    var turboB = false
    var turboToggle = false

    fun buttonDown(key: Int) {
        if (key == BUTTON_TURBO_A) {
            turboA = true
        } else if (key == BUTTON_TURBO_B) {
            turboB = true
        } else {
            state[key] = 0x41
            if (key == BUTTON_A) baseA = 0x41
            if (key == BUTTON_B) baseB = 0x41
        }
    }

    fun buttonUp(key: Int) {
        if (key == BUTTON_TURBO_A) {
            turboA = false
            state[BUTTON_A] = baseA
        } else if (key == BUTTON_TURBO_B) {
            turboB = false
            state[BUTTON_B] = baseB
        } else {
            state[key] = 0x40
            if (key == BUTTON_A) baseA = 0x40
            if (key == BUTTON_B) baseB = 0x40
        }
    }

    // Called once per frame to toggle turbo button states. Produces a ~30 Hz
    // press rate at 60 FPS, matching the fast end of the NES Advantage's
    // adjustable turbo range.
    fun clock() {
        if (!turboA && !turboB) return
        turboToggle = !turboToggle
        if (turboA) {
            state[BUTTON_A] = if (turboToggle) 0x41 else 0x40
        }
        if (turboB) {
            state[BUTTON_B] = if (turboToggle) 0x41 else 0x40
        }
    }
}
