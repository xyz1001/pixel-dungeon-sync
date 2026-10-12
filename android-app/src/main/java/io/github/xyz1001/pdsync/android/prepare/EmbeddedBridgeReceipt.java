package io.github.xyz1001.pdsync.android.prepare;

import java.io.IOException;
import java.util.Properties;

/** Pure receipt checks, kept independent of Android so malformed bindings fail on the host too. */
final class EmbeddedBridgeReceipt {
    static final String MODE = "embedded-v1";
    private EmbeddedBridgeReceipt() {}

    static String authority(GameSourceIdentity identity) {
        return "io.github.xyz1001.pdsync.embedded.g" + identity.originIdentity.substring(0, 32) + ".files";
    }

    static void requireBinding(Properties properties, GameSourceIdentity identity) throws IOException {
        if (!"3".equals(properties.getProperty("format")) || !MODE.equals(properties.getProperty("bridgeMode"))) throw new IOException("Embedded bridge receipt requires format 3 and embedded-v1 mode");
        if (!identity.gamePackage.equals(properties.getProperty("bridgeHostPackage")) || !authority(identity).equals(properties.getProperty("authority"))) throw new IOException("Embedded provider identity receipt mismatch");
        String dex = properties.getProperty("runtimeDexName", "");
        if (!dex.matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")) throw new IOException("Invalid embedded runtime DEX entry name");
        requireHash(properties, "runtimeDexSha256"); requireHash(properties, "mainAppCertSha256");
        if (!"1".equals(properties.getProperty("bridgeRuntimeVersion"))) throw new IOException("Unsupported embedded bridge runtime version");
        for (String field : new String[]{"helperPackage", "sharedUid", "helperSha256", "unsignedHelperSha256", "templateSha256", "helperVersionCode", "helperOnlyUpgrade"}) {
            if (properties.containsKey(field)) throw new IOException("Embedded receipt contains obsolete helper field " + field);
        }
    }

    private static void requireHash(Properties properties, String field) throws IOException {
        if (!properties.getProperty(field, "").matches("[0-9a-f]{64}")) throw new IOException("Invalid embedded receipt digest " + field);
    }
}
