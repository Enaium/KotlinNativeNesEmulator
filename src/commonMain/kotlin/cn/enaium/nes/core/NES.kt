package cn.enaium.nes.core

import cn.enaium.nes.core.mappers.Mapper
import cn.enaium.nes.core.papu.PAPU
import cn.enaium.nes.core.ppu.PPU
import kotlin.time.Clock

class NES(opts: Options) {
    class Options(
        val onFrame: (IntArray) -> Unit = {},
        val onAudioSample: ((Float, Float) -> Unit)? = null,
        val onStatusUpdate: (String) -> Unit = {},
        val onBatteryRamWrite: (Int, Int) -> Unit = { _, _ -> },

        val emulateSound: Boolean = true,
        val sampleRate: Int = 48000, // Sound sample rate in hz
    )

    class UI(
        val writeFrame: (IntArray) -> Unit,
        val updateStatus: (String) -> Unit,
    )

    val opts = opts
    val ui = UI(opts.onFrame, opts.onStatusUpdate)
    var cpu: CPU = CPU(this)
    var ppu: PPU = PPU(this)
    var papu: PAPU = PAPU(this)
    val gameGenie = GameGenie()
    var mmap: Mapper? = null
    val controllers = Array(3) { Controller() }
    var rom: ROM? = null

    var fpsFrameCount = 0
    var romData: ByteArray? = null
    var lastFpsTime: Long? = null
    var crashed = false

    init {
        gameGenie.onChange = { cpu._updateCartridgeLoader() }
        ui.updateStatus("Ready to load a ROM.")
    }

    // Resets the system
    fun reset() {
        cpu = CPU(this)
        ppu = PPU(this)
        papu = PAPU(this)

        if (mmap != null) {
            mmap = rom!!.createMapper()
        }

        lastFpsTime = null
        fpsFrameCount = 0

        crashed = false
    }

    // The frame loop. PPU is advanced inline after every CPU bus operation
    // (in cpu.load/write/push/pull). APU is clocked in bulk after each
    // instruction for compatibility with its sample timing logic.
    fun frame() {
        if (crashed) {
            throw Error(
                "Game has crashed. Call reset() or loadROM() to restart.",
            )
        }
        controllers[1].clock()
        controllers[2].clock()
        ppu.startFrame()
        var cycles: Int
        val cpu = this.cpu
        val ppu = this.ppu
        val papu = this.papu
        try {
            while (true) {
                if (cpu.cyclesToHalt == 0) {
                    // Execute a CPU instruction. PPU advancement happens inline
                    // inside the bus operations (load/write/push/pull).
                    cycles = cpu.emulate()

                    // Clock APU with the full cycle count. The frame counter
                    // portion subtracts any cycles already advanced by APU
                    // catch-up.
                    papu.clockFrameCounter(cycles, cpu.apuCatchupCycles)
                    cpu.apuCatchupCycles = 0

                    // Check if VBlank fired during inline PPU stepping.
                    if (ppu.frameEnded) {
                        ppu.frameEnded = false
                        break
                    }
                } else {
                    // DMA halt cycles: step PPU per cycle. APU is clocked in bulk.
                    var chunk = minOf(cpu.cyclesToHalt, 8)
                    for (i in 0 until chunk) {
                        ppu.advanceDots(3)
                    }
                    papu.clockFrameCounter(chunk)
                    cpu.cyclesToHalt -= chunk
                    cpu._cpuCycleBase += chunk

                    if (ppu.frameEnded) {
                        ppu.frameEnded = false
                        break
                    }
                }
            }
        } catch (e: Throwable) {
            crashed = true
            throw e
        }
        fpsFrameCount++
    }

    fun buttonDown(controller: Int, button: Int) {
        controllers[controller].buttonDown(button)
    }

    fun buttonUp(controller: Int, button: Int) {
        controllers[controller].buttonUp(button)
    }

    fun zapperMove(x: Int, y: Int) {
        mmap?.let {
            it.zapperX = x
            it.zapperY = y
        }
    }

    fun zapperFireDown() {
        mmap?.zapperFired = true
    }

    fun zapperFireUp() {
        mmap?.zapperFired = false
    }

    fun getFPS(): Double? {
        val now = Clock.System.now().toEpochMilliseconds()
        var fps: Double? = null
        if (lastFpsTime != null) {
            fps = fpsFrameCount / ((now - lastFpsTime!!) / 1000.0)
        }
        fpsFrameCount = 0
        lastFpsTime = now
        return fps
    }

    fun reloadROM() {
        if (romData != null) {
            loadROM(romData!!)
        }
    }

    // Loads a ROM file into the CPU and PPU.
    // The ROM file is validated first.
    fun loadROM(data: ByteArray) {
        // Load ROM file:
        rom = ROM(this)
        rom!!.load(data)

        reset()
        mmap = rom!!.createMapper()
        mmap!!.loadROM()
        ppu.setMirroring(rom!!.getMirroringType())
        romData = data
    }

    // Adjust audio sample timing for a non-standard host frame rate. At the
    // default 60fps each frame() produces ~800 samples at 48kHz. If the host
    // calls frame() less often (e.g. 30fps), the sample timer must fire more
    // frequently per CPU cycle so each frame still fills the audio buffer.
    fun setFramerate(rate: Int) {
        papu.setFrameRate(rate)
    }
}
