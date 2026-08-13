# NesEmulator

A NES emulator written in Kotlin Multiplatform. The core logic is ported from
[bfirsh/jsnes](https://github.com/bfirsh/jsnes) (Apache-2.0). Rendering uses
[sdl-kmp](https://github.com/Enaium/sdl-kmp) (SDL3 2D renderer), and file
selection/reading uses [FileKit](https://github.com/vinceglb/FileKit).

All emulator core code (CPU / PPU / APU / Mapper / ROM parsing) lives in
`commonMain` and is reused across every platform; the window, rendering, audio
and input layer also lives in `commonMain` (on top of sdl-kmp), with each
platform only providing an entry point.

## Supported platforms

| Platform | Target | File dialog |
| --- | --- | --- |
| JVM desktop (Linux/macOS/Windows) | `jvm` | ✅ FileKit |
| Native Windows | `mingwX64` | ✅ FileKit |
| Native macOS (Apple Silicon) | `macosArm64` | ✅ FileKit |
| Native macOS (Intel) | `macosX64` | ❌ path argument (FileKit has no macosX64 variant) |
| Native Linux | `linuxX64` | ❌ path argument (FileKit dialogs unsupported on Linux native) |
| iOS | `iosArm64`, `iosSimulatorArm64` | ✅ FileKit |
| Android (4 ABIs) | `androidNativeArm64/32/X64/X86` | ✅ FileKit in the Compose launcher |
| tvOS | declared | path argument (no FileKit variant) |

FileKit's pickers (`filekit-dialogs`) are available on JVM, Android, iOS, macOS
arm64 and Windows native — but **not** on Linux native (`linuxX64`). On Linux
native the ROM path is passed as a command-line argument.

## Project structure

```
src/
  commonMain/kotlin/cn/enaium/nes/
    core/                # Emulator core (no platform dependencies)
      CPU.kt             # 6502 CPU (all official + unofficial opcodes)
      PPU.kt  (ppu/)     # Rendering, sprites, scrolling, NMI
      PAPU.kt (papu/)    # APU, 5 audio channels
      ROM.kt, Tile.kt, Controller.kt, GameGenie.kt
      mappers/           # 20+ mappers (NROM/MMC1/MMC3/MMC5...)
      NES.kt             # Top-level wrapper (frame / loadROM / reset / button...)
    NesApp.kt            # sdl-kmp SDL renderer + audio + keyboard input (commonMain)
    FileLoader.kt        # expect: ROM file loading
  jvmMain/               # JVM entry point + FileKit file picker
  nativeMain/            # Native entry point (reads ROM by path)
  androidNativeMain/     # Android SDL_main entry point
android/                 # Android app module (AGP)
  LauncherActivity       # Compose + FileKit to pick a ROM
  EmulatorActivity       # SDLActivity, loads libmain.so and runs the emulator
```

## Build & run

Requirements: JDK 17+, Android SDK + NDK (only for Android builds), macOS host
(only for Apple targets).

```bash
# Run on the JVM (desktop shows a native file picker; a ROM path may also be passed)
./gradlew :jvmRun
./gradlew :jvmRun --args="/path/to/rom.nes"

# Headless run (CI / servers) — exits after 300 frames
SDL_VIDEO_DRIVER=dummy ./gradlew :jvmRun --args="/path/to/rom.nes"

# Native Linux
./gradlew :linkDebugExecutableLinuxX64
SDL_VIDEO_DRIVER=dummy ./build/bin/linuxX64/debugExecutable/NesEmulator.kexe /path/to/rom.nes

# Native Windows (cross-compiled on Linux)
./gradlew :linkDebugExecutableMingwX64

# Android APK (first builds libmain.so for all 4 androidNative ABIs, then packages)
./gradlew :android:assembleDebug
adb install -r android/build/outputs/apk/debug/android-debug.apk
```

### Running the tests

`nestest.nes` is the standard 6502 CPU test suite (official + unofficial opcodes):

```bash
./gradlew :jvmTest
```

## Controls

| Key | Action |
| --- | --- |
| Arrow keys | D-pad |
| X / Z | A / B |
| Enter / Backspace | Start / Select |
| S / A | Turbo A / Turbo B |
| O | Open a ROM (file picker on the JVM desktop) |
| R | Reset the console |
| P | Show FPS |
| ESC | Quit |

## Android architecture

As suggested, the app uses two activities:

1. **LauncherActivity (Compose)** — runs on ART (JVM) and uses FileKit's
   `openFilePicker(type = FileKitType.File("nes"))` to show the system file
   picker. The selected ROM is copied to the app's internal storage
   (`filesDir/rom.nes`) and its path is passed to the second activity.
2. **EmulatorActivity (SDLActivity subclass)** — loads `libmain.so` built by
   the KMP module (SDL3 statically linked in, `SDL_main` exported). It passes
   the ROM path as an argument by overriding `getArguments()`, and the native
   `SDL_main(argc, argv)` reads `argv[1]` and starts the emulator.

Note: the SDL3 embedded in the sdl-kmp native klib is 3.5.0 (submodule
`b640b80`), while the latest `SDL3-3.4.14.aar` on the GitHub releases is a
different version — `SDLActivity`'s version check would reject it. Therefore
the matching `org.libsdl.app` Java glue for that commit is vendored
(`android/src/main/java/org/libsdl/app/`). If sdl-kmp later upgrades to an SDL3
version that matches a release AAR, the vendored directory can be replaced by
that AAR.

## Platform notes

- **JVM desktop**: full FileKit file picker dialog support, audio via LWJGL SDL3.
- **Native Windows / macOS (arm64) / iOS**: FileKit pickers are available and
  used by the app (press `O`).
- **Native Linux / macOS (x64) / tvOS**: `filekit-dialogs` has no variant for
  these targets, so the ROM path is passed as a command-line argument; file
  reading still uses plain POSIX.
- **Android**: the ROM is picked with FileKit in the Compose layer; the native
  layer reads it from the path received as an argument.
- **SDL rendering**: each NES frame is a 256×240 ARGB buffer uploaded to an RGB24
  streaming texture and scaled to the window (RGB24 is used because the LWJGL
  SDL3 renderer mangles the green/blue channels of 32-bit streaming textures).
- **Audio**: the APU produces ~800 48 kHz stereo samples per frame, packed as
  F32LE and fed to the SDL audio stream. Audio init is best-effort: if no audio
  device is available the emulator simply runs without sound.
