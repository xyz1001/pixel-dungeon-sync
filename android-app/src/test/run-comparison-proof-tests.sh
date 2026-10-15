#!/usr/bin/env bash
# Execute the actual production intent/proof model. No Gradle, Android, devices, or core writes.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
JAVA=${JAVA_BIN:-java}
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-comparison-proof.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -encoding UTF-8 -Xlint:all,-options -Werror -d "$OUT" \
  "$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android/ComparisonProofState.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/ComparisonProofStateChecks.java"
"$JAVA" -cp "$OUT" io.github.xyz1001.pdsync.android.ComparisonProofStateChecks
python3 -m unittest discover -s "$HERE/python" -p 'test_shared_comparison_android_contract.py'
