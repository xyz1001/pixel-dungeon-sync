package io.github.xyz1001.pdsync.android;

/** Executes exact-session status reconciliation. No Android service, Root, or network is run. */
public final class AutoSyncStatusChecks {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static AutoSyncStatusPolicy.Record failure() {
        return new AutoSyncStatusPolicy.Record("session-a", "BLOCKED", AutoSyncStatusPolicy.IDLE_FAILURE,
                "root-game-identity-a", "space-a", 1000L);
    }
    private static String acknowledge(AutoSyncStatusPolicy.Record before, AutoSyncStatusPolicy.Record after,
            String kind, boolean recovery, boolean pending) {
        return AutoSyncStatusPolicy.acknowledgment(before, after, "root-game-identity-a", "space-a",
                kind, recovery, pending);
    }
    public static void main(String[] args) {
        AutoSyncStatusPolicy.Record original = failure();
        AutoSyncStatusPolicy.Record before = AutoSyncStatusPolicy.capture(original, "root-game-identity-a", "space-a");
        check(before == original, "Legacy BLOCKED exact idle failure is recognized without migration");
        check(AutoSyncStatusPolicy.IDLE_FAILURE.equals(AutoSyncStatusPolicy.visibleResult(original, "", "root-game-identity-a", "space-a")),
                "Unverified old warning remains visible");
        check(acknowledge(before, original, "BLOCKED", false, false) == null,
                "A still-busy failed manual comparison does not clear the warning");
        String checked = acknowledge(before, original, "IDENTICAL", false, false);
        check(checked != null, "Successful manual save inspection acknowledges exactly the prior failure");
        check(AutoSyncStatusPolicy.visibleResult(original, checked, "root-game-identity-a", "space-a").isEmpty(),
                "Successful manual comparison then completion/Home no longer displays stale idle warning");
        check(original.state.equals("BLOCKED") && original.result.equals(AutoSyncStatusPolicy.IDLE_FAILURE),
                "Original terminal state and diagnostic remain unchanged; no listener has resumed");
        check(checked.equals(acknowledge(before, original, "IDENTICAL", false, false)), "Repeated success is idempotent");
        String[] kinds = {null, "", "UNKNOWN", "BLOCKED", "BOTH_EMPTY", "LOCAL_ONLY", "REMOTE_ONLY", "IDENTICAL", "DIFFERENT", "BRANCHED"};
        for (String kind : kinds) for (int flags = 0; flags < 4; flags++) {
            boolean recovery = (flags & 1) != 0, pending = (flags & 2) != 0;
            boolean success = kind != null && !(kind.isEmpty() || kind.equals("UNKNOWN") || kind.equals("BLOCKED"));
            check((acknowledge(before, original, kind, recovery, pending) != null) == (success && !recovery && !pending),
                    "Only successful stable export/comparison without recovery or pending acknowledges: " + kind + flags);
        }
        String[] states = {"", "ARMED", "PLAYING", "SYNCING", "UPLOADED", "UNCHANGED", "SKIPPED_CONFLICT", "SKIPPED_UNCERTAIN", "RETRY_LATER", "CANCELLED", "PAUSED", "EXPIRED", "TIMEOUT"};
        for (String state : states) {
            AutoSyncStatusPolicy.Record changed = new AutoSyncStatusPolicy.Record(original.token, state, original.result,
                    original.gameSelection, original.namespace, original.startedAt);
            check(AutoSyncStatusPolicy.capture(changed, original.gameSelection, original.namespace) == null,
                    "Other listener states are not stale idle failures: " + state);
            check(acknowledge(before, changed, "IDENTICAL", false, false) == null, "A changed state cannot be acknowledged");
            check(AutoSyncStatusPolicy.visibleResult(changed, checked, original.gameSelection, original.namespace).equals(changed.result),
                    "A late old acknowledgement never hides a new active/terminal state");
        }
        String[] messages = {"", "系统没有允许本次后台同步，请返回首页手动检查。", "未观察到本次游戏前台活动", "本次自动同步未开始：WebDAV 连接或未完成恢复需要检查。", "自动上传未完成：云端冲突", AutoSyncStatusPolicy.IDLE_FAILURE + "new"};
        for (String message : messages) {
            AutoSyncStatusPolicy.Record other = new AutoSyncStatusPolicy.Record(original.token, "BLOCKED", message,
                    original.gameSelection, original.namespace, original.startedAt);
            check(AutoSyncStatusPolicy.capture(other, original.gameSelection, original.namespace) == null,
                    "Unrelated BLOCKED errors are not dismissed by a successful DAV/save check");
            check(acknowledge(before, other, "IDENTICAL", false, false) == null, "Changed reason remains");
            check(AutoSyncStatusPolicy.visibleResult(other, checked, original.gameSelection, original.namespace).equals(message),
                    "Unrelated diagnostic remains visible");
        }
        AutoSyncStatusPolicy.Record[] changed = {
            new AutoSyncStatusPolicy.Record("session-b", original.state, original.result, original.gameSelection, original.namespace, 1000),
            new AutoSyncStatusPolicy.Record(original.token, original.state, original.result, "game-b", original.namespace, 1000),
            new AutoSyncStatusPolicy.Record(original.token, original.state, original.result, original.gameSelection, "space-b", 1000),
            new AutoSyncStatusPolicy.Record(original.token, original.state, original.result, original.gameSelection, original.namespace, 1001),
            new AutoSyncStatusPolicy.Record("", original.state, original.result, original.gameSelection, original.namespace, 1000),
            new AutoSyncStatusPolicy.Record(original.token, original.state, original.result, original.gameSelection, original.namespace, 0)
        };
        for (AutoSyncStatusPolicy.Record other : changed) {
            check(acknowledge(before, other, "IDENTICAL", false, false) == null, "Session/target/restart changes revoke old proof");
            check(AutoSyncStatusPolicy.visibleResult(other, checked, other.gameSelection, other.namespace).equals(other.result),
                    "An acknowledgement written after a new session starts cannot clear it");
        }
        check(AutoSyncStatusPolicy.capture(original, "game-b", original.namespace) == null, "Another game cannot capture proof");
        check(AutoSyncStatusPolicy.capture(original, original.gameSelection, "space-b") == null, "Another namespace cannot capture proof");
        check(!AutoSyncStatusPolicy.visibleResult(original, checked, "game-b", original.namespace).isEmpty(), "Switching game revokes display acknowledgment");
        check(!AutoSyncStatusPolicy.visibleResult(original, checked, original.gameSelection, "space-b").isEmpty(), "Switching namespace revokes display acknowledgment");
        check(acknowledge(null, original, "IDENTICAL", false, false) == null, "Failure first appearing during inspection is not dismissed");
        check(acknowledge(before, null, "IDENTICAL", false, false) == null, "Missing post-inspection session is not success");
        System.out.println("Auto-sync status reconciliation: " + checks + " host checks passed; no listener restart/device calls.");
    }
}
