#!/usr/bin/env bash
# Production resolver + identity code against host PM/preparation/file boundary doubles. No su/ADB.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
JAVA=${JAVA_BIN:-java}
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-root-resolver.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
APP="$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android"
ROOT="$REPO/android-root/src/main/java/io/github/xyz1001/pdsync/root"
SHARED="$REPO/android-helper/src/main/java/io/github/xyz1001/pdsync/bridge"
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -sourcepath "$REPO/core/src/main/java" -source 17 -target 17 -encoding UTF-8 -d "$OUT" \
  $(find "$HERE/fixtures/runtime-stubs" "$HERE/fixtures/root-resolver-stubs" -name '*.java' | sort) \
  "$APP"/{RootTargetResolver,SelectedRootGame,RootSelectionStore,InstalledBridgeBinding,ApkSetIdentity}.java \
  "$APP/prepare"/{GameSourceIdentity,ApkArchive,BinaryManifestPatch,PayloadInventory}.java \
  "$ROOT"/{RootBinding,RootProtocol}.java \
  "$SHARED"/{SaveArchive,SaveDirectoryPolicy,BridgeFailure}.java \
  "$HERE/java/io/github/xyz1001/pdsync/android/RootTargetResolverChecks.java"
"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT" io.github.xyz1001.pdsync.android.RootTargetResolverChecks
