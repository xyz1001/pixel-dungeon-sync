#!/usr/bin/env python3
"""Embedded runtime authentication and process tests. JVM stubs are not device tests."""
import base64
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from test_android_api_compat import platform_stubs, write_java, PRODUCTION, JAVA


def embedded_stubs(root, sdk):
    files = platform_stubs(root, sdk)
    def add(name, body):
        path = write_java(root, name, body)
        if path not in files:
            files.append(path)
    add('android.os.Process', '''package android.os; public final class Process {
        public static int myUid() { return 42; } public static int myPid() { return 4242; }
    }''')
    add('android.os.Bundle', '''package android.os; public class Bundle extends java.util.HashMap<String,Object> {
        public void putString(String key,String value) { put(key,value); }
    }''')
    add('android.content.ComponentName', '''package android.content; public class ComponentName {
        public final String pkg,name; public ComponentName(String p,String n) { pkg=p; name=n; }
    }''')
    add('android.content.pm.ApplicationInfo', '''package android.content.pm; public class ApplicationInfo {
        public int uid; public String dataDir; public android.os.Bundle metaData;
    }''')
    add('android.content.pm.ProviderInfo', '''package android.content.pm; public class ProviderInfo {
        public String packageName,authority,processName,readPermission,writePermission;
        public boolean exported,grantUriPermissions; public android.os.Bundle metaData;
    }''')
    package_extra = '''public SigningInfo signingInfo;
        public long getLongVersionCode() { return versionCode & 0xffffffffL; }''' if sdk >= 28 else ''
    add('android.content.pm.PackageInfo', '''package android.content.pm; public class PackageInfo {
        public String packageName,versionName,sharedUserId; public int versionCode;
        public ApplicationInfo applicationInfo; public Signature[] signatures;
    ''' + package_extra + '}')
    add('android.content.pm.PackageManager', '''package android.content.pm; public class PackageManager {
        public static final int GET_SIGNATURES=64,GET_META_DATA=128,GET_SIGNING_CERTIFICATES=134217728;
        public java.util.Map<String,PackageInfo> packages=new java.util.HashMap<>();
        public java.util.Map<Integer,String[]> uids=new java.util.HashMap<>();
        public ProviderInfo provider;
        public static class NameNotFoundException extends Exception { }
        public PackageInfo getPackageInfo(String name,int flags) throws NameNotFoundException {
            PackageInfo info=packages.get(name); if(info==null) throw new NameNotFoundException(); return info;
        }
        public ApplicationInfo getApplicationInfo(String name,int flags) throws NameNotFoundException {
            return getPackageInfo(name,flags).applicationInfo;
        }
        public ProviderInfo getProviderInfo(android.content.ComponentName name,int flags) throws NameNotFoundException {
            if(provider==null || !provider.packageName.equals(name.pkg)
                || !name.name.equals("io.github.xyz1001.pdsync.bridge.SaveBridgeProvider")) throw new NameNotFoundException(); return provider;
        }
        public String[] getPackagesForUid(int uid) { return uids.get(uid); }
        public int checkSignatures(String a,String b) { return a.equals(b)?0:-1; }
    }''')
    add('android.app.ActivityManager', '''package android.app; public class ActivityManager {
        public java.util.List<RunningAppProcessInfo> running;
        public java.util.List<ProcessErrorStateInfo> errors;
        public java.util.List<RunningAppProcessInfo> getRunningAppProcesses() { return running; }
        public java.util.List<ProcessErrorStateInfo> getProcessesInErrorState() { return errors; }
        public static class RunningAppProcessInfo { public int pid,uid,importance; public String processName; public String[] pkgList; }
        public static class ProcessErrorStateInfo { public int uid; }
    }''')
    add('android.content.Context', '''package android.content; public class Context {
        public android.content.pm.PackageManager pm=new android.content.pm.PackageManager();
        public android.app.ActivityManager manager=new android.app.ActivityManager();
        public String pkg="test.embedded.game";
        public String getPackageName() { return pkg; }
        public android.content.pm.PackageManager getPackageManager() { return pm; }
        public Object getSystemService(String name) { return name.equals("activity")?manager:null; }
        public android.content.pm.ApplicationInfo getApplicationInfo() { return pm.packages.get(pkg).applicationInfo; }
    }''')
    add('io.github.xyz1001.pdsync.bridge.SaveBridgeProvider', '''package io.github.xyz1001.pdsync.bridge;
        public final class SaveBridgeProvider { }
    ''')
    return files


