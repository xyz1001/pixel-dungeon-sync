#!/usr/bin/env bash
# Pure Java host checks, scripted client responses only: no sockets, accounts or device.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
SRC="$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android"
JAVA=${JAVA_BIN:-java}
RUNTIME=${PDSYNC_RUNTIME_LIB:?Set PDSYNC_RUNTIME_LIB to the existing pinned runtime jars}
CORE=${PDSYNC_CORE_JAR:-$REPO/core/build/libs/core.jar}
[[ -f "$CORE" ]] || { echo 'Build the current core first: ./gradlew :core:jar' >&2; exit 1; }
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-manual-flow.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
CP="$CORE:$(find -L "$RUNTIME" -maxdepth 1 -name '*.jar' | sort | paste -sd:)"
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -Xlint:all,-serial,-options -Werror -encoding UTF-8 -cp "$CP" -d "$OUT" \
  "$SRC/ManualOrderClient.java" "$SRC/ManualPaymentFlow.java" "$SRC/ManualOrderDraftFile.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/ManualPaymentFlowChecks.java"
"$JAVA" -cp "$OUT:$CP" io.github.xyz1001.pdsync.android.ManualPaymentFlowChecks
