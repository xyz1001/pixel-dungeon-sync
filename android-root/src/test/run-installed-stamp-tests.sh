#!/usr/bin/env bash
set -euo pipefail
BASE=$(cd "$(dirname "$0")/../.." && pwd)
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-root-stamp.XXXXXX")
trap 'rm -rf "$OUT"' EXIT
java com.sun.tools.javac.Main -source 17 -target 17 -d "$OUT" \
  "$BASE/../android-helper/src/main/java/io/github/xyz1001/pdsync/bridge/SaveArchive.java" \
  "$BASE/src/main/java/io/github/xyz1001/pdsync/root/RootProtocol.java" \
  "$BASE/src/main/java/io/github/xyz1001/pdsync/root/RootBinding.java" \
  "$BASE/src/main/java/io/github/xyz1001/pdsync/root/RootInstalledStamp.java" \
  "$BASE/src/test/java/io/github/xyz1001/pdsync/root/RootInstalledStampTests.java"
java -cp "$OUT" io.github.xyz1001.pdsync.root.RootInstalledStampTests
