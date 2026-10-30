@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

import org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins { kotlin("jvm"); id("org.jetbrains.kotlin.plugin.compose"); id("org.jetbrains.compose") }

val releaseManifest = rootProject.file("android-app/AndroidManifest.xml").readText()
val releaseName = Regex("android:versionName=\"([^\"]+)\"").find(releaseManifest)!!.groupValues[1]
val releaseCode = Regex("android:versionCode=\"([0-9]+)\"").find(releaseManifest)!!.groupValues[1]

kotlin {
 compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
 sourceSets {
  main { kotlin.srcDir(rootProject.file("shared-ui/src/main/kotlin")) }
  test { kotlin.srcDir(rootProject.file("shared-ui/src/test/kotlin")) }
 }
}
dependencies {
 implementation(project(":core")); implementation(compose.desktop.currentOs)
 testImplementation(kotlin("test"))
 testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
 testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
 testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
tasks.test {
 useJUnitPlatform()
 // Existing behavioral fixtures are Chinese; localization tests explicitly exercise other locales.
 systemProperty("user.language", "zh")
 systemProperty("user.country", "CN")
}
compose.desktop { application {
 mainClass = "pdsync.MainKt"
 // Shattered's LWJGL runtime also needs these modules (verified against official 3.3.8).
 nativeDistributions {
  targetFormats(TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Dmg)
  packageName = "PDSync"
  packageVersion = releaseName.substringBefore('-')
  description = "Pixel Dungeon save synchronization"
  vendor = "PDSync contributors"
  linux { packageName = "pdsync"; menuGroup = "Game" }
  windows { menu = true; shortcut = true; upgradeUuid = "6abfd52c-0eb1-47c2-b6e1-90cf82e08a04" }
  // Compose 1.9.3 requires a positive macOS major version; preserve the public
  // versionName in artifact names, and encode versionCode in the bundle version.
  macOS { bundleID = "io.github.xyz1001.pdsync"; packageVersion = "1.$releaseCode.0"; packageBuildVersion = "1.$releaseCode.0" }
  modules("java.management", "jdk.unsupported")
  windows { iconFile.set(project.file("src/main/resources/icons/pdsync.ico")) }
  macOS { iconFile.set(project.file("src/main/resources/icons/pdsync.icns")) }
  linux { iconFile.set(project.file("src/main/resources/icons/pdsync.png")) }
 }
} }

// Compose 1.9.3 otherwise strips bin/java from the packaged runtime. This internal
// Property is deliberately tied to the pinned plugin; recheck when upgrading Compose.
// freeArgs cannot override it: Compose appends --strip-native-commands afterwards.
tasks.withType<AbstractJLinkTask>().configureEach {
 stripNativeCommands.set(false)
 doLast {
  val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
  check(destinationDir.file("bin/$executable").get().asFile.isFile) {
   "Packaged runtime must contain bin/$executable to launch the selected game JAR"
  }
 }
}

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

tasks.withType<JavaExec>().configureEach { workingDir(rootProject.projectDir) }
