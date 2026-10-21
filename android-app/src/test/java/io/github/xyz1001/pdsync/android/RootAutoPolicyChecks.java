package io.github.xyz1001.pdsync.android;

/** Existing retry and cancellation policy remains independent of game process state. */
public final class RootAutoPolicyChecks {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        for (boolean stopped : new boolean[]{false, true}) {
            for (boolean active : new boolean[]{false, true}) {
                for (String token : new String[]{null, "old", "current"}) {
                    check(AutoSyncPolicy.mayPublish(stopped, active, token, "current")
                                    == (!stopped && active && "current".equals(token)),
                            "Stopped, inactive or superseded sessions must not publish");
                }
            }
        }
        AutoSyncPolicy policy = new AutoSyncPolicy(0, false);
        check(policy.nextTransientRetryMillis() == 3000, "First retry keeps existing backoff");
        check(policy.nextTransientRetryMillis() == 6000, "Second retry keeps existing backoff");
        check(policy.nextTransientRetryMillis() == 9000, "Third retry keeps existing backoff");
        check(policy.nextTransientRetryMillis() == -1, "Retries remain bounded");
        System.out.println("Automatic retry/cancellation policy checks passed: " + checks + " assertions; no service, process, or device execution.");
    }
}
