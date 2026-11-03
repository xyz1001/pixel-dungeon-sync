buildscript {
    configurations.named("classpath") {
        resolutionStrategy.activateDependencyLocking()
    }
}

plugins {
    id("com.android.application") version "8.11.1" apply false
    kotlin("android") version "2.2.20" apply false
    kotlin("jvm") version "2.2.20" apply false
    kotlin("plugin.serialization") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("org.jetbrains.compose") version "1.9.3" apply false
}

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
    tasks.withType<Test>().configureEach {
        defaultCharacterEncoding = "UTF-8"
    }
    tasks.withType<org.gradle.api.tasks.bundling.AbstractArchiveTask>().configureEach {
        exclude("**/config.json", "**/config.json.legacy*", "**/.local/**", "**/.pixel-dungeon-sync/**")
    }
    dependencyLocking {
        lockAllConfigurations()
        // Native Compose dependencies differ by host. Only resolved hosts get a lockfile.
        val host = System.getProperty("os.name").substringBefore(' ').lowercase()
        val arch = System.getProperty("os.arch")
        lockFile.set(layout.projectDirectory.file(
            if (name == "android-app") "gradle.lockfile" else "gradle-$host-$arch.lockfile"
        ))
    }
}
