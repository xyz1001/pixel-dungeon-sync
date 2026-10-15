# Shared Compose UI (stage 1)

`src/main/kotlin` is a **single physical source tree** compiled by both
`:desktop:compileKotlin` and `:android-app:compileReleaseKotlin`. The desktop
source set also runs `src/test/kotlin` as ordinary JVM tests. This deliberately
uses the existing JVM core and independent JVM/Android compilations, not KMP.
Do not copy UI source into either platform directory or include Android/desktop
APIs in this directory; platform hosts provide callbacks and persisted settings.

Pinned toolchain: Gradle 8.14.3, Kotlin and Compose compiler plugin 2.2.20,
Compose Desktop 1.9.3, AGP 8.11.1, Android Compose 1.9.4, Activity Compose 1.11.0,
Lifecycle ViewModel 2.9.4, Java target 17. Android uses compile/target SDK 36,
Build Tools 36.0.0, and minimum SDK 26. Use a complete JDK 17+ with `javac`,
`jlink`, and `jmod`. Install those SDK components before
building Android and set `ANDROID_HOME` (or an ignored `local.properties`). No emulator,
NDK, SDK auto-install, device, server, or signing material is needed.
Desktop-only `:desktop:compileKotlin` does not require the SDK or Android input
variables; Android inputs are checked only when Android build tasks run.

## Android inputs and unsigned build

Prepare the existing bridge runtime, root-native libraries (all four ABIs), and
API-26 compatibility-patched apksig JAR with the existing documented tools.
The game bridge itself still uses `tools/android-rebuild/build-apk.py` unchanged.
The Android **main app** now uses AGP so Kotlin and Compose are genuinely compiled.

Supply portable paths with environment variables:

```sh
export PDSYNC_BRIDGE_RUNTIME_DEX=/absolute/path/to/bridge-runtime.dex
export PDSYNC_ROOT_NATIVE_DIR=/absolute/path/to/root-native/output
export PDSYNC_APKSIG_JAR=/absolute/path/to/generated-deps/apksigner-api26.jar
./gradlew :core:test :desktop:test :android-app:assembleRelease --offline
```

Equivalent Gradle properties are `pdsync.bridgeRuntimeDex`,
`pdsync.rootNativeDir`, and `pdsync.apksigJar`. Relative paths are interpreted
from the repository root. Missing inputs fail closed. Defaults are the ignored
`.local/android/` directory. The existing apksig patcher requires its output
outside the repository and validates the original SDK JAR checksum; its output
may then be copied into `.local/android/` if desired.

Only the unsigned release variant is enabled. Output:
`android-app/build/outputs/apk/release/android-app-release-unsigned.apk`.
There is no debug signing key generation and no release signing configuration.
A generated manifest removes the package attribute rejected by AGP 8 and the
hardcoded debug flag (the release build type explicitly sets `isDebuggable=false`),
then sets `extractNativeLibs=true`, matching prior SDK-only native packaging;
the original manifest, application ID, version, permissions, and SDK levels
are preserved. Existing licenses, OkHttp public-suffix data, API-26 apksig
modification notice, bridge-runtime asset, and four root-native libraries are
packaged. Native ELF inputs retain their 16 KiB compatibility and compressed/extracted
packaging preserves `ApplicationInfo.nativeLibraryDir` for the root process. No native rebuild/stripping occurs.

## Dependency resolution

The repositories are the official Gradle Plugin Portal, Maven Central, and
Google Maven. Resolve once under a controlled network policy, record downloaded
artifact sizes, and write locks with the same pinned build tasks plus
`--write-locks`. Then rerun `--offline` without `--write-locks`.
Desktop lockfiles are host-specific because its Skiko runtime is host-specific;
Android uses its platform-independent `android-app/gradle.lockfile`;
the root buildscript classpath is locked separately for AGP/compiler/plugin transitives. Never commit caches, SDKs,
prebuilt toolchains, signing keys, or a developer's absolute paths.
