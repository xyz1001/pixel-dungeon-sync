#!/usr/bin/env python3
"""Reproducible SDK-only Java Android build; never downloads or deploys."""
import argparse, hashlib, json, os, re, shutil, struct, subprocess, sys, tempfile, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
from apksig_compat import build_compat_jar

SCRIPT=Path(__file__).resolve()
REPO=next((p for p in SCRIPT.parents if (p/'settings.gradle.kts').is_file()), SCRIPT.parent.parent/'source')
ROOT=Path(os.environ.get('PDSYNC_BUILD_HOME', str(REPO.parent))).resolve()
SDK=Path(os.environ.get('ANDROID_SDK_ROOT', os.environ.get('ANDROID_HOME', str(ROOT/'tools/sdk')))).resolve()
BT=SDK/'build-tools/36.0.0'
ANDROID=SDK/'platforms/android-36/android.jar'
RUNTIME=Path(os.environ.get('PDSYNC_RUNTIME_LIB', str(ROOT.parent/'cloud-v11-validation/desktop-runtime/lib'))).resolve()
KEY=Path(os.environ.get('PDSYNC_KEYSTORE_FILE', str(ROOT/'test-signing/reconstruction-test.p12'))).resolve()
PASSWORD=Path(os.environ.get('PDSYNC_PASSWORD_FILE', str(ROOT/'test-signing/store-password.txt'))).resolve()
KEY_ALIAS=os.environ.get('PDSYNC_KEY_ALIAS','reconstruction-test')
ANDROID_NS='{http://schemas.android.com/apk/res/android}'
DEFAULT_PACKAGE='io.github.xyz1001.pdsync.android'
DEPS=('kotlin-stdlib-2.2.20.jar','kotlin-stdlib-jdk7-2.1.21.jar','kotlin-stdlib-jdk8-2.1.21.jar','kotlinx-coroutines-core-jvm-1.10.2.jar','kotlinx-serialization-core-jvm-1.9.0.jar','kotlinx-serialization-json-jvm-1.9.0.jar','okhttp-4.12.0.jar','okio-jvm-3.6.0.jar','annotations-23.0.0.jar')

def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def run(args, **kw):
    print('+ '+ ' '.join(map(str,args)),flush=True)
    return subprocess.run(list(map(str,args)),check=True,**kw)
def zip_write(z,name,data,compression=zipfile.ZIP_DEFLATED):
    if name.startswith('/') or '..' in Path(name).parts: raise ValueError('Unsafe archive path')
    zi=zipfile.ZipInfo(name,(1980,1,1,0,0,0)); zi.compress_type=compression; zi.external_attr=0o644<<16
    z.writestr(zi,data)
def collect_java_sources(module, generated, repo=REPO):
    """Resolve explicit source sharing without packaging another module wholesale.

    Additional inputs are repository-relative Java files/directories declared in
    the selected module's additional-java-sources.txt. No globs or external paths.
    """
    module=Path(module).resolve(); repo=Path(repo).resolve()
    src=next((f for f in [module/'src/main/java',module/'src',module/'java'] if f.is_dir()),module)
    sources=set(src.rglob('*.java')) | set(Path(generated).rglob('*.java'))
    declared=module/'additional-java-sources.txt'
    if not declared.exists() and not declared.is_symlink(): return sorted(sources)
    if declared.is_symlink() or not declared.is_file(): raise ValueError('Unsafe additional Java source declaration')
    for line in declared.read_text(encoding='utf-8').splitlines():
        name=line.strip()
        if not name or name.startswith('#'): continue
        relative=Path(name)
        if relative.is_absolute() or '..' in relative.parts or any(c in name for c in '*?[]\\'):
            raise ValueError('Additional Java source must be an explicit repository-relative path: '+name)
        candidate=repo/relative
        if not candidate.exists(): raise ValueError('Additional Java source is missing: '+name)
        for part in [candidate, *candidate.parents]:
            if part==repo: break
            if part.is_symlink(): raise ValueError('Additional Java source path contains a symlink: '+name)
        resolved=candidate.resolve()
        if repo not in resolved.parents: raise ValueError('Additional Java source escapes repository: '+name)
        entries=sorted(candidate.rglob('*.java')) if candidate.is_dir() else [candidate]
        if not entries: raise ValueError('Additional Java source directory is empty: '+name)
        for entry in entries:
            if entry.suffix!='.java' or not entry.is_file() or entry.is_symlink() or repo not in entry.resolve().parents:
                raise ValueError('Additional source is not a repository Java file: '+name)
            # A recursive directory may contain a symlinked subdirectory on some hosts.
            if any(p.is_symlink() for p in entry.parents if p!=repo and repo in p.parents):
                raise ValueError('Additional Java source path contains a symlink: '+name)
            sources.add(entry.resolve())
    return sorted(sources)

