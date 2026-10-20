#!/usr/bin/env bash
# Host-only localization and invariant checks; no APK is installed or executed.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-"$REPO/../tools/sdk"}}
JAVA=${JAVA_BIN:-java}
APKSIG=${PDSYNC_APKSIG_JAR:-"$SDK/build-tools/36.0.0/lib/apksigner.jar"}
OUT=${PDSYNC_PREPARATION_LOCALE_TEST_OUTPUT:-"$REPO/android-app/build/preparation-localization-host-tests"}
mkdir -p "$OUT/classes"
mapfile -t SOURCES < <(find "$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android/prepare" -name '*.java' | sort)
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 \
    -cp "$SDK/platforms/android-36/android.jar:$APKSIG" -d "$OUT/classes" \
    "$REPO/core/src/main/java/pdsync/i18n/UiLocale.java" \
    "$REPO/core/src/main/java/pdsync/i18n/UiText.java" \
    "$REPO/core/src/main/java/pdsync/i18n/PrepareMessages.java" \
    "${SOURCES[@]}" "$HERE/java/io/github/xyz1001/pdsync/android/prepare/PreparationLocalizationTest.java"
"$JAVA" -cp "$OUT/classes:$SDK/platforms/android-36/android.jar:$APKSIG" \
    io.github.xyz1001.pdsync.android.prepare.PreparationLocalizationTest
