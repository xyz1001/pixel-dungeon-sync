#!/usr/bin/env bash
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-root-auth.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
JAVA=${JAVA_BIN:-java}
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -encoding UTF-8 -d "$OUT" \
  "$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android/RootAuthorizationProbe.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/RootAuthorizationProbeChecks.java"
"$JAVA" -cp "$OUT" io.github.xyz1001.pdsync.android.RootAuthorizationProbeChecks
