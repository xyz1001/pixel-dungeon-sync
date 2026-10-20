package io.github.xyz1001.pdsync.android;

public final class HomeArchiveReadStateChecks {
    public static void main(String[] args) {
        HomeArchiveReadState state = new HomeArchiveReadState();
        check(!state.loading(), "idle starts hidden");
        long first = state.begin();
        check(state.loading() && state.accepts(first), "read is visible and current");
        state.finish(first);
        check(!state.loading(), "success, failure, or cancellation finally clears the read");
        long stale = state.begin();
        state.invalidate();
        check(!state.loading() && !state.accepts(stale), "leaving the page rejects the old read");
        long current = state.begin();
        state.finish(stale);
        check(state.loading(), "stale completion cannot hide a newer read");
        state.finish(current);
        check(!state.loading(), "current completion hides loading");
        state.begin();
        state.close();
        check(!state.loading(), "destroy clears loading");
        check(state.begin() == 0 && !state.loading(), "destroyed host cannot start loading");
        HomeArchiveReadState replacement = new HomeArchiveReadState();
        long replacementRead = replacement.begin();
        state.finish(current);
        check(replacement.accepts(replacementRead), "old activity completion cannot clear replacement activity read");
        System.out.println("Home archive read state checks passed");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
