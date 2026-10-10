plugins {
    kotlin("jvm") version "2.2.20" apply false
    kotlin("plugin.serialization") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("org.jetbrains.compose") version "1.9.3" apply false
}

subprojects {
    tasks.withType<org.gradle.api.tasks.bundling.AbstractArchiveTask>().configureEach {
        exclude("**/config.json", "**/config.json.legacy*", "**/.local/**", "**/.pixel-dungeon-sync/**")
    }
    dependencyLocking {
        lockAllConfigurations()
        // Native Compose dependencies differ by host. Only resolved hosts get a lockfile.
        val host = System.getProperty("os.name").substringBefore(' ').lowercase()
        val arch = System.getProperty("os.arch")
        lockFile.set(layout.projectDirectory.file("gradle-$host-$arch.lockfile"))
    }
}
