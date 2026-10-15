#!/usr/bin/env bash
# Host-only official-contract checks. Synthetic identities/responses; no network or accounts.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
JAVA=${JAVA_BIN:-java}
RUNTIME=${PDSYNC_RUNTIME_LIB:?Set PDSYNC_RUNTIME_LIB to the existing pinned runtime jars}
CORE=${PDSYNC_CORE_JAR:-$REPO/core/build/libs/core.jar}
if [[ ! -f "$CORE" ]]; then
  echo "Build the current core first: ./gradlew :core:jar" >&2
  exit 1
fi
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-managed-account.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
CP="$CORE:$(find -L "$RUNTIME" -maxdepth 1 -name '*.jar' | sort | paste -sd:)"
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -encoding UTF-8 -cp "$CP" -d "$OUT" \
  "$REPO/core/src/main/java/io/github/xyz1001/pdsync/android/ManagedAccountClient.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/ManagedAccountClientChecks.java"
"$JAVA" -cp "$OUT:$CP" io.github.xyz1001.pdsync.android.ManagedAccountClientChecks
