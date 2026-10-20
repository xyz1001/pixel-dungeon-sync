#!/usr/bin/env bash
# Exercise actual preparation entry points with only Android framework boundaries stubbed.
# APK/DEX contents are parsed as data and never installed, launched, or executed.
set -euo pipefail
REQUIRE_REFERENCE=false
if [[ "${1:-}" == "--require-reference" ]]; then REQUIRE_REFERENCE=true; shift; fi
if [[ $# -ne 0 ]]; then echo "Usage: run-preparation-service-tests.sh [--require-reference]" >&2; exit 2; fi
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-"$REPO/../tools/sdk"}}
JAVA=${JAVA_BIN:-java}
APKSIG=${PDSYNC_APKSIG_JAR:-"$SDK/build-tools/36.0.0/lib/apksigner.jar"}
REFERENCE=${PDSYNC_CODE45_REFERENCE_APK:-"$REPO/../../code45-reimplementation-review/artifacts/pixel-dungeon-sync-0.5.24-code45-signed.apk"}
OUT=${PDSYNC_SERVICE_TEST_OUTPUT:-"$REPO/android-app/build/preparation-service-host-tests"}
FIXTURES="$HERE/fixtures/provider-injection"
mkdir -p "$OUT/classes" "$OUT/fixtures" "$OUT/game-classes" "$OUT/runtime-classes" "$OUT/game-dex" "$OUT/runtime-dex"
mapfile -t SOURCES < <(find "$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android/prepare" "$HERE/fixtures/preparation-service-stubs" -name '*.java' | sort)
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -cp "$APKSIG" -d "$OUT/classes" \
    "$REPO/core/src/main/java/pdsync/i18n/UiLocale.java" "$REPO/core/src/main/java/pdsync/i18n/UiText.java" "$REPO/core/src/main/java/pdsync/i18n/PrepareMessages.java" "${SOURCES[@]}" "$HERE/java/io/github/xyz1001/pdsync/android/prepare/PreparationServiceIntegrationTest.java"
for kind in game runtime; do
    mapfile -t FILES < <(find "$FIXTURES/$kind" -name '*.java' | sort)
    "$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -bootclasspath "$SDK/platforms/android-26/android.jar" -d "$OUT/$kind-classes" "${FILES[@]}"
    mapfile -t CLASSES < <(find "$OUT/$kind-classes" -name '*.class' | sort)
    "$JAVA" -cp "$SDK/build-tools/36.0.0/lib/d8.jar" com.android.tools.r8.D8 --min-api 26 \
        --lib "$SDK/platforms/android-26/android.jar" --output "$OUT/$kind-dex" "${CLASSES[@]}"
done
for fixture in "$FIXTURES/"*.xml; do
    "$SDK/build-tools/36.0.0/aapt2" link -I "$SDK/platforms/android-36/android.jar" --manifest "$fixture" \
        -o "$OUT/fixtures/$(basename "$fixture" .xml).apk"
done
# Compile distinct real binary manifests for picked-file version regressions.
# PackageManager metadata alone must not stand in for the selected APK's version.
python3 - "$FIXTURES/base.xml" "$OUT/fixtures" <<'PY'
import pathlib, sys, xml.etree.ElementTree as ET
source, output = map(pathlib.Path, sys.argv[1:])
android = 'http://schemas.android.com/apk/res/android'
ET.register_namespace('android', android)
for name, version in [('picked-older', 122), ('picked-newer', 124)]:
    manifest = ET.parse(source)
    manifest.getroot().set(f'{{{android}}}versionCode', str(version))
    manifest.getroot().set(f'{{{android}}}versionName', name)
    manifest.write(output / f'{name}.xml', encoding='utf-8', xml_declaration=True)
PY
for name in picked-older picked-newer; do
    "$SDK/build-tools/36.0.0/aapt2" link -I "$SDK/platforms/android-36/android.jar" \
        --manifest "$OUT/fixtures/$name.xml" -o "$OUT/fixtures/$name.apk"
done
HISTORICAL_RUNTIME=""
if [[ -f "$REFERENCE" ]]; then
python3 - "$REFERENCE" "$OUT/code45-runtime.dex" <<'PY'
import hashlib, pathlib, sys, zipfile
source, output = map(pathlib.Path, sys.argv[1:])
if hashlib.sha256(source.read_bytes()).hexdigest() != 'aa839df6d2e89dfa3be062c73fb219c894ee1a08ec0556ad362d2998bdd311e5':
    raise SystemExit('Refusing unverified code45 reference APK')
with zipfile.ZipFile(source) as apk: runtime = apk.read('assets/bridge-runtime.dex')
if hashlib.sha256(runtime).hexdigest() != '92aa07a4ebc64ccc9f78d3dae160afe191a342e7596c5e4b26cf56eb3699893d':
    raise SystemExit('Refusing unverified code45 runtime DEX')
output.write_bytes(runtime)
PY
HISTORICAL_RUNTIME="$OUT/code45-runtime.dex"
elif [[ "$REQUIRE_REFERENCE" == true ]]; then
    echo "Required verified code45 reference APK missing. Set PDSYNC_CODE45_REFERENCE_APK to the published APK; SHA-256 is checked before use." >&2
    exit 1
else
    echo "SKIP: historical code45 runtime upgrade fixture (reference APK unavailable). Set PDSYNC_CODE45_REFERENCE_APK and --require-reference to require it."
fi
"$JAVA" -cp "$OUT/classes:$APKSIG" io.github.xyz1001.pdsync.android.prepare.PreparationServiceIntegrationTest \
    "$OUT/fixtures" "$OUT/game-dex/classes.dex" "$OUT/runtime-dex/classes.dex" "$OUT" "$HISTORICAL_RUNTIME"