DRIVER = r'''
package io.github.xyz1001.pdsync.bridge;
import android.content.Context;
import android.content.pm.*;
import android.app.ActivityManager;
import android.os.Bundle;
import java.nio.file.*;
import java.util.*;
public final class EmbeddedRuntimeTests {
    static int assertions;
    static final String GAME="test.embedded.game", MAIN=MainCallerPolicy.MAIN;
    interface Action { void run() throws Exception; }
    static void check(boolean condition,String description) { assertions++; if(!condition) throw new AssertionError(description); }
    static void denied(Action action,String description) throws Exception {
        assertions++; try { action.run(); } catch(SecurityException | java.io.IOException expected) { return; }
        throw new AssertionError("Expected refusal: "+description);
    }
    static void sign(PackageInfo p,byte[] certificate,int sdk) throws Exception {
        p.signatures=new Signature[]{new Signature(certificate)};
        if(sdk>=28) {
            Object signing=Class.forName("android.content.pm.SigningInfo").getConstructor().newInstance();
            signing.getClass().getField("signers").set(signing,p.signatures);
            PackageInfo.class.getField("signingInfo").set(p,signing);
        }
    }
    static ActivityManager.RunningAppProcessInfo process(String name,int pid,int importance,String... packages) {
        ActivityManager.RunningAppProcessInfo p=new ActivityManager.RunningAppProcessInfo();
        p.processName=name; p.pid=pid; p.uid=42; p.importance=importance; p.pkgList=packages; return p;
    }
    public static void main(String[] args) throws Exception {
        int sdk=Integer.parseInt(args[0]);
        byte[] certificate=Base64.getMimeDecoder().decode(new String(Files.readAllBytes(Paths.get(args[1])),java.nio.charset.StandardCharsets.US_ASCII));
        MainCallerPolicy.requirePinnedSigner(MainCallerPolicy.MAIN_SIGNER_SHA256,new byte[][]{certificate}); assertions++;
        Context c=new Context(); PackageInfo game=new PackageInfo(),main=new PackageInfo();
        game.packageName=GAME; game.versionCode=17; game.versionName="v17";
        game.applicationInfo=new ApplicationInfo(); game.applicationInfo.uid=42; game.applicationInfo.dataDir="/data/user/0/"+GAME;
        main.packageName=MAIN; main.applicationInfo=new ApplicationInfo(); main.applicationInfo.uid=10;
        sign(main,certificate,sdk); c.pm.packages.put(GAME,game); c.pm.packages.put(MAIN,main);
        c.pm.uids.put(42,new String[]{GAME}); c.pm.uids.put(10,new String[]{MAIN});
        Bundle metadata=new Bundle(); String origin=String.join("",Collections.nCopies(64,"1"));
        String[] values={"embedded-v1",GAME,origin,"android-"+origin,origin,"17","v17",MainCallerPolicy.MAIN_SIGNER_SHA256,"1"};
        for(int i=0;i<values.length;i++) metadata.putString(GameBinding.PREFIX+GameBinding.EMBEDDED_METADATA_NAMES[i],values[i]);
        game.applicationInfo.metaData=metadata;
        ProviderInfo provider=new ProviderInfo(); c.pm.provider=provider;
        provider.packageName=GAME; provider.authority="io.github.xyz1001.pdsync.embedded.g"+origin.substring(0,32)+".files";
        provider.processName=GAME+":pdsync_bridge"; provider.exported=true;
        MainCallerAuthenticator.authenticateProvider(c,MAIN,10); assertions++;
        MainCallerAuthenticator.authenticateActivity(c,MAIN); assertions++;
        denied(()->MainCallerAuthenticator.authenticateActivity(c,null),"activity must be started for result by verified main");
        denied(()->MainCallerAuthenticator.authenticateActivity(c,"evil.pkg"),"foreign activation");
        denied(()->MainCallerAuthenticator.authenticateProvider(c,MAIN,42),"same-UID game caller");
        denied(()->MainCallerAuthenticator.authenticateProvider(c,MAIN,11),"spoofed Binder UID");
        denied(()->MainCallerAuthenticator.authenticateProvider(c,"evil.pkg",10),"spoofed provider package");
        c.pm.uids.put(10,new String[]{MAIN,"evil.pkg"});
        denied(()->MainCallerAuthenticator.authenticateProvider(c,MAIN,10),"nonunique main UID");
        c.pm.uids.put(10,new String[]{MAIN}); main.applicationInfo.uid=11;
        denied(()->MainCallerAuthenticator.authenticateProvider(c,MAIN,10),"changed installed main UID"); main.applicationInfo.uid=10;
        sign(main,new byte[]{1,2,3},sdk);
        denied(()->MainCallerAuthenticator.authenticateProvider(c,MAIN,10),"wrong current certificate");
        main.signatures=new Signature[]{new Signature(certificate)};
        if(sdk>=28) denied(()->MainCallerAuthenticator.authenticateProvider(c,MAIN,10),"historical/legacy signer cannot override current signer");
        sign(main,certificate,sdk);
        metadata.putString(GameBinding.PREFIX+"MAIN_APP_CERT_SHA256",origin);
        denied(()->MainCallerAuthenticator.authenticateProvider(c,MAIN,10),"manifest cannot redefine pin");
        metadata.putString(GameBinding.PREFIX+"MAIN_APP_CERT_SHA256",MainCallerPolicy.MAIN_SIGNER_SHA256);
        metadata.put(GameBinding.PREFIX+"BRIDGE_RUNTIME_VERSION",1);
        denied(()->MainCallerAuthenticator.authenticateProvider(c,MAIN,10),"resource/integer metadata rejected");
        metadata.putString(GameBinding.PREFIX+"BRIDGE_RUNTIME_VERSION","1");
        provider.metaData=new Bundle(); provider.metaData.putString(GameBinding.PREFIX+"BRIDGE_RUNTIME_VERSION","2");
        denied(()->BridgeMetadata.read(c),"conflicting metadata rejected");
        provider.metaData.putString(GameBinding.PREFIX+"BRIDGE_RUNTIME_VERSION","1");
        check(new GameGuard(c).binding().embedded,"matching duplicate metadata accepted");
        game.applicationInfo.metaData=null; provider.metaData=metadata;
        check(new GameGuard(c).binding().embedded,"provider-only binding accepted");
        game.applicationInfo.metaData=metadata; provider.metaData=null;
        GameGuard guard=new GameGuard(c); check(guard.installedGame()==game,"own game installed without helper");
        c.pm.uids.put(42,new String[]{GAME,"evil.pkg"});
        denied(guard::installedGame,"unexpected shared game UID"); c.pm.uids.put(42,new String[]{GAME});
        provider.readPermission="original.game.permission";
        denied(()->new GameGuard(c).binding(),"inherited application permission rejected"); provider.readPermission=null;
        provider.writePermission="original.game.permission";
        denied(()->new GameGuard(c).binding(),"inherited write permission rejected"); provider.writePermission="";
        provider.processName=GAME;
        denied(()->new GameGuard(c).binding(),"bridge in main game process rejected"); provider.processName=GAME+":pdsync_bridge";
        provider.grantUriPermissions=true;
        denied(()->new GameGuard(c).binding(),"URI grants rejected"); provider.grantUriPermissions=false;
        ActivityManager.RunningAppProcessInfo self=process(GAME+":pdsync_bridge",4242,100,GAME);
        c.manager.running=new ArrayList<>(); c.manager.running.add(self);
        check(!guard.observe().gameProcessPresent,"own active bridge process excluded from game visibility");
        ActivityManager.RunningAppProcessInfo other=process("custom.game.process",4250,100,GAME);
        c.manager.running.add(other);
        check(guard.observe().gameForeground && guard.observe().gameProcessPresent,"all owned game processes count regardless of name");
        denied(guard::requireQuiescent,"foreground game blocks save access");
        other.importance=400;
        check(!guard.observe().gameForeground && guard.observe().gameProcessPresent,"cached game process requires downstream quiet scan");
        other.pkgList=new String[]{GAME,"evil.pkg"}; denied(guard::observe,"ambiguous process package list"); other.pkgList=new String[]{GAME};
        other.processName=GAME+":pdsync_bridge"; denied(guard::observe,"second bridge PID is ambiguous"); other.processName=GAME;
        c.manager.running.remove(self); denied(guard::observe,"own PID visibility required"); c.manager.running.add(self);
        self.processName=GAME; denied(guard::observe,"own PID must have dedicated process identity"); self.processName=GAME+":pdsync_bridge";
        ActivityManager.ProcessErrorStateInfo error=new ActivityManager.ProcessErrorStateInfo(); error.uid=42;
        c.manager.errors=Arrays.asList(error); denied(guard::observe,"crashing game or bridge process blocks transfer"); c.manager.errors=null;
        c.manager.running=null; denied(guard::observe,"missing process visibility");
        System.out.println("PASS: embedded runtime API "+sdk+", "+assertions+" authentication/process assertions; JVM stubs only.");
    }
}
'''