def native_library_inputs(mappings):
    """Validate explicit ELF/ABI inputs; never discover or load arbitrary native code."""
    supported={'armeabi-v7a':(1,40), 'arm64-v8a':(2,183), 'x86':(1,3), 'x86_64':(2,62)}
    result={}
    for mapping in mappings:
        name, separator, value=mapping.partition('=')
        parts=name.split('/')
        if not separator or len(parts)!=2 or parts[0] not in supported or not re.fullmatch(r'lib[A-Za-z0-9_]+\.so',parts[1]):
            raise ValueError('Expected native library ABI/libNAME.so=FILE')
        if name in result: raise ValueError('Duplicate native library: '+name)
        source=Path(value).resolve()
        if not source.is_file() or source.stat().st_size<52 or source.stat().st_size>16*1024*1024:
            raise ValueError('Missing or oversized native library: '+name)
        data=source.read_bytes()
        elf_class,machine=supported[parts[0]]
        if data[:4]!=b'\x7fELF' or data[4]!=elf_class or data[5]!=1 or data[6]!=1:
            raise ValueError('Native library ELF class/encoding mismatch: '+name)
        if struct.unpack_from('<HHI',data,16)!=(3,machine,1):
            raise ValueError('Native library ELF type/machine mismatch: '+name)
        # All loadable segments must support Android's 16 KiB page-size devices.
        phoff=struct.unpack_from('<I' if elf_class==1 else '<Q',data,28 if elf_class==1 else 32)[0]
        phentsize,phnum=struct.unpack_from('<HH',data,42 if elf_class==1 else 54)
        if not phnum or phentsize!=(32 if elf_class==1 else 56) or phoff+phentsize*phnum>len(data):
            raise ValueError('Invalid native ELF program headers: '+name)
        loads=0
        for index in range(phnum):
            offset=phoff+index*phentsize
            if struct.unpack_from('<I',data,offset)[0]!=1: continue
            loads+=1
            if elf_class==1: fileoff,vaddr=struct.unpack_from('<II',data,offset+4); alignment=struct.unpack_from('<I',data,offset+28)[0]
            else: fileoff,vaddr=struct.unpack_from('<QQ',data,offset+8); alignment=struct.unpack_from('<Q',data,offset+48)[0]
            if alignment<16384 or alignment&(alignment-1) or fileoff%16384!=vaddr%16384:
                raise ValueError('Native ELF does not support 16 KiB pages: '+name)
        if not loads: raise ValueError('Native ELF has no loadable segments: '+name)
        result[name]=(source,data)
    return result

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--module',required=True,type=Path,help='Directory with manifest, Java source, optional res/assets')
    p.add_argument('--out',required=True,type=Path)
    p.add_argument('--asset',action='append',default=[],help='Build-time asset NAME=FILE; does not modify source')
    p.add_argument('--native-library',action='append',default=[],help='Main-only explicit ABI/libNAME.so=FILE input; no game injection')
    p.add_argument('--core',type=Path,help='Rebuilt core.jar; defaults to rebuilt source/core then preserved cache')
    p.add_argument('--standalone',action='store_true',help='Do not package core/Kotlin/OkHttp/apksig (helper)')
    p.add_argument('--no-apksig',action='store_true',help='Omit app runtime APK signing implementation')
    p.add_argument('--unsigned',action='store_true',help='Produce zipaligned unsigned APK, e.g. helper template')
    p.add_argument('--application-id',help='Explicit override; otherwise preserve manifest package')
    p.add_argument('--version-code',type=int)
    p.add_argument('--version-name')
    p.add_argument('--main-class',help='Optional main activity for generated manifest, fully qualified')
    p.add_argument('--label',default='Pixel Dungeon Sync Test')
    p.add_argument('--min-sdk',type=int,default=36)
    p.add_argument('--target-sdk',type=int,default=36)
    p.add_argument('--work-dir',type=Path,help='Dedicated disposable working directory')
    a=p.parse_args(); module=a.module.resolve(); out=a.out.resolve()
    if not module.is_dir(): p.error('Module directory does not exist')
    if module == (REPO / 'android-app').resolve():
        p.error('The main app now compiles shared Kotlin/Compose with Gradle. Run '
                './gradlew :android-app:assembleRelease (unsigned); set PDSYNC_BRIDGE_RUNTIME_DEX, '
                'PDSYNC_ROOT_NATIVE_DIR and PDSYNC_APKSIG_JAR. See shared-ui/README.md. '
                'This SDK-only script remains the standalone bridge builder.')
    if KEY == module or PASSWORD == module or module in KEY.parents or module in PASSWORD.parents: p.error('Signing secrets must remain outside the selected module')
    if ROOT/'test-signing' in module.parents or module==ROOT/'test-signing': p.error('Signing directory cannot be a module')
    if ROOT/'test-signing' in out.parents or out in (KEY,PASSWORD): p.error('APK cannot overwrite signing material')
    try: native_inputs=native_library_inputs(a.native_library)
    except ValueError as error: p.error(str(error))
    if a.standalone and native_inputs: p.error('Standalone game bridge cannot include root native libraries')
    java=os.environ.get('JAVA_BIN','java')
    deps=[]
    if not a.standalone:
        rebuilt=REPO/'core/build/libs/core.jar'
        core=(a.core or (rebuilt if rebuilt.exists() else RUNTIME/'core.jar')).resolve()
        deps=[core]+[RUNTIME/n for n in DEPS]
        if not a.no_apksig:
            override=os.environ.get('PDSYNC_APKSIG_JAR')
            deps.append(Path(override).resolve() if override else BT/'lib/apksigner.jar')
    for f in [ANDROID,BT/'aapt2',BT/'lib/d8.jar',BT/'zipalign',BT/'lib/apksigner.jar']+deps:
        if not f.exists(): p.error(f'Required input missing: {f}')
    work=(a.work_dir or ROOT/'build-scripts/out'/out.stem).resolve()
    if work==module or work in module.parents or module in work.parents or work==ROOT or ROOT/'test-signing' in work.parents or work in KEY.parents or work in PASSWORD.parents:
        p.error('Unsafe work directory')
    work.mkdir(parents=True,exist_ok=True)
    # Clear only our explicitly named intermediates. Source and output are never removed.
    for name in ['classes','dex','generated','compiled-res.zip','resources.apk','unsigned.apk','aligned.apk','AndroidManifest.xml','app-classes.jar']:
        f=work/name
        if f.is_dir(): shutil.rmtree(f)
        elif f.exists(): f.unlink()
    for name in ['classes','dex','generated']: (work/name).mkdir()
    source_manifest=next((f for f in [module/'src/main/AndroidManifest.xml',module/'AndroidManifest.xml'] if f.exists()),None)
    if source_manifest:
        tree=ET.parse(source_manifest); manifest=tree.getroot()
    else:
        if not a.main_class: p.error('Manifest missing; --main-class required')
        ET.register_namespace('android',ANDROID_NS[1:-1])
        manifest=ET.Element('manifest',{'package':a.application_id or DEFAULT_PACKAGE})
        app=ET.SubElement(manifest,'application',{ANDROID_NS+'label':a.label,ANDROID_NS+'theme':'@android:style/Theme.Material.Light.NoActionBar'})
        act=ET.SubElement(app,'activity',{ANDROID_NS+'name':a.main_class,ANDROID_NS+'exported':'true'})
        filt=ET.SubElement(act,'intent-filter'); ET.SubElement(filt,'action',{ANDROID_NS+'name':'android.intent.action.MAIN'}); ET.SubElement(filt,'category',{ANDROID_NS+'name':'android.intent.category.LAUNCHER'})
    if native_inputs:
        app=manifest.find('application')
        if app is None: p.error('Native libraries require an application manifest')
        app.set(ANDROID_NS+'extractNativeLibs','true')
    if a.application_id: manifest.set('package',a.application_id)
    package=manifest.attrib.get('package',DEFAULT_PACKAGE)
    if not re.fullmatch(r'[A-Za-z_][\w]*(\.[A-Za-z_][\w]*)+',package): p.error('Invalid package')
    sdk=manifest.find('uses-sdk')
    if sdk is None: sdk=ET.SubElement(manifest,'uses-sdk')
    min_sdk=int(sdk.get(ANDROID_NS+'minSdkVersion',a.min_sdk)); target_sdk=int(sdk.get(ANDROID_NS+'targetSdkVersion',a.target_sdk))
    if not a.standalone and not a.no_apksig and min_sdk < 28 and not os.environ.get('PDSYNC_APKSIG_JAR'):
        deps[-1]=build_compat_jar(BT/'lib/apksigner.jar', ROOT/'generated-deps/apksigner-api26.jar',
                                SDK/'platforms/android-26/android.jar', java)
    sdk.set(ANDROID_NS+'minSdkVersion',str(min_sdk)); sdk.set(ANDROID_NS+'targetSdkVersion',str(target_sdk))
    if a.version_code is not None: manifest.set(ANDROID_NS+'versionCode',str(a.version_code))
    if a.version_name is not None: manifest.set(ANDROID_NS+'versionName',a.version_name)
    if ANDROID_NS+'versionCode' not in manifest.attrib: manifest.set(ANDROID_NS+'versionCode','1')
    if ANDROID_NS+'versionName' not in manifest.attrib: manifest.set(ANDROID_NS+'versionName','reconstruction-test')
    ET.register_namespace('android',ANDROID_NS[1:-1])
    ET.ElementTree(manifest).write(work/'AndroidManifest.xml',encoding='utf-8',xml_declaration=True)
    res=next((f for f in [module/'src/main/res',module/'res'] if f.is_dir()),None)
    assets=next((f for f in [module/'src/main/assets',module/'assets'] if f.is_dir()),None)
    link=[BT/'aapt2','link','-o',work/'resources.apk','--manifest',work/'AndroidManifest.xml','-I',ANDROID,'--java',work/'generated','--min-sdk-version',str(min_sdk),'--target-sdk-version',str(target_sdk),'--auto-add-overlay']
    if res and any(f.is_file() for f in res.rglob('*')):
        run([BT/'aapt2','compile','--dir',res,'-o',work/'compiled-res.zip']); link+=['-R',work/'compiled-res.zip']
    if assets: link+=['-A',assets]
    run(link)
    try: java_sources=collect_java_sources(module,work/'generated')
    except ValueError as error: p.error(str(error))
    if not java_sources: p.error('No Java sources found')
    classes=work/'classes'
    run([java,'com.sun.tools.javac.Main','-source','17','-target','17','-encoding','UTF-8','-classpath',os.pathsep.join(map(str,[ANDROID]+deps)),'-d',classes,*java_sources])
    appjar=work/'app-classes.jar'
    with zipfile.ZipFile(appjar,'w') as z:
        for f in sorted(classes.rglob('*.class')): zip_write(z,f.relative_to(classes).as_posix(),f.read_bytes())
    run([java,'-Xmx1536m','-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--release','--min-api',str(min_sdk),'--lib',ANDROID,'--output',work/'dex',appjar,*deps])
    entries={}
    with zipfile.ZipFile(work/'resources.apk') as z:
        for i in z.infolist():
            if not i.is_dir(): entries[i.filename]=(z.read(i),i.compress_type)
    asset_inputs={}
    for mapping in a.asset:
        name, separator, value = mapping.partition('=')
        if not separator or not name or Path(name).is_absolute() or '..' in Path(name).parts: p.error('Expected safe asset NAME=FILE')
        f=Path(value).resolve()
        if f in (KEY,PASSWORD): p.error('Signing secret cannot be an asset')
        entries['assets/'+name]=(f.read_bytes(),zipfile.ZIP_DEFLATED)
        asset_inputs[name]={'path':str(f),'sha256':sha(f)}
    for name,(_,data) in native_inputs.items():
        archive_name='lib/'+name
        if archive_name in entries: p.error('Native library would replace an existing packaged entry')
        entries[archive_name]=(data,zipfile.ZIP_STORED)
    # Keep runtime resources (notably OkHttp publicsuffix data and service metadata).
    for jar in deps:
        with zipfile.ZipFile(jar) as z:
            for i in z.infolist():
                n=i.filename
                if i.is_dir() or n.endswith('.class') or n=='META-INF/MANIFEST.MF' or n.startswith('META-INF/versions/') or re.match(r'META-INF/[^/]+\.(SF|RSA|DSA|EC)$',n): continue
                data=z.read(i)
                if n.startswith('META-INF/services/') and n in entries:
                    old=entries[n][0]
                    lines=sorted(set(old.decode().splitlines()+data.decode().splitlines()))
                    data=('\n'.join(lines)+'\n').encode()
                elif n in entries and entries[n][0]!=data: continue
                entries[n]=(data,zipfile.ZIP_DEFLATED)
    for f in sorted((work/'dex').glob('*.dex')): entries[f.name]=(f.read_bytes(),zipfile.ZIP_DEFLATED)
    if not any(n.endswith('.dex') for n in entries): raise RuntimeError('D8 emitted no DEX')
    secret_patterns=('.p12','.jks','.keystore','store-password','private-key','private_key')
    for n,(data,_) in entries.items():
        if any(x in n.lower() for x in secret_patterns) or b'-----BEGIN PRIVATE KEY-----' in data or b'-----BEGIN RSA PRIVATE KEY-----' in data: raise RuntimeError('Refusing to package signing secret: '+n)
    with zipfile.ZipFile(work/'unsigned.apk','w') as z:
        for n,(data,comp) in sorted(entries.items()): zip_write(z,n,data,comp)
    run([BT/'zipalign','-f','-P','16','4',work/'unsigned.apk',work/'aligned.apk'])
    out.parent.mkdir(parents=True,exist_ok=True)
    signer_digest=None
    if a.unsigned: shutil.copyfile(work/'aligned.apk',out)
    else:
        key=KEY; password=PASSWORD
        if not key.exists() or not password.exists(): p.error('Retained signing key missing; never silently regenerating')
        if key.stat().st_mode&0o077 or password.stat().st_mode&0o077: p.error('Signing files must not be group/world readable')
        run([java,'-jar',BT/'lib/apksigner.jar','sign','--ks',key,'--ks-key-alias',KEY_ALIAS,'--ks-pass',f'file:{password}','--v1-signing-enabled','false','--v2-signing-enabled','true','--v3-signing-enabled','true','--v4-signing-enabled','false','--out',out,work/'aligned.apk'])
        verified=run([java,'-jar',BT/'lib/apksigner.jar','verify','--verbose','--print-certs',out],capture_output=True,text=True)
        (out.with_suffix('.verify.txt')).write_text(verified.stdout)
        print(verified.stdout,end='')
        match=re.search(r'certificate SHA-256 digest: ([a-f0-9]+)',verified.stdout)
        signer_digest=match.group(1) if match else None
    run([BT/'zipalign','-c','-P','16','4',out])
    badging=run([BT/'aapt2','dump','badging',out],capture_output=True,text=True).stdout
    (out.with_suffix('.badging.txt')).write_text(badging)
    report={'apk':str(out),'package':package,'sha256':sha(out),'size':out.stat().st_size,'signed':not a.unsigned,'signer_sha256':signer_digest,'min_sdk':min_sdk,'target_sdk':target_sdk,'compile_sdk':36,'java_source_target':17, 'java_release_api_fence':False,'sdk_build_tools':'36.0.0','input_sources':{str(f):sha(f) for f in java_sources},'input_manifest':str(source_manifest) if source_manifest else None,'dependency_sha256':{str(f):sha(f) for f in deps},'android_jar_sha256':sha(ANDROID),'device_tested':False,'native_library_sha256':{name:{'path':str(source),'sha256':sha(source)} for name,(source,_) in native_inputs.items()},'injected_assets':asset_inputs,'input_assets':{str(f):sha(f) for f in sorted(assets.rglob('*')) if f.is_file()} if assets else {},'input_resources':{str(f):sha(f) for f in sorted(res.rglob('*')) if f.is_file()} if res else {},'source_fidelity':'Source build only; equivalence to lost source must be assessed separately.'}
    out.with_suffix('.build.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:report[k] for k in ('apk','package','size','sha256','signed','signer_sha256')},indent=2))
if __name__=='__main__': main()
