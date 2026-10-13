import ast,hashlib,json,os,pathlib,shutil,time
root=pathlib.Path('/opt/sftpgo')
source=root/'auth_service.py'
module=root/'auth_v2.py'
text=source.read_text()
tree=ast.parse(text)
assert not any(isinstance(n,ast.ImportFrom) and n.module=='auth_v2' for n in tree.body), 'Already integrated; inspect instead of duplicating'
guards=[n for n in tree.body if isinstance(n,ast.If) and isinstance(n.test,ast.Compare) and isinstance(n.test.left,ast.Name) and n.test.left.id=='__name__']
assert len(guards)==1
compile(module.read_text(),str(module),'exec')
lines=text.splitlines(keepends=True)
lines[guards[0].lineno-1:guards[0].lineno-1]=['from auth_v2 import install as _install_auth_v2\n','_install_auth_v2(globals())\n\n']
updated=''.join(lines)
compile(updated,str(source),'exec')
backup=root/('auth-v2-backup-'+time.strftime('%Y%m%dT%H%M%SZ',time.gmtime()))
backup.mkdir(mode=0o700)
for path in (source,root/'API_DOC.md'):
 shutil.copy2(path,backup/path.name)
 os.chmod(backup/path.name,0o600)
def replace(path,content):
 st=path.stat();tmp=path.with_name(path.name+'.v2tmp')
 fd=os.open(tmp,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
 with os.fdopen(fd,'w') as f:f.write(content);f.flush();os.fsync(f.fileno())
 os.chown(tmp,st.st_uid,st.st_gid);os.chmod(tmp,st.st_mode & 0o777)
 os.replace(tmp,path)
replace(source,updated)
doc=root/'API_DOC.md'
replace(doc,doc.read_text()+'\n\n'+(root/'auth_v2_contract.txt').read_text())
print(json.dumps({'backup':str(backup),'moduleSHA256':hashlib.sha256(module.read_bytes()).hexdigest(),'integration':'installed','accounts_changed_by_deployment':False}))
