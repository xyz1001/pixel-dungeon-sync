#!/usr/bin/env bash
# Host-only UI policy and source integration regressions; no Android device, Root, or live DAV.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
SRC="$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android"
JAVA=${JAVA_BIN:-java}
CORE=${PDSYNC_CORE_JAR:-$REPO/core/build/libs/core.jar}
RUNTIME=${PDSYNC_RUNTIME_LIB:?Set PDSYNC_RUNTIME_LIB to the Kotlin runtime jars}
if [[ ! -f "$CORE" ]]; then
  echo "Build the current core first: ./gradlew :core:jar" >&2
  exit 1
fi
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-ui-flow.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
CP="$CORE:$(find "$RUNTIME" -maxdepth 1 -name '*.jar' | sort | paste -sd:)"
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -sourcepath "$REPO/core/src/main/java" -source 17 -target 17 -encoding UTF-8 -cp "$CP" -d "$OUT" \
  "$SRC"/{AndroidUiFlowPolicy,ConnectionEndpointPolicy,ConnectionUpdatePolicy}.java \
  "$HERE/java/io/github/xyz1001/pdsync/android/AndroidUiFlowChecks.java"
"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT:$CP" io.github.xyz1001.pdsync.android.AndroidUiFlowChecks
bash "$HERE/run-connection-entry-tests.sh"
bash "$HERE/run-connection-auth-attempt-tests.sh"
python3 -m unittest discover -s "$HERE/python" -p 'test_ui_flow_contract.py'

python3 -m unittest discover -s "$HERE/python" -p 'test_shared_connection_android_contract.py'
