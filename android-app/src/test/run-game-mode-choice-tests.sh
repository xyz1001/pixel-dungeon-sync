#!/usr/bin/env bash
# Executes production native-dialog listeners against minimal Android window stubs, without a phone or signing.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
SRC="$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android"
JAVA=${JAVA_BIN:-java}
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-game-mode-choice.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -sourcepath "$REPO/core/src/main/java" -source 17 -target 17 -encoding UTF-8 -Xlint:all,-options -Werror -d "$OUT" \
  "$HERE/fixtures/game-mode-dialog-stubs/android/app/Activity.java" \
  "$HERE/fixtures/game-mode-dialog-stubs/android/app/AlertDialog.java" \
  "$HERE/fixtures/game-mode-dialog-stubs/android/content/DialogInterface.java" \
  "$SRC"/{GameModeChoice,GameModeChoiceDialog}.java \
  "$HERE/java/io/github/xyz1001/pdsync/android/GameModeChoiceDialogChecks.java"
"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT" io.github.xyz1001.pdsync.android.GameModeChoiceDialogChecks
