#!/usr/bin/env python3
"""Prepare a bounded, pinned Android C cross toolchain; never install system packages."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile
import urllib.request

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
LOCK = json.loads((HERE / 'toolchain-lock.json').read_text())

def checked(data, size, digest):
    if len(data) != size or hashlib.sha256(data).hexdigest() != digest:
        raise SystemExit('Pinned toolchain size/SHA-256 mismatch')
    return data

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', required=True, type=Path)
    parser.add_argument('--fetch', action='store_true', help='Explicitly allow missing official pinned downloads (18.35 MB maximum total)')
    args = parser.parse_args()
    base = args.directory.resolve()
    if base == REPO or REPO in base.parents:
        raise SystemExit('Toolchains and binaries must stay outside the repository')
    llvm = LOCK['existing_llvm']
    checked(Path(llvm['path']).read_bytes(), llvm['bytes'], llvm['sha256'])
    downloads = base / 'downloads'
    downloads.mkdir(parents=True, exist_ok=True)
    if LOCK['download_bytes'] > 20_000_000:
        raise SystemExit('Download budget exceeded')
    for artifact in LOCK['artifacts']:
        path = downloads / artifact['file']
        if not path.exists():
            if not args.fetch:
                raise SystemExit('Missing pinned artifact; supply --fetch to download: ' + artifact['file'])
            # Default HTTPS certificate verification. The lock contains only official HTTPS sources.
            with urllib.request.urlopen(artifact['url'], timeout=120) as response:
                data = response.read(artifact['bytes'] + 1)
            checked(data, artifact['bytes'], artifact['sha256'])
            path.write_bytes(data)
        checked(path.read_bytes(), artifact['bytes'], artifact['sha256'])
        if artifact['kind'] == 'debian-package':
            subprocess.run(['dpkg-deb', '-x', str(path), str(base / 'toolchain')], check=True)
        elif artifact['kind'] == 'android-headers':
            output = base / 'sysroot/usr/include'
            output.mkdir(parents=True, exist_ok=True)
            with tarfile.open(path) as archive:
                members = archive.getmembers()
                if len(members) > 5000 or sum(m.size for m in members) > 64 * 1024 * 1024:
                    raise SystemExit('Header extraction budget exceeded')
                archive.extractall(output, members=members, filter='data')
        else:
            data = base64.b64decode(path.read_bytes(), validate=True)
            checked(data, artifact['decoded_bytes'], artifact['decoded_sha256'])
            output = base / 'sysroot/usr/lib' / artifact['triple'] / '26/libc.so'
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(data)
    print('Pinned toolchain ready; no system packages installed. Download bytes:', LOCK['download_bytes'])

if __name__ == '__main__':
    main()
