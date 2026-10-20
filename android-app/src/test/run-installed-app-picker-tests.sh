#!/usr/bin/env bash
# Host-only checks: no Android SDK, platform stubs, devices, or APK tooling needed.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
JAVA=${JAVA_BIN:-java}
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-installed-app-picker.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
# Use source/target rather than --release so stripped host JDKs without ct.sym also work.
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -sourcepath "$REPO/core/src/main/java" -source 17 -target 17 -encoding UTF-8 -Xlint:all,-options -Werror -d "$OUT" \
  "$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android/InstalledAppPickerModel.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/InstalledAppPickerModelChecks.java"
"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT" io.github.xyz1001.pdsync.android.InstalledAppPickerModelChecks
