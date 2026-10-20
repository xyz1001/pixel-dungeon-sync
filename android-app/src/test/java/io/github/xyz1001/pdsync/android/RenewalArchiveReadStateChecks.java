package io.github.xyz1001.pdsync.android;

/** Executes the production queue; account refresh and navigation do no archive I/O here. */
public final class RenewalArchiveReadStateChecks {
    private static final String GAME = "game-a", SPACE = "namespace-a";
    private static final Object ACCOUNT = new Object();

    public static void main(String[] args) {
        unavailableUntilFreshActive();
        activeHomeDoesNotReRead();
        repeatedRefreshIsOneShot();
        pendingAndFailureReplaceEarlierSuccess();
        scopesUseAccountIdentityAndExactSelection();
        cancellationDropsPendingAndLateResults();
        System.out.println("Renewal archive read state checks passed");
    }

    private static void unavailableUntilFreshActive() {
        RenewalArchiveReadState state = new RenewalArchiveReadState();
        state.open(ACCOUNT, GAME, SPACE, false);
        check(!state.take(ACCOUNT, GAME, SPACE), "unavailable HOME cannot read from cached account state");
        state.started();
        check(!state.take(ACCOUNT, GAME, SPACE), "pending account refresh cannot read");
        state.result(false);
        check(!state.take(ACCOUNT, GAME, SPACE), "expired or failed account refresh cannot read");
        state.started();
        state.result(true);
        check(state.take(ACCOUNT, GAME, SPACE), "fresh active recovers unavailable HOME");
        check(!state.take(ACCOUNT, GAME, SPACE), "recovery is consumed exactly once");
    }

    private static void activeHomeDoesNotReRead() {
        RenewalArchiveReadState state = new RenewalArchiveReadState();
        state.open(ACCOUNT, GAME, SPACE, true);
        state.started();
        state.result(true);
        check(!state.take(ACCOUNT, GAME, SPACE), "already available HOME requires no recovery read");
        state.result(false);
        state.started();
        state.result(true);
        check(state.take(ACCOUNT, GAME, SPACE), "later unavailable-to-active transition does need recovery");
    }

    private static void repeatedRefreshIsOneShot() {
        RenewalArchiveReadState state = new RenewalArchiveReadState();
        state.open(ACCOUNT, GAME, SPACE, false);
        state.result(true);
        state.result(true);
        state.open(ACCOUNT, GAME, SPACE, false);
        state.started();
        state.result(true);
        check(state.take(ACCOUNT, GAME, SPACE), "repeated refreshes retain one recovery");
        for (int i = 0; i < 3; i++) {
            state.started();
            state.result(true);
            state.open(ACCOUNT, GAME, SPACE, true);
            check(!state.take(ACCOUNT, GAME, SPACE), "repeated active refresh must not enqueue a second read");
        }
    }

    private static void pendingAndFailureReplaceEarlierSuccess() {
        RenewalArchiveReadState state = new RenewalArchiveReadState();
        state.open(ACCOUNT, GAME, SPACE, false);
        state.result(true);
        state.started();
        check(!state.take(ACCOUNT, GAME, SPACE), "new pending refresh invalidates earlier queued success");
        state.result(false);
        check(!state.take(ACCOUNT, GAME, SPACE), "failure cannot drain an older successful refresh");
        state.started();
        state.result(true);
        check(state.take(ACCOUNT, GAME, SPACE), "a later fresh success can recover");
        state.result(false);
        state.result(true);
        check(state.take(ACCOUNT, GAME, SPACE), "a second actual availability loss can recover once");
        check(!state.take(ACCOUNT, GAME, SPACE), "second transition also consumes once");
    }

    private static void scopesUseAccountIdentityAndExactSelection() {
        for (int changed = 0; changed < 3; changed++) {
            RenewalArchiveReadState state = new RenewalArchiveReadState();
            state.open(ACCOUNT, GAME, SPACE, false);
            state.result(true);
            Object account = changed == 0 ? new Object() : ACCOUNT;
            String game = changed == 1 ? "game-b" : GAME;
            String space = changed == 2 ? "namespace-b" : SPACE;
            check(!state.take(account, game, space), "stale account, selection, or namespace cannot consume");
            check(!state.take(ACCOUNT, GAME, SPACE), "scope mismatch discards the old queued result");
            state.open(account, game, space, true);
            state.result(true);
            check(!state.take(account, game, space), "new available scope inherits no recovery");
        }
        Object equalAccountA = new String("same-account");
        Object equalAccountB = new String("same-account");
        RenewalArchiveReadState state = new RenewalArchiveReadState();
        state.open(equalAccountA, GAME, SPACE, false);
        state.result(true);
        check(!state.matches(equalAccountB, GAME, SPACE), "equal credentials do not replace connection identity");
        check(!state.take(equalAccountB, GAME, SPACE), "new equal account object cannot consume old work");
    }

    private static void cancellationDropsPendingAndLateResults() {
        RenewalArchiveReadState state = new RenewalArchiveReadState();
        state.open(ACCOUNT, GAME, SPACE, false);
        state.result(true);
        state.clear();
        check(!state.matches(ACCOUNT, GAME, SPACE), "close/cancel drops scope");
        state.result(true);
        check(!state.take(ACCOUNT, GAME, SPACE), "late success after cancellation cannot revive work");
        state.open(ACCOUNT, GAME, SPACE, true);
        state.started();
        state.result(true);
        check(!state.take(ACCOUNT, GAME, SPACE), "fresh available session inherits no canceled read");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
