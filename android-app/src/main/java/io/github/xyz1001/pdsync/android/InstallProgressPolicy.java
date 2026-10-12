package io.github.xyz1001.pdsync.android;

/* loaded from: classes.dex */
public final class InstallProgressPolicy {
    public static boolean mayResetExpiredConfirmation(boolean z, boolean z2) {
        return z && !z2;
    }

    /** Cleanup identity is deliberately separate from permission to install or resume a request. */
    public static boolean cleanupTargetMatches(boolean embedded, String gamePackage, String helperPackage, String pendingPackage) {
        return pendingPackage != null && !pendingPackage.isEmpty()
                && (pendingPackage.equals(gamePackage) || (!embedded && pendingPackage.equals(helperPackage)));
    }

    public static boolean mayCancelObsoleteLegacy(boolean dialogClosedByUser, boolean embedded,
            boolean targetMatches, boolean uninstall, boolean unchangedInstalledIdentity) {
        return dialogClosedByUser && !embedded && targetMatches && (!uninstall || unchangedInstalledIdentity);
    }

    public static boolean requiresRebindAfterLegacyRecovery(boolean z, boolean z2) {
        return z && !z2;
    }

    public static boolean uninstallSettled(boolean z, boolean z2) {
        return !z || z2;
    }

    private InstallProgressPolicy() {
    }

    public static boolean acceptsCallback(boolean z, int i, int i2, String str, String str2) {
        if (str == null || str.isEmpty() || !str.equals(str2)) {
            return false;
        }
        if (z) {
            return true;
        }
        return i >= 0 && i == i2;
    }
}
