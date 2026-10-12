#!/usr/bin/env bash
# Compile main against SDK, then run injected in-memory Process tests. Never calls su/ADB.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
if [[ -f "$REPO/../tools/android-env.sh" ]]; then source "$REPO/../tools/android-env.sh"; fi
JAVA=${JAVA_BIN:-java}
CORE=${PDSYNC_CORE_JAR:-$REPO/core/build/libs/core.jar}
SDK=${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}
RUNTIME=${PDSYNC_RUNTIME_LIB:?Set PDSYNC_RUNTIME_LIB}
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-root-client.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
CP="$SDK/platforms/android-36/android.jar:$CORE:$(find "$RUNTIME" -maxdepth 1 -name '*.jar' | sort | paste -sd:):$SDK/build-tools/36.0.0/lib/apksigner.jar"
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -encoding UTF-8 -cp "$CP" -d "$OUT" \
  $(find "$REPO/android-app/src/main/java" "$REPO/android-root/src/main/java" -name '*.java' | sort) \
  "$REPO/android-helper/src/main/java/io/github/xyz1001/pdsync/bridge/SaveArchive.java" \
  "$REPO/android-helper/src/main/java/io/github/xyz1001/pdsync/bridge/SaveDirectoryPolicy.java" \
  "$REPO/android-helper/src/main/java/io/github/xyz1001/pdsync/bridge/BridgeFailure.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/RootClientChecks.java"
"$JAVA" -cp "$OUT:$CP" io.github.xyz1001.pdsync.android.RootClientChecks
