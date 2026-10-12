#!/usr/bin/env python3
"""Compile and structurally verify four tiny API26 JNI libraries outside Git."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
SOURCES = REPO / 'android-root/src/main/native'
LIBRARY = 'libpdsync_root_atomic.so'
ENTRY = 'Java_io_github_xyz1001_pdsync_root_RootAtomicRename_nativeRenameNoReplace'
TARGETS = {
    'arm64-v8a': ('aarch64-linux-android', 'aarch64-linux-android26', 2, 183),
    'armeabi-v7a': ('arm-linux-androideabi', 'armv7a-linux-androideabi26', 1, 40),
    'x86': ('i686-linux-android', 'i686-linux-android26', 1, 3),
    'x86_64': ('x86_64-linux-android', 'x86_64-linux-android26', 2, 62),
}

def verify(path, elf_class, machine):
    data = path.read_bytes()
    assert len(data) < 32768 and data[:6] == b'\x7fELF' + bytes([elf_class, 1])
    assert struct.unpack_from('<HH', data, 16) == (3, machine), 'Wrong ELF type/machine'
    if elf_class == 2:
        phoff = struct.unpack_from('<Q', data, 32)[0]
        entry_size, count = struct.unpack_from('<HH', data, 54)
    else:
        phoff = struct.unpack_from('<I', data, 28)[0]
        entry_size, count = struct.unpack_from('<HH', data, 42)
    loads, stacks, relros = 0, 0, 0
    for i in range(count):
        entry = phoff + i * entry_size
        if elf_class == 2:
            kind, flags, offset, vaddr, _, _, _, align = struct.unpack_from('<IIQQQQQQ', data, entry)
        else:
            kind, offset, vaddr, _, _, _, flags, align = struct.unpack_from('<IIIIIIII', data, entry)
        assert not (kind == 1 and flags & 1 and flags & 2), 'Writable executable segment'
        assert kind != 3, 'Shared JNI library must not contain an interpreter'
        if kind == 1:
            loads += 1
            assert align >= 16384 and offset % align == vaddr % align, '16 KiB page alignment missing'
        if kind == 0x6474e551:
            stacks += 1
            assert flags & 1 == 0, 'Executable stack'
        if kind == 0x6474e552:
            relros += 1
    assert loads and stacks == 1 and relros == 1
    dynamic = subprocess.check_output(['readelf', '-W', '-d', str(path)], text=True)
    assert re.findall(r'\(NEEDED\).*\[(.*?)\]', dynamic) == ['libc.so']
    assert re.findall(r'\(SONAME\).*\[(.*?)\]', dynamic) == [LIBRARY]
    assert 'BIND_NOW' in dynamic and 'TEXTREL' not in dynamic
    symbols = subprocess.check_output(['readelf', '-W', '--dyn-syms', str(path)], text=True)
    defined, undefined = [], []
    for line in symbols.splitlines():
        columns = line.split()
        if len(columns) < 8 or not columns[0].endswith(':') or columns[4] not in ('GLOBAL', 'WEAK'):
            continue
        (undefined if columns[6] == 'UND' else defined).append(columns[7])
    assert defined == [ENTRY + '@@PDSYNC_ROOT_ATOMIC_1'], defined
    permitted = {'syscall@LIBC', '__errno@LIBC', '__stack_chk_fail@LIBC', '__stack_chk_guard@LIBC'}
    assert set(undefined) <= permitted and {'syscall@LIBC', '__errno@LIBC'} <= set(undefined), undefined
    return dict(bytes=len(data), sha256=hashlib.sha256(data).hexdigest(), imports=undefined,
                single_jni_export=True, page_alignment=16384, android_runtime_verified=False)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--toolchain', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    base, output = args.toolchain.resolve(), args.output.resolve()
    for path in (base, output):
        if path == REPO or REPO in path.parents:
            raise SystemExit('Toolchain and output must stay outside the repository')
    compiler = base / 'toolchain/usr/bin/clang-19'
    linker = base / 'toolchain/usr/bin/ld.lld-19'
    sysroot = base / 'sysroot'
    env = dict(os.environ, LD_LIBRARY_PATH=str(base / 'toolchain/usr/lib/x86_64-linux-gnu'))
    version = subprocess.check_output([str(compiler), '--version'], env=env, text=True)
    assert 'Debian clang version 19.1.7 (3+b1)' in version
    report = dict(api=26, compiler=version.strip(), libraries={}, android_runtime_verified=False)
    for abi, (triple, target, elf_class, machine) in TARGETS.items():
        library = output / abi / LIBRARY
        library.parent.mkdir(parents=True, exist_ok=True)
        command = [str(compiler), '--target=' + target, '--sysroot=' + str(sysroot), '--ld-path=' + str(linker),
                   '-std=c11', '-Oz', '-Wall', '-Wextra', '-Werror', '-fPIC', '-fvisibility=hidden',
                   '-fstack-protector-strong', '-fno-unwind-tables', '-fno-asynchronous-unwind-tables',
                   '-nostdlib', '-shared', str(SOURCES / 'root_atomic_rename.c'),
                   '-L' + str(sysroot / 'usr/lib' / triple / '26'), '-lc',
                   '-Wl,--no-undefined,--fatal-warnings,--build-id=sha1,--hash-style=both,-z,relro,-z,now,-z,noexecstack,-z,max-page-size=16384,-soname,' + LIBRARY + ',--version-script=' + str(SOURCES / 'exports.map'),
                   '-o', str(library)]
        subprocess.run(command, check=True, env=env)
        report['libraries'][abi] = verify(library, elf_class, machine)
    (output / 'build-report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))

if __name__ == '__main__':
    main()
