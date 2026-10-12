package io.github.xyz1001.pdsync.android.prepare;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Properties;

/** Runs the real PreparedPair constructor without executing Android framework code. */
public final class PreparationReceiptTest {
    static final String CERT = "a".repeat(64), HASH = "b".repeat(64);
    static int checks;
    static GamePreparationService.PreparedPair read(Properties p) throws Exception {
        Constructor<GamePreparationService.PreparedPair> c = GamePreparationService.PreparedPair.class.getDeclaredConstructor(File.class, Properties.class, InstallCompatibility.Decision.class, InstallCompatibility.Decision.class);
        c.setAccessible(true);
        try { return c.newInstance(new File("/host-test-only/receipt"), p, InstallCompatibility.decide(false,false,false,0,123), InstallCompatibility.decide(false,false,false,0,5)); }
        catch (InvocationTargetException e) { if (e.getCause() instanceof Exception ex) throw ex; throw e; }
    }
    static void rejects(Properties original, String name, String value) throws Exception {
        Properties changed = new Properties(); changed.putAll(original);
        if (value == null) changed.remove(name); else changed.setProperty(name,value);
        checks++;
        try { read(changed); } catch (IOException expected) { return; }
        throw new AssertionError("Accepted invalid receipt field: " + name);
    }
    public static void main(String[] args) throws Exception {
        GameSourceIdentity identity = new GameSourceIdentity("com.example.game", List.of(CERT));
        Properties p = new Properties();
        p.setProperty("id","12345678-1234-1234-1234-123456789abc");
        p.setProperty("gamePackage",identity.gamePackage); p.setProperty("gameLabel","Game");
        p.setProperty("gameVersionCode","123"); p.setProperty("gameVersionName","test");
        p.setProperty("sourceCertificates",CERT); p.setProperty("originIdentity",identity.originIdentity); p.setProperty("namespaceId",identity.namespaceId);
        p.setProperty("helperPackage",identity.helperPackage); p.setProperty("authority",identity.authority); p.setProperty("sharedUid",identity.sharedUid);
        p.setProperty("legacyIdentity","false"); p.setProperty("legacyUnboundHelper","false");
        p.setProperty("apkCount","1"); p.setProperty("originalSha256.0",HASH); p.setProperty("gameSha256.0",HASH);
        for (String key : List.of("sourceApkSetSha256","helperSha256","unsignedHelperSha256","templateSha256","certificateSha256")) p.setProperty(key,HASH);
        p.setProperty("protectedEntryCount","2"); p.setProperty("helperVersionCode","5"); p.setProperty("completeInstalledSet","false");
        var pair = read(p);
        if (!pair.gamePackage.equals(identity.gamePackage) || !pair.namespaceId.equals(identity.namespaceId) || !pair.authority.equals(identity.authority) || pair.gameStrategy != GamePreparationService.InstallStrategy.FRESH_INSTALL) throw new AssertionError("Happy receipt construction failed");
        checks++;
        rejects(p,"namespaceId","android-"+HASH); rejects(p,"originIdentity",HASH); rejects(p,"authority","other.provider");
        rejects(p,"helperPackage","other.helper"); rejects(p,"sharedUid","other.shared"); rejects(p,"sourceCertificates",HASH);
        rejects(p,"gameVersionCode","-1"); rejects(p,"apkCount","0"); rejects(p,"helperVersionCode","0");
        rejects(p,"helperOnlyUpgrade","unknown"); rejects(p,"legacyUnboundHelper","true"); rejects(p,"legacyIdentity","true");
        rejects(p,"gameSha256.0",null); rejects(p,"certificateSha256","bad");
        System.out.println("Preparation receipt checks passed: " + checks);
    }
}
