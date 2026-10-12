package io.github.xyz1001.pdsync.android;

/** Production service's pure evidence gate and existing policy, without creating a service. */
public final class RootAutoPolicyChecks {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        for (GameAccessBackend.Mode mode : GameAccessBackend.Mode.values()) {
            for (int flags = 0; flags < 16; flags++) {
                GameAccessBackend.Observation observation = new GameAccessBackend.Observation(
                        (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0);
                boolean accepted = AutoSyncService.reliableForAutomaticObservation(mode, observation);
                check(accepted == (observation.reliable && (mode != GameAccessBackend.Mode.ROOT_DIRECT
                                || observation.foreground || observation.ready)),
                        "Only actual reliable foreground/quiet evidence enters Root exit policy");
                AutoSyncPolicy policy = new AutoSyncPolicy(0, true);
                check((policy.observe(accepted, observation.foreground, 1000) == AutoSyncPolicy.Step.UPLOAD)
                                == (accepted && !observation.foreground),
                        "Automatic publication still uses existing exit policy");
            }
        }
        GameAccessBackend.Observation cached = new GameAccessBackend.Observation(false, true, true, true);
        GameAccessBackend.Observation absent = new GameAccessBackend.Observation(false, false, true, true);
        check(AutoSyncService.reliableForAutomaticObservation(GameAccessBackend.Mode.ROOT_DIRECT, cached),
                "Reliable CACHED accepts normal exit without force-stopping or requiring absence");
        check(AutoSyncService.reliableForAutomaticObservation(GameAccessBackend.Mode.ROOT_DIRECT, absent), "Reliable ABSENT accepts quiet state");
        check(!AutoSyncService.reliableForAutomaticObservation(GameAccessBackend.Mode.ROOT_DIRECT,
                new GameAccessBackend.Observation(false, true, false, true)), "Active background game is not quiet");
        AutoSyncPolicy unknown = new AutoSyncPolicy(0, true);
        for (int attempt = 1; attempt <= 20; attempt++) {
            AutoSyncPolicy.Step step = unknown.observe(AutoSyncService.reliableForAutomaticObservation(
                    GameAccessBackend.Mode.ROOT_DIRECT, GameAccessBackend.Observation.unknown()), false, attempt * 1500L);
            check(step == (attempt == 20 ? AutoSyncPolicy.Step.STOP_UNKNOWN : AutoSyncPolicy.Step.WAIT),
                    "Unknown evidence remains bounded and never publishes");
        }
        System.out.println("Root automatic-observation policy checks passed: " + checks + " assertions; no service instance, su, or device execution.");
    }
}
