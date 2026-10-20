#!/usr/bin/env bash
# Production pure-Java request model plus source seams. No Android device or Root operation.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
JAVA=${JAVA_BIN:-java}
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-path-preview.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -sourcepath "$REPO/core/src/main/java" -source 17 -target 17 -d "$OUT" \
  "$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android/PathPreviewState.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/PathPreviewStateChecks.java"
"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT" io.github.xyz1001.pdsync.android.PathPreviewStateChecks
python3 -m unittest discover -s "$HERE/python" -p 'test_shared_save_path_android_contract.py'
