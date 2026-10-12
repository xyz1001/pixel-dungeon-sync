#!/usr/bin/env bash
# Interface-only JVM checks against the actual current core jar. No device access.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
SRC="$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android"
JAVA=${JAVA_BIN:-java}
CORE=${PDSYNC_CORE_JAR:-$REPO/core/build/libs/core.jar}
if [[ ! -f "$CORE" ]]; then
  echo "Build the current core jar first, or set PDSYNC_CORE_JAR." >&2
  exit 1
fi
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-game-access.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -cp "$CORE" -d "$OUT" \
  "$SRC/GameAccessBackend.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/GameAccessBackendChecks.java"
"$JAVA" -cp "$OUT:$CORE" io.github.xyz1001.pdsync.android.GameAccessBackendChecks
