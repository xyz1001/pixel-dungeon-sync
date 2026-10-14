"""Extract current production access methods into a host harness with counted fake DAV I/O."""
from pathlib import Path
import sys

repo, output = map(Path, sys.argv[1:])
base = repo / "android-app/src/main/java/io/github/xyz1001/pdsync/android"
main = (base / "MainActivity.java").read_text()
client = (base / "ManagedAccountClient.java").read_text()


def method(source, signature):
    start = source.index(signature)
    brace = source.index("{", start)
    depth, end = 1, brace + 1
    while depth:
        if source[end] == "{":
            depth += 1
        elif source[end] == "}":
            depth -= 1
        end += 1
    return source[start:end]


production = "\n".join(method(main, name) for name in (
    "private static boolean isOfficialAccount(",
    "private static ManagedAccountClient.Entitlement verifyOfficialConnection(",
    "private AndroidSyncFacade.OperationResult restoreForCurrentAccount(",
))
gate = method(client, "void requireSyncAccess()")
harness = r'''
package io.github.xyz1001.pdsync.android;
import java.io.IOException;
public final class ManagedAccountAccessHarness {
    private SecureConnectionStore.Connection connection;
    private final AndroidSyncFacade facade = new AndroidSyncFacade();
    private static int checks, saved;
    private static final android.content.Context APP = new android.content.Context();
    private android.content.Context getApplicationContext() { return APP; }
    private AndroidSyncFacade sync() { return facade; }
    private static void rememberEntitlement(android.content.Context app, SecureConnectionStore.Connection account,
                                            ManagedAccountClient.Entitlement entitlement) { saved++; }
    static final class OfficialServiceRegistrationClient { static final String DAV_URL = "https://webdav.xyz1001.top:8443/"; }
    static final class SecureConnectionStore {
        static final class Connection {
            static final String PROVIDER_OFFICIAL = "official";
            String provider = "official", endpoint = OfficialServiceRegistrationClient.DAV_URL;
            String username = "synthetic-account", password = "synthetic-password";
        }
    }
    static final class AndroidSyncFacade {
        int connections, readOnlyProbes, restores, readOnlyRestores;
        boolean passes = true;
        static final class ConnectionStatus { boolean success; boolean isSuccess() { return success; } }
        static final class OperationResult { }
        static final class Inspection { }
        ConnectionStatus connect() { connections++; ConnectionStatus status = new ConnectionStatus(); status.success = passes; return status; }
        ConnectionStatus testConnection() { readOnlyProbes++; throw new AssertionError("Deprecated probe"); }
        OperationResult restoreSelected(Inspection inspection, String snapshotId, boolean replace) { restores++; return new OperationResult(); }
        OperationResult restoreSelectedReadOnly(Inspection inspection, String snapshotId, boolean replace) { readOnlyRestores++; throw new AssertionError("Deprecated restore"); }
    }
    static final class ManagedAccountClient {
        static int reads;
        static Entitlement next;
        static Runnable afterRead;
        enum FailureKind { ACCOUNT_EXPIRED, ACCOUNT_INACTIVE }
        static final class ManagedAccountException extends IOException {
            private static final long serialVersionUID = 1L;
            final FailureKind kind;
            ManagedAccountException(FailureKind kind) { this.kind = kind; }
        }
        static ManagedAccountException failure(FailureKind kind) { return new ManagedAccountException(kind); }
        static final class Entitlement {
            boolean active; Long expiresMs, serverTimeMs;
            ENTITLEMENT_GATE
        }
        ManagedAccountClient(String endpoint, String username, String password) { }
        Entitlement entitlement() throws IOException {
            reads++;
            if (afterRead != null) { Runnable action = afterRead; afterRead = null; action.run(); }
            if (next == null) throw new IOException("Synthetic unknown authentication/network failure");
            return next;
        }
    }
    PRODUCTION_METHODS
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private interface Action { void run() throws Exception; }
    private static void rejected(Action action, ManagedAccountClient.FailureKind kind) throws Exception {
        try { action.run(); throw new AssertionError("Expected rejection"); }
        catch (ManagedAccountClient.ManagedAccountException failure) { check(failure.kind == kind, "Typed rejection"); }
    }
    private static void account(boolean active, Long expiry, Long serverTime) {
        ManagedAccountClient.Entitlement value = new ManagedAccountClient.Entitlement();
        value.active = active; value.expiresMs = expiry; value.serverTimeMs = serverTime;
        ManagedAccountClient.next = value; ManagedAccountClient.reads = 0;
    }
    public static void main(String[] args) throws Exception {
        ManagedAccountAccessHarness h = new ManagedAccountAccessHarness();
        h.connection = new SecureConnectionStore.Connection();
        account(false, 100L, 100L);
        rejected(() -> verifyOfficialConnection(h.connection, h.facade, APP), ManagedAccountClient.FailureKind.ACCOUNT_EXPIRED);
        rejected(() -> h.restoreForCurrentAccount(new AndroidSyncFacade.Inspection(), "snapshot", false), ManagedAccountClient.FailureKind.ACCOUNT_EXPIRED);
        rejected(() -> h.restoreForCurrentAccount(new AndroidSyncFacade.Inspection(), "snapshot", true), ManagedAccountClient.FailureKind.ACCOUNT_EXPIRED);
        check(h.facade.connections == 0 && h.facade.readOnlyProbes == 0 && h.facade.restores == 0 && h.facade.readOnlyRestores == 0,
                "Known expired official stops every probe/download/restore before fake DAV");
        for (Long[] dates : new Long[][] {{100L, null}, {null, 100L}, {200L, 100L}}) {
            account(false, dates[0], dates[1]);
            rejected(() -> verifyOfficialConnection(h.connection, h.facade, APP), ManagedAccountClient.FailureKind.ACCOUNT_INACTIVE);
            rejected(() -> h.restoreForCurrentAccount(new AndroidSyncFacade.Inspection(), "snapshot", true), ManagedAccountClient.FailureKind.ACCOUNT_INACTIVE);
        }
        check(h.facade.connections == 0 && h.facade.restores == 0, "Unknown inactive accounts also do no DAV without claiming expiry");
        account(true, 300L, 101L);
        verifyOfficialConnection(h.connection, h.facade, APP);
        h.restoreForCurrentAccount(new AndroidSyncFacade.Inspection(), "snapshot", true);
        check(h.facade.connections == 1 && h.facade.restores == 1, "Authenticated renewal restores ordinary active path");
        h.facade.passes = false;
        try { verifyOfficialConnection(h.connection, h.facade, APP); throw new AssertionError("False DAV proof"); }
        catch (IOException expected) { check(!(expected instanceof ManagedAccountClient.ManagedAccountException), "Active metadata alone cannot grant Next"); }
        ManagedAccountClient.next = null;
        int before = h.facade.restores;
        try { h.restoreForCurrentAccount(new AndroidSyncFacade.Inspection(), "snapshot", true); throw new AssertionError("Unknown authority"); }
        catch (IOException expected) { check(!(expected instanceof ManagedAccountClient.ManagedAccountException), "Unknown service error is not expiry"); }
        check(h.facade.restores == before, "Unknown current authority does not start restore");
        h.connection.provider = "custom";
        ManagedAccountClient.reads = 0;
        h.restoreForCurrentAccount(new AndroidSyncFacade.Inspection(), "snapshot", false);
        check(ManagedAccountClient.reads == 0 && h.facade.restores == before + 1, "Custom DAV keeps original path without account requests");
        h.connection = new SecureConnectionStore.Connection();
        account(true, 300L, 101L);
        ManagedAccountClient.afterRead = () -> h.connection = new SecureConnectionStore.Connection();
        before = h.facade.restores;
        try { h.restoreForCurrentAccount(new AndroidSyncFacade.Inspection(), "snapshot", true); throw new AssertionError("Changed account"); }
        catch (IOException expected) { check(h.facade.restores == before, "Account replacement aborts before restore"); }
        check(saved > 0, "Only successful entitlement results reach display-cache handoff");
        System.out.println("ManagedAccountAccessHarness: " + checks + " current production-method checks passed; counted fake I/O only.");
    }
}
'''.replace("ENTITLEMENT_GATE", gate).replace("PRODUCTION_METHODS", production)
(output / "ManagedAccountAccessHarness.java").write_text(harness)
context = output / "android/content/Context.java"
context.parent.mkdir(parents=True, exist_ok=True)
context.write_text("package android.content; public class Context {}\n")
