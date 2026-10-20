#!/usr/bin/env python3
"""Execute the real provider with host framework doubles; never an Android runtime claim."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_android_api_compat import write_java, PRODUCTION, JAVA
from test_embedded_runtime import embedded_stubs

SDK = Path(os.environ.get('ANDROID_SDK_ROOT', '/nonexistent')) / 'platforms/android-36/android.jar'

class AccessHandshakeTests(unittest.TestCase):
    @unittest.skipUnless(SDK.is_file(), 'Android SDK compilation jar is required')
    def test_production_provider_handshake_and_absent_directory(self):
        with tempfile.TemporaryDirectory(prefix='pdsync-provider-access-') as temporary:
            root = Path(temporary); classes = root/'production'; classes.mkdir(); hosts=root/'hosts'; hosts.mkdir()
            subprocess.run([JAVA, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-source','17','-target','17',
                            '-cp',str(SDK),'-d',str(classes)] + [str(p) for p in PRODUCTION.glob('*.java')],check=True)
            sources = embedded_stubs(root/'src',36)
            sources = [p for p in sources if p.name != 'SaveBridgeProvider.java']
            def add(name, body):
                path=write_java(root/'src',name,body)
                if path not in sources: sources.append(path)
            add('android.content.ContentProvider', '''package android.content;
                public class ContentProvider {
                    public Context context; public String caller="io.github.xyz1001.pdsync.android";
                    public Context getContext(){return context;} public String getCallingPackage(){return caller;}
                }''')
            add('android.os.Binder', '''package android.os; public final class Binder {
                public static int uid=10; public static int getCallingUid(){return uid;}
                public static long clearCallingIdentity(){return 1;} public static void restoreCallingIdentity(long token){}
            }''')
            add('android.os.Bundle', '''package android.os; public class Bundle extends java.util.HashMap<String,Object> {
                public Object get(String k){return super.get(k);} public void putString(String k,String v){put(k,v);}
                public String getString(String k){return (String)get(k);} public String getString(String k,String d){return containsKey(k)?getString(k):d;}
                public void putBoolean(String k,boolean v){put(k,v);} public boolean getBoolean(String k,boolean d){return containsKey(k)?(Boolean)get(k):d;}
                public void putInt(String k,int v){put(k,v);} public int getInt(String k,int d){return containsKey(k)?(Integer)get(k):d;}
                public void putLong(String k,long v){put(k,v);} public void putStringArray(String k,String[] v){put(k,v);}
                public void putParcelable(String k,Parcelable v){put(k,v);}
            }''')
            add('android.system.OsConstants', '''package android.system; public final class OsConstants {
                public static final int O_RDONLY=0,O_NONBLOCK=2048,O_NOFOLLOW=131072;
                public static boolean S_ISDIR(int mode){return (mode&0170000)==0040000;}
                public static boolean S_ISREG(int mode){return (mode&0170000)==0100000;}
            }''')
            add('android.system.Os', '''package android.system;
                import java.nio.file.*;import java.nio.file.attribute.*;import java.io.*;import java.util.*;
                public final class Os {
                    public static int opens,stats,denied; public static boolean replaceOnOpen;
                    static Map<FileDescriptor,String> descriptors=new IdentityHashMap<>();
                    public static StructStat lstat(String name)throws ErrnoException {
                        stats++; try {
                            Map<String,Object> a=Files.readAttributes(Path.of(name),"unix:dev,ino,mode,uid,nlink,size",LinkOption.NOFOLLOW_LINKS);
                            StructStat s=new StructStat(); s.st_dev=((Number)a.get("dev")).longValue();s.st_ino=((Number)a.get("ino")).longValue();
                            s.st_mode=((Number)a.get("mode")).intValue();s.st_uid=42;s.st_nlink=((Number)a.get("nlink")).longValue();s.st_size=((Number)a.get("size")).longValue(); return s;
                        }catch(NoSuchFileException e){throw new ErrnoException("lstat",2);}catch(IOException e){throw new ErrnoException("lstat",5);}
                    }
                    public static FileDescriptor open(String name,int flags,int mode)throws ErrnoException {
                        opens++;if(denied!=0)throw new ErrnoException("open",denied);
                        if(Files.isSymbolicLink(Path.of(name)))throw new ErrnoException("open",40);
                        FileDescriptor fd=new FileDescriptor();descriptors.put(fd,name);return fd;
                    }
                    public static StructStat fstat(FileDescriptor fd)throws ErrnoException {
                        StructStat s=lstat(descriptors.get(fd)); if(replaceOnOpen)s.st_ino++;return s;
                    }
                    public static void close(FileDescriptor fd){descriptors.remove(fd);}
                }''')
            sources.append(write_java(root/'src','io.github.xyz1001.pdsync.bridge.ProviderAccessChecks',DRIVER))
            subprocess.run([JAVA,'-m','jdk.compiler/com.sun.tools.javac.Main','-cp',os.pathsep.join([str(classes),str(SDK)]),
                            '-d',str(hosts)] + [str(p) for p in sources],check=True)
            subprocess.run([JAVA,'-Xverify:all','-Dstub.sdk=36','-cp',os.pathsep.join([str(hosts),str(classes),str(SDK)]),
                            'io.github.xyz1001.pdsync.bridge.ProviderAccessChecks',str(root/'fixture')],check=True)

DRIVER=r'''
package io.github.xyz1001.pdsync.bridge;
import android.content.*;import android.content.pm.*;import android.os.*;import android.system.*;import android.app.ActivityManager;
import java.nio.file.*;import java.util.*;
public final class ProviderAccessChecks {
    static int checks; static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        Path data=Path.of(args[0]); Context c=new Context(); PackageInfo game=new PackageInfo(),main=new PackageInfo();
        String origin="a".repeat(64);game.packageName=c.pkg;game.versionCode=17;game.versionName="v17";
        game.applicationInfo=new ApplicationInfo();game.applicationInfo.uid=42;game.applicationInfo.dataDir=data.toString();
        main.packageName=MainCallerPolicy.MAIN;main.applicationInfo=new ApplicationInfo();main.applicationInfo.uid=10;
        c.pm.packages.put(c.pkg,game);c.pm.packages.put(main.packageName,main);c.pm.uids.put(42,new String[]{c.pkg});c.pm.uids.put(10,new String[]{main.packageName});
        Bundle m=new Bundle();String[] values={"embedded-v1",c.pkg,origin,"android-"+origin,"b".repeat(64),"17","v17","0".repeat(64),"1"};
        for(int i=0;i<values.length;i++)m.putString(GameBinding.PREFIX+GameBinding.EMBEDDED_METADATA_NAMES[i],values[i]);game.applicationInfo.metaData=m;
        ProviderInfo p=new ProviderInfo();p.packageName=c.pkg;p.authority="io.github.xyz1001.pdsync.embedded.g"+origin.substring(0,32)+".files";p.processName=c.pkg+":pdsync_bridge";p.exported=true;c.pm.provider=p;
        SaveBridgeProvider provider=new SaveBridgeProvider();provider.context=c;check(provider.onCreate(),"inert create");
        Bundle reply=provider.call("authenticate",null,null);
        check(reply.getBoolean("ok",false)&&reply.getBoolean("accessAuthenticated",false),"pure handshake success");
        check(reply.getInt("accessProtocolVersion",0)==1&&reply.getBoolean("metadataOnly",false),"pure protocol identified");
        check(Os.opens==0&&Os.stats==0&&!Files.exists(data),"handshake never touches nonexistent game data");
        check(c.manager.running==null,"handshake does not need game process observation");
        Bundle extras=new Bundle();extras.putString("packageName",MainCallerPolicy.MAIN);
        try{provider.call("authenticate",null,extras);throw new AssertionError("spoof extras allowed");}catch(IllegalArgumentException expected){checks++;}
        Binder.uid=11;try{provider.call("authenticate",null,null);throw new AssertionError("spoof UID allowed");}catch(SecurityException expected){checks++;}Binder.uid=10;
        try{provider.call("unknown",null,null);throw new AssertionError("unknown method allowed");}catch(IllegalArgumentException expected){checks++;}
        check(Os.opens==0&&Os.stats==0&&!Files.exists(data),"invalid method has no filesystem effects");
        Files.createDirectory(data);
        ActivityManager.RunningAppProcessInfo process=new ActivityManager.RunningAppProcessInfo();process.uid=42;process.pid=4242;process.pkgList=new String[]{c.pkg};process.processName=p.processName;process.importance=100;c.manager.running=List.of(process);
        reply=provider.call("inspect",null,null);
        check(reply.getBoolean("ok",false)&&reply.getBoolean("ready",false)&&reply.getBoolean("transferReady",false),"missing files accepted after parent capability proof");
        check(reply.getBoolean("missingDirectoryVerified",false)&&!reply.getBoolean("firstLaunchNeeded",true),"verified absence is not first-launch failure");
        try(var children=Files.list(data)){check(children.count()==0,"inspection creates neither files nor workspace");}
        for(int errno:new int[]{13,1,5}){Os.denied=errno;reply=provider.call("inspect",null,null);check(!reply.getBoolean("ok",true),"permission and IO failure cannot become empty");}Os.denied=0;
        Os.replaceOnOpen=true;reply=provider.call("inspect",null,null);check(!reply.getBoolean("ok",true),"parent descriptor identity race rejected");Os.replaceOnOpen=false;
        Files.createSymbolicLink(data.resolve("files"),data);reply=provider.call("inspect",null,null);check(!reply.getBoolean("ok",true),"save symlink rejected");Files.delete(data.resolve("files"));
        Files.delete(data);reply=provider.call("inspect",null,null);check(!reply.getBoolean("ok",true),"missing game data parent rejected");
        System.out.println("PASS: "+checks+" production provider handshake/absence checks; host framework doubles only.");
    }
}
'''
if __name__=='__main__':unittest.main()
