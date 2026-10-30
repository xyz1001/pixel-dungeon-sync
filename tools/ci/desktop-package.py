"""Check native app-image, run its bundled JVM UI smoke, collect release files."""
import hashlib
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile
from version import version

ROOT=Path(__file__).resolve().parents[2]

def run(*args, **kwargs):
    return subprocess.run([str(x) for x in args],check=True,**kwargs)

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
    assert launcher.is_file() and java.is_file(), (launcher,java)
    run(java,'-version')
    with tempfile.TemporaryDirectory(prefix='pdsync-smoke-') as temp:
        env=dict(os.environ,PDSYNC_SMOKE='1',JAVA_TOOL_OPTIONS='-Dpdsync.workspace='+temp)
        command=[str(launcher)]
        if system=='Linux': command=['xvfb-run','-a',*command]
        result=run(*command,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=90)
        (out/f'{system.lower()}-smoke.txt').write_text(result.stdout,encoding='utf-8')
        assert 'PDSYNC_UI_SMOKE_OK' in result.stdout,result.stdout[-4000:]
    packages=list((base/suffix).glob('*.'+suffix)); assert len(packages)==1,packages
    dest=out/f'PDSync-{v}-{system.lower()}-{arch}.{suffix}'
    shutil.copy2(packages[0],dest)
    if system=='Windows':
        # Whole app image, including jlink runtime and native Skiko. No external Java.
        shutil.make_archive(str(out/f'PDSync-{v}-windows-x64-portable'),'zip',images,'PDSync')
    elif system=='Linux':
        run('dpkg-deb','--info',dest)
        with tempfile.TemporaryDirectory() as d:
            run('dpkg-deb','--extract',dest,d)
            assert list(Path(d).rglob('bin/PDSync'))
            assert list(Path(d).rglob('runtime/bin/java'))
    else:
        run('hdiutil','verify',dest)
    print('Packaged native app and bundled-runtime UI smoke passed:',dest.name)

if __name__=='__main__': main()
