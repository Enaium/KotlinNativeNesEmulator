plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The root KMP module builds libmain.so (with an exported SDL_main and SDL3
// statically linked) for every androidNative ABI; copy those into jniLibs and
// depend on the link tasks so the APK is assembled after them.
val androidAbis = mapOf(
    "androidNativeArm64" to "arm64-v8a",
    "androidNativeArm32" to "armeabi-v7a",
    "androidNativeX64" to "x86_64",
    "androidNativeX86" to "x86",
)

val jniLibsDir: DirectoryProperty = objects.directoryProperty()
jniLibsDir.set(layout.buildDirectory.dir("generated/jniLibs"))

val prepareJniLibs = tasks.register("prepareJniLibs") {
    group = "build"
    outputs.dir(jniLibsDir)
    androidAbis.keys.forEach { target ->
        dependsOn(rootProject.tasks.named("linkMainDebugShared${target.replaceFirstChar { it.uppercase() }}"))
    }
    doLast {
        val out = jniLibsDir.get().asFile
        out.deleteRecursively()
        val bin = rootProject.layout.projectDirectory.dir("build/bin").asFile
        androidAbis.forEach { (target, abi) ->
            val src = File(bin, "$target/mainDebugShared/libmain.so")
            val dstDir = File(out, abi)
            dstDir.mkdirs()
            src.copyTo(File(dstDir, "libmain.so"), overwrite = true)
        }
    }
}

android {
    namespace = "cn.enaium.nes"
    compileSdk = 36
    defaultConfig {
        applicationId = "cn.enaium.nes"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

// Register the generated libmain.so directory with AGP's Variant API.
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(prepareJniLibs) { jniLibsDir }
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(libs.filekit.core)
    implementation(libs.filekit.dialogs)
}
