#!/usr/bin/env bash
set -euo pipefail
BASE=$(cd "$(dirname "$0")/../.." && pwd)
REPO=$(cd "$BASE/.." && pwd)
OUT="${PDSYNC_MUTATION_TEST_OUTPUT:-${TMPDIR:-/tmp}/pdsync-mutation-host-classes}"
mkdir -p "$OUT"
SRC="$BASE/src/main/java/io/github/xyz1001/pdsync/root"
SHARED="$REPO/android-helper/src/main/java/io/github/xyz1001/pdsync/bridge"
java com.sun.tools.javac.Main -source 17 -target 17 -d "$OUT" \
  "$SHARED"/{SaveArchive,SaveDirectoryPolicy,BridgeFailure}.java \
  "$SRC"/{RootBinding,RootProtocol,RootFileSystem}.java \
  "$SRC"/mutation/{MutationOs,MutationProcessLock,RootMutationSession,RootMutationReadGuard}.java \
  "$BASE/src/test/java/io/github/xyz1001/pdsync/root/mutation/"*.java
java -cp "$OUT" io.github.xyz1001.pdsync.root.mutation.MutationHostTests
java -cp "$OUT" io.github.xyz1001.pdsync.root.mutation.ReadGuardHostTests
