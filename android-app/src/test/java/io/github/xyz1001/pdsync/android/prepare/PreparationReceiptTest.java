package io.github.xyz1001.pdsync.android.prepare;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Properties;

/** Exercises the real PreparedPair constructor without executing Android framework code. */
public final class PreparationReceiptTest {
    static final String CERT = "a".repeat(64), HASH = "b".repeat(64);
    static int checks;
    static GamePreparationService.PreparedPair read(Properties p) throws Exception {
        Constructor<GamePreparationService.PreparedPair> c = GamePreparationService.PreparedPair.class.getDeclaredConstructor(File.class, Properties.class, InstallCompatibility.Decision.class, InstallCompatibility.Decision.class);
        c.setAccessible(true);
        try { return c.newInstance(new File("/host-test-only/receipt"), p, InstallCompatibility.decide(false,false,false,0,123), "embedded-v1".equals(p.getProperty("bridgeMode")) ? null : InstallCompatibility.decide(false,false,false,0,5)); }
        catch (InvocationTargetException e) { if (e.getCause() instanceof Exception ex) throw ex; throw e; }
    }
    static void rejects(Properties original, String name, String value) throws Exception {
        Properties changed = new Properties(); changed.putAll(original);
        if (value == null) changed.remove(name); else changed.setProperty(name,value);
        checks++;
        try { read(changed); } catch (IOException expected) { return; }
        throw new AssertionError("Accepted invalid receipt field: " + name + "=" + value);
    }
    static void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
    static Properties sourceReceipt(GameSourceIdentity identity) {
        Properties p = new Properties();
        p.setProperty("id","12345678-1234-1234-1234-123456789abc");
        p.setProperty("gamePackage",identity.gamePackage); p.setProperty("gameLabel","Game");
        p.setProperty("gameVersionCode","123"); p.setProperty("gameVersionName","test");
        p.setProperty("sourceCertificates",CERT); p.setProperty("originIdentity",identity.originIdentity); p.setProperty("namespaceId",identity.namespaceId);
        p.setProperty("apkCount","1"); p.setProperty("originalSha256.0",HASH); p.setProperty("gameSha256.0",HASH);
        p.setProperty("sourceApkSetSha256",HASH); p.setProperty("certificateSha256",HASH);
        p.setProperty("protectedEntryCount","2"); p.setProperty("completeInstalledSet","false");
        return p;
    }
    public static void main(String[] args) throws Exception {
        GameSourceIdentity identity = new GameSourceIdentity("com.example.game", List.of(CERT));
        Properties p = sourceReceipt(identity);
        p.setProperty("helperPackage",identity.helperPackage); p.setProperty("authority",identity.authority); p.setProperty("sharedUid",identity.sharedUid);
        p.setProperty("legacyIdentity","false"); p.setProperty("legacyUnboundHelper","false");
        for (String key : List.of("helperSha256","unsignedHelperSha256","templateSha256")) p.setProperty(key,HASH);
        p.setProperty("helperVersionCode","5");
        var pair = read(p);
        check(pair.gamePackage.equals(identity.gamePackage) && pair.namespaceId.equals(identity.namespaceId) && pair.authority.equals(identity.authority) && pair.gameStrategy == GamePreparationService.InstallStrategy.FRESH_INSTALL, "Happy legacy receipt construction failed");
        check(!pair.embeddedBridge() && pair.bridgeHostPackage.equals(identity.helperPackage), "Legacy mode must remain explicit");
        rejects(p,"namespaceId","android-"+HASH); rejects(p,"originIdentity",HASH); rejects(p,"authority","other.provider");
        rejects(p,"helperPackage","other.helper"); rejects(p,"sharedUid","other.shared"); rejects(p,"sourceCertificates",HASH);
        rejects(p,"gameVersionCode","-1"); rejects(p,"apkCount","0"); rejects(p,"helperVersionCode","0");
        rejects(p,"helperOnlyUpgrade","unknown"); rejects(p,"legacyUnboundHelper","true"); rejects(p,"legacyIdentity","true");
        rejects(p,"gameSha256.0",null); rejects(p,"certificateSha256","bad");

        Properties embedded = sourceReceipt(identity);
        embedded.setProperty("format","3"); embedded.setProperty("bridgeMode","embedded-v1");
        embedded.setProperty("bridgeHostPackage",identity.gamePackage); embedded.setProperty("authority",EmbeddedBridgeReceipt.authority(identity));
        embedded.setProperty("runtimeDexName","classes2.dex"); embedded.setProperty("runtimeDexSha256",HASH);
        embedded.setProperty("bridgeRuntimeVersion","1"); embedded.setProperty("mainAppCertSha256",CERT);
        pair = read(embedded);
        check(pair.embeddedBridge() && pair.bridgeHostPackage.equals(pair.gamePackage), "Embedded provider belongs to game package");
        check(pair.authority.equals("io.github.xyz1001.pdsync.embedded.g" + identity.originIdentity.substring(0,32) + ".files"), "Deterministic origin-bound authority");
        check(pair.helperApk == null && pair.helperPackage == null && pair.helperStrategy == null && pair.helperStrategyReason == null && pair.sharedUid == null && pair.helperVersionCode == 0 && !pair.helperOnlyUpgrade, "Embedded mode cannot fabricate successful helper state");
        check(pair.runtimeDexName.equals("classes2.dex") && pair.runtimeDexSha256.equals(HASH) && pair.mainAppCertSha256.equals(CERT) && pair.bridgeRuntimeVersion.equals("1"), "Runtime and caller cert are bound in receipt");
        check(pair.gameApks.size() == 1 && pair.gameApk.equals(pair.gameApks.get(0)), "Single game APK set remains installable");
        check(pair.originIdentity.equals(identity.originIdentity) && pair.namespaceId.equals(identity.namespaceId), "Embedding preserves source/cloud namespace");
        for (String field : List.of("helperPackage","sharedUid","helperSha256","unsignedHelperSha256","templateSha256","helperVersionCode","helperOnlyUpgrade")) rejects(embedded,field,"obsolete");
        rejects(embedded,"bridgeMode","unknown"); rejects(embedded,"bridgeMode",null); rejects(embedded,"format","2"); rejects(embedded,"format",null);
        rejects(embedded,"bridgeHostPackage","other.game"); rejects(embedded,"authority",identity.authority);
        rejects(embedded,"mainAppCertSha256","bad"); rejects(embedded,"runtimeDexSha256",null); rejects(embedded,"bridgeRuntimeVersion","2");
        rejects(embedded,"runtimeDexName","classes1.dex"); rejects(embedded,"runtimeDexName","classes02.dex"); rejects(embedded,"runtimeDexName","../classes2.dex");
        rejects(embedded,"legacyIdentity","true"); rejects(embedded,"legacyUnboundHelper","true");
        rejects(embedded,"gameSha256.0",null); rejects(embedded,"originalSha256.0",null); rejects(embedded,"sourceApkSetSha256",null); rejects(embedded,"id","../receipt");
        Properties split = new Properties(); split.putAll(embedded); split.setProperty("apkCount","2");
        split.setProperty("originalSha256.1",CERT); split.setProperty("gameSha256.1",HASH);
        check(read(split).gameApks.size() == 2, "Embedded receipt retains full base and split set");
        rejects(split,"gameSha256.1",null); rejects(split,"originalSha256.1",null);
        Properties noDex = new Properties(); noDex.putAll(embedded); noDex.setProperty("runtimeDexName","classes.dex");
        check(read(noDex).runtimeDexName.equals("classes.dex"), "Resource/native-only source may receive first DEX");
        System.out.println("Preparation receipt checks passed: " + checks);
    }
}
