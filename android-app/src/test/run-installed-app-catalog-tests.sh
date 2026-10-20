#!/usr/bin/env bash
# Production catalog and model against isolated PM metadata doubles. No SDK/device/su.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
JAVA=${JAVA_BIN:-java}
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-app-catalog.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
APP="$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android"
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -sourcepath "$REPO/core/src/main/java" -source 17 -target 17 -encoding UTF-8 -d "$OUT" \
  $(find "$HERE/fixtures/catalog-stubs" -name '*.java' | sort) \
  "$APP"/{InstalledAppCatalog,InstalledAppPickerModel}.java \
  "$HERE/java/io/github/xyz1001/pdsync/android/InstalledAppCatalogChecks.java"
"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT" io.github.xyz1001.pdsync.android.InstalledAppCatalogChecks
