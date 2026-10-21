package io.github.xyz1001.pdsync.bridge;

import java.util.Properties;


public final class BackupCleanupPolicy {
    private BackupCleanupPolicy() {
    }

    public static boolean validId(String str) {
        return str != null && str.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    public static boolean validFinalizedMarker(String str, Properties properties) {
        return validId(str) && properties != null && str.equals(properties.getProperty("backupId")) && "verified-finalized".equals(properties.getProperty("state")) && properties.getProperty("fingerprint", "").matches("[0-9a-f]{64}");
    }

    public static boolean mayReclaim(String str, Properties properties, boolean z) {
        // Historical archives are retained; new restores create no backups.
        return false;
    }
}
