# NesEmulator

A NES emulator written in Kotlin Multiplatform. The core logic is ported from
[bfirsh/jsnes](https://github.com/bfirsh/jsnes) (Apache-2.0). Rendering uses
[sdl-kmp](https://github.com/Enaium/sdl-kmp) (SDL3 2D renderer). The ROM is
passed as a command-line argument on every platform (Android picks the ROM in
its Compose launcher and forwards the path).

All emulator core code (CPU / PPU / APU / Mapper / ROM parsing) lives in
`commonMain` and is reused across every platform; the window, rendering, audio,
input and ROM file loading (kotlinx-io's `SystemFileSystem`, the same backing
store FileKit uses) also live in `commonMain`, with each platform only
providing an entry point.

## Supported platforms

| Platform | Target | ROM source |
| --- | --- | --- |
| JVM desktop (Linux/macOS/Windows) | `jvm` | command-line argument |
| Native Windows | `mingwX64` | command-line argument |
| Native macOS (Apple Silicon) | `macosArm64` | command-line argument |
| Native Linux | `linuxX64` | command-line argument |
| Android (4 ABIs) | `androidNativeArm64/32/X64/X86` | picked in the Compose launcher |

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
    NesApp.kt            # sdl-kmp SDL renderer + audio + input + aspect-ratio window (commonMain)
    FileLoader.kt        # ROM file loading via kotlinx-io (commonMain, no expect/actual)
  jvmMain/               # JVM entry point
  nativeMain/            # Native entry point (Linux / Windows / macOS)
  androidNativeMain/     # Android SDL_main entry point
android/                 # Android app module (AGP)
  LauncherActivity       # Compose + FileKit to pick a ROM
  EmulatorActivity       # SDLActivity, loads libmain.so and runs the emulator
```

## Build & run

Requirements: JDK 17+, Android SDK + NDK (only for Android builds), macOS host
(only for the macOS target).

```bash
# Run on the JVM with a ROM path
./gradlew :jvmRun --args="/path/to/rom.nes"

# Headless run (CI / servers) — exits after 300 frames
SDL_VIDEO_DRIVER=dummy ./gradlew :jvmRun --args="/path/to/rom.nes"

# Native Linux
./gradlew :linkDebugExecutableLinuxX64
SDL_VIDEO_DRIVER=dummy ./build/bin/linuxX64/debugExecutable/NesEmulator.kexe /path/to/rom.nes

# Native Windows (cross-compiled on Linux)
./gradlew :linkDebugExecutableMingwX64

# Native macOS (Apple Silicon)
./gradlew :linkDebugExecutableMacosArm64
./build/bin/macosArm64/debugExecutable/NesEmulator.kexe /path/to/rom.nes

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
| R | Reset the console |
| P | Show FPS |
| ESC | Quit |

## Android architecture

The app uses two activities:

1. **LauncherActivity (Compose)** — runs on ART (JVM) and uses FileKit to
   show the system file picker. The selected ROM is copied to the app's
   internal storage (`filesDir/rom.nes`) and its path is passed to the second
   activity.
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

- **File loading**: `readRomFile` in `commonMain` uses kotlinx-io's
  `SystemFileSystem` (available on JVM, all native targets and Android
  native). FileKit itself has no Android-native variant and its published
  jvm/native artifacts are not consumable from Kotlin Multiplatform
  (expect/actual metadata breaks), so FileKit is only used in the Android
  Compose launcher where it works.
- **Desktop window**: resizes snap to the NES 256:240 aspect ratio; the frame
  is always scaled proportionally and centered (letterboxed).
- **Android**: the ROM is picked with FileKit in the Compose layer; the SDL
  window is created at the display size and the frame is letterboxed to the
  NES aspect.
- **SDL rendering**: each NES frame is a 256×240 ARGB buffer uploaded to an RGB24
  streaming texture and scaled to the window (RGB24 is used because the LWJGL
  SDL3 renderer mangles the green/blue channels of 32-bit streaming textures).
- **Audio**: the APU produces ~800 48 kHz stereo samples per frame, packed as
  F32LE and fed to the SDL audio stream. Audio init is best-effort: if no audio
  device is available the emulator simply runs without sound. Frame pacing is
  locked to the audio clock so sound and video stay continuous.
