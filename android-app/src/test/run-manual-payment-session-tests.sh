#!/usr/bin/env bash
# Pure Java session/lifecycle checks. Every executor, clock, transport and verifier is injected.
# No sockets, Android device, credentials, real payment, or external service is used.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
SRC="$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android"
SHARED="$REPO/core/src/main/java/io/github/xyz1001/pdsync/android"
JAVA=${JAVA_BIN:-java}
RUNTIME=${PDSYNC_RUNTIME_LIB:?Set PDSYNC_RUNTIME_LIB to the existing pinned runtime jars}
CORE=${PDSYNC_CORE_JAR:-$REPO/core/build/libs/core.jar}
[[ -f "$CORE" ]] || { echo 'Build the current core first: ./gradlew :core:jar' >&2; exit 1; }
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-manual-session.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
CP="$CORE:$(find -L "$RUNTIME" -maxdepth 1 -name '*.jar' | sort | paste -sd:)"
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -Xlint:all,-serial,-options -Werror -encoding UTF-8 -cp "$CP" -d "$OUT" \
  "$SHARED/OfficialServiceRegistrationClient.java" "$SRC/ManualOrderClient.java" \
  "$SRC/ManualPaymentFlow.java" "$SRC/ManualPaymentSession.java" "$SRC/ManualPaymentCopy.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/ManualPaymentSessionChecks.java"
"$JAVA" -cp "$OUT:$CP" io.github.xyz1001.pdsync.android.ManualPaymentSessionChecks
