package io.github.xyz1001.pdsync.android;

/** One foreground read per terminal automatic session. No timers, I/O, or upload actions. */
final class AutoSyncHistoryRefresh {
    static final class Ticket {
        final String recordKey, selection, namespace, connectionScope;
        Ticket(AutoSyncStatusPolicy.Record record) {
            recordKey = record.key(); selection = record.gameSelection;
            namespace = record.namespace; connectionScope = record.connectionScope;
        }
    }

    private String attempted = "";

    static boolean matches(AutoSyncStatusPolicy.Record record, String selection, String namespace, String scope) {
        return record != null && terminal(record.state) && !record.token.isEmpty() && record.startedAt > 0
                && !record.gameSelection.isEmpty() && !record.namespace.isEmpty() && !record.connectionScope.isEmpty()
                && record.gameSelection.equals(selection) && record.namespace.equals(namespace)
                && record.connectionScope.equals(scope);
    }

    static boolean unconsumed(AutoSyncStatusPolicy.Record record, String consumedKey) {
        return record != null && !record.key().equals(consumedKey);
    }

    boolean needsRead(AutoSyncStatusPolicy.Record record) {
        return record != null && !attempted.equals(record.key());
    }

    Ticket begin(AutoSyncStatusPolicy.Record record, String selection, String namespace, String scope,
            boolean foregroundHome, boolean idle) {
        if (!foregroundHome || !idle || !matches(record, selection, namespace, scope)
                || attempted.equals(record.key())) return null;
        attempted = record.key();
        return new Ticket(record);
    }

    boolean accepts(Ticket ticket, AutoSyncStatusPolicy.Record record, String selection, String namespace, String scope) {
        return ticket != null && matches(record, selection, namespace, scope)
                && ticket.recordKey.equals(record.key()) && ticket.recordKey.equals(attempted);
    }

    // A queued task paused before it performed I/O is still owed one read on Home resume.
    void deferBeforeRead(Ticket ticket) {
        if (ticket != null && attempted.equals(ticket.recordKey)) attempted = "";
    }

    private static boolean terminal(String state) {
        return "UPLOADED".equals(state) || "UNCHANGED".equals(state) || "SKIPPED_CONFLICT".equals(state)
                || "SKIPPED_UNCERTAIN".equals(state) || "RETRY_LATER".equals(state) || "BLOCKED".equals(state)
                || "PAUSED".equals(state) || "EXPIRED".equals(state) || "TIMEOUT".equals(state)
                || "CANCELLED".equals(state);
    }
}
