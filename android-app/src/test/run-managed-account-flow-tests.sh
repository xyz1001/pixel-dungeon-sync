#!/usr/bin/env bash
# Independent host-only state/cache/Activity-wiring checks. No Android device or real service.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
JAVA=${JAVA_BIN:-java}
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-managed-flow.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -sourcepath "$REPO/core/src/main/java" -source 17 -target 17 -encoding UTF-8 -Xlint:all,-options -Werror -d "$OUT" \
  "$REPO/core/src/main/java/io/github/xyz1001/pdsync/android/ManagedAccountFlow.java" \
  "$REPO/core/src/main/java/io/github/xyz1001/pdsync/android/TrustedEntitlementCache.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/ManagedAccountFlowChecks.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/TrustedEntitlementCacheChecks.java"
"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT" io.github.xyz1001.pdsync.android.ManagedAccountFlowChecks "$REPO"
"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT" io.github.xyz1001.pdsync.android.TrustedEntitlementCacheChecks

python3 "$HERE/fixtures/managed-account-access-harness.py" "$REPO" "$OUT"
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -sourcepath "$REPO/core/src/main/java" -source 17 -target 17 -encoding UTF-8 -Xlint:all,-options -Werror -d "$OUT" \
  "$OUT/android/content/Context.java" "$OUT/ManagedAccountAccessHarness.java"
"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT" io.github.xyz1001.pdsync.android.ManagedAccountAccessHarness
