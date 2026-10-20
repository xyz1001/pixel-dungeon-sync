package io.github.xyz1001.pdsync.android;

import android.content.Context;
import android.content.pm.*;
import android.os.Build;
import android.os.Bundle;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;

/** Real production policies and file/transport code, with host-only PM doubles. */
public final class InstalledBridgeAccessChecks {
    static int checks;
    interface Action { void run() throws Exception; }
    static void check(boolean result,String detail) { checks++; if(!result)throw new AssertionError(detail); }
    static void rejected(Action action,String detail)throws Exception {
        checks++;try{action.run();}catch(IOException|SecurityException expected){return;}throw new AssertionError(detail);
    }
    public static void main(String[] args)throws Exception {
        discovery(); store(); protocol(); deadlines();
        System.out.println("Installed bridge discovery/selection/protocol/deadline checks: "+checks+" assertions. JVM doubles, not Android IPC.");
    }
    static void discovery()throws Exception {
        for(int sdk:new int[]{26,36}) {
            Build.VERSION.SDK_INT=sdk; Fixture f=new Fixture();
            InstalledBridgeBinding binding=InstalledBridgeResolver.resolve(f.context,Fixture.GAME);
            check(binding!=null&&binding.originIdentity.equals(Fixture.ORIGIN),"real manifest binding adopted without receipt");
            check(binding.sourceApkSetSha256.equals(Fixture.SOURCE)&&binding.namespaceId.equals("android-"+Fixture.ORIGIN),"source namespace inherited, not derived from public game signer");
            check(binding.accessProtocol.equals("1"),"pure access marker retained");
            check(!Files.exists(Path.of(binding.apkPaths.get(0))),"discovery reads no nonexistent APK bytes");
            f.main.signatures=new Signature[]{new Signature(new byte[]{9,9,9})};f.main.signingInfo.signatures=f.main.signatures;
            check(binding.matches(InstalledBridgeResolver.resolve(f.context,Fixture.GAME)),"main signing certificate not pinned");
            f.game.lastUpdateTime++;rejected(()->InstalledBridgeResolver.requireUnchanged(f.context,binding),"updated installed package rejected");f.game.lastUpdateTime--;
            f.provider.processName=Fixture.GAME;rejected(()->InstalledBridgeResolver.resolve(f.context,Fixture.GAME),"wrong bridge process rejected");f.provider.processName=Fixture.GAME+":pdsync_bridge";
            f.provider.grantUriPermissions=true;rejected(()->InstalledBridgeResolver.resolve(f.context,Fixture.GAME),"URI grants rejected");f.provider.grantUriPermissions=false;
            f.gameMembers=new String[]{Fixture.GAME,"other.game"};rejected(()->InstalledBridgeResolver.resolve(f.context,Fixture.GAME),"shared game UID rejected");f.gameMembers=new String[]{Fixture.GAME};
            f.mainMembers=new String[]{InstalledBridgeResolver.MAIN,"other.client"};rejected(()->InstalledBridgeResolver.resolve(f.context,Fixture.GAME),"shared main UID rejected");f.mainMembers=new String[]{InstalledBridgeResolver.MAIN};
            f.provider.metaData=new Bundle();f.provider.metaData.put(Fixture.META+"BOUND_SOURCE_APK_SHA256","d".repeat(64));
            rejected(()->InstalledBridgeResolver.resolve(f.context,Fixture.GAME),"conflicting provider metadata rejected");f.provider.metaData=null;
            f.game.applicationInfo.metaData.remove(Fixture.META+"MAIN_APP_CERT_SHA256");
            check(binding.matches(InstalledBridgeResolver.resolve(f.context,Fixture.GAME)),"historical main cert metadata not required");
            f.game.applicationInfo.metaData=null;f.provider.name="someone.else.ModifiedProvider";
            check(InstalledBridgeResolver.resolve(f.context,Fixture.GAME)==null,"unrelated modified app is not our bridge");
            f.provider.name=InstalledBridgeResolver.PROVIDER;
            rejected(()->InstalledBridgeResolver.resolve(f.context,Fixture.GAME),"own component without source binding rejected");
        }
    }
    static void store()throws Exception {
        Path root=Files.createTempDirectory("installed-bridge-selection-");
        try {
            Fixture f=new Fixture();InstalledBridgeBinding original=InstalledBridgeResolver.resolve(f.context,Fixture.GAME);
            InstalledBridgeSelectionStore store=new InstalledBridgeSelectionStore(root.toFile());check(store.load()==null,"fresh local selection absent");
            store.save(original);check(original.matches(store.load()),"cold selection retains complete PM and source binding");
            Path file=root.resolve("installed-bridge-selection-v1.properties");
            Files.writeString(file,"\nversion=1\n",StandardOpenOption.APPEND);rejected(store::load,"duplicate persisted fields rejected");
            store.save(original);Files.writeString(file,"\nforeign=value\n",StandardOpenOption.APPEND);rejected(store::load,"unknown persisted fields rejected");
            store.save(original);store.clear();check(store.load()==null,"selection clear verified");
            Path target=Files.writeString(root.resolve("external"),"retained");Files.createSymbolicLink(file,target);
            rejected(store::load,"selection symlink rejected");rejected(()->store.save(original),"selection writer rejects symlink");check(Files.readString(target).equals("retained"),"symlink target unchanged");
        } finally {try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    static void protocol()throws Exception {
        Path root=Files.createTempDirectory("installed-bridge-protocol-");
        try {
            byte[] runtime="current-bundled-pure-runtime-fixture".getBytes();String hash=PackageTrust.hex(MessageDigest.getInstance("SHA-256").digest(runtime));
            Path apk=root.resolve("base.apk");writeZip(apk,Map.of("classes.dex","original-game-payload-not-read".getBytes(),"classes2.dex",runtime));
            check(InstalledBridgeProtocol.requireKnownRuntime(apk.toFile(),hash).equals(hash),"exact isolated last runtime accepted");
            rejected(()->InstalledBridgeProtocol.requireKnownRuntime(apk.toFile(),"0".repeat(64)),"unknown historical runtime refused before IPC");
            writeZip(apk,Map.of("classes.dex",runtime,"classes2.dex","unknown-tail".getBytes()));
            rejected(()->InstalledBridgeProtocol.requireKnownRuntime(apk.toFile(),hash),"known runtime in non-tail position not adopted");
            writeZip(apk,Map.of("classes.dex","original".getBytes(),"classes3.dex",runtime));
            rejected(()->InstalledBridgeProtocol.requireKnownRuntime(apk.toFile(),hash),"DEX gap refused");
            writeZip(apk,Map.of("classes.dex",new byte[(int)InstalledBridgeProtocol.MAX_RUNTIME_BYTES+1]));
            rejected(()->InstalledBridgeProtocol.requireKnownRuntime(apk.toFile(),hash),"runtime decompression bounded");
            rejected(()->InstalledBridgeProtocol.boundedHash(new ByteArrayInputStream(new byte[0])),"empty bundled runtime rejected");
            String published=System.getenv("PDSYNC_CODE45_APK");
            if(published!=null) {
                check(PackageTrust.sha256(new File(published)).equals("aa839df6d2e89dfa3be062c73fb219c894ee1a08ec0556ad362d2998bdd311e5"),"published code45 fixture identity verified");
                byte[] historical;
                try(ZipFile zip=new ZipFile(published);InputStream input=zip.getInputStream(zip.getEntry("assets/bridge-runtime.dex"))) { historical=input.readAllBytes(); }
                writeZip(apk,Map.of("classes.dex","opaque-original-game-fixture".getBytes(),"classes2.dex",historical));
                check(InstalledBridgeProtocol.requireKnownRuntime(apk.toFile(),hash).equals(InstalledBridgeProtocol.CODE45_RUNTIME),"actual published code45 standalone runtime accepted without receipt");
                historical[historical.length-1]^=1;writeZip(apk,Map.of("classes.dex","opaque-original-game-fixture".getBytes(),"classes2.dex",historical));
                rejected(()->InstalledBridgeProtocol.requireKnownRuntime(apk.toFile(),hash),"modified code45 runtime rejected before IPC");
            }
            writeZip(apk,Map.of("classes.dex",runtime));Path link=root.resolve("alias.apk");Files.createSymbolicLink(link,apk);
            rejected(()->InstalledBridgeProtocol.requireKnownRuntime(link.toFile(),hash),"base APK symlink refused");
        } finally {try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    static void writeZip(Path path,Map<String,byte[]> entries)throws IOException {
        try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(path))) {
            for(var entry:new TreeMap<>(entries).entrySet()){out.putNextEntry(new ZipEntry(entry.getKey()));out.write(entry.getValue());out.closeEntry();}
        }
    }
    static BridgeDiagnostics.Failure failure(BridgeDiagnostics.Stage stage,String code,Throwable error,BridgeDiagnostics.TransportInfo info) {
        return new BridgeDiagnostics.Failure(stage,code,code,error,info);
    }
    static void deadlines()throws Exception {
        AtomicInteger closed=new AtomicInteger(),disposed=new AtomicInteger();
        BridgeTransport.Result<String> quick=BridgeTransport.exchangeBounded(()->new BridgeTransport.Client<String>() {
            public String call(){return "reply";}public void close(){closed.incrementAndGet();}
        },reply->disposed.incrementAndGet(),InstalledBridgeAccessChecks::failure,1000);
        check(quick.reply.equals("reply")&&closed.get()==1&&disposed.get()==0,"timely reply closes client once and remains consumable");
        CountDownLatch release=new CountDownLatch(1);CountDownLatch acquired=new CountDownLatch(2);
        for(int i=0;i<2;i++) {
            final boolean lateAcquire=i==0;
            try {
                BridgeTransport.exchangeBounded(()-> {
                    acquired.countDown();if(lateAcquire)release.await();
                    return new BridgeTransport.Client<String>(){
                        public String call()throws Exception {if(!lateAcquire)release.await();return "late";}
                        public void close(){closed.incrementAndGet();}
                    };
                },reply->disposed.incrementAndGet(),InstalledBridgeAccessChecks::failure,30);
                throw new AssertionError("blocked access not bounded");
            }catch(BridgeDiagnostics.Failure expected){check("ACCESS_HANDSHAKE_TIMEOUT".equals(expected.code),"acquire and call timeout explicit: "+expected.code);}
        }
        check(acquired.await(1,TimeUnit.SECONDS),"both bounded workers began");
        try {BridgeTransport.exchangeBounded(()->null,reply->{},InstalledBridgeAccessChecks::failure,1000);throw new AssertionError("unbounded abandoned worker spawn");}
        catch(BridgeDiagnostics.Failure expected){check("ACCESS_WORKERS_BUSY".equals(expected.code),"outstanding timed-out calls bounded to two workers");}
        release.countDown();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(disposed.get()!=2&&System.nanoTime()<until)Thread.sleep(5);
        check(disposed.get()==2&&closed.get()==3,"late acquisition and IPC close own clients and discard late replies exactly once");
    }
    static final class Fixture extends PackageManager {
        static final String GAME="test.actual.game",ORIGIN="a".repeat(64),SOURCE="b".repeat(64),META=InstalledBridgeResolver.PREFIX;
        final PackageInfo game=new PackageInfo(),main=new PackageInfo();final ProviderInfo provider=new ProviderInfo();
        String[] gameMembers={GAME},mainMembers={InstalledBridgeResolver.MAIN};
        final Context context=new Context(){public String getPackageName(){return InstalledBridgeResolver.MAIN;}public PackageManager getPackageManager(){return Fixture.this;}public File getFilesDir(){throw new AssertionError("Discovery must not touch app files");}};
        Fixture() {
            game.packageName=GAME;game.version=17;game.versionCode=17;game.versionName="v17";game.firstInstallTime=1;game.lastUpdateTime=2;
            game.applicationInfo=new ApplicationInfo();game.applicationInfo.packageName=GAME;game.applicationInfo.uid=200;game.applicationInfo.enabled=true;game.applicationInfo.dataDir="/not-read/data/game";game.applicationInfo.sourceDir="/not-read/apk/base.apk";
            game.signatures=new Signature[]{new Signature(new byte[]{1,2,3})};game.signingInfo=new SigningInfo();game.signingInfo.signatures=game.signatures;
            main.packageName=InstalledBridgeResolver.MAIN;main.applicationInfo=new ApplicationInfo();main.applicationInfo.uid=100;main.signatures=new Signature[]{new Signature(new byte[]{4,5,6})};main.signingInfo=new SigningInfo();main.signingInfo.signatures=main.signatures;
            Bundle m=new Bundle();game.applicationInfo.metaData=m;
            String[] names={"BRIDGE_MODE","BRIDGE_RUNTIME_VERSION","BRIDGE_ACCESS_PROTOCOL","BOUND_GAME_PACKAGE","BOUND_ORIGIN_IDENTITY","BOUND_NAMESPACE_ID","BOUND_SOURCE_APK_SHA256","BOUND_GAME_VERSION_CODE","BOUND_GAME_VERSION_NAME","MAIN_APP_CERT_SHA256"};
            String[] values={"embedded-v1","1","1",GAME,ORIGIN,"android-"+ORIGIN,SOURCE,"17","v17","c".repeat(64)};
            for(int i=0;i<names.length;i++)m.put(META+names[i],values[i]);
            provider.name=InstalledBridgeResolver.PROVIDER;provider.authority="io.github.xyz1001.pdsync.embedded.g"+ORIGIN.substring(0,32)+".files";provider.packageName=GAME;provider.processName=GAME+":pdsync_bridge";provider.enabled=true;provider.exported=true;provider.applicationInfo=game.applicationInfo;
            game.providers=new ProviderInfo[]{provider};
        }
        public PackageInfo getPackageInfo(String name,int flags)throws NameNotFoundException {if(name.equals(GAME))return game;if(name.equals(main.packageName))return main;throw new NameNotFoundException();}
        public String[] getPackagesForUid(int uid){return uid==200?gameMembers:uid==100?mainMembers:null;}
        public ProviderInfo resolveContentProvider(String authority,int flags){return provider.authority.equals(authority)?provider:null;}
    }
}
