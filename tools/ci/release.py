"""Tag-only publishing. No debug keys, no tag creation, no published-asset overwrite."""
import base64
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from version import version, ROOT

def run(*args, capture=False):
    return subprocess.run([str(x) for x in args],check=True,text=True,
                          stdout=subprocess.PIPE if capture else None).stdout

def main(verify_only=False):
    tag=None if verify_only else os.environ['RELEASE_TAG']
    v=version(tag=tag)
    for key in ('PDSYNC_KEYSTORE_BASE64','PDSYNC_KEY_ALIAS','PDSYNC_STORE_PASSWORD','PDSYNC_KEY_PASSWORD'):
        if not os.environ.get(key): raise SystemExit('Missing required signing secret: '+key)
    out=ROOT/'dist'
    sdk=Path(os.environ['ANDROID_HOME'])/'build-tools/36.0.0'
    unsigned=out/'PDSync-android-unsigned.apk'
    apk=out/f'PDSync-{v["name"]}-android.apk'
    badging=run(sdk/'aapt2','dump','badging',unsigned,capture=True)
    expected=f"versionCode='{v['code']}' versionName='{v['name']}'"
    if expected not in badging: raise SystemExit('APK version does not match Manifest/tag')
    pin=(ROOT/'tools/ci/android-signer.sha256').read_text().strip()
    if not re.fullmatch('[0-9a-f]{64}',pin): raise SystemExit('Invalid public release certificate fingerprint')
    with tempfile.TemporaryDirectory(prefix='pdsync-sign-') as temporary:
        key=Path(temporary)/'release.keystore'
        key.write_bytes(base64.b64decode(os.environ['PDSYNC_KEYSTORE_BASE64'],validate=True))
        key.chmod(0o600)
        run('java','--class-path',sdk/'lib/apksigner.jar',ROOT/'tools/ci/SignApk.java',key,apk,unsigned)
    for api in (26,36):
        verify=run('java','-jar',sdk/'lib/apksigner.jar','verify','--verbose','--print-certs',
                   '--min-sdk-version',api,'--max-sdk-version',api,apk,capture=True)
        certs=re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)$',verify,re.M)
        if certs != [pin]: raise SystemExit('Release signer must match the retained application certificate fingerprint')
    run(sdk/'zipalign','-c','-P','16','4',apk)
    names=[apk.name,f'PDSync-{v["name"]}-windows-x64.exe',f'PDSync-{v["name"]}-windows-x64-portable.zip',
           f'PDSync-{v["name"]}-linux-x64.deb',f'PDSync-{v["name"]}-linux-x64.pkg.tar.zst',f'PDSync-{v["name"]}-darwin-arm64.dmg']
    for name in names:
        if not (out/name).is_file() or (out/name).stat().st_size==0: raise SystemExit('Missing release artifact: '+name)
    checksums=out/'SHA256SUMS.txt'
    checksums.write_text(''.join(hashlib.sha256((out/n).read_bytes()).hexdigest()+'  '+n+'\n' for n in sorted(names)))
    notes=out/'RELEASE-NOTES.md'
    notes.write_text(f'''PDSync {v['name']} (Android versionCode {v['code']})

Android APK uses the retained release signer: `{pin}`.
Windows EXE/portable ZIP: x64. Linux DEB/pacman: x64. macOS DMG: Apple Silicon arm64.
Desktop packages include Java. Windows binaries are not Authenticode signed;
macOS is not Developer ID signed or notarized. OS trust warnings may apply.
See README for platform/protocol support boundaries; packaging smoke is not a
cross-platform save interoperability certification. Verify files using SHA256SUMS.txt.
''',encoding='utf-8')
    if verify_only:
        print('SIGNING_VALIDATION_OK: six artifacts verified; APK certificate SHA256 '+pin)
        return
    # Read before mutation: a completed release is immutable to this workflow.
    releases=json.loads(run('gh','api',f'repos/{os.environ["GH_REPO"]}/releases/tags/{tag}',capture=True)) if release_exists(tag) else None
    if releases and not releases['draft']: raise SystemExit('Release already published; refusing to replace assets. Use a new version/tag.')
    if not releases:
        command=['gh','release','create',tag,'--verify-tag','--draft','--title','PDSync '+v['name'],'--notes-file',notes]
        if v['prerelease']: command+=['--prerelease']
        run(*command)
    run('gh','release','upload',tag,*[out/n for n in names],checksums,notes,'--clobber')
    assets=json.loads(run('gh','release','view',tag,'--json','assets',capture=True))['assets']
    by_name={a['name']:a for a in assets}
    for path in [*[out/n for n in names],checksums,notes]:
        if path.name not in by_name or by_name[path.name]['size']!=path.stat().st_size:
            raise SystemExit('Draft attachment verification failed: '+path.name)
    run('gh','release','edit',tag,'--draft=false','--latest='+str(not v['prerelease']).lower())

def release_exists(tag):
    # Distinguish 404 from authentication/network errors; never create on ambiguity.
    p=subprocess.run(['gh','api',f'repos/{os.environ["GH_REPO"]}/releases/tags/{tag}'],text=True,capture_output=True)
    if p.returncode==0: return True
    if 'HTTP 404' in p.stderr: return False
    raise RuntimeError('Could not determine existing release state; no publication attempted')

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--verify-only',action='store_true',help='Sign and check artifacts without any GitHub Release operation')
    main(verify_only=parser.parse_args().verify_only)
