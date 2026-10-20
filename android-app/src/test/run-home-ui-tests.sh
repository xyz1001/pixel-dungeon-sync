#!/usr/bin/env bash
# Host-only callback ownership and source seam checks. No Gradle, APK or device execution.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
JAVA=${JAVA_BIN:-java}
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-home-ui.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -sourcepath "$REPO/core/src/main/java" -source 17 -target 17 -encoding UTF-8 -Xlint:all,-options -Werror -d "$OUT" \
  "$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android/HomeActionContext.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/HomeActionContextChecks.java" \
  "$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android/HomeArchiveReadState.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/HomeArchiveReadStateChecks.java"
"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT" io.github.xyz1001.pdsync.android.HomeActionContextChecks
"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT" io.github.xyz1001.pdsync.android.HomeArchiveReadStateChecks
python3 -m unittest discover -s "$HERE/python" -p 'test_shared_home_android_contract.py'
python3 -m unittest discover -s "$HERE/python" -p 'test_history_rendering_contract.py'

python3 -m unittest discover -s "$HERE/python" -p 'test_home_archive_action_execution.py'

python3 -m unittest discover -s "$HERE/python" -p 'test_home_initial_history_execution.py'

python3 -m unittest discover -s "$HERE/python" -p 'test_home_history_read_execution.py'

python3 -m unittest discover -s "$HERE/python" -p 'test_renewal_archive_read_execution.py'

python3 -m unittest discover -s "$HERE/python" -p 'test_shared_branch_choice_android_contract.py'

python3 -m unittest discover -s "$HERE/python" -p 'test_branch_choice_host_execution.py'
