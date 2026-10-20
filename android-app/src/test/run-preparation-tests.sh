#!/usr/bin/env bash
# Host-only byte and policy checks. No APK is installed, launched, or executed.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-"$REPO/../tools/sdk"}}
JAVA=${JAVA_BIN:-java}
# Optional external fixtures preserve the original command-line interface.
# Without a helper, build an inert host-only template using the real helper manifest.
HELPER=${1:-}
RELEASE=${2:-${PDSYNC_SIGNED_RELEASE_APK:-}}
GAME_DEX=${3:-${PDSYNC_GAME_TEST_DEX:-}}
OUT="$REPO/android-app/build/preparation-host-tests"
CLASSES="$OUT/classes"
FIXTURES="$OUT/fixtures"
mkdir -p "$CLASSES" "$FIXTURES"
if [[ -z "$GAME_DEX" ]]; then
    mkdir -p "$OUT/game-classes" "$OUT/game-dex"
    "$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 \
        -bootclasspath "$SDK/platforms/android-26/android.jar" -d "$OUT/game-classes" \
        "$HERE/fixtures/provider-injection/game/com/example/game/OriginalGame.java"
    "$JAVA" -cp "$SDK/build-tools/36.0.0/lib/d8.jar" com.android.tools.r8.D8 --min-api 26 \
        --lib "$SDK/platforms/android-26/android.jar" --output "$OUT/game-dex" \
        "$OUT/game-classes/com/example/game/OriginalGame.class"
    GAME_DEX="$OUT/game-dex/classes.dex"
fi
if [[ -z "$HELPER" ]]; then
    "$SDK/build-tools/36.0.0/aapt2" compile --dir "$REPO/android-helper/res" -o "$OUT/helper-resources.zip"
    "$SDK/build-tools/36.0.0/aapt2" link -I "$SDK/platforms/android-36/android.jar" \
        --manifest "$REPO/android-helper/AndroidManifest.xml" -o "$OUT/helper-resources.apk" "$OUT/helper-resources.zip"
    HELPER="$OUT/helper-host-fixture.apk"
    python3 - "$OUT/helper-resources.apk" "$GAME_DEX" "$HELPER" <<'PYFIXTURE'
import pathlib, sys, zipfile
resource, dex, output = map(pathlib.Path, sys.argv[1:])
with zipfile.ZipFile(resource) as original, zipfile.ZipFile(output, 'w') as destination:
    for entry in original.infolist():
        destination.writestr(entry, original.read(entry.filename))
    destination.writestr('classes.dex', dex.read_bytes(), compress_type=zipfile.ZIP_STORED)
PYFIXTURE
fi
APKSIG=${PDSYNC_APKSIG_JAR:-"$SDK/build-tools/36.0.0/lib/apksigner.jar"}
CP="$SDK/platforms/android-36/android.jar:$APKSIG"
mapfile -t SOURCES < <(find "$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android/prepare" -name '*.java' | sort)
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -cp "$CP" -d "$CLASSES" "$REPO/core/src/main/java/pdsync/i18n/UiLocale.java" "$REPO/core/src/main/java/pdsync/i18n/UiText.java" "$REPO/core/src/main/java/pdsync/i18n/PrepareMessages.java" "${SOURCES[@]}" "$HERE/java/io/github/xyz1001/pdsync/android/prepare/PreparationRecoveryTest.java" "$HERE/java/io/github/xyz1001/pdsync/android/prepare/PreparationReceiptTest.java" "$HERE/java/io/github/xyz1001/pdsync/android/prepare/InstalledEmbeddedReuseTest.java"
for fixture in "$HERE/fixtures/preparation/"*.xml; do
    "$SDK/build-tools/36.0.0/aapt2" link -I "$SDK/platforms/android-36/android.jar" --manifest "$fixture" -o "$FIXTURES/$(basename "$fixture" .xml).apk"
done
"$JAVA" -cp "$CLASSES:$APKSIG" io.github.xyz1001.pdsync.android.prepare.PreparationRecoveryTest "$HELPER" "$RELEASE" "$FIXTURES" "${PDSYNC_V1_TEST_APK:-}" "$GAME_DEX"

# Always run the real service receipt model and embedded reselect policy on the host.
# The Android jar supplies type definitions; no Android framework method executes.
"$JAVA" -cp "$CLASSES:$CP" io.github.xyz1001.pdsync.android.prepare.PreparationReceiptTest
"$JAVA" -cp "$CLASSES:$APKSIG" io.github.xyz1001.pdsync.android.prepare.InstalledEmbeddedReuseTest

# Execute the platform boundary with genuinely absent API28 classes/members on
# the legacy classpath, and separately verify modern certificate-rotation rules.
STUBS="$HERE/fixtures/preparation-platform-stubs"
for level in 26 28; do
    DEST="$OUT/platform-api$level"
    mkdir -p "$DEST"
    mapfile -t STUB_SOURCES < <(find "$STUBS/common" "$STUBS/api$level" -name '*.java' | sort)
    TEST=PreparationPlatformLegacyTest
    if [[ "$level" == 28 ]]; then TEST=PreparationPlatformModernTest; fi
    "$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -cp "$CLASSES" -d "$DEST" "${STUB_SOURCES[@]}" "$HERE/java/io/github/xyz1001/pdsync/android/prepare/$TEST.java"
    "$JAVA" -cp "$DEST:$CLASSES" "io.github.xyz1001.pdsync.android.prepare.$TEST"
done

# SDK bootclasspath check proves the signer uses only APIs available in 26.
# The guarded API28 platform adapter is already compiled separately above.
if [[ -f "$SDK/platforms/android-26/android.jar" ]]; then
    mkdir -p "$OUT/api26-compile"
    mapfile -t API26_SOURCES < <(find "$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android/prepare" -name '*.java' ! -name PreparationPlatform.java | sort)
    "$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -bootclasspath "$SDK/platforms/android-26/android.jar" -cp "$CLASSES:$APKSIG" -d "$OUT/api26-compile" "${API26_SOURCES[@]}"
    echo "Preparation service, signer, and policy API26 bootclasspath compile passed"
else
    echo "Preparation API26 bootclasspath compile skipped: platform android-26 absent"
fi
