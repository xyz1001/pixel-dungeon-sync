#!/usr/bin/env bash
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
SRC="$HERE/../main/java/io/github/xyz1001/pdsync/android"
REPO=$(cd "$HERE/../../.." && pwd)
JAVA=${JAVA_BIN:-java}
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-external-installer.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -sourcepath "$REPO/core/src/main/java" -source 17 -target 17 -encoding UTF-8 -d "$OUT" \
  "$SRC"/{ExternalInstallerHandoff,ExternalInstallerFiles,ExternalApkProvider,InstallSessionWriter,InstallDiagnostics,ApkSetIdentity}.java \
  $(find "$HERE/fixtures/external-installer-stubs" -name '*.java' | sort) \
  "$HERE/java/io/github/xyz1001/pdsync/android/ExternalInstallerHandoffChecks.java"
"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT" io.github.xyz1001.pdsync.android.ExternalInstallerHandoffChecks "$OUT/data"
python3 -m unittest discover -s "$HERE/python" -p 'test_external_installer_contract.py'
