#!/usr/bin/env bash
# Host setup state and source integration. Android system pages still require device QA.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
SRC="$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android"
JAVA=${JAVA_BIN:-java}
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-game-setup.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -sourcepath "$REPO/core/src/main/java" -source 17 -target 17 -encoding UTF-8 -d "$OUT" \
  "$SRC"/{GameSetupFlow,InstallUiCopy}.java \
  "$HERE/java/io/github/xyz1001/pdsync/android/GameSetupFlowChecks.java"
"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT" io.github.xyz1001.pdsync.android.GameSetupFlowChecks
"$HERE/run-game-mode-choice-tests.sh"
python3 -m unittest discover -s "$HERE/python" -p 'test_setup_lifecycle_contract.py'
"$HERE/run-installed-app-picker-tests.sh"
"$HERE/run-installed-app-catalog-tests.sh"
