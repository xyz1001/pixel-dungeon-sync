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
CP="$SDK/platforms/android-36/android.jar:$SDK/build-tools/36.0.0/lib/apksigner.jar"
mapfile -t SOURCES < <(find "$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android/prepare" -name '*.java' ! -name GamePreparationService.java | sort)
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -cp "$CP" -d "$CLASSES" "${SOURCES[@]}" "$HERE/java/io/github/xyz1001/pdsync/android/prepare/PreparationRecoveryTest.java"
for fixture in "$HERE/fixtures/preparation/"*.xml; do
    "$SDK/build-tools/36.0.0/aapt2" link -I "$SDK/platforms/android-36/android.jar" --manifest "$fixture" -o "$FIXTURES/$(basename "$fixture" .xml).apk"
done
"$JAVA" -cp "$CLASSES:$SDK/build-tools/36.0.0/lib/apksigner.jar" io.github.xyz1001.pdsync.android.prepare.PreparationRecoveryTest "$HELPER" "$RELEASE" "$FIXTURES"
