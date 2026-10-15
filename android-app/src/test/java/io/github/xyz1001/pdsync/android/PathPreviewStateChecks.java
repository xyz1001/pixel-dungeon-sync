package io.github.xyz1001.pdsync.android;

/** Executes the production request model without an Android runtime or a mock read service. */
public final class PathPreviewStateChecks {
    private static int checks;
    private static final PathPreviewState.Context GAME = new PathPreviewState.Context("embedded:apk-a", "one", "/game/files");

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static PathPreviewState state() {
        PathPreviewState value = new PathPreviewState();
        value.bind(GAME);
        return value;
    }

    private static PathPreviewState.Completion finish(PathPreviewState state, PathPreviewState.Request request) {
        return state.finish(request, GAME, true, false, true, true, true);
    }

    public static void main(String[] args) {
        PathPreviewState state = state();
        check(!state.attempted() && !state.inFlight(), "Entry starts unread");
        PathPreviewState.Request first = state.begin(GAME, true, false, true, false);
        check(first != null && state.inFlight() && state.attempted(), "Entry read is a real single request");
        check(!state.continueAfterRead(), "Entry read alone never navigates");
        check(state.begin(GAME, true, false, true, false) == null, "Repeated entry cannot create duplicate I/O");
        check(!state.bind(new PathPreviewState.Context("embedded:apk-a", "one", "/game/files")), "Rerendering the same identity does not invalidate a read");
        check(state.queueNext(GAME, true, false), "Next queues on an ordinary read");
        check(state.queueNext(GAME, true, false), "Repeated Next is idempotent");
        check(state.continueAfterRead(), "Waiting is visible");
        check(state.isCurrent(first, GAME, true, false), "Waiting rerender preserves read ownership");
        check(finish(state, first) == PathPreviewState.Completion.ADVANCE, "Current successful read consumes queued Next");
        check(!state.inFlight() && !state.continueAfterRead() && !state.failed(), "Completion clears only read ownership");
        check(finish(state, first) == PathPreviewState.Completion.STALE, "Duplicate completion cannot navigate twice");
        check(!state.queueNext(GAME, true, false), "A completed request cannot queue a second continuation");

        state = state();
        first = state.begin(GAME, true, false, true, false);
        check(finish(state, first) == PathPreviewState.Completion.ACCEPTED, "An unqueued entry read stays on Step 2");

        for (String cause : new String[]{"Back", "pause", "destroy", "rotation", "readiness change", "hard operation"}) {
            state = state();
            first = state.begin(GAME, true, false, true, true);
            state.invalidate();
            check(!state.inFlight() && !state.continueAfterRead(), cause + " revokes both request and navigation");
            check(!state.attempted(), cause + " permits a new explicit or eligible embedded entry read");
            check(finish(state, first) == PathPreviewState.Completion.STALE, cause + " rejects a late result even after foreground re-entry");
            PathPreviewState.Request newer = state.begin(GAME, true, false, true, false);
            check(finish(state, first) == PathPreviewState.Completion.STALE, cause + " cannot complete a replacement read");
            check(state.isCurrent(newer, GAME, true, false), cause + " leaves replacement ownership untouched");
            check(finish(state, newer) == PathPreviewState.Completion.ACCEPTED, cause + " cannot carry queued Next into the new read");
        }

        for (PathPreviewState.Context changed : new PathPreviewState.Context[]{
                new PathPreviewState.Context("embedded:apk-b", "one", "/game/files"),
                new PathPreviewState.Context("root:apk-a", "one", "/game/files"),
                new PathPreviewState.Context("embedded:apk-a", "two", "/game/files"),
                new PathPreviewState.Context("embedded:apk-a", "one", "/other/files")}) {
            state = state();
            first = state.begin(GAME, true, false, true, true);
            check(!state.isCurrent(first, changed, true, false), "Changed selection/backend/namespace/path rejects old read");
            check(state.bind(changed), "Changed identity invalidates evidence");
            check(!state.inFlight() && !state.continueAfterRead() && !state.attempted(), "Changed identity clears old state");
            check(state.finish(first, changed, true, false, true, true, true) == PathPreviewState.Completion.STALE,
                    "Changed identity cannot consume old queued Next");
        }

        state = state();
        first = state.begin(GAME, true, false, true, true);
        check(state.finish(first, GAME, true, false, true, false, false) == PathPreviewState.Completion.ACCEPTED,
                "A current failure is accepted for display");
        check(state.failed() && state.attempted() && !state.inFlight() && !state.continueAfterRead(),
                "Failure stays attempted/FAILED and does not auto-retry");
        state.invalidate();
        check(state.failed() && state.attempted(), "An ordinary rerender/lifecycle cancellation does not erase a completed failure");
        PathPreviewState.Request retry = state.begin(GAME, true, false, true, true);
        check(retry != null && !state.failed() && state.continueAfterRead(), "Explicit Retry starts a new queued read");
        check(finish(state, first) == PathPreviewState.Completion.STALE, "A prior failure cannot replace its retry");
        check(finish(state, retry) == PathPreviewState.Completion.ADVANCE, "Successful Retry advances exactly once");

        for (boolean ready : new boolean[]{false, true}) {
            for (boolean realSummary : new boolean[]{false, true}) {
                state = state();
                first = state.begin(GAME, true, false, true, true);
                PathPreviewState.Completion result = state.finish(first, GAME, true, false, ready, realSummary, true);
                check((result == PathPreviewState.Completion.ADVANCE) == (ready && realSummary),
                        "Navigation needs current readiness and a real summary");
                check(state.failed() == !(ready && realSummary), "Missing proof is failure, never empty success");
            }
        }

        state = state();
        check(state.begin(GAME, false, false, true, true) == null, "Background cannot start a read");
        check(state.begin(GAME, true, true, true, true) == null, "Hard busy cannot start a read");
        check(state.begin(GAME, true, false, false, true) == null, "Unavailable access cannot start a read");
        check(state.begin(null, true, false, true, true) == null, "Missing identity cannot start a read");
        first = state.begin(GAME, true, false, true, true);
        check(!state.queueNext(GAME, false, false), "Background Next is ignored");
        check(!state.queueNext(GAME, true, true), "Hard-busy Next is ignored");
        check(state.finish(first, GAME, false, false, true, true, true) == PathPreviewState.Completion.STALE,
                "Paused success cannot mutate visible state");
        check(state.finish(first, GAME, true, true, true, true, true) == PathPreviewState.Completion.STALE,
                "Old read cannot clear or complete another hard operation");
        check(state.inFlight(), "Rejected result does not mutate read ownership without explicit invalidation");
        state.reset();
        check(!state.inFlight() && !state.attempted() && !state.failed() && !state.continueAfterRead(), "Readiness reset removes all read proof");
        check(finish(state, first) == PathPreviewState.Completion.STALE, "Readiness reset rejects late results");
        System.out.println("PathPreviewStateChecks: " + checks + " checks passed");
    }
}
