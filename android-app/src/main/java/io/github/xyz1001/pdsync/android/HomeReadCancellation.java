package io.github.xyz1001.pdsync.android;

import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;

/** One detached read owns cancellation; cancelling it never interrupts the mutation executor. */
final class HomeReadCancellation {
    private boolean cancelled;
    private AutoCloseable resource;
    private Future<?> future;

    synchronized void attach(Future<?> value) {
        if (cancelled) value.cancel(true); else future = value;
    }
    synchronized void attach(AutoCloseable value) {
        if (cancelled) close(value); else resource = value;
    }
    synchronized void checkActive() {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
    synchronized void cancel() {
        if (cancelled) return;
        cancelled = true;
        if (resource != null) { close(resource); resource = null; }
        if (future != null) { future.cancel(true); future = null; }
    }
    private static void close(AutoCloseable value) {
        try { value.close(); } catch (Exception ignored) { /* Cancellation does not publish a read error. */ }
    }
}
