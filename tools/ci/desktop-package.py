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

def linux_launcher(app):
    candidates=[p for p in (app/'bin').iterdir() if p.is_file() and p.name.lower()=='pdsync']
    assert len(candidates)==1,candidates
    return candidates[0]

def linux_image(images):
    candidates=[p for p in images.iterdir() if p.is_dir() and (p/'lib/runtime/bin/java').is_file()]
    assert len(candidates)==1,candidates
    linux_launcher(candidates[0])
    return candidates[0]

def smoke(app, system, out, label):
    if system=='Windows':
        launcher=app/'PDSync.exe'; java=app/'runtime/bin/java.exe'
    elif system=='Darwin':
        launcher=app/'Contents/MacOS/PDSync'; java=app/'Contents/runtime/Contents/Home/bin/java'
    else:
        launcher=linux_launcher(app); java=app/'lib/runtime/bin/java'
    assert launcher.is_file() and java.is_file(), (launcher,java)
    run(java,'-version')
    with tempfile.TemporaryDirectory(prefix='pdsync-smoke-') as temp:
        # macOS /var is a symlink; use the actual path rather than weakening app path checks.
        env=dict(os.environ,PDSYNC_SMOKE='1',JAVA_TOOL_OPTIONS='-Dpdsync.workspace='+str(Path(temp).resolve()))
        command=[str(launcher)]
        if system=='Linux': command=['xvfb-run','-a','-e','/dev/stderr',*command]
        try:
            result=run(*command,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=90)
        except (subprocess.TimeoutExpired, subprocess.CalledProcessError) as error:
            # Preserve startup/shutdown evidence even when the launcher never returns.
            # This workspace is an empty CI fixture, not a user's configured account.
            output=error.stdout or ''
            if isinstance(output, bytes): output=output.decode('utf-8',errors='replace')
            (out/f'{label}-smoke.txt').write_text(output,encoding='utf-8')
            print(f'{label} smoke failed: {type(error).__name__}\n{output}',flush=True)
            raise
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
        app=linux_image(images)
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
            launchers=[p for p in Path(d).rglob('*') if p.is_file() and p.parent.name=='bin' and p.name.lower()=='pdsync']
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
