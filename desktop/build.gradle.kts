@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

import org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask

plugins { kotlin("jvm"); id("org.jetbrains.kotlin.plugin.compose"); id("org.jetbrains.compose") }
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
 testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
 testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
tasks.test { useJUnitPlatform() }
compose.desktop { application {
 mainClass = "pdsync.MainKt"
 // Shattered's LWJGL runtime also needs these modules (verified against official 3.3.8).
 nativeDistributions { modules("java.management", "jdk.unsupported") }
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
