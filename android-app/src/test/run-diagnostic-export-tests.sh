#!/usr/bin/env bash
# Host fake-provider/state regression plus source contracts; no APK, device, Root or network.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
SRC="$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android"
JAVA=${JAVA_BIN:-java}
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-diagnostic-export.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -sourcepath "$REPO/core/src/main/java" -source 17 -target 17 -encoding UTF-8 -d "$OUT" \
  "$SRC"/{DiagnosticExportState,DiagnosticExportWriter}.java \
  "$HERE/java/io/github/xyz1001/pdsync/android/DiagnosticExportChecks.java"
"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT" io.github.xyz1001.pdsync.android.DiagnosticExportChecks
python3 -m unittest discover -s "$HERE/python" -p 'test_diagnostic_export_contract.py'
