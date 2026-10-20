#!/usr/bin/env bash
# Real coordinator/receiver/resolver/session-writer on host doubles, not Android device evidence.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
SRC="$HERE/../main/java/io/github/xyz1001/pdsync/android"
REPO=$(cd "$HERE/../../.." && pwd)
JAVA=${JAVA_BIN:-java}
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-install-component.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main "$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android/StoredAndroidText.java" "$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android/AutoSyncMessages.java" -sourcepath "$REPO/core/src/main/java" -source 17 -target 17 -d "$OUT" \
  "$SRC"/{InstallCoordinator,InstallResultReceiver,SelectedApkInstallPolicy,InstalledBridgeResolver,InstalledBridgeBinding,InstallSessionWriter,InstallDiagnostics,InstallProgressPolicy,ApkSetIdentity,SystemConfirmationToken}.java \
  $(find "$HERE/fixtures/install-component-stubs" -name '*.java' | sort) \
  "$HERE/java/io/github/xyz1001/pdsync/android/InstallComponentCoordinatorChecks.java"
"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT" io.github.xyz1001.pdsync.android.InstallComponentCoordinatorChecks "$OUT/data"
