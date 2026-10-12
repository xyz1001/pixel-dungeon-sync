package io.github.xyz1001.pdsync.android;

import io.github.xyz1001.pdsync.android.BridgeDiagnostics;

/* loaded from: classes.dex */
final class BridgeTransport {

    interface Acquirer<R> {
        Client<R> acquire() throws Exception;
    }

    interface Client<R> {
        R call() throws Exception;

        void close() throws Exception;
    }

    interface Disposer<R> {
        void discard(R r) throws Exception;
    }

    interface FailureFactory {
        BridgeDiagnostics.Failure create(BridgeDiagnostics.Stage stage, String str, Throwable th, BridgeDiagnostics.TransportInfo transportInfo);
    }

    static final class Result<R> {
        final R reply;
        final BridgeDiagnostics.TransportInfo transport;

        Result(R r, BridgeDiagnostics.TransportInfo transportInfo) {
            this.reply = r;
            this.transport = transportInfo;
        }
    }

    private BridgeTransport() {
    }

    static <R> Result<R> exchange(Acquirer<R> acquirer, Disposer<R> disposer, FailureFactory failures) {
        long started = System.nanoTime();
        final Client<R> client;
        try { client = acquirer.acquire(); }
        catch (Exception e) {
            throw failures.create(BridgeDiagnostics.Stage.PROVIDER_ACQUIRE,
                    e instanceof SecurityException ? "PROVIDER_ACQUIRE_SECURITY_REJECTED" : "PROVIDER_ACQUIRE_FAILED",
                    e, info(false, started, BridgeDiagnostics.ClientClose.NOT_ACQUIRED));
        }
        BridgeDiagnostics.TransportInfo acquired = info(client != null, started, BridgeDiagnostics.ClientClose.NOT_ACQUIRED);
        if (client == null) throw failures.create(BridgeDiagnostics.Stage.PROVIDER_ACQUIRE, "PROVIDER_ACQUIRE_NULL", null, acquired);
        R reply = null;
        Throwable callFailure = null, closeFailure = null;
        try { reply = client.call(); }
        catch (Exception | Error e) { callFailure = e; }
        finally { try { client.close(); } catch (Exception | Error e) { closeFailure = e; } }
        BridgeDiagnostics.TransportInfo detail = new BridgeDiagnostics.TransportInfo(true, acquired.acquireMillis,
                closeFailure == null ? BridgeDiagnostics.ClientClose.CLOSED : BridgeDiagnostics.ClientClose.FAILED);
        if (callFailure != null) {
            if (callFailure instanceof Error) {
                if (closeFailure != null && closeFailure != callFailure) callFailure.addSuppressed(closeFailure);
                throw (Error) callFailure;
            }
            BridgeDiagnostics.Failure failure = failures.create(BridgeDiagnostics.Stage.IPC_CALL,
                    callFailure instanceof SecurityException ? "IPC_SECURITY_REJECTED" : "IPC_CALL_FAILED", callFailure, detail);
            if (closeFailure != null) failure.addSuppressed(failures.create(BridgeDiagnostics.Stage.IPC_CLOSE, "IPC_CLOSE_FAILED", closeFailure, detail));
            throw failure;
        }
        if (closeFailure != null) {
            Throwable failure = closeFailure instanceof Error ? closeFailure : failures.create(BridgeDiagnostics.Stage.IPC_CLOSE, "IPC_CLOSE_FAILED", closeFailure, detail);
            if (reply != null) {
                try { disposer.discard(reply); }
                catch (Exception | Error e) { if (e != failure) failure.addSuppressed(failures.create(BridgeDiagnostics.Stage.IPC_CLOSE, "IPC_REPLY_CLOSE_FAILED", e, detail)); }
            }
            if (failure instanceof Error) throw (Error) failure;
            throw (BridgeDiagnostics.Failure) failure;
        }
        return new Result<>(reply, detail);
    }

    private static BridgeDiagnostics.TransportInfo info(boolean z, long j, BridgeDiagnostics.ClientClose clientClose) {
        return new BridgeDiagnostics.TransportInfo(z, BridgeDiagnostics.boundedAcquireMillis(System.nanoTime() - j), clientClose);
    }
}
