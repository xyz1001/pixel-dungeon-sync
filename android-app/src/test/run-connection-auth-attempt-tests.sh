#!/usr/bin/env bash
# Host-only checks: no Android SDK, platform stubs, devices, or network required.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
JAVA=${JAVA_BIN:-java}
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-connection-auth.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -encoding UTF-8 -Xlint:all,-options -Werror -d "$OUT" \
  "$REPO/core/src/main/java/io/github/xyz1001/pdsync/android/ConnectionAuthAttempt.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/ConnectionAuthAttemptChecks.java"
"$JAVA" -cp "$OUT" io.github.xyz1001.pdsync.android.ConnectionAuthAttemptChecks
