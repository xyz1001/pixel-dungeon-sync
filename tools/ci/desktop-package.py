"""Check native app-image, run its bundled JVM UI smoke, collect release files."""
import hashlib
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile
import zipfile
from version import version

ROOT=Path(__file__).resolve().parents[2]

def run(*args, **kwargs):
    return subprocess.run([str(x) for x in args],check=True,**kwargs)

def smoke(app, system, out, label):
    if system=='Windows':
        launcher=app/'PDSync.exe'; java=app/'runtime/bin/java.exe'
    elif system=='Darwin':
        launcher=app/'Contents/MacOS/PDSync'; java=app/'Contents/runtime/Contents/Home/bin/java'
    else:
        launcher=app/'bin/PDSync'; java=app/'lib/runtime/bin/java'
    assert launcher.is_file() and java.is_file(), (launcher,java)
    run(java,'-version')
    with tempfile.TemporaryDirectory(prefix='pdsync-smoke-') as temp:
        # macOS /var is a symlink; use the actual path rather than weakening app path checks.
        env=dict(os.environ,PDSYNC_SMOKE='1',JAVA_TOOL_OPTIONS='-Dpdsync.workspace='+str(Path(temp).resolve()))
        command=[str(launcher)]
        if system=='Linux': command=['xvfb-run','-a',*command]
        result=run(*command,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=90)
        (out/f'{label}-smoke.txt').write_text(result.stdout,encoding='utf-8')
        assert 'PDSYNC_UI_SMOKE_OK' in result.stdout,result.stdout[-4000:]

def main():
    system=platform.system()
    v=version()['name']
    out=ROOT/'dist'; out.mkdir(exist_ok=True)
    base=ROOT/'desktop/build/compose/binaries/main'
    images=base/'app'
    if system=='Windows':
        app=images/'PDSync'; launcher=app/'PDSync.exe'; java=app/'runtime/bin/java.exe'
        suffix,arch='exe','x64'
    elif system=='Darwin':
        app=images/'PDSync.app'; launcher=app/'Contents/MacOS/PDSync'; java=app/'Contents/runtime/Contents/Home/bin/java'
        suffix,arch='dmg','arm64'
        assert platform.machine()=='arm64',platform.machine()
    else:
        app=images/'PDSync'; launcher=app/'bin/PDSync'; java=app/'lib/runtime/bin/java'
        suffix,arch='deb','x64'
    smoke(app,system,out,system.lower())
    packages=list((base/suffix).glob('*.'+suffix)); assert len(packages)==1,packages
    dest=out/f'PDSync-{v}-{system.lower()}-{arch}.{suffix}'
    shutil.copy2(packages[0],dest)
    if system=='Windows':
        # Whole app image, including jlink runtime and native Skiko. No external Java.
        portable=shutil.make_archive(str(out/f'PDSync-{v}-windows-x64-portable'),'zip',images,'PDSync')
        with tempfile.TemporaryDirectory(prefix='pdsync-portable-') as d:
            with zipfile.ZipFile(portable) as archive:
                archive.extractall(d)
            smoke(Path(d)/'PDSync',system,out,'portable-extracted')
    elif system=='Linux':
        run('dpkg-deb','--info',dest)
        with tempfile.TemporaryDirectory() as d:
            run('dpkg-deb','--extract',dest,d)
            launchers=list(Path(d).rglob('bin/PDSync'))
            assert len(launchers)==1,launchers
            smoke(launchers[0].parent.parent,system,out,'deb-extracted')
    else:
        run('hdiutil','verify',dest)
        with tempfile.TemporaryDirectory(prefix='pdsync-dmg-') as mount:
            run('hdiutil','attach',dest,'-readonly','-nobrowse','-mountpoint',mount)
            try:
                apps=list(Path(mount).glob('*.app'))
                assert len(apps)==1,apps
                smoke(apps[0],system,out,'dmg-mounted')
            finally:
                run('hdiutil','detach',mount)
    print('Packaged native app and bundled-runtime UI smoke passed:',dest.name)

if __name__=='__main__': main()
