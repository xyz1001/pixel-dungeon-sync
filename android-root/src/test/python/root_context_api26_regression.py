import pathlib, re, subprocess, tempfile, sys

REPO = pathlib.Path(__file__).resolve().parents[4]
RUNTIME = REPO / 'android-root/src/main/java/io/github/xyz1001/pdsync/root/AndroidRootRuntime.java'
READINESS = REPO / 'android-app/src/main/java/io/github/xyz1001/pdsync/android/InstallationReadiness.java'

def method(text, signature):
    start = text.index(signature)
    brace = text.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]

source = RUNTIME.read_text(encoding='utf-8')
readiness = READINESS.read_text(encoding='utf-8')
context_method = method(source, 'private Context userContext(')
classify = method(readiness, 'public static AccessStatus classify(')
enum = re.search(r'public enum AccessStatus\s*\{[^}]+}', readiness).group()
expected = sys.argv[1] if len(sys.argv) > 1 else 'fixed'
files = {
 'android/os/Looper.java': 'package android.os; public class Looper { public static Looper myLooper(){return new Looper();} public static void prepare(){} }',
 'android/os/UserHandle.java': 'package android.os; public class UserHandle { public final int user; public UserHandle(int u){user=u;} public static UserHandle getUserHandleForUid(int uid){return new UserHandle(uid/100000);} }',
 # API 26 surface: createContextAsUser is absent, createPackageContextAsUser exists.
 'android/content/Context.java': '''package android.content; import android.os.UserHandle;
 public class Context { public static boolean deny; public static int requestedUser=-1;
 public Context createPackageContextAsUser(String name,int flags,UserHandle user) {
 if(deny)throw new SecurityException("test denial");
 if(!"android".equals(name)||flags!=0)throw new AssertionError("unexpected package/flags");
 requestedUser=user.user;return new Context(); } }''',
 'android/app/ActivityThread.java': '''package android.app; import android.content.Context;
 public class ActivityThread { public static ActivityThread systemMain(){return new ActivityThread();}
 public Context getSystemContext(){return new Context();} }''',
 'Check.java': '''import android.content.Context; import android.os.*; import java.io.*; import java.lang.reflect.*;
 public class Check {
 static class RootProtocol { enum Status { UNRELIABLE, IO_ERROR } }
 static class RootFailure extends IOException { final RootProtocol.Status status;
 RootFailure(RootProtocol.Status s,Throwable t){super(t);status=s;} }
 static class RootBinding { final int uid; RootBinding(int u){uid=u;} }
 private Context systemContext;
 ''' + enum + '\n' + classify + '\n' + context_method + '''
 public static void main(String[] args)throws Exception {
 if(args[0].equals("baseline")) {
 try { new Check().userContext(new RootBinding(10132));throw new AssertionError("expected API26 failure"); }
 catch(RootFailure e){ if(!(e.getCause() instanceof NoSuchMethodException))throw e;
 if(classify("ROOT_"+e.status)!=AccessStatus.GAME_BUSY)throw new AssertionError("not reproduced");
 System.out.println("PASS baseline: missing API26 method -> NoSuchMethodException -> ROOT_UNRELIABLE -> GAME_BUSY"); }
 } else {
 for(int uid:new int[]{10132,1010132}){new Check().userContext(new RootBinding(uid));
 if(Context.requestedUser!=uid/100000)throw new AssertionError("wrong user");}
 Context.deny=true;
 try {new Check().userContext(new RootBinding(10132));throw new AssertionError("denial must block");}
 catch(RootFailure e){if(e.status!=RootProtocol.Status.IO_ERROR)throw new AssertionError(e.status);
 if(classify("ROOT_"+e.status)==AccessStatus.GAME_BUSY||classify("ROOT_"+e.status)==AccessStatus.READY)throw new AssertionError("unsafe classification");}
 System.out.println("PASS fixed: API26 primary/secondary users; reflection target denial stays blocked and is not GAME_BUSY");
 }
 }}'''
}
with tempfile.TemporaryDirectory(prefix='root-context-regression-', dir=pathlib.Path(__file__).resolve().parent) as d:
    root = pathlib.Path(d)
    for name, text in files.items():
        path = root/name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')
    subprocess.run(['javac','-encoding','UTF-8','-d',d,*map(str,root.rglob('*.java'))],check=True)
    subprocess.run(['java','-cp',d,'Check',expected],check=True)
