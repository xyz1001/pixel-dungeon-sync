# Android reconstruction build

The SDK-only script builds the independent Java game bridge with API 36 and Android Build-Tools 36.0.0. The main app now compiles the same shared Kotlin/Compose sources as desktop through Android Gradle Plugin 8.11.1. It no longer supports the SDK-only javac/D8 main-app entrypoint, which fails early with the migration command. Neither flow needs an emulator or device installation. See [shared UI build instructions](../../shared-ui/README.md) for the pinned Gradle toolchain and dependency locks.

This is a source reconstruction from published test APKs, not a claim that the lost original source was recovered byte-for-byte. The main app retains package `io.github.xyz1001.pdsync.android`; its new development certificate cannot update the old installed app. Device installation and destructive clean-install steps are deliberately outside this script.

## Inputs

Environment variables (paths must be absolute):

- `ANDROID_SDK_ROOT`: SDK containing `platforms/android-36/android.jar` and `build-tools/36.0.0`
- `PDSYNC_BUILD_HOME`: directory outside the checkout for intermediate output
- `PDSYNC_RUNTIME_LIB`: directory containing the pinned core runtime dependencies named in `build-apk.py`
- `PDSYNC_KEYSTORE_FILE`, `PDSYNC_PASSWORD_FILE`, `PDSYNC_KEY_ALIAS`: needed only for signing on the build host; these identify the retained, repository-external test keystore, single-line password file and key alias. They are not required for `--unsigned` builds. Signing secrets must never enter Git, assets, logs, or APKs. The password value is never printed or passed as a command argument. For build-host signing, keystore/password files must have mode 0600; their parent should have mode 0700.
- `JAVA_BIN`: optional Java executable
- `PDSYNC_APKSIG_JAR`: optional explicit runtime apksig dependency. By default, builds with minSdk below 28 generate the checksum-pinned API-26 compatibility jar described below. Final APK signing and verification still use the unmodified official SDK apksigner.

The local reconstruction layout is detected automatically. Reuse the same retained key; this build never generates or silently replaces one. Keep a durable local backup of the signing key and its recovery information. For the recommended user-device signing workflow below, the private key, keystore and passwords remain on the user's device and local backup; never upload them to the cloud build host, repository, chat or an anonymous file-transfer service. Only the public certificate and its SHA-256 fingerprint are needed by the build host.

## Commands

Run from the source checkout. Build the standalone bridge exactly as before, then use the unsigned Gradle main-app build. Final signing remains a separate, explicitly authorized operation using the retained key; Gradle does not generate or use a debug key.

```sh
set -eu
tools/android-rebuild/test-helper-host.sh
tools/android-rebuild/build-apk.sh --module android-helper --standalone --unsigned --out "$PDSYNC_BUILD_HOME/helper-unsigned.apk"
python3 - "$PDSYNC_BUILD_HOME/helper-unsigned.apk" "$PDSYNC_BUILD_HOME/bridge-runtime.dex" <<'PYTHON'
import sys, zipfile
from pathlib import Path
with zipfile.ZipFile(sys.argv[1]) as apk:
    if sorted(name for name in apk.namelist() if name.endswith(".dex")) != ["classes.dex"]:
        raise SystemExit("Expected exactly one standalone bridge classes.dex")
    Path(sys.argv[2]).write_bytes(apk.read("classes.dex"))
PYTHON
export PDSYNC_BRIDGE_RUNTIME_DEX="$PDSYNC_BUILD_HOME/bridge-runtime.dex"
export PDSYNC_ROOT_NATIVE_DIR="/absolute/path/to/root-native/output"
export PDSYNC_APKSIG_JAR="$PDSYNC_BUILD_HOME/generated-deps/apksigner-api26.jar"
# Generate the patched apksig JAR below and build the four native libraries first.
./gradlew :android-app:assembleRelease --offline
```

The current flow embeds a Provider runtime in the prepared game. The `android-helper` module is reused only to compile the standalone runtime; its unsigned APK is an intermediate container from which `classes.dex` is extracted. Only `bridge-runtime.dex` is bundled as a main-app asset. The helper APK and its legacy manifest are neither bundled nor installed in this flow, and no separately installed helper is required. `--standalone` excludes core, Kotlin, OkHttp and apksig dependencies from the runtime DEX.

