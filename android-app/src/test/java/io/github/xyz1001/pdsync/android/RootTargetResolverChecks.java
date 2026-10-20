package io.github.xyz1001.pdsync.android;

import android.content.Context;
import android.content.pm.*;
import android.os.Bundle;
import io.github.xyz1001.pdsync.android.prepare.GamePreparationService;
import io.github.xyz1001.pdsync.android.prepare.GameSourceIdentity;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Runs the actual resolver and binding decisions, with only external boundaries doubled. */
public final class RootTargetResolverChecks {
    private static final String GAME="com.shatteredpixel.shatteredpixeldungeon";
    private static final String MAIN="io.github.xyz1001.pdsync.android";
    private static final String PEER="io.github.xyz1001.pdsync.bridge.gdc5b5819";
    private static final String APK="/data/app/game/base.apk";
    private static final String SPLIT="/data/app/game/split_config.apk";
    private static final String ORIGINAL="a".repeat(64);
    private static int checks;
    interface Action { void run() throws Exception; }
    public static void main(String[] args)throws Exception {
        test("ordinary installed package",()->new Fixture().select());
        test("declared shared UID with one installed member",()-> {
            Fixture f=new Fixture();f.info.sharedUserId="io.github.xyz1001.pdsync.shared.gdc5b5819";
            check(f.select().uid==10042,"Selected actual shared UID");
        });
        test("declared shared UID with helper peer",()-> {
            Fixture f=new Fixture();f.shared();SelectedRootGame selected=f.select();
            check(selected.apkHashes.keySet().equals(Collections.singleton(APK)),"No sibling APK paths");
            check(selected.dataDir.equals("/data/user/0/"+GAME),"Package-specific dataDir");
            check(f.pm.queries.equals(Arrays.asList(GAME,GAME)),"Never queries sibling packages");
        });
        test("membership independent of list order",()-> {
            Fixture f=new Fixture();f.shared();f.pm.members=new String[]{PEER,GAME};f.select();
        });
        test("multiple members without declared sharedUserId",()-> {
            Fixture f=new Fixture();f.pm.members=new String[]{GAME,PEER};f.select();
        });
        test("null UID membership rejected",()-> {Fixture f=new Fixture();f.pm.members=null;reject(f::select);});
        test("empty UID membership rejected",()-> {Fixture f=new Fixture();f.pm.members=new String[0];reject(f::select);});
        test("wrong UID membership rejected",()-> {Fixture f=new Fixture();f.pm.members=new String[]{PEER};reject(f::select);});
        test("main app UID rejected",()-> {Fixture f=new Fixture();f.info.applicationInfo.uid=10001;reject(f::select);});
        test("other Android user rejected",()-> {Fixture f=new Fixture();f.info.applicationInfo.uid=110042;reject(f::select);});
        test("main package rejected",()-> {Fixture f=new Fixture();reject(()->f.resolver.selectInstalled(MAIN));});
        test("PM package mismatch rejected",()-> {Fixture f=new Fixture();f.info.packageName=PEER;reject(f::select);});
        test("PM application package mismatch rejected",()-> {Fixture f=new Fixture();f.info.applicationInfo.packageName=PEER;reject(f::select);});
        test("disabled package rejected",()-> {Fixture f=new Fixture();f.info.applicationInfo.enabled=false;reject(f::select);});
        test("shared sibling dataDir rejected",()-> {Fixture f=new Fixture();f.shared();f.info.applicationInfo.dataDir="/data/user/0/"+PEER;reject(f::select);});
        test("non-install APK path rejected",()-> {
            Fixture f=new Fixture();f.info.applicationInfo.sourceDir="/sdcard/base.apk";
            PackageTrust.contents.put("/sdcard/base.apk",new byte[]{1});reject(f::select);
        });
        test("missing APK rejected",()-> {Fixture f=new Fixture();PackageTrust.contents.clear();reject(f::select);});
        test("current signer unavailable rejected",()-> {Fixture f=new Fixture();f.info.signingInfo=null;reject(f::select);});
        test("same shared target remains selected",()-> {Fixture f=new Fixture();f.shared();f.resolver.requireUnchanged(f.select());});
        test("membership removed after selection rejected",()-> {
            Fixture f=new Fixture();f.shared();SelectedRootGame selected=f.select();f.pm.members=new String[]{PEER};
            reject(()->f.resolver.requireUnchanged(selected));
        });
        test("same UID sibling addition does not broaden binding",()-> {
            Fixture f=new Fixture();SelectedRootGame selected=f.select();f.pm.members=new String[]{GAME,PEER};
            f.resolver.requireUnchanged(selected);
        });
        test("signer changed after selection rejected",()-> {
            Fixture f=new Fixture();SelectedRootGame selected=f.select();
            f.info.signingInfo.signatures=new Signature[]{new Signature(new byte[]{9})};reject(()->f.resolver.requireUnchanged(selected));
        });
        test("APK bytes changed after selection rejected",()-> {
            Fixture f=new Fixture();SelectedRootGame selected=f.select();PackageTrust.contents.put(APK,new byte[]{9});
            reject(()->f.resolver.requireUnchanged(selected));
        });
        test("PM metadata changed between snapshots rejected",()-> {
            Fixture f=new Fixture();f.pm.onSecondRead=()->f.info.lastUpdateTime++;reject(f::select);
        });
        test("missing receipt uses actual signer even with injection markers",()-> {
            Fixture f=new Fixture();f.shared();f.markers();actualSource(f.select());
        });
        test("invalid key or malformed receipt does not prevent Root selection",()-> {
            Fixture f=new Fixture();f.markers();GamePreparationService.reloadFailure=new SecurityException("Keystore/receipt unavailable");
            actualSource(f.select());
        });
        test("valid exact receipt inherits original identity",()-> {
            Fixture f=new Fixture();GamePreparationService.PreparedPair receipt=f.receipt();SelectedRootGame selected=f.select();
            check(selected.retainedProvenance,"Validated provenance retained");
            check(selected.sourceSignerCertificateSha256.equals(Collections.singletonList(ORIGINAL)),"Original signer retained");
            check(selected.sourceApkSetSha256.equals(receipt.sourceApkSetSha256),"Original APK-set retained");
            check(receipt.validationCount==2,"Validated on each snapshot");
            check(!selected.originIdentity.equals(new GameSourceIdentity(GAME,selected.currentSignerCertificateSha256).originIdentity),
                    "Original namespace only with exact valid receipt");
        });
        test("invalid matching receipt falls back without deleting record",()-> {
            Fixture f=new Fixture();GamePreparationService.PreparedPair receipt=f.receipt();receipt.invalidReceipt=true;
            actualSource(f.select());check(GamePreparationService.retained==receipt,"Receipt is preserved");
        });
        test("receipt for another package cannot supply origin",()-> {
            Fixture f=new Fixture();f.receipt().gamePackage=PEER;actualSource(f.select());
        });
        test("receipt for another version cannot supply origin",()-> {
            Fixture f=new Fixture();f.receipt().gameVersionCode++;actualSource(f.select());
        });
        test("receipt for another installed signer cannot supply origin",()-> {
            Fixture f=new Fixture();f.receipt().signingCertificateSha256=ORIGINAL;actualSource(f.select());
        });
        test("receipt for different APK bytes cannot supply origin",()-> {
            Fixture f=new Fixture();f.receipt();PackageTrust.contents.put(APK,new byte[]{4});actualSource(f.select());
        });
        test("full split set required for receipt identity",()-> {
            Fixture f=new Fixture();f.receipt();f.info.applicationInfo.splitSourceDirs=new String[]{SPLIT};
            f.info.splitNames=new String[]{"config"};PackageTrust.contents.put(SPLIT,new byte[]{5});
            SelectedRootGame selected=f.select();actualSource(selected);check(selected.apkHashes.size()==2,"Both installed APKs bound");
        });
        test("missing split identity rejected",()-> {
            Fixture f=new Fixture();f.info.applicationInfo.splitSourceDirs=new String[]{SPLIT};
            PackageTrust.contents.put(SPLIT,new byte[]{5});reject(f::select);
        });
        test("unsigned retained provenance keeps namespace and no fabricated signer",()-> {
            Fixture f=new Fixture(); GamePreparationService.PreparedPair receipt=f.receipt();
            receipt.sourceProvenance=GameSourceIdentity.Provenance.UNSIGNED;
            receipt.sourceSignerCertificateSha256=Collections.emptyList(); receipt.originIdentity=GameSourceIdentity.unsigned(GAME).originIdentity;
            SelectedRootGame selected=f.select(); check(selected.retainedProvenance,"retained unsigned");
            check(selected.sourceSignerCertificateSha256.isEmpty(),"unknown signer stays empty");
            check(selected.originIdentity.equals(receipt.originIdentity),"unsigned namespace retained");
            roundTrip(selected);
        });
        test("inherited receipt preserves prior origin with unknown original signer",()-> {
            Fixture f=new Fixture(); GamePreparationService.PreparedPair receipt=f.receipt();
            receipt.sourceProvenance=GameSourceIdentity.Provenance.INHERITED_EMBEDDED;
            receipt.sourceSignerCertificateSha256=Collections.emptyList();
            SelectedRootGame selected=f.select(); check(selected.originIdentity.equals(receipt.originIdentity),"origin retained");
            check(selected.sourceSignerCertificateSha256.isEmpty(),"no fake signer"); roundTrip(selected);
        });
        test("recognized bridge without receipt preserves source namespace",()-> {
            Fixture f=new Fixture(); f.markers();
            String origin="d".repeat(64);String signer=PackageTrust.hex(MessageDigest.getInstance("SHA-256").digest(new byte[]{1,2,3}));
            InstalledBridgeResolver.binding=new InstalledBridgeBinding(GAME,"Game","3.3.8",896,origin,"android-"+origin,
                    "b".repeat(64),"io.github.xyz1001.pdsync.embedded.g"+origin.substring(0,32)+".files","1","1",signer,
                    10042,"/data/user/0/"+GAME,100,200,Collections.singletonList(APK));
            SelectedRootGame selected=f.select();check(selected.sourceProvenance==GameSourceIdentity.Provenance.INHERITED_EMBEDDED,"explicit inherited");
            check(selected.originIdentity.equals(origin),"no receipt namespace remains");roundTrip(selected);
        });
        test("v1 selection restores without namespace migration",()-> {
            Fixture f=new Fixture();SelectedRootGame selected=f.select();
            java.nio.file.Path dir=java.nio.file.Files.createTempDirectory("root-provenance-v1-");
            try { RootSelectionStore store=new RootSelectionStore(dir.toFile());store.save(selected);
                java.nio.file.Path path=dir.resolve("root-selection-v1.properties");Properties values=new Properties();
                try(java.io.InputStream in=java.nio.file.Files.newInputStream(path)){values.load(in);}
                values.setProperty("version","1");values.remove("sourceProvenance");values.remove("originIdentity");
                try(java.io.OutputStream out=java.nio.file.Files.newOutputStream(path)){values.store(out,"legacy");}
                check(store.load().matches(selected),"v1 identity survives");store.clear();
            } finally {java.nio.file.Files.delete(dir);}
        });
        System.out.println("RootTargetResolverChecks: "+checks+" behavior checks passed");
    }
    static void roundTrip(SelectedRootGame selected)throws Exception {
        java.nio.file.Path dir=java.nio.file.Files.createTempDirectory("root-provenance-");
        try {RootSelectionStore store=new RootSelectionStore(dir.toFile());store.save(selected);
            check(store.load().matches(selected),"typed provenance round trip");
            check(store.load().namespaceId.equals(selected.namespaceId),"same namespace");store.clear();}
        finally {java.nio.file.Files.delete(dir);}
    }
    static void actualSource(SelectedRootGame selected)throws Exception {
        check(!selected.retainedProvenance,"No fabricated original provenance");
        check(selected.sourceSignerCertificateSha256.equals(selected.currentSignerCertificateSha256),"Actual installed signer retained");
        check(selected.originIdentity.equals(new GameSourceIdentity(GAME,selected.currentSignerCertificateSha256).originIdentity),
                "Actual signer namespace");
        check(!selected.sourceApkSetSha256.equals("b".repeat(64)),"No unverified receipt APK-set identity");
    }
    static void test(String name,Action action)throws Exception {
        try {action.run();checks++;}catch(Throwable failure){throw new AssertionError(name,failure);}
    }
    static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
    static void reject(Action action)throws Exception {
        try {action.run();}catch(Exception expected){return;}throw new AssertionError("Unsafe selection unexpectedly accepted");
    }
    static final class Fixture {
        final PackageInfo info=new PackageInfo();
        final FakePm pm=new FakePm(info);
        final RootTargetResolver resolver;
        Fixture() {
            android.os.Build.VERSION.SDK_INT=36;android.os.Process.uid=10001;
            GamePreparationService.retained=null;GamePreparationService.reloadFailure=null;InstalledBridgeResolver.binding=null;
            PackageTrust.contents.clear();PackageTrust.contents.put(APK,"installed game".getBytes(StandardCharsets.UTF_8));
            info.packageName=GAME;info.applicationInfo=new ApplicationInfo();info.applicationInfo.packageName=GAME;
            info.applicationInfo.enabled=true;info.applicationInfo.uid=10042;info.applicationInfo.dataDir="/data/user/0/"+GAME;
            info.applicationInfo.sourceDir=APK;info.version=896;info.versionName="3.3.8";
            info.firstInstallTime=100;info.lastUpdateTime=200;
            info.signingInfo=new SigningInfo();info.signingInfo.signatures=new Signature[]{new Signature(new byte[]{1,2,3})};
            resolver=new RootTargetResolver(new Context() {
                public PackageManager getPackageManager(){return pm;}
                public String getPackageName(){return MAIN;}
                public File getFilesDir(){throw new AssertionError("No private file scan");}
            });
        }
        void shared(){info.sharedUserId="io.github.xyz1001.pdsync.shared.gdc5b5819";pm.members=new String[]{GAME,PEER};}
        void markers() {
            info.applicationInfo.metaData=new Bundle();
            info.applicationInfo.metaData.put("io.github.xyz1001.pdsync.sourceSigner",ORIGINAL);
            info.applicationInfo.metaData.put("io.github.xyz1001.pdsync.namespaceId","forged-original");
            ProviderInfo provider=new ProviderInfo();provider.name="io.github.xyz1001.pdsync.bridge.SaveBridgeProvider";
            info.providers=new ProviderInfo[]{provider};
        }
        SelectedRootGame select()throws Exception{return resolver.selectInstalled(GAME);}
        GamePreparationService.PreparedPair receipt()throws Exception {
            GamePreparationService.PreparedPair result=new GamePreparationService.PreparedPair();
            result.bridgeMode="embedded-v1";result.gamePackage=GAME;result.gameVersionCode=896;
            result.signingCertificateSha256=PackageTrust.hex(MessageDigest.getInstance("SHA-256").digest(new byte[]{1,2,3}));
            result.sourceSignerCertificateSha256=Collections.singletonList(ORIGINAL);result.originIdentity=new GameSourceIdentity(GAME,result.sourceSignerCertificateSha256).originIdentity;result.sourceApkSetSha256="b".repeat(64);
            String staged="/data/app/prepared/base.apk";PackageTrust.contents.put(staged,PackageTrust.contents.get(APK).clone());
            result.gameApks=Collections.singletonList(PackageTrust.apk(staged));
            GamePreparationService.retained=result;return result;
        }
    }
    static final class FakePm extends PackageManager {
        final PackageInfo info;String[] members={GAME};final List<String> queries=new ArrayList<>();Runnable onSecondRead;
        FakePm(PackageInfo info){this.info=info;}
        public PackageInfo getPackageInfo(String name,int flags) {
            queries.add(name);check(GAME.equals(name),"Unexpected sibling lookup");
            if(queries.size()==2&&onSecondRead!=null)onSecondRead.run();return info;
        }
        public String[] getPackagesForUid(int uid){return members;}
        public ProviderInfo resolveContentProvider(String authority,int flags){throw new AssertionError("No bridge provider access");}
    }
}
