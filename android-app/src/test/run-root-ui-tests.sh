#!/usr/bin/env bash
# Host-only readiness and source wiring. Never invokes a root process or Android UI.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
JAVA=${JAVA_BIN:-java}
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-root-ui.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -d "$OUT" \
  "$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android/InstallationReadiness.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/RootReadinessChecks.java"
"$JAVA" -cp "$OUT" io.github.xyz1001.pdsync.android.RootReadinessChecks
python3 -m unittest discover -s "$HERE/python" -p 'test_root_ui_contract.py'
