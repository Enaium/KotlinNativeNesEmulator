package cn.enaium.nes

import cn.enaium.nes.core.Controller
import cn.enaium.nes.core.NES
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLAudioDeviceID
import cn.enaium.sdl.SDLAudioFormat
import cn.enaium.sdl.SDLAudioSpec
import cn.enaium.sdl.SDLAudioStream
import cn.enaium.sdl.SDLEvent
import cn.enaium.sdl.SDLFRect
import cn.enaium.sdl.SDLInitFlags
import cn.enaium.sdl.SDLKeycode
import cn.enaium.sdl.SDLPixelFormat
import cn.enaium.sdl.SDLRect
import cn.enaium.sdl.SDLTextureAccess
import cn.enaium.sdl.SDLWindowEventType
import cn.enaium.sdl.SDLWindowFlags
import cn.enaium.sdl.SDLPoint
import kotlin.math.min
import kotlin.math.roundToInt

const val NES_WIDTH = 256
const val NES_HEIGHT = 240

/**
 * SDL renderer based NES frontend. The emulator core lives in commonMain and
 * the rendering uses the SDL2D renderer API from sdl-kmp (a NES frame is a
 * 256x240 ARGB buffer that is uploaded to a streaming texture each frame).
 *
 * Controls:
 *  - ESC / close button: quit
 *  - R: reset the console
 *  - Arrow keys: d-pad, X: A, Z: B, Enter: Start, Backspace: Select,
 *    S: turbo A, A: turbo B
 *
 * The ROM is passed as the first command-line argument.
 */
class NesApp(private val initialRom: ByteArray?, private val desktop: Boolean = true) {

    private val argb = IntArray(NES_WIDTH * NES_HEIGHT)
    private val rgb = ByteArray(NES_WIDTH * NES_HEIGHT * 3)

