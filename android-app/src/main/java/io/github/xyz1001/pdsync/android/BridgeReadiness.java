package io.github.xyz1001.pdsync.android;

/* loaded from: classes.dex */
final class BridgeReadiness {
    private BridgeReadiness() {
    }

    static String failureCode(boolean z, boolean z2, Boolean bool) {
        if (z2) {
            return "FIRST_LAUNCH_NEEDED";
        }
        if (Boolean.FALSE.equals(bool)) {
            return "UNRECOGNIZED_SAVE_LAYOUT";
        }
        if (z) {
            return null;
        }
        return "SAVE_NOT_READY";
    }
}
