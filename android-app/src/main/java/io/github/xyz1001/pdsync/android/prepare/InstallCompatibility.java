package io.github.xyz1001.pdsync.android.prepare;

public final class InstallCompatibility {

    public enum Strategy {
        FRESH_INSTALL,
        COMPATIBLE_UPDATE,
        REQUIRES_UNINSTALL
    }

    public static final class Decision {
        public final String reason;
        public final Strategy strategy;

        Decision(Strategy strategy, String str) {
            this.strategy = strategy;
            this.reason = str;
        }
    }

    private InstallCompatibility() {
    }

    public static Decision decide(boolean z, boolean z2, boolean z3, long j, long j2) {
        if (!z) {
            return new Decision(Strategy.FRESH_INSTALL, "Package is not currently installed.");
        }
        if (!z2) {
            return new Decision(Strategy.REQUIRES_UNINSTALL, "The installed package has a different signing certificate. Back up game saves outside the game before uninstalling; Android cannot update it with this local key.");
        }
        if (!z3) {
            return new Decision(Strategy.REQUIRES_UNINSTALL, "The installed package has a different shared-UID configuration. Android requires uninstall and reinstall; game data will be deleted.");
        }
        if (j > j2) {
            return new Decision(Strategy.REQUIRES_UNINSTALL, "The installed version is newer than the prepared version. A downgrade is not offered as an update; preserve saves before any uninstall.");
        }
        return new Decision(Strategy.COMPATIBLE_UPDATE, "Signing certificate, shared UID, and version are compatible with an update; Android still makes the final install decision.");
    }
}