During game preparation, the main app appends that standalone DEX as the next unused `classesN.dex` entry in the base APK (or `classes.dex` if none existed), patches the manifest with the per-game Provider and activation bindings, and signs the prepared APK set with the retained local game key. Original game DEX, resources, assets and native libraries remain byte-for-byte unchanged as ZIP-entry payloads; the manifest, APK signatures and ZIP packaging necessarily change. Split APKs receive the permitted manifest patch and new signatures, with no injected DEX. Payload verification checks the original protected entries plus exactly the declared runtime DEX. The embedded bridge authenticates the main app's exact pinned certificate and independent UID.

The main app has `minSdkVersion=26` (Android 8.0) and `targetSdkVersion=36`; D8 also uses minimum API 26 for the standalone runtime. Prepared games have their minimum SDK raised to at least 26 while retaining the game's original target SDK, including its effective target when the source manifest omitted an explicit value. The build script reads these SDK settings from the module manifests; its SDK command-line defaults do not override existing manifest values.

SDK-only bridge build output includes `.build.json`, `.badging.txt` and, for signed APKs, `.verify.txt`. The Gradle main build writes `android-app/build/outputs/apk/release/android-app-release-unsigned.apk` and AGP metadata. Inputs, hashes, SDK versions, new signer identity, and device-test status are recorded. ZIP payload timestamps are fixed. A host smoke test confirms deterministic signed output when inputs and the retained key are unchanged.

The installed cloud Java runtime exposes javac but lacks `ct.sym`, so application compilation uses `-source 17 -target 17` rather than `--release 17`. This proves JVM17 bytecode compatibility, not a Java17 API fence. D8 uses the selected module manifest's minimum SDK. Android device/runtime testing remains required.

## Explicit Java source sharing

A module may declare repository-relative Java files or source directories in
`additional-java-sources.txt`, one path per line. Blank lines and `#` comments
are ignored. Only Java sources from those explicit inputs are compiled; another
module's manifest, resources, assets and dependencies are never imported.
Entries must exist inside the repository and must not use globs, parent traversal,
absolute paths or symlinks. Duplicate source paths are compiled once. Every
selected source remains recorded in the build output's `input_sources` hashes.

This supports a main-app-only runtime sharing narrowly selected archive/layout
utilities without including the root runtime in the injected game bridge. The
`android-helper` build reads only its own source declaration, if present.

Run the offline source-selection regression with:

```sh
python3 tools/android-rebuild/tests/test_java_sources.py
```

## Main-only native library inputs (legacy SDK packager tests)

Current main-app packaging uses `PDSYNC_ROOT_NATIVE_DIR` in Gradle, validates all four ELF inputs and preserves their bytes. It uses compressed/extracted libraries and `extractNativeLibs=true`, so the root process can still use `ApplicationInfo.nativeLibraryDir`. The following describes retained SDK-packager utilities and their tests; the legacy main-app command itself is no longer supported.

`--native-library ABI/libNAME.so=FILE` may be repeated to package explicitly built
main-app native libraries. Supported ABIs are `arm64-v8a`, `armeabi-v7a`, `x86` and
`x86_64`. The builder verifies the ELF class/machine, shared-library type and every
load segment's 16 KiB page alignment before packaging. It records source hashes,
stores native entries without ZIP compression, applies 16 KiB ZIP alignment and
sets `extractNativeLibs=true` so the main app's PackageManager native directory is
available to its separate process. Actual linker/SELinux loading needs device tests.

Native inputs are rejected for `--standalone` game-bridge builds. They are never
appended to a prepared game's original native payloads or bundled into the injected
bridge DEX. Native sources are compiled separately; the APK builder does not download,
execute or silently discover a library. Run the metadata-only packaging tests with
`python3 tools/android-rebuild/tests/test_native_libraries.py`.

## User-device final signing (recommended)

This optional split workflow keeps the main-app signing key durable on the user's Windows device while the cloud builds the unsigned APK. It does not require changing or rebuilding an already delivered, correctly signed APK. Build-host signing above remains supported when the retained signing material is intentionally available on that host.

### Release signing and bridge caller identity

The current embedded runtime authenticates the main app by its framework-provided package name, actual UID and game activation/source binding, not by its signing certificate. Historical certificate metadata is not a caller-access pin. This does not distinguish a malicious replacement using the same package name, and assumes Android package/UID isolation is trusted; root or cross-signature replacement weakens that assumption. Legacy standalone helper permissions and game-package integrity checks are separate mechanisms.

