#!/usr/bin/env bash
# Pure JVM regression tests. No Android APK is installed, launched, or executed.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
SRC="$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android"
JAVA=${JAVA_BIN:-java}
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-main-runtime.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -d "$OUT" \
  "$SRC"/{BridgeTransport,BridgeDiagnostics,BridgeReadiness,InstallationReadiness,HelperActivationPolicy,PackageTrust,ApkSetIdentity,WebDavConnectionFile,LegacyWebDavConnectionFile,ConnectionEndpointPolicy,ConnectionUpdatePolicy,AutoSyncPolicy,AutoSyncMessages,AndroidCompat,RuntimePlatformCompat,InstallProgressPolicy,InstallDiagnostics,InstallSessionWriter,SystemConfirmationToken,InstallResultReceiver,HistoryArrowGeometry}.java \
  $(find "$HERE/fixtures/runtime-stubs" -name '*.java' | sort) \
  "$HERE/java/io/github/xyz1001/pdsync/android/MainRuntimeRecoveryChecks.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/HistoryArrowGeometryChecks.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/RuntimePlatformCompatibilityChecks.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/EmbeddedBridgeRuntimeChecks.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/InstallSessionWriterChecks.java"
"$JAVA" -cp "$OUT" io.github.xyz1001.pdsync.android.MainRuntimeRecoveryChecks
"$JAVA" -cp "$OUT" io.github.xyz1001.pdsync.android.HistoryArrowGeometryChecks
"$JAVA" -cp "$OUT" io.github.xyz1001.pdsync.android.RuntimePlatformCompatibilityChecks

"$JAVA" -cp "$OUT" io.github.xyz1001.pdsync.android.EmbeddedBridgeRuntimeChecks

"$JAVA" -cp "$OUT" io.github.xyz1001.pdsync.android.InstallSessionWriterChecks
