import java.util.zip.ZipFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Build inputs are supplied explicitly. No checkout-specific/cloud path, signing key,
// SDK installation, NDK download, or game/phone dependency is hidden in the build.
fun externalInput(property: String, environment: String, fallback: String) =
    rootProject.file(providers.gradleProperty(property)
        .orElse(providers.environmentVariable(environment)).orElse(fallback).get())
val bridgeRuntimeDex = externalInput("pdsync.bridgeRuntimeDex", "PDSYNC_BRIDGE_RUNTIME_DEX", ".local/android/bridge-runtime.dex")
val rootNativeDir = externalInput("pdsync.rootNativeDir", "PDSYNC_ROOT_NATIVE_DIR", ".local/android/root-native")
val apksigJar = externalInput("pdsync.apksigJar", "PDSYNC_APKSIG_JAR", ".local/android/apksigner-api26.jar")
val prepared = layout.buildDirectory.dir("generated/pdsync")
val sourceManifest = file("AndroidManifest.xml").readText()
val manifestVersionCode = Regex("android:versionCode=\"([0-9]+)\"").find(sourceManifest)!!.groupValues[1].toInt()
val manifestVersionName = Regex("android:versionName=\"([^\"]+)\"").find(sourceManifest)!!.groupValues[1]
val nativeAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

val prepareAndroidInputs by tasks.registering {
    inputs.file("AndroidManifest.xml")
    inputs.file(bridgeRuntimeDex)
    inputs.file(apksigJar)
    nativeAbis.forEach { inputs.file(rootNativeDir.resolve("$it/libpdsync_root_atomic.so")) }
    val bridgeUtilities = listOf("SaveArchive.java", "SaveDirectoryPolicy.java", "BridgeFailure.java")
        .map { rootProject.file("android-helper/src/main/java/io/github/xyz1001/pdsync/bridge/$it") }
    inputs.files(bridgeUtilities)
    outputs.dir(prepared)
    doLast {
        check(bridgeRuntimeDex.isFile && bridgeRuntimeDex.readBytes().take(4).toByteArray().contentEquals(byteArrayOf(100, 101, 120, 10))) {
            "pdsync.bridgeRuntimeDex / PDSYNC_BRIDGE_RUNTIME_DEX must name the independently built bridge-runtime.dex"
        }
        ZipFile(apksigJar).use { zip ->
            check(zip.getEntry("com/android/apksig/internal/asn1/Android26ElementType.class") != null &&
                zip.getEntry("META-INF/pdsync-apksig-api26.NOTICE") != null) {
                "Use the checksum-pinned API-26 apksig compatibility JAR from tools/android-rebuild/apksig_compat.py"
            }
        }
        val machines = mapOf("armeabi-v7a" to 40, "arm64-v8a" to 183, "x86" to 3, "x86_64" to 62)
        nativeAbis.forEach { abi ->
            val library = rootNativeDir.resolve("$abi/libpdsync_root_atomic.so")
            val data = library.readBytes()
            val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            check(data.size >= 64 && b.getInt(0) == 0x464c457f && data[5].toInt() == 1 &&
                b.getShort(16).toInt() == 3 && b.getShort(18).toInt() == machines.getValue(abi)) {
                "Invalid root-native ELF for $abi"
            }
            val elf64 = data[4].toInt() == 2
            check(data[4].toInt() == if (abi in listOf("arm64-v8a", "x86_64")) 2 else 1)
            val headerOffset = if (elf64) b.getLong(32) else b.getInt(28).toLong() and 0xffffffffL
            val entrySize = b.getShort(if (elf64) 54 else 42).toInt() and 0xffff
            val count = b.getShort(if (elf64) 56 else 44).toInt() and 0xffff
            check(count > 0 && entrySize == if (elf64) 56 else 32)
            check(headerOffset >= 0 && headerOffset + entrySize.toLong() * count <= data.size)
            var loads = 0
            repeat(count) { index ->
                val offset = (headerOffset + index * entrySize).toInt()
                if (b.getInt(offset) == 1) {
                    loads++
                    val fileOffset = if (elf64) b.getLong(offset + 8) else b.getInt(offset + 4).toLong() and 0xffffffffL
                    val address = if (elf64) b.getLong(offset + 16) else b.getInt(offset + 8).toLong() and 0xffffffffL
                    val alignment = if (elf64) b.getLong(offset + 48) else b.getInt(offset + 28).toLong() and 0xffffffffL
                    check(alignment >= 16384 && (alignment and (alignment - 1)) == 0L && fileOffset % 16384 == address % 16384) {
                        "Root-native ELF for $abi must retain 16 KiB load alignment"
                    }
                }
            }
            check(loads > 0)
        }
        val output = prepared.get().asFile
        output.mkdirs()
        // AGP 8 rejects package= in source manifests. The original reconstruction
        // manifest remains untouched; namespace/applicationId below preserve it.
        // Release's isDebuggable=false owns the debug flag (lint forbids hardcoding it).
        val manifest = file("AndroidManifest.xml").readText()
            .replace(Regex("\\s+package=\"[^\"]*\""), "")
            .replace(Regex("\\s+android:debuggable=\"[^\"]*\""), "")
            .replace("<application", "<application android:extractNativeLibs=\"true\"")
        output.resolve("AndroidManifest.xml").writeText(manifest)
        copy { from(bridgeRuntimeDex); into(output.resolve("assets")); rename { "bridge-runtime.dex" } }
        copy { from(bridgeUtilities); into(output.resolve("java/io/github/xyz1001/pdsync/bridge")) }
    }
}

android {
    namespace = "io.github.xyz1001.pdsync.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "io.github.xyz1001.pdsync.android"
        minSdk = 26
        targetSdk = 36
        versionCode = manifestVersionCode
        versionName = manifestVersionName
        ndk { abiFilters += nativeAbis }
    }
    buildFeatures { compose = true; buildConfig = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main") {
        manifest.srcFile(prepared.map { it.file("AndroidManifest.xml") })
        java.srcDirs("src/main/java", rootProject.file("android-root/src/main/java"), prepared.map { it.dir("java") })
        res.srcDir("res")
        assets.srcDirs("assets", prepared.map { it.dir("assets") })
        jniLibs.srcDir(rootNativeDir)
    }
    buildTypes {
        getByName("debug") { signingConfig = null }
        getByName("release") {
            signingConfig = null
            isMinifyEnabled = false
            isDebuggable = false
        }
    }
    packaging {
        jniLibs {
            // Root app_process loads these from ApplicationInfo.nativeLibraryDir.
            // Keep extraction enabled, as in the existing SDK-only main packaging.
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
        resources {
            excludes += setOf("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/versions/**")
            merges += setOf("META-INF/services/**", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

// Only the unsigned release variant is available. In particular there is no
// validateSigningDebug task that could create ~/.android/debug.keystore.
androidComponents { beforeVariants(selector().withBuildType("debug")) { it.enable = false } }
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    sourceSets.getByName("main").kotlin.srcDirs("src/main/kotlin", rootProject.file("shared-ui/src/main/kotlin"))
}

dependencies {
    implementation(project(":core"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation(files(apksigJar))
    implementation("androidx.compose.runtime:runtime:1.9.4")
    implementation("androidx.compose.ui:ui:1.9.4")
    implementation("androidx.compose.foundation:foundation:1.9.4")
    implementation("androidx.compose.material:material:1.9.4")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}

tasks.named("preBuild") { dependsOn(prepareAndroidInputs) }
