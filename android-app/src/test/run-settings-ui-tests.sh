#!/usr/bin/env bash
# Host-only settings ownership, capabilities and source seam checks. No Gradle or device execution.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
JAVA=${JAVA_BIN:-java}
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-settings-ui.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -sourcepath "$REPO/core/src/main/java" -source 17 -target 17 -encoding UTF-8 -Xlint:all,-options -Werror -d "$OUT" \
  "$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android/SettingsActionContext.java" \
  "$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android/SettingsCapabilities.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/SettingsHostChecks.java"
"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT" io.github.xyz1001.pdsync.android.SettingsHostChecks
python3 -m unittest discover -s "$HERE/python" -p 'test_shared_settings_android_contract.py'