Release APK signing remains mandatory. The lowercase SHA-256 digest of the signing certificate's DER bytes must match `tools/ci/android-signer.sha256`; do not hash its Base64 text. Keep the same private key for ordinary Android updates. Replacing it requires the key owner's explicit approval and ordinarily prevents an in-place update, even though the current embedded bridge does not pin the main-app signer. Never generate a replacement merely because a build machine was replaced. This key is separate from the game-signing key held by the Android app for game preparation.

### Build and transfer the unsigned main app

After extracting `bridge-runtime.dex` as above, use this main-app command on the build host:

```sh
export PDSYNC_BRIDGE_RUNTIME_DEX="$PDSYNC_BUILD_HOME/bridge-runtime.dex"
export PDSYNC_ROOT_NATIVE_DIR="/absolute/path/to/root-native/output"
export PDSYNC_APKSIG_JAR="$PDSYNC_BUILD_HOME/generated-deps/apksigner-api26.jar"
./gradlew :android-app:assembleRelease --offline
cp android-app/build/outputs/apk/release/android-app-release-unsigned.apk \
  "$PDSYNC_BUILD_HOME/pixel-dungeon-sync-android-unsigned.apk"
```

AGP produces the aligned unsigned APK. The main build still includes its API-26-patched runtime APK-signing dependencies for game preparation; only final main-app signing is absent. The debug variant is disabled and release has no signing configuration. Transfer the unsigned APK to the user's Windows device and compare its SHA-256 before signing. No signing secrets need to cross to the cloud.

### Sign and verify on Windows

Use the unmodified official Build-Tools 36.0.0 `lib/apksigner.jar`, not the API-26 runtime compatibility jar. The official jar's class files are major version 52 and these final-signing commands are compatible with Java 11; building the source still requires the Java compiler described above. Set `ANDROID_SDK_ROOT`, `PDSYNC_BUILD_HOME`, `PDSYNC_KEYSTORE_FILE` and `PDSYNC_KEY_ALIAS` to the appropriate device-local SDK, output directory, existing keystore and alias. `PDSYNC_BUILD_HOME` here is a Windows-local directory containing the transferred unsigned APK, not the cloud path. Optionally set `JAVA_BIN` to the local Java executable.

Run in a local PowerShell terminal. The signing command prompts locally for the keystore password (and key password if different); no password appears in the command or needs to be supplied to the cloud:

```powershell
$java = if ($env:JAVA_BIN) { $env:JAVA_BIN } else { "java" }
$apksigner = Join-Path $env:ANDROID_SDK_ROOT 'build-tools/36.0.0/lib/apksigner.jar'
$unsigned = Join-Path $env:PDSYNC_BUILD_HOME 'pixel-dungeon-sync-android-unsigned.apk'
$signed = Join-Path $env:PDSYNC_BUILD_HOME 'pixel-dungeon-sync-android-test.apk'
& $java -jar $apksigner sign --ks $env:PDSYNC_KEYSTORE_FILE --ks-key-alias $env:PDSYNC_KEY_ALIAS --min-sdk-version 26 --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false --out $signed $unsigned
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed' }
& $java -jar $apksigner verify --verbose --print-certs --min-sdk-version 26 --max-sdk-version 26 $signed
if ($LASTEXITCODE -ne 0) { throw 'API-26 signature verification failed' }
& $java -jar $apksigner verify --verbose --print-certs --min-sdk-version 36 --max-sdk-version 36 $signed
if ($LASTEXITCODE -ne 0) { throw 'API-36 signature verification failed' }
```

Check that the printed signer certificate SHA-256 digest exactly matches `tools/ci/android-signer.sha256`. API 26 uses v2 signatures; v3 applies from API 28, so do not expect v3 verification on API 26. Do not modify or run zipalign on the signed APK afterward, because post-signing changes invalidate its signature. These checks establish signature verification at the selected API levels, not a real-device runtime test. Preserve the same local key and backup for every later update; transfer only the resulting APK and public verification information as needed.

## API-26 runtime apksig compatibility

Android Build-Tools 36.0.0 includes a direct API-28 `Type.getTypeName()` call in `Asn1BerParser.getElementType(Field)`. This path is used when decoding ASN.1 collections, including V1 APK signature metadata. Disabling V1 verification is not an acceptable compatibility fix.