class EmbeddedRuntimeTests(unittest.TestCase):
    def test_authentication_and_game_processes(self):
        with tempfile.TemporaryDirectory(prefix='pdsync-embedded-runtime-') as temp:
            root = Path(temp)
            compile_cmd = [JAVA, 'com.sun.tools.javac.Main', '-source', '8', '-target', '8', '-Xlint:-options']
            stubs = embedded_stubs(root / 'compile-stubs', 36)
            driver = write_java(root / 'driver', 'io.github.xyz1001.pdsync.bridge.EmbeddedRuntimeTests', DRIVER)
            classes = root / 'classes'
            production = ['AndroidApiCompat', 'DescriptorFlags', 'BridgeFailure', 'MainCallerPolicy', 'MainCallerAuthenticator', 'GameBinding', 'GameGuard', 'BridgeMetadata']
            subprocess.run(compile_cmd + ['-d', str(classes)] + list(map(str, stubs))
                           + [str(PRODUCTION / (name + '.java')) for name in production] + [str(driver)], check=True)
            shutil.rmtree(classes / 'android')
            for sdk in (26, 27, 28, 29, 30, 36):
                with self.subTest(sdk=sdk):
                    runtime = root / ('runtime-' + str(sdk))
                    stubs = embedded_stubs(root / ('stubs-' + str(sdk)), sdk)
                    subprocess.run(compile_cmd + ['-d', str(runtime)] + list(map(str, stubs)), check=True)
                    subprocess.run([JAVA, '-Xverify:all', '-Dstub.sdk=' + str(sdk), '-cp', os.pathsep.join((str(classes), str(runtime))),
                                    'io.github.xyz1001.pdsync.bridge.EmbeddedRuntimeTests', str(sdk),
                                    str(Path(__file__).with_name('main-public-certificate.base64'))], check=True)

    def test_only_authenticated_call_surface(self):
        source = (PRODUCTION / 'SaveBridgeProvider.java').read_text()
        import re
        for method in ('query', 'getType', 'insert', 'delete', 'update', 'openFile', 'bulkInsert', 'applyBatch', 'canonicalize', 'uncanonicalize', 'refresh', 'getStreamTypes'):
            match = re.search(r'public [^{]+\b' + method + r'\([^{}]*\{([^}]*)', source)
            self.assertIsNotNone(match, method)
            self.assertIn('authenticate();', match.group(1), method)
        self.assertIn('int iAuthenticate = authenticate();', source)
        self.assertNotIn('enforceCallingPermission', source)
        self.assertIn('? getContext().getNoBackupFilesDir() : getContext().getFilesDir()', source)
        auth = (PRODUCTION / 'MainCallerAuthenticator.java').read_text()
        self.assertNotIn('getString(', auth)
        self.assertNotIn('checkPermission', auth)


if __name__ == '__main__':
    unittest.main()
