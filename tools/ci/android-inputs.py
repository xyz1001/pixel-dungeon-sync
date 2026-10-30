"""Build all Android binary inputs from this checkout; no retained local binaries."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]

def run(*args):
    subprocess.run([str(x) for x in args], check=True)

def main():
    sdk = Path(os.environ['ANDROID_HOME'])
    out = Path(os.environ['PDSYNC_BUILD_HOME'])
    out.mkdir(parents=True, exist_ok=True)
    ndk = sdk / 'ndk/27.2.12479018'
    host = ndk / 'toolchains/llvm/prebuilt/linux-x86_64'
    assert '27.2.12479018' in (ndk / 'source.properties').read_text()
    spec = importlib.util.spec_from_file_location('native', ROOT / 'tools/android-root-native/build.py')
    native = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(native)
    report = {}
    for abi, (triple, target, elf_class, machine) in native.TARGETS.items():
        library = out / 'root-native' / abi / native.LIBRARY
        library.parent.mkdir(parents=True, exist_ok=True)
        run(host / 'bin/clang', '--target=' + target, '--sysroot=' + str(host / 'sysroot'),
            '-std=c11', '-Oz', '-Wall', '-Wextra', '-Werror', '-fPIC', '-fvisibility=hidden',
            '-fstack-protector-strong', '-fno-unwind-tables', '-fno-asynchronous-unwind-tables',
            '-nostdlib', '-shared', native.SOURCES / 'root_atomic_rename.c',
            '-L' + str(host / 'sysroot/usr/lib' / triple / '26'), '-lc',
            '-Wl,--no-undefined,--fatal-warnings,--build-id=sha1,--hash-style=both,-z,relro,-z,now,-z,noexecstack,-z,max-page-size=16384,-soname,' + native.LIBRARY + ',--version-script=' + str(native.SOURCES / 'exports.map'),
            '-o', library)
        report[abi] = native.verify(library, elf_class, machine)
    (out / 'native-report.json').write_text(json.dumps(report, indent=2))
    run(sys.executable, ROOT / 'tools/android-rebuild/build-apk.py', '--module', 'android-helper',
        '--standalone', '--unsigned', '--out', out / 'helper-unsigned.apk')
    with zipfile.ZipFile(out / 'helper-unsigned.apk') as apk:
        assert sorted(n for n in apk.namelist() if n.endswith('.dex')) == ['classes.dex']
        (out / 'bridge-runtime.dex').write_bytes(apk.read('classes.dex'))
    run(sys.executable, ROOT / 'tools/android-rebuild/apksig_compat.py',
        '--input', sdk / 'build-tools/36.0.0/lib/apksigner.jar',
        '--android-jar', sdk / 'platforms/android-26/android.jar', '--output', out / 'apksigner-api26.jar')

if __name__ == '__main__':
    main()
