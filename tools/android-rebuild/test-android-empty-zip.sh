#!/usr/bin/env bash
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO="${PDSYNC_SOURCE_ROOT:-$(cd "$HERE/../.." && pwd)}"
OUT="${PDSYNC_EMPTY_ZIP_TEST_OUTPUT:-${TMPDIR:-/tmp}/pdsync-android-empty-zip}"
JAVA="${JAVA_BIN:-java}"
mkdir -p "$OUT/agent" "$OUT/interop"
"$JAVA" --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED com.sun.tools.javac.Main \
  --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED -d "$OUT/agent" "$HERE/tests/AndroidEmptyZipAgent.java"
python3 - "$OUT" <<'PY'
import pathlib, sys, zipfile
out=pathlib.Path(sys.argv[1])
with zipfile.ZipFile(out/'android-empty-zip-agent.jar','w') as jar:
    jar.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nPremain-Class: AndroidEmptyZipAgent\nCan-Retransform-Classes: true\n\n')
    for file in (out/'agent').rglob('*.class'): jar.write(file,file.relative_to(out/'agent'))
(out/'android-empty-zip.init.gradle').write_text('''allprojects {
    tasks.withType(Test).configureEach {
        jvmArgs '--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED', '-javaagent:%s'
        testLogging { events 'failed', 'skipped'; exceptionFormat 'full' }
    }
}
''' % (out/'android-empty-zip-agent.jar'))
PY
"$JAVA" com.sun.tools.javac.Main -source 17 -target 17 -d "$OUT/interop" \
  "$REPO/android-helper/src/main/java/io/github/xyz1001/pdsync/bridge/SaveArchive.java" \
  "$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android/SaveArchiveCodec.java" \
  "$HERE/tests/ArchiveInteropTests.java"
"$JAVA" --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED \
  "-javaagent:$OUT/android-empty-zip-agent.jar" -cp "$OUT/interop" io.github.xyz1001.pdsync.bridge.ArchiveInteropTests
cd "$REPO"
"${GRADLE_BIN:-$REPO/gradlew}" --no-daemon --console=plain --max-workers=2 \
  -I "$OUT/android-empty-zip.init.gradle" :core:test \
  --tests pdsync.EmptyArchiveCompatibilityTest --tests pdsync.AndroidFacadeReconstructionTest \
  --tests pdsync.OnboardingComparisonTest --tests pdsync.CoreTest "$@"
