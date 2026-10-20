#!/usr/bin/env bash
# Host-only tests; never install, launch, or execute APK code.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-"$REPO/../tools/sdk"}}
JAVA=${JAVA_BIN:-java}
APKSIG=${PDSYNC_APKSIG_JAR:-"$SDK/build-tools/36.0.0/lib/apksigner.jar"}
OUT="$REPO/android-app/build/public-signing-host-tests"
mkdir -p "$OUT/classes" "$OUT/fixtures" "$OUT/platform-stubs"
CP="$SDK/platforms/android-36/android.jar:$APKSIG"
mapfile -t SOURCES < <(find "$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android/prepare" -name '*.java' | sort)
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -sourcepath "$REPO/core/src/main/java" -source 17 -target 17 -cp "$CP" -d "$OUT/classes" \
    "${SOURCES[@]}" "$HERE/fixtures/preparation-platform-stubs/common/android/os/Build.java" \
    "$HERE/java/io/github/xyz1001/pdsync/android/prepare/PublicSigningIdentityTest.java"
for fixture in "$HERE/fixtures/public-signing/"*.xml; do
    "$SDK/build-tools/36.0.0/aapt2" link -I "$SDK/platforms/android-36/android.jar" --manifest "$fixture" \
        -o "$OUT/fixtures/$(basename "$fixture" .xml).apk"
done
"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT/classes:$CP" io.github.xyz1001.pdsync.android.prepare.PublicSigningIdentityTest "$OUT/fixtures"