    fun run() {
        SDL.setMainReady()

        // Video + events are the core; audio is initialized separately below so
        // a missing audio device never breaks the video display.
        var headless = false
        if (!SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)) {
            println("video init failed: ${SDL.error()}")
            SDL.setHint("SDL_VIDEO_DRIVER", "dummy")
            if (SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)) {
                println("video init fell back to the dummy driver - running headless")
                headless = true
            } else {
                error("SDL_Init failed: ${SDL.error()}\nSet SDL_VIDEO_DRIVER=dummy to run headless.")
            }
        }

        headless = headless || SDL.getCurrentVideoDriver() == "dummy"
        val maxFrames = if (headless) 300 else Int.MAX_VALUE

        println("SDL ${SDL.version()} (${SDL.revision()})")
        println("Video driver: ${SDL.getCurrentVideoDriver()}")

        // Audio is best-effort: if the platform has no audio device (e.g. a
        // static SDL3 build without ALSA/PipeWire, or a headless session) the
        // emulator simply runs without sound.
        var audioStream: SDLAudioStream? = null
        if (!headless) {
            if (SDL.init(SDLInitFlags.AUDIO)) {
                println("Audio driver: ${SDL.getCurrentAudioDriver()}")
                try {
                    audioStream = SDL.openAudioDeviceStream(
                        deviceId = SDLAudioDeviceID.DEFAULT_PLAYBACK,
                        spec = SDLAudioSpec(format = SDLAudioFormat.F32LE, channels = 2, freq = 48000),
                    ).also {
                        it.gain = 0.5f
                        it.devicePaused = false
                        SDL.resumeAudioDevice(SDLAudioDeviceID.DEFAULT_PLAYBACK)
                    }
                } catch (e: Throwable) {
                    println("audio unavailable: ${e.message}")
                    audioStream = null
                }
            } else {
                println("audio init failed: ${SDL.error()} - running without sound")
            }
        }
        val audio = NesAudioOutput(audioStream)
        // One frame of stereo F32 at 48kHz in bytes: 48000 * 2 channels * 4 bytes / 60fps.
        val audioFrameBytes = if (audioStream != null) (48000 * 2 * 4) / 60 else 0
        // Keep roughly 3 frames (~50ms) of audio buffered: enough headroom to
        // absorb scheduling jitter without adding audible latency.
        val audioTargetQueued = audioFrameBytes * 3

        fun createNes(): NES {
            return NES(
                NES.Options(
                    onFrame = { frame -> frame.copyInto(argb) },
                    onAudioSample = { l, r -> audio.onSample(l, r) },
                    onStatusUpdate = { println(it) },
                    onBatteryRamWrite = { _, _ -> },
                    emulateSound = audioStream != null,
                    sampleRate = 48000,
                ),
            )
        }

        var nes: NES? = null
        initialRom?.let { data ->
            try {
                nes = createNes()
                nes!!.loadROM(data)
                println("Loaded ROM (${data.size} bytes)")
            } catch (e: Throwable) {
                println("Failed to load initial ROM: ${e.message}")
            }
        }
        println(
            "Controls: arrows=d-pad, X=A, Z=B, Enter=Start, Backspace=Select, " +
                "S=Turbo A, A=Turbo B | R=reset, P=fps, ESC=quit",
        )

        // On mobile (Android) the SDL window must match the actual surface
        // size: SDL3 Android otherwise keeps the requested size (768x720) and
        // the SurfaceView stretches that buffer to the whole screen, which no
        // renderer-side letterboxing can compensate for.
        val initialWindowSize = if (desktop) {
            SDLPoint(NES_WIDTH * 3, NES_HEIGHT * 3)
        } else {
            val bounds = SDL.getPrimaryDisplay().usableBounds
            SDLPoint(bounds.width, bounds.height)
        }
        SDL.createWindow(
            title = "Kotlin NES",
            width = initialWindowSize.x,
            height = initialWindowSize.y,
            flags = SDLWindowFlags.RESIZABLE,
        ).use { window ->
            SDL.createRenderer(window).use { renderer ->
                val texture = renderer.createTexture(
                    format = SDLPixelFormat.RGB24,
                    access = SDLTextureAccess.STREAMING,
                    width = NES_WIDTH,
                    height = NES_HEIGHT,
                )

                var running = true
                var frames = 0
                // Wall-clock pacing fallback (no audio / headless / no ROM).
                // A double accumulator compensates for delay() overshoot so the
                // average frame period is exactly 1000/60 ms.
                val framePeriodMs = 1000.0 / 60.0
                var lastTicks = SDL.getTicks()
                var pacingAccum = 0.0
                fun paceWithClock() {
                    val now = SDL.getTicks()
                    pacingAccum += (now - lastTicks).toLong().toDouble()
                    lastTicks = now
                    if (pacingAccum < framePeriodMs) {
                        SDL.delay((framePeriodMs - pacingAccum).toInt())
                    }
                    pacingAccum -= framePeriodMs
                    if (pacingAccum < -framePeriodMs) {
                        // Fell too far behind (slow machine or hiccup): reset
                        // instead of spiralling.
                        pacingAccum = 0.0
                    }
                }
                while (running) {
                    // ---- events ----
                    while (true) {
                        val event = SDL.pollEvent() ?: break
                        when (event) {
                            is SDLEvent.Quit -> running = false
                            is SDLEvent.Window -> when {
                                event.type == SDLWindowEventType.CLOSE_REQUESTED -> running = false
                                // On desktop, snap the window to the NES aspect
                                // ratio when the user resizes it (mobile ignores
                                // this: the OS controls the window size).
                                // window.size is used (not the event's pixel
                                // data) so high-DPI scaling doesn't drift.
                                event.type == SDLWindowEventType.RESIZED && desktop -> {
                                    val size = window.size
                                    val snapped = snapAspectSize(size.x, size.y)
                                    if (snapped != size) {
                                        window.size = snapped
                                    }
                                }
                            }
                            is SDLEvent.Key -> when {
                                event.repeat -> Unit
                                !event.down -> {
                                    val button = KEY_MAP[event.keycode]
                                    if (button != null) nes?.buttonUp(1, button)
                                }
                                event.keycode == SDLKeycode.ESCAPE -> running = false
                                event.keycode == SDLKeycode.r -> nes?.reset()
                                event.keycode == SDLKeycode.p -> {
                                    val fps = nes?.getFPS()
                                    if (fps != null) println("fps: ${fps.toInt()}")
                                }
                                else -> {
                                    val button = KEY_MAP[event.keycode]
                                    if (button != null) nes?.buttonDown(1, button)
                                }
                            }
                            else -> Unit
                        }
                    }

                    // ---- emulate one frame ----
                    val current = nes
                    if (current != null) {
                        try {
                            current.frame()
                            audio.flush()
                        } catch (e: Throwable) {
                            println("Emulator error: ${e.message}")
                            running = false
                        }

                        // ---- render the 256x240 ARGB frame buffer ----
                        argbToRgb(argb, rgb)
                        texture.update(null, rgb, NES_WIDTH * 3)
                        renderer.drawColor = SDLColor(0, 0, 0)
                        renderer.clear()
                        // outputSize is the renderer's pixel space (what dst
                        // rects are interpreted in); on Android it can differ
                        // from window.size (rotated/high-DPI surfaces).
                        val size = renderer.outputSize
                        // Scale proportionally and center: never stretch the
                        // frame to the window (Android fullscreen windows have
                        // a non-4:3 aspect and were vertically stretched).
                        val fit = fitAspectRect(size.x, size.y)
                        renderer.renderTexture(
                            texture = texture,
                            dst = fit,
                        )
                        renderer.present()
                    } else {
                        // No ROM loaded: show a visible placeholder instead of a
                        // pure black screen.
                        renderer.drawColor = SDLColor(14, 18, 38)
                        renderer.clear()
                        val size = renderer.outputSize
                        renderer.drawColor = SDLColor(70, 110, 190)
                        renderer.fillRect(
                            SDLRect(
                                size.x / 2 - 130,
                                size.y / 2 - 24,
                                260,
                                48,
                            ),
                        )
                        renderer.drawColor = SDLColor(200, 210, 235)
                        renderer.fillRect(
                            SDLRect(
                                size.x / 2 - 120,
                                size.y / 2 - 14,
                                240,
                                28,
                            ),
                        )
                        renderer.present()
                        if (frames % 120 == 0) {
                            println(
                                "No ROM loaded. Pass a .nes file path as the first argument.",
                            )
                        }
                    }

                    frames++
                    if (frames >= maxFrames) {
                        running = false
                    }

                    // ---- frame pacing ----
                    // With audio, the stream drains in real time, so wait until
                    // the buffered audio drops below the target: the loop then
                    // runs at exactly the audio clock rate and both sound and
                    // video stay continuous. Without audio, pace off the wall
                    // clock instead.
                    if (nes != null && audioStream != null) {
                        while (audioStream.queued > audioTargetQueued) {
                            SDL.delay(1)
                        }
                    } else {
                        paceWithClock()
                    }
                }

                println("ran $frames frames")
                texture.close()
            }
        }

        audioStream?.close()
        SDL.quit()
        if (headless) {
            println("headless run finished")
        }
    }

    private fun argbToRgb(argb: IntArray, rgb: ByteArray) {
        argbToRgbBytes(argb, rgb)
    }

    companion object {
        private val KEY_MAP = mapOf(
            SDLKeycode.UP to Controller.BUTTON_UP,
            SDLKeycode.DOWN to Controller.BUTTON_DOWN,
            SDLKeycode.LEFT to Controller.BUTTON_LEFT,
            SDLKeycode.RIGHT to Controller.BUTTON_RIGHT,
            SDLKeycode.x to Controller.BUTTON_A,
            SDLKeycode.z to Controller.BUTTON_B,
            SDLKeycode.RETURN to Controller.BUTTON_START,
            SDLKeycode.BACKSPACE to Controller.BUTTON_SELECT,
            SDLKeycode.s to Controller.BUTTON_TURBO_A,
            SDLKeycode.a to Controller.BUTTON_TURBO_B,
        )
    }

    private fun aspectScale(winW: Int, winH: Int): Float {
        return min(winW / NES_WIDTH.toFloat(), winH / NES_HEIGHT.toFloat())
    }

    /**
     * Largest centered destination rect with the NES aspect ratio that fits in
     * a [winW]x[winH] window (letterboxing).
     */
    private fun fitAspectRect(winW: Int, winH: Int): SDLFRect {
        val scale = aspectScale(winW, winH)
        val w = NES_WIDTH * scale
        val h = NES_HEIGHT * scale
        return SDLFRect((winW - w) / 2f, (winH - h) / 2f, w, h)
    }

    /**
     * Nearest window size with the NES aspect ratio, never smaller than
     * 256x240. Used to snap desktop window resizes to proportional scaling.
     */
    private fun snapAspectSize(w: Int, h: Int): SDLPoint {
        val scale = aspectScale(w, h).coerceAtLeast(1f)
        return SDLPoint(
            (NES_WIDTH * scale).roundToInt(),
            (NES_HEIGHT * scale).roundToInt(),
        )
    }
}

