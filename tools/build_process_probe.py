#!/usr/bin/env python3
"""Package original production detector bytecode with its Kotlin runtime; never modify the source JAR."""
import argparse,hashlib,json,pathlib,subprocess,tempfile,zipfile
p=argparse.ArgumentParser();p.add_argument('--core',type=pathlib.Path,required=True);p.add_argument('--stdlib',type=pathlib.Path,required=True);p.add_argument('--javac',required=True);p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args()
source=pathlib.Path(__file__).with_name('ProcessVisibilityCheck.java');classes={}
prefixes=('GameProcess','JvmProcessInventory','ObservedGameProcess','ProcessInventory','LinuxProcessArguments','TargetGame')
with zipfile.ZipFile(a.core) as z:
 for name in z.namelist():
  if name.startswith('pdsync/') and name.endswith('.class') and name.split('/')[-1].startswith(prefixes):classes[name]=z.read(name)
assert classes
with tempfile.TemporaryDirectory() as d:
 subprocess.run([a.javac,'--release','17','-cp',str(a.core)+':'+str(a.stdlib),'-d',d,str(source)],check=True)
 classes['ProcessVisibilityCheck.class']=(pathlib.Path(d)/'ProcessVisibilityCheck.class').read_bytes()
 a.output.parent.mkdir(parents=True,exist_ok=True)
 with zipfile.ZipFile(a.output,'w',compression=zipfile.ZIP_DEFLATED) as out:
  with zipfile.ZipFile(a.stdlib) as std:
   for name in std.namelist():
    if name.endswith('/') or name.upper().endswith(('.SF','.RSA','.DSA')) or name.upper()=='META-INF/MANIFEST.MF':continue
    out.writestr(name,std.read(name))
  for name,data in classes.items():out.writestr(name,data)
  out.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nMain-Class: ProcessVisibilityCheck\n\n')
  out.writestr('PRODUCTION_CLASSES.json',json.dumps({n:hashlib.sha256(d).hexdigest() for n,d in classes.items() if n.startswith('pdsync/')},indent=2))
print(a.output);print('bytes',a.output.stat().st_size);print('sha256',hashlib.sha256(a.output.read_bytes()).hexdigest())
