#!/usr/bin/env bash
set -euo pipefail
BASE=$(cd "$(dirname "$0")/../.." && pwd)
REPO=$(cd "$BASE/.." && pwd)
SYSROOT="${1:-${PDSYNC_NDK_SYSROOT:-$REPO/../tools/root-native/sysroot}}"
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-open-flags-tests.XXXXXX")
trap 'rm -rf "$OUT"' EXIT
SRC="$BASE/src/main/java/io/github/xyz1001/pdsync/root"
SHARED="$REPO/android-helper/src/main/java/io/github/xyz1001/pdsync/bridge"

# Preprocess each pinned ABI header; never run a cross-compiled binary or query a device.
python3 "$BASE/src/test/verify-open-flags-headers.py" "$SYSROOT" "$OUT/flags.tsv" "$SRC"
java com.sun.tools.javac.Main -source 17 -target 17 -d "$OUT/classes" \
  "$SHARED"/{SaveArchive,SaveDirectoryPolicy,BridgeFailure}.java \
  "$SRC"/{RootBinding,RootProtocol,RootFileSystem,RootOpenFlags}.java \
  "$SRC/mutation/MutationOs.java" \
  "$BASE/src/test/java/io/github/xyz1001/pdsync/root/RootOpenFlagsHostTests.java"
java -cp "$OUT/classes" io.github.xyz1001.pdsync.root.RootOpenFlagsHostTests "$OUT/flags.tsv"
echo 'Host-only open-flag verification passed; no root, su, app_process, ADB or Android device invoked.'
