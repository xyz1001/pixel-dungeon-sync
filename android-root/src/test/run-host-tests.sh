#!/usr/bin/env bash
set -euo pipefail
BASE=$(cd "$(dirname "$0")/../.." && pwd)
REPO=$(cd "$BASE/.." && pwd)
OUT="${PDSYNC_ROOT_TEST_OUTPUT:-${TMPDIR:-/tmp}/pdsync-root-host-classes}"
mkdir -p "$OUT"
SRC="$BASE/src/main/java/io/github/xyz1001/pdsync/root"
SHARED="$REPO/android-helper/src/main/java/io/github/xyz1001/pdsync/bridge"
java com.sun.tools.javac.Main -source 17 -target 17 -d "$OUT" \
  "$SHARED"/{SaveArchive,SaveDirectoryPolicy,BridgeFailure}.java \
  "$SRC"/{RootBinding,RootFailure,RootProtocol,RootFileSystem,RootSaveTree,RootUidState,RootRuntime,RootService}.java \
  "$BASE/src/test/java/io/github/xyz1001/pdsync/root/RootHostTests.java"
java -cp "$OUT" io.github.xyz1001.pdsync.root.RootHostTests
