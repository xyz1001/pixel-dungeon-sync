plugins { application; kotlin("jvm"); kotlin("plugin.serialization") }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
 implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 testImplementation(kotlin("test")); testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
 testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
tasks.test {
 useJUnitPlatform()
 // Existing behavioral fixtures are Chinese; localization tests explicitly exercise other locales.
 systemProperty("user.language", "zh")
 systemProperty("user.country", "CN")
}

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

application { mainClass = "pdsync.DemoKt" }

tasks.withType<JavaExec>().configureEach { workingDir(rootProject.projectDir) }
