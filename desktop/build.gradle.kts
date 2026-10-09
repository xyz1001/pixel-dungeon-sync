plugins { kotlin("jvm"); id("org.jetbrains.kotlin.plugin.compose"); id("org.jetbrains.compose") }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
 implementation(project(":core")); implementation(compose.desktop.currentOs)
 testImplementation(kotlin("test"))
 testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
tasks.test { useJUnitPlatform() }
compose.desktop { application { mainClass = "pdsync.MainKt"; jvmArgs += listOf("-Dpdsync.workspace=${rootProject.projectDir.absolutePath}") } }

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

tasks.withType<JavaExec>().configureEach { workingDir(rootProject.projectDir) }