/**
 * Converts a 256x240 NES frame buffer into RGB888 bytes (3 bytes per pixel).
 *
 * The buffer holds standard 0xRRGGBB colors (R in the high byte). RGB24 is used
 * instead of a 32-bit format because the JVM (LWJGL) SDL3 renderer mangles the
 * green/blue channels of 32-bit streaming textures.
 */
internal fun argbToRgbBytes(argb: IntArray, rgb: ByteArray) {
    var o = 0
    for (c in argb) {
        rgb[o] = (c shr 16).toByte()
        rgb[o + 1] = (c shr 8).toByte()
        rgb[o + 2] = c.toByte()
        o += 3
    }
}

/**
 * Accumulates PAPU stereo samples emitted during frame emulation and flushes
 * them into the SDL audio stream as F32LE data.
 */
class NesAudioOutput(private val stream: SDLAudioStream?) {

    private val samples = ArrayList<Float>()

    fun onSample(left: Float, right: Float) {
        samples.add(left)
        samples.add(right)
    }

    fun flush() {
        val s = stream ?: run {
            samples.clear()
            return
        }
        if (samples.isEmpty()) return
        val bytes = ByteArray(samples.size * 4)
        for (i in samples.indices) {
            val bits = samples[i].toBits()
            val o = i * 4
            bytes[o] = (bits ushr 0).toByte()
            bytes[o + 1] = (bits ushr 8).toByte()
            bytes[o + 2] = (bits ushr 16).toByte()
            bytes[o + 3] = (bits ushr 24).toByte()
        }
        s.putData(bytes)
        samples.clear()
    }

    fun clear() {
        samples.clear()
        stream?.clear()
    }
}
