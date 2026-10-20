#!/usr/bin/env bash
# Pure JVM regression tests. No Android APK is installed, launched, or executed.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
SRC="$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android"
JAVA=${JAVA_BIN:-java}
CORE=${PDSYNC_CORE_JAR:-$REPO/core/build/libs/core.jar}
RUNTIME=${PDSYNC_RUNTIME_LIB:?Set PDSYNC_RUNTIME_LIB to the Kotlin runtime jars}
CP="$CORE:$(find -L "$RUNTIME" -maxdepth 1 -name '*.jar' | sort | paste -sd:)"
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-main-runtime.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main "$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android/StoredAndroidText.java" "$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android/AutoSyncMessages.java" -sourcepath "$REPO/core/src/main/java" -source 17 -target 17 -cp "$CP" -d "$OUT" \
  "$SRC"/{AndroidDiagnosticLogging,BridgeTransport,BridgeDiagnostics,BridgeSaveLayout,BridgeReadiness,InstallationReadiness,PathPreviewState,HelperActivationPolicy,PackageTrust,InstalledBridgeBinding,InstalledBridgeResolver,InstalledBridgeSelectionStore,InstalledBridgeProtocol,ApkSetIdentity,WebDavConnectionFile,LegacyWebDavConnectionFile,ConnectionEndpointPolicy,ConnectionUpdatePolicy,AutoSyncPolicy,AutoSyncMessages,AutoSyncStatusPolicy,AutoSyncHistoryRefresh,AndroidCompat,RuntimePlatformCompat,InstallProgressPolicy,InstallDiagnostics,InstallSessionWriter,SyncInspectionDiagnostics,SystemConfirmationToken,InstallResultReceiver,HistoryArrowGeometry}.java \
  "$SRC/prepare/"{GameSourceIdentity,ApkArchive,BinaryManifestPatch,PayloadInventory}.java \
  $(find "$HERE/fixtures/runtime-stubs" -name '*.java' | sort) \
  "$HERE/java/io/github/xyz1001/pdsync/android/PathPreviewStateChecks.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/AndroidDiagnosticLoggingChecks.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/AutoSyncStatusChecks.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/AutoSyncHistoryRefreshChecks.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/MainRuntimeRecoveryChecks.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/SaveLayoutDiagnosticChecks.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/SyncInspectionDiagnosticChecks.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/HistoryArrowGeometryChecks.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/RuntimePlatformCompatibilityChecks.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/EmbeddedBridgeRuntimeChecks.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/InstallSessionWriterChecks.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/InstalledBridgeAccessChecks.java"
"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT:$CP" io.github.xyz1001.pdsync.android.MainRuntimeRecoveryChecks
"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT:$CP" io.github.xyz1001.pdsync.android.HistoryArrowGeometryChecks
"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT:$CP" io.github.xyz1001.pdsync.android.RuntimePlatformCompatibilityChecks

"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT:$CP" io.github.xyz1001.pdsync.android.EmbeddedBridgeRuntimeChecks

"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT:$CP" io.github.xyz1001.pdsync.android.InstallSessionWriterChecks

"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT:$CP" io.github.xyz1001.pdsync.android.SaveLayoutDiagnosticChecks

"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT:$CP" io.github.xyz1001.pdsync.android.SyncInspectionDiagnosticChecks

"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT:$CP" io.github.xyz1001.pdsync.android.AutoSyncStatusChecks

"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT:$CP" io.github.xyz1001.pdsync.android.AutoSyncHistoryRefreshChecks

"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT:$CP" io.github.xyz1001.pdsync.android.AndroidDiagnosticLoggingChecks

"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT:$CP" io.github.xyz1001.pdsync.android.PathPreviewStateChecks

"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT:$CP" io.github.xyz1001.pdsync.android.InstalledBridgeAccessChecks
