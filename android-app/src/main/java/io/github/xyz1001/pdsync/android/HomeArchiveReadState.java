package io.github.xyz1001.pdsync.android;

/** Read-only work visibility, independent of setup, upload, restore, and launch busy state. */
final class HomeArchiveReadState {
    private long generation;
    private long active;
    private boolean closed;

    synchronized long begin() {
        if (closed) return 0;
        active = ++generation;
        return active;
    }

    synchronized void finish(long ticket) {
        if (ticket != 0 && ticket == active) active = 0;
    }

    synchronized boolean accepts(long ticket) { return !closed && ticket != 0 && ticket == active; }

    synchronized boolean loading() { return active != 0; }

    synchronized void invalidate() { active = 0; ++generation; }

    synchronized void close() { invalidate(); closed = true; }
}
