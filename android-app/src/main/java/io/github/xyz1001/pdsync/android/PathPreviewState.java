package io.github.xyz1001.pdsync.android;

import java.util.Objects;

/** Read-only Step 2 request ownership. It deliberately does not own hard-operation busy. */
final class PathPreviewState {
    static final class Context {
        final String selectionToken, namespace, path;

        Context(String selectionToken, String namespace, String path) {
            this.selectionToken = Objects.requireNonNull(selectionToken);
            this.namespace = Objects.requireNonNull(namespace);
            this.path = Objects.requireNonNull(path);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Context)) return false;
            Context value = (Context) other;
            return selectionToken.equals(value.selectionToken) && namespace.equals(value.namespace)
                    && path.equals(value.path);
        }

        @Override public int hashCode() { return Objects.hash(selectionToken, namespace, path); }
    }

    static final class Request {
        final long generation;
        final Context context;
        private Request(long generation, Context context) {
            this.generation = generation;
            this.context = context;
        }
    }

    enum Completion { STALE, ACCEPTED, ADVANCE }

    private long generation;
    private Context context;
    private Request request;
    private boolean attempted, failed, continueAfterRead;

    /** Selection/backend, namespace or path changes revoke both the read and its evidence. */
    synchronized boolean bind(Context current) {
        if (Objects.equals(context, current)) return false;
        reset();
        context = current;
        return true;
    }

    synchronized Request begin(Context current, boolean foregroundPath, boolean hardBusy,
            boolean mayRequestRead, boolean continueWhenReady) {
        if (!foregroundPath || hardBusy || !mayRequestRead || current == null || request != null
                || !current.equals(context)) return null;
        request = new Request(++generation, current);
        attempted = true;
        failed = false;
        continueAfterRead = continueWhenReady;
        return request;
    }

    /** Repeated Next taps share one request and one continuation. Rendering has no effect. */
    synchronized boolean queueNext(Context current, boolean foregroundPath, boolean hardBusy) {
        if (!isCurrent(request, current, foregroundPath, hardBusy)) return false;
        continueAfterRead = true;
        return true;
    }

    synchronized boolean isCurrent(Request candidate, Context current, boolean foregroundPath, boolean hardBusy) {
        return candidate != null && candidate == request && candidate.generation == generation
                && candidate.context.equals(current) && candidate.context.equals(context)
                && foregroundPath && !hardBusy;
    }

    synchronized Completion finish(Request candidate, Context current, boolean foregroundPath,
            boolean hardBusy, boolean accessReady, boolean hasRealSummary, boolean success) {
        if (!isCurrent(candidate, current, foregroundPath, hardBusy)) return Completion.STALE;
        boolean accepted = success && accessReady && hasRealSummary;
        boolean advance = accepted && continueAfterRead;
        request = null;
        continueAfterRead = false;
        attempted = true;
        failed = !accepted;
        return advance ? Completion.ADVANCE : Completion.ACCEPTED;
    }

    /** Back/pause/destroy and a hard operation cancel reads, but never change hard busy. */
    synchronized void invalidate() {
        generation++;
        if (request != null) {
            attempted = false;
            failed = false;
        }
        request = null;
        continueAfterRead = false;
    }

    synchronized void reset() {
        invalidate();
        attempted = false;
        failed = false;
    }

    synchronized boolean inFlight() { return request != null; }
    synchronized boolean attempted() { return attempted; }
    synchronized boolean failed() { return failed; }
    synchronized boolean continueAfterRead() { return continueAfterRead; }
}
