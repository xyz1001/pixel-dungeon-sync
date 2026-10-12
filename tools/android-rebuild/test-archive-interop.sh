#!/usr/bin/env bash
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO="${PDSYNC_SOURCE_ROOT:-$(cd "$HERE/../.." && pwd)}"
OUT="${PDSYNC_INTEROP_TEST_OUTPUT:-${TMPDIR:-/tmp}/pdsync-archive-interop-classes}"
mkdir -p "$OUT"
java com.sun.tools.javac.Main -source 17 -target 17 -d "$OUT" "$REPO/android-helper/src/main/java/io/github/xyz1001/pdsync/bridge/SaveArchive.java" "$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android/SaveArchiveCodec.java" "$HERE/tests/ArchiveInteropTests.java"
java -cp "$OUT" io.github.xyz1001.pdsync.bridge.ArchiveInteropTests
