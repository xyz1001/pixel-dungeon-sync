package io.github.xyz1001.pdsync.android;

/* loaded from: classes.dex */
public final class InstallProgressPolicy {
    public static boolean mayResetExpiredConfirmation(boolean z, boolean z2) {
        return z && !z2;
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
