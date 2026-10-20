package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import io.github.xyz1001.pdsync.android.InstallationReadiness;

/* loaded from: classes.dex */
final class HelperActivationPolicy {
    static final String ACTIVITY = "io.github.xyz1001.pdsync.bridge.ActivationActivity";
    static final long BUNDLED_HELPER_VERSION = 5;
    static final String PERMISSION = "io.github.xyz1001.pdsync.android.ACCESS_BRIDGE";

    private HelperActivationPolicy() {
    }

    static boolean canOffer(InstallationReadiness installationReadiness, String str, boolean z) {
        return installationReadiness != null && installationReadiness.installationVerified() && !installationReadiness.systemRequestActive && !z && installationReadiness.access == InstallationReadiness.AccessStatus.IPC_ERROR && "PROVIDER_ACQUIRE_NULL".equals(str);
    }

    static boolean canUpdate(InstallationReadiness installationReadiness, boolean z, long j, long j2) {
        return (installationReadiness == null || !installationReadiness.installationVerified() || installationReadiness.systemRequestActive || installationReadiness.restorePending || installationReadiness.access == InstallationReadiness.AccessStatus.RECOVERY_NEEDED || installationReadiness.access == InstallationReadiness.AccessStatus.GAME_BUSY || installationReadiness.access == InstallationReadiness.AccessStatus.SYNC_BUSY || z || j <= 0 || j2 <= j) ? false : true;
    }

    static void requireEmbeddedActivity(String game, String name, String host, String process, String permission,
            boolean exported, boolean enabled, boolean applicationEnabled, int activityUid, int gameUid) {
        if (game == null || !game.equals(host) || !(game + ":pdsync_bridge").equals(process)
                || !ACTIVITY.equals(name) || (permission != null && !permission.isEmpty())
                || !exported || !enabled || !applicationEnabled || activityUid != gameUid)
            throw new SecurityException(AndroidMessages.HELPER_ACTIVATION_POLICY_01.text());
    }

    static void requireActivity(String str, String str2, String str3, String str4, String str5, boolean z, boolean z2, boolean z3, int i, int i2) {
        if (str == null || !str.equals(str3) || !str.equals(str4) || !"io.github.xyz1001.pdsync.bridge.ActivationActivity".equals(str2) || !"io.github.xyz1001.pdsync.android.ACCESS_BRIDGE".equals(str5) || !z || !z2 || !z3 || i != i2) {
            throw new SecurityException(AndroidMessages.HELPER_ACTIVATION_POLICY_02.text());
        }
    }
}
