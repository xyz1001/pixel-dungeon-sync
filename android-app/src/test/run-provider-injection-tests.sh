#!/usr/bin/env bash
# Host-only archive/manifest/DEX verification. Never installs or executes APK or DEX code.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-"$REPO/../tools/sdk"}}
JAVA=${JAVA_BIN:-java}
OUT=${PDSYNC_PROVIDER_TEST_OUTPUT:-"$REPO/android-app/build/provider-injection-host-tests"}
FIXTURES="$HERE/fixtures/provider-injection"
mkdir -p "$OUT/classes" "$OUT/fixtures" "$OUT/game-classes" "$OUT/runtime-classes" "$OUT/game-dex" "$OUT/runtime-dex"
SRC="$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android/prepare"
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -bootclasspath "$SDK/platforms/android-26/android.jar" -d "$OUT/classes" "$SRC/"{BinaryManifestPatch,ApkArchive,PayloadInventory,GameSourceIdentity}.java
for kind in game runtime; do
    mapfile -t FILES < <(find "$FIXTURES/$kind" -name '*.java' | sort)
    "$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -bootclasspath "$SDK/platforms/android-26/android.jar" -d "$OUT/$kind-classes" "${FILES[@]}"
    mapfile -t CLASSES < <(find "$OUT/$kind-classes" -name '*.class' | sort)
    "$JAVA" -cp "$SDK/build-tools/36.0.0/lib/d8.jar" com.android.tools.r8.D8 --min-api 26 --lib "$SDK/platforms/android-26/android.jar" --output "$OUT/$kind-dex" "${CLASSES[@]}"
done
for fixture in "$FIXTURES/"*.xml; do
    "$SDK/build-tools/36.0.0/aapt2" link -I "$SDK/platforms/android-36/android.jar" --manifest "$fixture" -o "$OUT/fixtures/$(basename "$fixture" .xml).apk"
done
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -cp "$OUT/classes" -d "$OUT/classes" "$HERE/java/io/github/xyz1001/pdsync/android/prepare/ProviderInjectionTest.java"
"$JAVA" -cp "$OUT/classes" io.github.xyz1001.pdsync.android.prepare.ProviderInjectionTest "$OUT/fixtures" "$OUT/game-dex/classes.dex" "$OUT/runtime-dex/classes.dex" "$OUT"
"$SDK/build-tools/36.0.0/aapt2" dump xmltree "$OUT/injected-fixture.apk" --file AndroidManifest.xml > "$OUT/injected-manifest.txt"
echo "AAPT2 independently parsed injected manifest: $OUT/injected-manifest.txt"
"$SDK/build-tools/36.0.0/aapt2" dump xmltree "$OUT/fixtures/base.apk" --file AndroidManifest.xml > "$OUT/original-manifest.txt"
"$SDK/build-tools/36.0.0/aapt2" dump xmltree "$OUT/no-code-injected-fixture.apk" --file AndroidManifest.xml > "$OUT/no-code-manifest.txt"
python3 - "$OUT" <<'PY'
import copy, pathlib, re, sys
out = pathlib.Path(sys.argv[1])
def tree(path):
    root = None
    stack = []
    for line in path.read_text().splitlines():
        indent = len(line) - len(line.lstrip())
        element = re.match(r'\s*E: (\S+)', line)
        if element:
            node = {'tag':element.group(1), 'attrs':{}, 'children':[]}
            while stack and stack[-1][0] >= indent: stack.pop()
            if stack: stack[-1][1]['children'].append(node)
            else: root = node
            stack.append((indent,node))
        elif line.lstrip().startswith('A: '):
            key, value = line.strip()[3:].split('=',1)
            stack[-1][1]['attrs'][key] = value
    return root
original = tree(out / 'original-manifest.txt')
injected = tree(out / 'injected-manifest.txt')
# Independently parsed original tree must survive with only the explicitly allowed migration.
for key in list(original['attrs']):
    if any(':'+name+'(' in key for name in ('sharedUserId','sharedUserLabel','sharedUserMaxSdkVersion')):
        original['attrs'].pop(key)
    elif ':targetSandboxVersion(' in key:
        original['attrs'][key] = '1'
app = next(n for n in injected['children'] if n['tag']=='application')
added = [n for n in app['children'] if any('io.github.xyz1001.pdsync.' in value for key,value in n['attrs'].items() if ':name(' in key)]
assert len(added) == 11, added
provider = next(n for n in added if n['tag']=='provider')
activity = next(n for n in added if n['tag']=='activity')
assert not provider['children'] and not activity['children']
for name in ('permission','readPermission','writePermission'):
    assert next(v for k,v in provider['attrs'].items() if ':'+name+'(' in k) == '""'
assert next(v for k,v in provider['attrs'].items() if ':grantUriPermissions(' in k) == 'false'
for component in (provider,activity):
    assert next(v for k,v in component['attrs'].items() if ':exported(' in k) == 'true'
    assert '":pdsync_bridge"' in next(v for k,v in component['attrs'].items() if ':process(' in k)
for node in added:
    if node['tag']=='meta-data':
        assert next(v for k,v in node['attrs'].items() if ':value(' in k).startswith('"'), node
app['children'] = [n for n in app['children'] if n not in added]
queries = [n for n in injected['children'] if n['tag']=='queries']
assert len(queries)==1 and len(queries[0]['children'])==1
assert 'io.github.xyz1001.pdsync.android' in str(queries[0])
injected['children'] = [n for n in injected['children'] if n['tag']!='queries']
assert original == injected, 'Existing manifest tree changed beyond declared migration'
no_code = tree(out / 'no-code-manifest.txt')
no_code_app = next(n for n in no_code['children'] if n['tag']=='application')
assert next(v for k,v in no_code_app['attrs'].items() if ':hasCode(' in k) == 'true'
print('Independent AAPT2 tree comparison passed: original application/components/launcher/settings retained; exact new provider/activity/query and nine string bindings')
PY
