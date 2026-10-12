#!/usr/bin/env bash
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
BASE="${PDSYNC_SOURCE_ROOT:-$(cd "$HERE/../.." && pwd)}/android-helper"
SRC="$BASE/src/main/java/io/github/xyz1001/pdsync/bridge"
OUT="${PDSYNC_TEST_OUTPUT:-${TMPDIR:-/tmp}/pdsync-helper-host-classes}"
mkdir -p "$OUT"
java com.sun.tools.javac.Main -source 17 -target 17 -d "$OUT" "$SRC"/{SaveArchive,BridgeFailure,SaveDirectoryPolicy,GameBinding,MainCallerPolicy,BackupCleanupPolicy,RestoreWorkspace}.java "$BASE/src/test/java/io/github/xyz1001/pdsync/bridge/HelperHostTests.java"
java -cp "$OUT" io.github.xyz1001.pdsync.bridge.HelperHostTests
