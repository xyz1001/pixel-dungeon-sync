package io.github.xyz1001.pdsync.android;

import pdsync.android.app.AndroidGameBridge;

/**
 * Android-specific access boundary around the core's save-transfer contract.
 * Mode and capabilities describe the implementation, never current permission,
 * package trust, save readiness, or a successful device check.
 */
public interface GameAccessBackend extends AndroidGameBridge {
    enum Mode {
        EMBEDDED_BRIDGE,
        LEGACY_HELPER_BRIDGE,
        ROOT_DIRECT
    }

    Mode mode();
    Capabilities capabilities();

    /** Verifies current access or throws; a capability flag is not a substitute. */
    String inspectReady();

    /** Recovers and verifies an interrupted backup, or throws without claiming success. */
    String recoverInterruptedBackup();

    /** A failed or unsupported observation must not report reliable readiness. */
    Observation observeLifecycle();

    /** Static implementation support only. Each operation still verifies access. */
    final class Capabilities {
        public final boolean exportSupported;
        public final boolean restoreSupported;
        public final boolean lifecycleObservation;
        public final boolean interruptedBackupRecovery;

        public Capabilities(boolean exportSupported, boolean restoreSupported,
                boolean lifecycleObservation, boolean interruptedBackupRecovery) {
            this.exportSupported = exportSupported;
            this.restoreSupported = restoreSupported;
            this.lifecycleObservation = lifecycleObservation;
            this.interruptedBackupRecovery = interruptedBackupRecovery;
        }
    }

    /** Immutable observation evidence; unknown state is deliberately all false. */
    final class Observation {
        public final boolean foreground;
        public final boolean processPresent;
        public final boolean ready;
        public final boolean reliable;

        public Observation(boolean foreground, boolean processPresent, boolean ready, boolean reliable) {
            this.foreground = foreground;
            this.processPresent = processPresent;
            this.ready = ready;
            this.reliable = reliable;
        }

        public static Observation unknown() {
            return new Observation(false, false, false, false);
        }
    }
}
