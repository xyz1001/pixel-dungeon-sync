#!/usr/bin/env bash
set -euo pipefail
BASE=$(cd "$(dirname "$0")/../.." && pwd)
REPO=$(cd "$BASE/.." && pwd)
SDK="${PDSYNC_ANDROID_JAR:-$REPO/../tools/sdk/platforms/android-36/android.jar}"
OUT="${PDSYNC_MUTATION_ANDROID_OUTPUT:-${TMPDIR:-/tmp}/pdsync-mutation-android-classes}"
mkdir -p "$OUT"
SRC="$BASE/src/main/java/io/github/xyz1001/pdsync/root"
SHARED="$REPO/android-helper/src/main/java/io/github/xyz1001/pdsync/bridge"
java com.sun.tools.javac.Main -source 8 -target 8 -cp "$SDK" -d "$OUT" \
  "$SHARED"/{SaveArchive,SaveDirectoryPolicy,BridgeFailure}.java \
  "$SRC"/{RootBinding,RootProtocol,RootFileSystem,RootAtomicRename}.java \
  "$SRC"/mutation/*.java
printf '%s\n' 'Android mutation sources compile-checked only. No runner/native/root/device execution.'
