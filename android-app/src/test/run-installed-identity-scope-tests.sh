#!/usr/bin/env bash
# Newly reconstructed code92 tests. Runs real identity/trust policy on JVM doubles.
# No APK installation, game, account, server, credentials or signing is used.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
SRC="$REPO/android-app/src/main/java"
PKG=io/github/xyz1001/pdsync/android
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-installed-identity.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
JAVA=${JAVA_BIN:-java}
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -d "$OUT" \
  -sourcepath "$HERE/fixtures/identity-scope-stubs:$HERE/fixtures/runtime-stubs:$SRC:$REPO/core/src/main/java" \
  "$SRC/$PKG/InstalledApkIdentityScope.java" \
  "$HERE/java/$PKG/InstalledApkIdentityScopeChecks.java"
"$JAVA" -cp "$OUT" io.github.xyz1001.pdsync.android.InstalledApkIdentityScopeChecks
# Insert unchanged production method bodies, rather than a reimplementation of their branches.
python3 - "$SRC/$PKG/BridgeClient.java" "$HERE/fixtures/identity-scope-stubs/BridgeClientIdentityChecks.java.in" "$OUT/BridgeClientIdentityChecks.java" <<'PY'
from pathlib import Path
import sys
source, template, target = (Path(value) for value in sys.argv[1:])
s = source.read_text()
def method(signature):
    start = s.index(signature)
    brace = s.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        if s[end] == '{': depth += 1
        if s[end] == '}': depth -= 1
        end += 1
    return s[start:end]
signatures = [
    'public BridgeClient(Context context, GamePreparationService.PreparedPair',
    'public BridgeClient(Context context, InstalledBridgeBinding',
    'private String gamePackage()', 'private String originIdentity()', 'private String sourceHash()',
    'private String authority()', 'private String runtimeVersion()', 'private boolean embedded()',
    'public AndroidGameTarget targetGame()', 'public AutoCloseable beginHomeReadIdentityScope()',
    'private static Failure homeIdentityFailure(', 'private Bundle exchange('
]
methods = '\n\n'.join(method(signature) for signature in signatures)
methods = methods.replace('public BridgeClient(', 'public BridgeClientIdentityChecks(').replace('BridgeClient::', 'BridgeClientIdentityChecks::')
target.write_text(template.read_text().replace('// PRODUCTION_METHODS', methods))
# Restore's early guard is checked in its unchanged production body. The actual scope mutation
# rejection executes above and in exchange; archive construction/Android FD code is not emulated.
restore = method('public void restoreFrom(')
assert restore.index('homeIdentity.requireNoReadScope()') < restore.index('targetGame()') < restore.index('File.createTempFile')
PY
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -d "$OUT" -cp "$OUT" "$OUT/BridgeClientIdentityChecks.java"
"$JAVA" -cp "$OUT" io.github.xyz1001.pdsync.android.BridgeClientIdentityChecks
