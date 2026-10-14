package io.github.xyz1001.pdsync.android;

/** Host-only session/lifecycle gate. No service, device, network, uploads, or Root process. */
public final class AutoSyncHistoryRefreshChecks {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static AutoSyncStatusPolicy.Record record(String token, String state, String scope) {
        return new AutoSyncStatusPolicy.Record(token, state, "result", "game-A", "namespace-A", 123L, scope);
    }
    private static AutoSyncHistoryRefresh.Ticket begin(AutoSyncHistoryRefresh gate, AutoSyncStatusPolicy.Record record,
            boolean foregroundHome, boolean idle) {
        return gate.begin(record, "game-A", "namespace-A", "account-profile-A", foregroundHome, idle);
    }
    private static boolean accepts(AutoSyncHistoryRefresh gate, AutoSyncHistoryRefresh.Ticket ticket,
            AutoSyncStatusPolicy.Record record) {
        return gate.accepts(ticket, record, "game-A", "namespace-A", "account-profile-A");
    }
    public static void main(String[] args) {
        for (String state : new String[]{"UPLOADED", "UNCHANGED", "SKIPPED_CONFLICT", "SKIPPED_UNCERTAIN",
                "RETRY_LATER", "BLOCKED", "PAUSED", "EXPIRED", "TIMEOUT", "CANCELLED"}) {
            AutoSyncHistoryRefresh gate = new AutoSyncHistoryRefresh();
            AutoSyncStatusPolicy.Record record = record("session", state, "account-profile-A");
            check(begin(gate, record, false, true) == null, state + ": background/settings defers");
            check(begin(gate, record, true, false) == null, state + ": busy defers");
            AutoSyncHistoryRefresh.Ticket ticket = begin(gate, record, true, true);
            check(ticket != null && accepts(gate, ticket, record), state + ": one foreground read");
            check(!gate.needsRead(record), state + ": failure is consumed too; no retry loop");
            check(begin(gate, record, true, true) == null, state + ": duplicate broadcast/resume is deduplicated");
            check(accepts(gate, ticket, record), state + ": completed result can be staged then applied on resume");
            check(!gate.accepts(ticket, record, "game-B", "namespace-A", "account-profile-A"), state + ": stale game ignored");
            check(!gate.accepts(ticket, record, "game-A", "namespace-B", "account-profile-A"), state + ": stale binding ignored");
            check(!gate.accepts(ticket, record, "game-A", "namespace-A", "account-profile-B"), state + ": stale account/profile ignored");
            check(!accepts(gate, ticket, record("new-session", state, "account-profile-A")), state + ": stale session ignored");
            check(!accepts(gate, ticket, record("session", "PLAYING", "account-profile-A")), state + ": active session blocks old response");
        }
        AutoSyncStatusPolicy.Record persisted = record("persisted", "UPLOADED", "account-profile-A");
        check(AutoSyncHistoryRefresh.unconsumed(persisted, ""), "First return after background completion is pending");
        String consumedKey = persisted.key();
        check(!AutoSyncHistoryRefresh.unconsumed(persisted, consumedKey), "Cold startup cannot replay consumed Root read");
        check(!AutoSyncHistoryRefresh.unconsumed(record("persisted", "UPLOADED", "account-profile-A"), consumedKey),
                "Persisted claim is independent of Activity/process object identity");
        check(AutoSyncHistoryRefresh.unconsumed(record("new", "UPLOADED", "account-profile-A"), consumedKey),
                "New terminal session after cold startup still gets its own read");
        check(AutoSyncHistoryRefresh.unconsumed(record("persisted", "SKIPPED_CONFLICT", "account-profile-A"), consumedKey),
                "Changed exact terminal record is not confused with an old claim");
        check(!AutoSyncHistoryRefresh.unconsumed(null, consumedKey), "Missing record is never pending");
        AutoSyncHistoryRefresh gate = new AutoSyncHistoryRefresh();
        for (String state : new String[]{"", "ARMED", "PLAYING", "SYNCING", "unknown"})
            check(begin(gate, record("session", state, "account-profile-A"), true, true) == null,
                    state + ": active/nonterminal records never trigger a read");
        AutoSyncStatusPolicy.Record result = record("first", "UPLOADED", "account-profile-A");
        AutoSyncHistoryRefresh.Ticket first = begin(gate, result, true, true);
        gate.deferBeforeRead(first);
        AutoSyncHistoryRefresh.Ticket resumed = begin(gate, result, true, true);
        check(resumed != null, "Paused before I/O is retried once on foreground Home");
        check(begin(gate, result, true, true) == null, "Pause after I/O stages result, never repeats read");
        AutoSyncStatusPolicy.Record next = record("next", "SKIPPED_CONFLICT", "account-profile-A");
        AutoSyncHistoryRefresh.Ticket newer = begin(gate, next, true, true);
        check(newer != null && accepts(gate, newer, next), "Next terminal session gets its own read");
        gate.deferBeforeRead(first);
        check(begin(gate, next, true, true) == null, "Stale queue cancellation cannot rearm a newer completed read");
        check(!accepts(gate, first, result), "Late previous-session response cannot replace newer result");
        for (AutoSyncStatusPolicy.Record invalid : new AutoSyncStatusPolicy.Record[]{
                null, record("", "UPLOADED", "account-profile-A"), record("legacy", "UPLOADED", ""),
                record("wrong-account", "UPLOADED", "account-profile-B"),
                new AutoSyncStatusPolicy.Record("token", "UPLOADED", "", "game-A", "namespace-A", 0, "account-profile-A"),
                new AutoSyncStatusPolicy.Record("token", "UPLOADED", "", "", "namespace-A", 1, "account-profile-A")}) {
            check(begin(new AutoSyncHistoryRefresh(), invalid, true, true) == null,
                    "Missing/unproved session identity never authorizes automatic Root reads");
        }
        System.out.println("Automatic history refresh checks passed: " + checks + " assertions");
    }
}
