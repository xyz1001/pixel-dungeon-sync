#!/usr/bin/env python3
import importlib.util
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('apksig_compat', HERE.parent / 'apksig_compat.py')
compat = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compat)
fixture_spec = importlib.util.spec_from_file_location('public_fixture', HERE / 'public_fixture.py')
public_fixture = importlib.util.module_from_spec(fixture_spec)
fixture_spec.loader.exec_module(public_fixture)

class PatchTests(unittest.TestCase):
    def test_unrecognized_bytes_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Unrecognized'):
            compat.patch_parser(b'not the pinned dependency')

    def test_patched_jar_and_runtime(self):
        root = compat.REPO.parent
        sdk = Path(os.environ.get('ANDROID_SDK_ROOT', str(root / 'tools/sdk')))
        java = os.environ.get('JAVA_BIN', 'java')
        source = sdk / 'build-tools/36.0.0/lib/apksigner.jar'
        android = sdk / 'platforms/android-26/android.jar'
        if not source.is_file() or not android.is_file():
            self.fail('Official SDK Build-Tools 36 and API-26 platform are required')
        with tempfile.TemporaryDirectory(prefix='pdsync-apksig-test-') as tmp:
            tmp = Path(tmp)
            output = compat.build_compat_jar(source, tmp / 'compat.jar', android, java)
            second = compat.build_compat_jar(source, tmp / 'compat-second.jar', android, java)
            self.assertEqual(output.read_bytes(), second.read_bytes(), 'Generated jar must be deterministic')
            with zipfile.ZipFile(source) as original, zipfile.ZipFile(output) as patched:
                names = {n for n in original.namelist() if not n.endswith('/')}
                self.assertEqual(set(patched.namelist()) - names, {compat.HELPER + '.class', compat.NOTICE})
                changed = [n for n in names if original.read(n) != patched.read(n)]
                self.assertEqual(changed, [compat.PARSER])
            self.assertEqual(compat.sha(source.read_bytes()), compat.JAR_SHA256)
            classes = tmp / 'classes'
            classes.mkdir()
            subprocess.run([java, 'com.sun.tools.javac.Main', '-source', '8', '-target', '8',
                            '-bootclasspath', str(android), '-classpath', str(output), '-d', str(classes),
                            str(HERE / 'ApksigApi26CompatTest.java')], check=True)
            fixture = public_fixture.aosp_v1_fixture()
            tampered = tmp / 'tampered-v1.apk'
            with zipfile.ZipFile(fixture) as original, zipfile.ZipFile(tampered, 'w') as changed:
                for entry in original.infolist():
                    payload = original.read(entry)
                    if entry.filename == 'temp.txt':
                        payload = b'tampered payload'
                    changed.writestr(entry, payload)
            subprocess.run([java, '-cp', str(classes) + os.pathsep + str(output),
                            'com.android.apksig.internal.asn1.ApksigApi26CompatTest',
                            str(fixture), str(tampered)], check=True)
            dex = tmp / 'dex'
            dex.mkdir()
            subprocess.run([java, '-Xmx512m', '-cp', str(sdk / 'build-tools/36.0.0/lib/d8.jar'),
                            'com.android.tools.r8.D8', '--release', '--min-api', '26', '--lib',
                            str(android), '--output', str(dex), str(output)], check=True)
            data = (dex / 'classes.dex').read_bytes()
            self.assertEqual(data[:8], b'dex\n038\0')
            # Class files may retain unused constant-pool entries. Verify the actual
            # Android method table, not text/constant-pool grep, after D8 processing.
            def uint(offset):
                return struct.unpack_from('<I', data, offset)[0]
            strings = []
            for index in range(uint(56)):
                offset = uint(uint(60) + index * 4)
                while data[offset] & 128:
                    offset += 1
                offset += 1
                strings.append(data[offset:data.index(0, offset)].decode('utf-8', errors='replace'))
            types = [strings[uint(uint(68) + index * 4)] for index in range(uint(64))]
            methods = set()
            for index in range(uint(88)):
                owner, proto, name = struct.unpack_from('<HHI', data, uint(92) + index * 8)
                methods.add((types[owner], strings[name]))
            self.assertNotIn(('Ljava/lang/reflect/Type;', 'getTypeName'), methods)
            self.assertNotIn(('Ljava/lang/invoke/LambdaMetafactory;', 'metafactory'), methods)
            self.assertIn(('L' + compat.HELPER + ';', 'getElementType'), methods)

if __name__ == '__main__':
    unittest.main()
