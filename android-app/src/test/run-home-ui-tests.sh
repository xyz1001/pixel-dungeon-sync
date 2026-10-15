#!/usr/bin/env bash
# Host-only callback ownership and source seam checks. No Gradle, APK or device execution.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
JAVA=${JAVA_BIN:-java}
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-home-ui.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -encoding UTF-8 -Xlint:all,-options -Werror -d "$OUT" \
  "$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android/HomeActionContext.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/HomeActionContextChecks.java"
"$JAVA" -cp "$OUT" io.github.xyz1001.pdsync.android.HomeActionContextChecks
python3 -m unittest discover -s "$HERE/python" -p 'test_shared_home_android_contract.py'
python3 -m unittest discover -s "$HERE/python" -p 'test_history_rendering_contract.py'
