package io.github.xyz1001.pdsync.android;

import java.util.Objects;

/** Acknowledges one historical idle-observation failure, never a running listener or upload. */
final class AutoSyncStatusPolicy {
    static final String IDLE_FAILURE = "多次无法可靠确认游戏已空闲，已停止本次监听。请正常退出游戏后返回首页检查。";
    private AutoSyncStatusPolicy() {}

    static final class Record {
        final String token, state, result, gameSelection, namespace;
        final long startedAt;
        Record(String token, String state, String result, String gameSelection, String namespace, long startedAt) {
            this.token = safe(token); this.state = safe(state); this.result = safe(result);
            this.gameSelection = safe(gameSelection); this.namespace = safe(namespace); this.startedAt = startedAt;
        }
        boolean idleFailureFor(String selection, String space) {
            // Exact legacy code31 text is intentional: other BLOCKED causes must survive.
            return "BLOCKED".equals(state) && IDLE_FAILURE.equals(result) && !token.isEmpty()
                    && startedAt > 0 && !gameSelection.isEmpty() && !namespace.isEmpty()
                    && gameSelection.equals(selection) && namespace.equals(space);
        }
        String key() {
            return part(token) + part(state) + part(result) + part(gameSelection) + part(namespace) + startedAt;
        }
    }

    static Record capture(Record current, String selection, String namespace) {
        return current != null && current.idleFailureFor(selection, namespace) ? current : null;
    }

    static String acknowledgment(Record before, Record after, String selection, String namespace,
            String comparisonKind, boolean needsRecovery, boolean retryablePending) {
        if (before == null || after == null || !before.idleFailureFor(selection, namespace)
                || !after.idleFailureFor(selection, namespace) || !before.key().equals(after.key())
                || needsRecovery || retryablePending || !successfulComparison(comparisonKind)) return null;
        return before.key();
    }

    static String visibleResult(Record current, String acknowledged, String selection, String namespace) {
        if (current == null) return "";
        return current.idleFailureFor(selection, namespace) && Objects.equals(current.key(), acknowledged)
                ? "" : current.result;
    }

    private static boolean successfulComparison(String kind) {
        return "BOTH_EMPTY".equals(kind) || "LOCAL_ONLY".equals(kind) || "REMOTE_ONLY".equals(kind)
                || "IDENTICAL".equals(kind) || "DIFFERENT".equals(kind) || "BRANCHED".equals(kind);
    }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String part(String value) { return value.length() + ":" + value; }
}
