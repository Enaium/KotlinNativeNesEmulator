package cn.enaium.nes

import cn.enaium.nes.core.NES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs the standard 6502 `nestest.nes` CPU test ROM in automation mode
 * (entry point $C000, bypassing the interactive menu).
 *
 * Results are stored in memory:
 *   mem[0x10] = page 1 result (official opcodes)
 *   mem[0x11] = page 2 result (unofficial opcodes)
 * A value of 0x00 means all tests in that page passed.
 *
 * A crash in the open bus region ($4018-$5FFF) is expected when open bus
 * is properly emulated — the automation mode executes from open bus
 * addresses, and the data bus values lead to a KIL opcode.
 */
class NESTestTest {

    private val openBusCrash = Regex("address \\$?[45][0-9a-f]{3}\$")

    @Test
    fun nestestPasses() {
        val rom = javaClass.classLoader.getResourceAsStream("nestest.nes")
            ?.use { it.readBytes() }
            ?: error("nestest.nes not found in test resources")

        val nes = NES(
            NES.Options(
                onFrame = {},
                onStatusUpdate = {},
                emulateSound = false,
                sampleRate = 44100,
            ),
        )
        nes.loadROM(rom)

        // Automation mode: start at $C000 and clear the pending reset IRQ so
        // our PC override is not overwritten.
        nes.cpu.REG_PC = 0xc000 - 1
        nes.cpu.REG_PC_NEW = 0xc000 - 1
        nes.cpu.irqRequested = false

        val maxInstructions = 100_000
        var count = 0
        var crashMessage: String? = null
        try {
            while (count < maxInstructions) {
                nes.cpu.emulate()
                count++
            }
        } catch (e: Throwable) {
            crashMessage = e.message
        }

        val crashedInOpenBus = crashMessage?.let { openBusCrash.containsMatchIn(it) } == true
        if (crashMessage != null && !crashedInOpenBus) {
            throw AssertionError("ROM crashed: $crashMessage")
        }
        assertFalse(crashMessage != null && !crashedInOpenBus, "unexpected crash")

        assertTrue(count > 1000, "Test ROM didn't run enough instructions: $count")

        val result02 = nes.cpu.mem[0x10]
        val result03 = nes.cpu.mem[0x11]
        println(
            "nestest: ran $count instructions, byte 0x02 = 0x" +
                result02.toString(16).uppercase() +
                ", byte 0x03 = 0x" + result03.toString(16).uppercase(),
        )
        assertEquals(0, result02, "official opcode tests failed (byte 0x02)")
        assertEquals(0, result03, "unofficial opcode tests failed (byte 0x03)")
    }
}
