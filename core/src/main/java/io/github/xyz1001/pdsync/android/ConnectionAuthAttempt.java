package io.github.xyz1001.pdsync.android;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * One explicitly requested authentication operation, shared by UI attachments.
 * The caller supplies a source-bound proof and must keep Activity/View references
 * out of the callable. This class has no Android dependency, executor or retry.
 */
final class ConnectionAuthAttempt<T> {
    final String proof;
    private final FutureTask<T> task;

    ConnectionAuthAttempt(String proof, Callable<T> request) {
        this.proof = Objects.requireNonNull(proof, "proof");
        this.task = new FutureTask<>(Objects.requireNonNull(request, "request"));
    }

    /** Runs on the first caller's thread; all attachments observe the same outcome. */
    T await() throws Exception {
        task.run();
        try {
            return task.get();
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("Authentication operation failed", cause);
        }
    }

    boolean isDone() {
        return task.isDone();
    }
}