`apksig_compat.py` accepts only the official SDK jar SHA-256 `3716d9311e55d2b0918a2fd9d54ba9e406c5f6abeea700b287f11259bc163dec` and original parser class SHA-256 `4ca7f8f668bfc2739233655e34632f71aa2f20d51b8ff29c6d0402697c12c575`. It changes exactly one method's Code attribute into a call to the adjacent, reviewable `apksig-compat/Android26ElementType.java`. The helper obtains the sole concrete generic element class using `ParameterizedType.getActualTypeArguments()`; raw, wildcard, nested generic and multi-argument types fail closed. No verifier, certificate check, signing scheme, or algorithm policy is removed.

The helper is compiled with the official API-26 `android.jar` as javac's boot classpath, with Java 8 class output. `platforms/android-26/android.jar` is therefore additionally required when building a minSdk-26/27 app without an explicit dependency override. The patched jar is generated outside the repository at `$PDSYNC_BUILD_HOME/generated-deps/apksigner-api26.jar`; the SDK jar is never edited. The compatibility sidecar records the original and output hashes, source helper hash, replacement method, license and the fact that generation alone is not a device test. Future SDK versions require a fresh source/bytecode audit rather than relaxing the checksum check.

Provenance: the input is the official `https://dl.google.com/android/repository/build-tools_r36_linux.zip` below. The original Android Open Source Project apksig license remains in `android-app/assets/licenses/apksig-LICENSE.txt`; the new compatibility helper and patch implementation are Apache-2.0. This is a narrow locally maintained compatibility patch, not an upstream apksig release.

```sh
python3 tools/android-rebuild/tests/test_apksig_compat.py
python3 tools/android-rebuild/apksig_compat.py \
  --input "$ANDROID_SDK_ROOT/build-tools/36.0.0/lib/apksigner.jar" \
  --android-jar "$ANDROID_SDK_ROOT/platforms/android-26/android.jar" \
  --output "$PDSYNC_BUILD_HOME/generated-deps/apksigner-api26.jar"
```

Tests compile against API26, check supported and rejected generic shapes, exercise the actual patched ASN.1 SET_OF parse/encode path, verify deterministic jar generation, and assert that every original jar entry except the one parser class remains byte-for-byte unchanged. The preparation tests should additionally run with `PDSYNC_APKSIG_JAR` pointing to the generated jar; Android Keystore/OEM behavior still requires a real-device test.

## Tool provenance

Official SDK repository: https://dl.google.com/android/repository/repository2-1.xml

- `platform-36_r02.zip`: 65,878,410 bytes; SHA1 `2c1a80dd4d9f7d0e6dd336ec603d9b5c55a6f576`; SHA256 `37607369a28c5b640b3a7998868d45898ebcb777565a0e85f9acf36f29631d2e`
- `build-tools_r36_linux.zip`: 63,737,259 bytes; SHA1 `b0b6376977657e8ad9b969bacf4093601da2c6fb`; SHA256 `5d9ac77fb6ff43d9da518a337b4fcf8f9097113df531d99ccefe80ef7ce8250b`

Both archive sizes and official SHA1 checksums were verified before extraction. Total download: 129,615,669 bytes. No emulator image was downloaded.

## Empty ZIP compatibility regression (Android 8)

Android 8's libcore `ZipOutputStream.finish()` throws `ZipException("No entries")`
for zero-entry archives. On first inspection, the main app captures its still-empty
mirror before copying a valid, nonempty helper export, so this also affects users
who already have game saves. Both core archive entry points, the main restore
writer, and the helper export writer emit the canonical 22-byte ZIP EOCD for an
empty file set, without adding fake save entries. Nonempty archives keep their
existing ZIP writer and validation rules.

Run `tools/android-rebuild/test-android-empty-zip.sh` with JDK 17+ and the usual
Gradle dependencies available. `GRADLE_BIN`, `JAVA_BIN`, `GRADLE_USER_HOME`, and
`PDSYNC_EMPTY_ZIP_TEST_OUTPUT` may select existing local tools/caches and an output
directory; pass `--offline` when using a populated cache. The script builds a
small test-only JVM agent using bundled JDK ASM, verifies that a plain empty ZIP
writer actually throws Android's exception, and runs production codecs plus
core/facade/onboarding/security tests under that behavior. It includes a fresh
empty mirror with three real-format synthetic slots and an empty loopback DAV,
asserting `LOCAL_ONLY` and read-only HTTP requests. This is host emulation of the
specific libcore behavior, not a real-device runtime test.

Upstream source: https://android.googlesource.com/platform/libcore/+/android-8.0.0_r1/ojluni/src/main/java/java/util/zip/ZipOutputStream.java
