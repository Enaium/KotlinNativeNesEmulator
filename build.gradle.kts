import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Kotlin/Native's own Android toolchain sysroot (api 26) ships the NDK stub
// libraries (libEGL, libGLESv2, libOpenSLES, libaaudio, ...) that SDL3's
// android drivers reference at link time. Point -L at the per-ABI directory
// so the libmain.so link resolves them without needing an extra NDK install.
fun konanAndroidLibDir(abi: String): String? {
    val konanData = System.getenv("KONAN_DATA_DIR")
        ?: providers.gradleProperty("konan.data.dir").getOrElse("${System.getProperty("user.home")}/.konan")
    val toolchain = File(konanData, "dependencies").listFiles()
        ?.firstOrNull { it.isDirectory && it.name.matches(Regex("target-toolchain-.*-android_ndk")) }
        ?: return null
    val triple = when (abi) {
        "arm64-v8a" -> "aarch64-linux-android"
        "armeabi-v7a" -> "arm-linux-androideabi"
        "x86_64" -> "x86_64-linux-android"
        "x86" -> "i686-linux-android"
        else -> return null
    }
    return "$toolchain/sysroot/usr/lib/$triple/26"
}

val hostOs = System.getProperty("os.name")
val isAppleHost = hostOs == "Mac OS X"

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    linuxX64 {
        binaries.executable()
    }

    mingwX64 {
        binaries.executable()
    }

    // Apple desktop/mobile targets can only be built on a macOS host.
    if (isAppleHost) {
        macosArm64 {
            binaries.executable()
        }
        macosX64 {
            binaries.executable()
        }
        iosArm64()
        iosSimulatorArm64()
        iosX64()
        tvosArm64()
        tvosSimulatorArm64()
    }

    // Android native targets build libmain.so with an exported SDL_main entry
    // point; SDLActivity (from the SDL3 Android archive) loads and calls it.
    androidNativeArm64 {
        binaries.sharedLib("main") {
            konanAndroidLibDir("arm64-v8a")?.let { linkerOpts("-L$it") }
            linkerOpts("-Wl,--allow-multiple-definition")
        }
    }
    androidNativeArm32 {
        binaries.sharedLib("main") {
            konanAndroidLibDir("armeabi-v7a")?.let { linkerOpts("-L$it") }
            linkerOpts("-Wl,--allow-multiple-definition")
        }
    }
    androidNativeX64 {
        binaries.sharedLib("main") {
            konanAndroidLibDir("x86_64")?.let { linkerOpts("-L$it") }
            linkerOpts("-Wl,--allow-multiple-definition")
        }
    }
    androidNativeX86 {
        binaries.sharedLib("main") {
            konanAndroidLibDir("x86")?.let { linkerOpts("-L$it") }
            linkerOpts("-Wl,--allow-multiple-definition")
        }
    }

    sourceSets {
        // Kotlin 2.4's default hierarchy template does not create the
        // intermediate source sets automatically; declare them manually.
        val nativeMain = create("nativeMain") {
            dependsOn(getByName("commonMain"))
        }
        linuxX64Main {
            dependsOn(nativeMain)
        }
        mingwX64Main {
            dependsOn(nativeMain)
        }
        if (isAppleHost) {
            macosArm64Main {
                dependsOn(nativeMain)
            }
            macosX64Main {
                dependsOn(nativeMain)
            }
            iosArm64Main {
                dependsOn(nativeMain)
            }
            iosSimulatorArm64Main {
                dependsOn(nativeMain)
            }
            iosX64Main {
                dependsOn(nativeMain)
            }
            tvosArm64Main {
                dependsOn(nativeMain)
            }
            tvosSimulatorArm64Main {
                dependsOn(nativeMain)
            }
        }

        // Native targets that ship FileKit dialogs (file pickers): Windows
        // (mingwX64), macOS arm64 (macosArm64) and iOS. Linux native and the
        // remaining Apple targets fall back to a ROM path argument instead.
        val nativeDialogsMain = create("nativeDialogsMain") {
            dependsOn(getByName("nativeMain"))
        }
        mingwX64Main {
            dependsOn(nativeDialogsMain)
        }
        if (isAppleHost) {
            macosArm64Main {
                dependsOn(nativeDialogsMain)
            }
            iosArm64Main {
                dependsOn(nativeDialogsMain)
            }
            iosSimulatorArm64Main {
                dependsOn(nativeDialogsMain)
            }
        }

        val androidNativeMain = create("androidNativeMain") {
            dependsOn(getByName("commonMain"))
        }
        androidNativeArm64Main {
            dependsOn(androidNativeMain)
        }
        androidNativeArm32Main {
            dependsOn(androidNativeMain)
        }
        androidNativeX64Main {
            dependsOn(androidNativeMain)
        }
        androidNativeX86Main {
            dependsOn(androidNativeMain)
        }

        androidNativeMain {
            dependencies {
                implementation(libs.kotlinx.io.core)
            }
        }

        jvm {
            mainRun {
                mainClass = "cn.enaium.nes.Main_jvmKt"
            }
        }

        commonMain {
            dependencies {
                implementation(libs.sdl.kmp)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        nativeMain {
            dependencies {
                // No FileKit here: macosX64 / iosX64 / tvos have no FileKit
                // variants, so native file I/O uses plain POSIX instead.
            }
        }
        getByName("nativeDialogsMain").dependencies {
            implementation(libs.filekit.dialogs)
            implementation(libs.filekit.core)
        }
        jvmMain {
            dependencies {
                implementation(libs.filekit.core)
                implementation(libs.filekit.dialogs)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

tasks.withType(JavaExec::class.java).configureEach {
    if (OperatingSystem.current().isMacOsX && name == "jvmRun") {
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-XstartOnFirstThread")
    }
}
