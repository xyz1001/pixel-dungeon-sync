#!/usr/bin/env bash
# Real Kotlin adapter against isolated window/lifecycle/Compose doubles; no SDK/device/APK execution.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-picker-host.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
KOTLINC=${KOTLINC_BIN:-kotlinc}
JAVA=${JAVA_BIN:-java}
RUNTIME_ARGS=(-include-runtime)
RUNTIME_CP=""
# A cached embeddable compiler can be used without downloading a kotlinc distribution.
if [[ -n ${KOTLIN_STDLIB_JAR:-} ]]; then
  RUNTIME_ARGS=(-no-stdlib -no-reflect)
  RUNTIME_CP=":$KOTLIN_STDLIB_JAR"
fi
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -sourcepath "$REPO/core/src/main/java" -source 17 -target 17 -d "$OUT/classes" \
  "$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android/InstalledAppPickerModel.java" \
  "$REPO/core/src/main/java/pdsync/i18n/AndroidHostMessages.java"
RUNTIME_ARGS+=(-classpath "$OUT/classes${KOTLIN_STDLIB_JAR:+:$KOTLIN_STDLIB_JAR}")
mapfile -t STUBS < <(find "$HERE/fixtures/picker-host-stubs" -name '*.kt' | sort)
"$KOTLINC" -jvm-target 17 "${RUNTIME_ARGS[@]}" -d "$OUT/checks.jar" \
  "${STUBS[@]}" \
  "${PICKER_ADAPTER_SOURCE:-$REPO/android-app/src/main/kotlin/io/github/xyz1001/pdsync/android/AndroidInstalledAppPickerComposition.kt}" \
  "$HERE/fixtures/picker-host-checks/AndroidPickerHostChecks.kt"
"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT/checks.jar:$OUT/classes$RUNTIME_CP" io.github.xyz1001.pdsync.android.AndroidPickerHostChecks
