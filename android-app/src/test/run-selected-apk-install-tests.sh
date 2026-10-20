#!/usr/bin/env bash
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
SRC="$HERE/../main/java/io/github/xyz1001/pdsync/android"
REPO=$(cd "$HERE/../../.." && pwd)
JAVA=${JAVA_BIN:-java}
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-selected-install.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -sourcepath "$REPO/core/src/main/java" -source 17 -target 17 -encoding UTF-8 -d "$OUT" \
  "$SRC/SelectedApkInstallPolicy.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/SelectedApkInstallPolicyChecks.java"
"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT" io.github.xyz1001.pdsync.android.SelectedApkInstallPolicyChecks
python3 -m unittest discover -s "$HERE/python" -p 'test_selected_apk_install_contract.py'

python3 -m unittest discover -s "$HERE/python" -p 'test_selected_apk_action_execution.py'

python3 -m unittest discover -s "$HERE/python" -p 'test_external_return_execution.py'
