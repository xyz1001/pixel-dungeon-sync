#!/usr/bin/env bash
# Host-only byte and policy checks. No APK is installed, launched, or executed.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-"$REPO/../tools/sdk"}}
JAVA=${JAVA_BIN:-java}
HELPER=${1:?Usage: run-preparation-tests.sh helper-unsigned.apk [signed-release.apk]}
RELEASE=${2:-}
OUT="$REPO/android-app/build/preparation-host-tests"
CLASSES="$OUT/classes"
FIXTURES="$OUT/fixtures"
mkdir -p "$CLASSES" "$FIXTURES"
APKSIG=${PDSYNC_APKSIG_JAR:-"$SDK/build-tools/36.0.0/lib/apksigner.jar"}
CP="$SDK/platforms/android-36/android.jar:$APKSIG"
mapfile -t SOURCES < <(find "$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android/prepare" -name '*.java' | sort)
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -cp "$CP" -d "$CLASSES" "${SOURCES[@]}" "$HERE/java/io/github/xyz1001/pdsync/android/prepare/PreparationRecoveryTest.java" "$HERE/java/io/github/xyz1001/pdsync/android/prepare/PreparationReceiptTest.java" "$HERE/java/io/github/xyz1001/pdsync/android/prepare/InstalledEmbeddedReuseTest.java"
for fixture in "$HERE/fixtures/preparation/"*.xml; do
    "$SDK/build-tools/36.0.0/aapt2" link -I "$SDK/platforms/android-36/android.jar" --manifest "$fixture" -o "$FIXTURES/$(basename "$fixture" .xml).apk"
done
"$JAVA" -cp "$CLASSES:$APKSIG" io.github.xyz1001.pdsync.android.prepare.PreparationRecoveryTest "$HELPER" "$RELEASE" "$FIXTURES" "${PDSYNC_V1_TEST_APK:-}"

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
