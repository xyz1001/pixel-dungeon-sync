#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-root-restore-host.XXXXXX")
trap 'rm -rf "$OUT"' EXIT
JAVA=${JAVA_BIN:-java}
"$JAVA" com.sun.tools.javac.Main -source 17 -target 17 -Xlint:all -d "$OUT" \
  "$ROOT"/src/main/java/io/github/xyz1001/pdsync/root/restore/*.java \
  "$ROOT"/src/test/java/io/github/xyz1001/pdsync/root/restore/RootRestoreHostTests.java
"$JAVA" -cp "$OUT" io.github.xyz1001.pdsync.root.restore.RootRestoreHostTests
