#!/usr/bin/env bash
# Real Kotlin adapter against isolated PM/graphics/Compose doubles; no SDK/device/APK execution.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-game-icon.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
KOTLINC=${KOTLINC_BIN:-kotlinc}
JAVA=${JAVA_BIN:-java}
RUNTIME_ARGS=(-include-runtime)
RUNTIME_CP=""
# A cached embeddable compiler can be used without downloading a kotlinc distribution.
if [[ -n ${KOTLIN_STDLIB_JAR:-} ]]; then
  RUNTIME_ARGS=(-no-stdlib -no-reflect -classpath "$KOTLIN_STDLIB_JAR")
  RUNTIME_CP=":$KOTLIN_STDLIB_JAR"
fi
mapfile -t STUBS < <(find "$HERE/fixtures/game-icon-stubs" -name '*.kt' | sort)
"$KOTLINC" -jvm-target 17 "${RUNTIME_ARGS[@]}" -d "$OUT/checks.jar" \
  "${STUBS[@]}" \
  "$REPO/android-app/src/main/kotlin/io/github/xyz1001/pdsync/android/AndroidGameIcon.kt" \
  "$HERE/fixtures/game-icon-checks/AndroidGameIconChecks.kt"
"$JAVA" -cp "$OUT/checks.jar$RUNTIME_CP" io.github.xyz1001.pdsync.android.AndroidGameIconChecks
