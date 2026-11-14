"""Check native app-image, run its bundled JVM UI smoke, collect release files."""
import hashlib
import os
from pathlib import Path
import platform
import shutil
import signal
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

def linux_timeout_diagnostics(process, java, out, label):
    """Capture bounded diagnostics before terminating the isolated smoke process group."""
    diagnostic=out/f'{label}-smoke-diagnostics.txt'
    with diagnostic.open('w',encoding='utf-8') as log:
        try:
            result=subprocess.run(['ps','-eo','pid,ppid,pgid,args'],stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT,text=True,timeout=5,check=False)
            log.write('Process list:\n'+result.stdout+'\n')
            jcmd=java.with_name('jcmd')
            if not jcmd.is_file():
                java_home=os.environ.get('JAVA_HOME')
                home_jcmd=Path(java_home)/'bin/jcmd' if java_home else None
                jcmd=home_jcmd if home_jcmd and home_jcmd.is_file() else shutil.which('jcmd')
            if jcmd:
                group_pids={fields[0] for line in result.stdout.splitlines()[1:]
                            if len(fields := line.strip().split(None,3))==4 and fields[2]==str(process.pid)}
                listing=subprocess.run([str(jcmd),'-l'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,
                                       text=True,timeout=5,check=False)
                log.write('\nJVM list:\n'+listing.stdout+'\n')
                for line in listing.stdout.splitlines():
                    pid=line.split(None,1)[0] if line.strip() else ''
                    if pid in group_pids:
                        dump=subprocess.run([str(jcmd),pid,'Thread.print'],stdout=subprocess.PIPE,
                                            stderr=subprocess.STDOUT,text=True,timeout=5,check=False)
                        log.write(f'\nThread dump for {pid}:\n{dump.stdout}\n')
        except (OSError, subprocess.TimeoutExpired) as error:
            log.write(f'Diagnostics unavailable: {error}\n')

def print_smoke_logs(out, label, system):
    names=[f'{label}-smoke.txt']
    if system=='Linux': names.extend([f'{label}-xvfb.txt',f'{label}-smoke-diagnostics.txt'])
    for name in names:
        path=out/name
        try:
            print(f'--- {name} ---\n{path.read_text(encoding="utf-8",errors="replace")}',flush=True)
        except OSError as error:
            print(f'--- {name} unavailable: {error} ---',flush=True)

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
        options='-Dpdsync.workspace='+str(Path(temp).resolve())
        if system=='Linux': options+=' -Dskiko.renderApi=SOFTWARE'
        env=dict(os.environ,PDSYNC_SMOKE='1',JAVA_TOOL_OPTIONS=options)
        command=[str(launcher)]
        if system=='Linux': command=['xvfb-run','-a','-e',str((out/f'{label}-xvfb.txt').resolve()),*command]
        log_path=out/f'{label}-smoke.txt'
        with log_path.open('w',encoding='utf-8') as log:
            process=subprocess.Popen(command,env=env,stdout=log,stderr=subprocess.STDOUT,
                                     start_new_session=(system=='Linux'))
            try:
                returncode=process.wait(timeout=90)
            except subprocess.TimeoutExpired:
                if system=='Linux':
                    try:
                        linux_timeout_diagnostics(process,java,out,label)
                    finally:
                        try: os.killpg(process.pid,signal.SIGKILL)
                        except ProcessLookupError: pass
                else:
                    process.kill()
                process.wait()
                log.flush()
                print_smoke_logs(out,label,system)
                raise
        output=log_path.read_text(encoding='utf-8',errors='replace')
        if returncode:
            print_smoke_logs(out,label,system)
            raise subprocess.CalledProcessError(returncode,command,output=output)
        assert 'PDSYNC_UI_SMOKE_OK' in output,output[-4000:]

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
