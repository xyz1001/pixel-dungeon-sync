package io.github.xyz1001.pdsync.android;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the production helper on the host JVM; no Android, credentials, or service. */
public final class ConnectionAuthAttemptChecks {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        check(latch.await(5, TimeUnit.SECONDS), message);
    }

    private static void concurrentAttachmentsExecuteOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Object result = new Object();
        ConnectionAuthAttempt<Object> attempt = new ConnectionAuthAttempt<>("OFFICIAL:account-a", () -> {
            calls.incrementAndGet();
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test release timed out");
            return result;
        });
        check(calls.get() == 0 && !attempt.isDone(), "Construction neither starts nor completes authentication");
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            List<Future<Object>> outcomes = new ArrayList<>();
            outcomes.add(pool.submit(attempt::await));
            await(started, "The initial attachment starts the operation");
            check(!attempt.isDone(), "A blocked operation remains in flight");
            CountDownLatch attached = new CountDownLatch(11);
            for (int i = 0; i < 11; i++) {
                // A replacement Activity receives this same retained object, not a new request.
                ConnectionAuthAttempt<Object> reattached = attempt;
                outcomes.add(pool.submit(() -> {
                    attached.countDown();
                    return reattached.await();
                }));
            }
            await(attached, "All replacement and concurrent attachments started");
            check(calls.get() == 1, "Concurrent attachments do not execute an in-flight request again");
            release.countDown();
            for (Future<Object> outcome : outcomes) {
                check(outcome.get(5, TimeUnit.SECONDS) == result, "Every attachment receives the exact original result");
            }
            check(attempt.isDone(), "Successful completion is visible to later attachments");
            for (int i = 0; i < 5; i++) {
                check(attempt.await() == result, "A completed reattachment reuses the same result");
            }
            check(calls.get() == 1, "Completion and repeated await never retry authentication");
            check(attempt.proof.equals("OFFICIAL:account-a"), "Completion leaves the supplied proof unchanged");
        } finally {
            release.countDown();
            pool.shutdownNow();
            check(pool.awaitTermination(5, TimeUnit.SECONDS), "All attachment threads terminate");
        }
    }

    private static void failureIsCachedUntilExplicitNewAttempt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        IOException failure = new IOException("Synthetic service failure");
        ConnectionAuthAttempt<Object> attempt = new ConnectionAuthAttempt<>("OFFICIAL:account-a", () -> {
            calls.incrementAndGet();
            throw failure;
        });
        for (int i = 0; i < 5; i++) {
            try {
                attempt.await();
                throw new AssertionError("A failed attempt must not produce a successful result");
            } catch (IOException expected) {
                check(expected == failure, "Reattachment unwraps the same original failure without retry");
            }
            check(attempt.isDone(), "Failure is a terminal state, not an in-flight retry");
        }
        check(calls.get() == 1, "A failed operation executes once across all await calls");
        Object result = new Object();
        ConnectionAuthAttempt<Object> explicitlyRequested = new ConnectionAuthAttempt<>(attempt.proof, () -> {
            calls.incrementAndGet();
            return result;
        });
        check(!explicitlyRequested.isDone() && calls.get() == 1, "New explicit attempt remains lazy");
        check(explicitlyRequested.await() == result, "The user can explicitly request another operation");
        check(calls.get() == 2, "Only a new explicit attempt executes the second operation");
        check(explicitlyRequested.await() == result && calls.get() == 2, "New attempt also has at-most-once execution");
    }

    private static void failuresAndNullResultsPreserveTheirMeaning() throws Exception {
        RuntimeException runtimeFailure = new IllegalArgumentException("Synthetic argument failure");
        ConnectionAuthAttempt<Object> runtime = new ConnectionAuthAttempt<>("OFFICIAL:runtime", () -> {
            throw runtimeFailure;
        });
        try {
            runtime.await();
            throw new AssertionError("Runtime failure must propagate");
        } catch (IllegalArgumentException expected) {
            check(expected == runtimeFailure, "Runtime exception is not replaced by ExecutionException");
        }
        AssertionError fatal = new AssertionError("Synthetic callable error");
        AtomicInteger calls = new AtomicInteger();
        ConnectionAuthAttempt<Object> error = new ConnectionAuthAttempt<>("OFFICIAL:error", () -> {
            calls.incrementAndGet();
            throw fatal;
        });
        for (int i = 0; i < 2; i++) {
            try {
                error.await();
                throw new AssertionError("Error must propagate");
            } catch (AssertionError expected) {
                check(expected == fatal, "Original Error propagates unchanged on every attachment");
            }
        }
        check(calls.get() == 1 && error.isDone(), "Error is terminal and never retried");
        ConnectionAuthAttempt<Object> nullResult = new ConnectionAuthAttempt<>("OFFICIAL:null", () -> null);
        check(nullResult.await() == null && nullResult.isDone(), "Null result is completed, not mistaken for pending");
        check(nullResult.await() == null, "Null result can be reattached without a retry");
    }

    private static void interruptedAttachmentDoesNotCancelOrRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> attachmentResult = new AtomicReference<>();
        Object result = new Object();
        ConnectionAuthAttempt<Object> attempt = new ConnectionAuthAttempt<>("OFFICIAL:interrupt", () -> {
            calls.incrementAndGet();
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test release timed out");
            return result;
        });
        ExecutorService owner = Executors.newSingleThreadExecutor();
        Thread attachment = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                attempt.await();
                attachmentResult.set(new AssertionError("Interrupted attachment must stop waiting"));
            } catch (Throwable failure) {
                attachmentResult.set(failure);
            }
        }, "interrupted-auth-attachment");
        try {
            Future<Object> original = owner.submit(attempt::await);
            await(started, "Original request is running before the interrupted attachment arrives");
            attachment.start();
            attachment.join(5000);
            check(!attachment.isAlive(), "An interrupted attachment terminates promptly");
            check(attachmentResult.get() instanceof InterruptedException, "Waiting attachment receives interruption");
            check(calls.get() == 1 && !attempt.isDone(), "Interruption neither cancels nor restarts the original request");
            release.countDown();
            check(original.get(5, TimeUnit.SECONDS) == result, "Original request still completes successfully");
            check(attempt.await() == result && calls.get() == 1, "A later replacement reuses that completed request");
        } finally {
            release.countDown();
            attachment.interrupt();
            attachment.join(5000);
            owner.shutdownNow();
            check(owner.awaitTermination(5, TimeUnit.SECONDS), "Original request thread terminates");
        }
    }

    private static void proofsAndAttemptsAreIndependent() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ConnectionAuthAttempt<Integer> official = new ConnectionAuthAttempt<>("OFFICIAL:account-a", calls::incrementAndGet);
        ConnectionAuthAttempt<Integer> custom = new ConnectionAuthAttempt<>("CUSTOM:account-a", calls::incrementAndGet);
        check(!official.proof.equals(custom.proof), "Source-specific proofs are not conflated");
        check(official.await() == 1, "First source owns its result");
        check(!custom.isDone(), "Completing one proof cannot complete another attempt");
        check(custom.await() == 2, "Separate source owns a separate explicit operation");
        check(official.await() == 1 && custom.await() == 2 && calls.get() == 2, "Results remain isolated across proofs");
    }

    private static void helperHasOnlyContextFreeState() throws Exception {
        check(Modifier.isFinal(ConnectionAuthAttempt.class.getModifiers()), "Helper cannot acquire Activity state through a subclass");
        Field[] fields = ConnectionAuthAttempt.class.getDeclaredFields();
        check(fields.length == 2, "Only source proof and FutureTask are retained");
        for (Field field : fields) {
            check(!Modifier.isStatic(field.getModifiers()), "Attempts never share mutable global state");
            check(Modifier.isFinal(field.getModifiers()), "Attempt identity is immutable: " + field.getName());
            check(field.getType() == String.class || field.getType() == FutureTask.class,
                    "No direct Activity, Context, executor, or callback field: " + field.getName());
        }
        check(Modifier.isPrivate(ConnectionAuthAttempt.class.getDeclaredField("task").getModifiers()),
                "Callers cannot replace, cancel, or reset the shared FutureTask");
        for (int i = 0; i < 2; i++) {
            try {
                if (i == 0) new ConnectionAuthAttempt<>(null, () -> null);
                else new ConnectionAuthAttempt<>("proof", null);
                throw new AssertionError("Missing proof or request must fail before execution");
            } catch (NullPointerException expected) {
                checks++;
            }
        }
    }

    public static void main(String[] args) throws Exception {
        concurrentAttachmentsExecuteOnce();
        failureIsCachedUntilExplicitNewAttempt();
        failuresAndNullResultsPreserveTheirMeaning();
        interruptedAttachmentDoesNotCancelOrRetry();
        proofsAndAttemptsAreIndependent();
        helperHasOnlyContextFreeState();
        System.out.println("Connection-auth attempt checks passed: " + checks
                + " assertions; host-only at-most-once requests, no Android, network, or device execution.");
    }
}
