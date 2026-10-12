# Android reconstruction build

Builds Java Android modules with API 36 and Android Build-Tools 36.0.0, without an emulator, Android Gradle plugin, or device installation. Python 3, Java with `jdk.compiler`, AAPT2, D8, zipalign and apksigner are required.

This is a source reconstruction from published test APKs, not a claim that the lost original source was recovered byte-for-byte. The main app retains package `io.github.xyz1001.pdsync.android`; its new development certificate cannot update the old installed app. Device installation and destructive clean-install steps are deliberately outside this script.

## Inputs

Environment variables (paths must be absolute):

- `ANDROID_SDK_ROOT`: SDK containing `platforms/android-36/android.jar` and `build-tools/36.0.0`
- `PDSYNC_BUILD_HOME`: directory outside the checkout for intermediate output
- `PDSYNC_RUNTIME_LIB`: directory containing the pinned core runtime dependencies named in `build-apk.py`
- `PDSYNC_KEYSTORE_FILE`, `PDSYNC_PASSWORD_FILE`, `PDSYNC_KEY_ALIAS`: retained, repository-external test keystore and single-line password file. Signing secrets must never enter Git, assets, logs, or APKs. The password value is never printed or passed as a command argument. Keystore/password files must have mode 0600; their parent should have mode 0700.
- `JAVA_BIN`: optional Java executable
- `PDSYNC_APKSIG_JAR`: optional explicit runtime apksig dependency. By default, builds with minSdk below 28 generate the checksum-pinned API-26 compatibility jar described below. Final APK signing and verification still use the unmodified official SDK apksigner.

The local reconstruction layout is detected automatically. Reuse the same retained key; this build never generates or silently replaces one. Export and back up the keystore only through a separately authorized secure channel.

## Commands

Run from the source checkout:

```sh
tools/android-rebuild/test-helper-host.sh
tools/android-rebuild/build-apk.sh --module android-helper --standalone --unsigned --out "$PDSYNC_BUILD_HOME/helper-unsigned.apk"
tools/android-rebuild/build-apk.sh --module android-app --core core/build/libs/core.jar --asset "helper-unsigned.apk=$PDSYNC_BUILD_HOME/helper-unsigned.apk" --out "$PDSYNC_BUILD_HOME/pixel-dungeon-sync-android-test.apk"
```

The helper is an unbound template. The main application's preparation flow rewrites its per-game manifest bindings and signs it with the prepared game's key. The helper independently authenticates the main app's exact pinned certificate and independent UID.

Build output includes `.build.json`, `.badging.txt` and, for signed APKs, `.verify.txt`. Inputs, hashes, SDK versions, new signer identity, and device-test status are recorded. ZIP payload timestamps are fixed. A host smoke test confirms deterministic signed output when inputs and the retained key are unchanged.

The installed cloud Java runtime exposes javac but lacks `ct.sym`, so application compilation uses `-source 17 -target 17` rather than `--release 17`. This proves JVM17 bytecode compatibility, not a Java17 API fence. D8 uses the selected module manifest's minimum SDK. Android device/runtime testing remains required.

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
