package io.github.xyz1001.pdsync.android;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Host regression checks. These do not run Android Binder, PackageManager, or an APK. */
public final class MainRuntimeRecoveryChecks {
    private static int assertions;
    interface Checked { void run() throws Exception; }
    static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected=" + expected + " actual=" + actual);
    }
    static <T extends Throwable> T fails(Class<T> type, Checked operation) {
        try { operation.run(); }
        catch (Throwable failure) {
            check(type.isInstance(failure), "Expected " + type + " but got " + failure);
            return type.cast(failure);
        }
        throw new AssertionError("Expected " + type);
    }
    static void raise(Throwable failure) throws Exception {
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) throw (Exception) failure;
    }
    static final BridgeTransport.FailureFactory FAILURES = (stage, code, cause, detail) ->
            new BridgeDiagnostics.Failure(stage, code, "Redacted bridge failure", cause, detail);

    public static void main(String[] args) throws Exception {
        bridgeTransport();
        readinessAndDiagnostics();
        packageTrust();
        System.out.println("Main runtime host regression checks passed: " + assertions + " assertions.");
        System.out.println("Boundary: pure Java only; Android service lifecycle, Binder, permissions and device behavior are not executed.");
    }

    static void bridgeTransport() throws Exception {
        AtomicInteger disposed = new AtomicInteger();
        BridgeDiagnostics.Failure missing = fails(BridgeDiagnostics.Failure.class,
                () -> BridgeTransport.exchange(() -> null, r -> disposed.incrementAndGet(), FAILURES));
        equal(BridgeDiagnostics.Stage.PROVIDER_ACQUIRE, missing.stage, "null acquire stage");
        equal("PROVIDER_ACQUIRE_NULL", missing.code, "null acquire code");
        check(!missing.transport.acquired, "null provider not acquired");
        equal(BridgeDiagnostics.ClientClose.NOT_ACQUIRED, missing.transport.close, "null provider never closed");
        for (Exception cause : new Exception[]{new IOException("acquire"), new SecurityException("acquire")}) {
            BridgeDiagnostics.Failure result = fails(BridgeDiagnostics.Failure.class,
                    () -> BridgeTransport.exchange(() -> { throw cause; }, r -> disposed.incrementAndGet(), FAILURES));
            equal(BridgeDiagnostics.Stage.PROVIDER_ACQUIRE, result.stage, "acquire failure stage");
            equal(cause instanceof SecurityException ? "PROVIDER_ACQUIRE_SECURITY_REJECTED" : "PROVIDER_ACQUIRE_FAILED", result.code, "acquire failure code");
            check(result.getCause() == cause, "acquire cause preserved");
            check(!result.transport.acquired, "failed acquire not acquired");
        }
        AssertionError acquireError = new AssertionError("acquire");
        check(fails(AssertionError.class, () -> BridgeTransport.exchange(() -> { throw acquireError; }, r -> {}, FAILURES)) == acquireError,
                "acquire Error propagates unchanged");
        equal(0, disposed.get(), "unacquired replies never disposed");

        Throwable[] calls = {null, new IOException("call"), new SecurityException("call"), new AssertionError("call")};
        Throwable[] closes = {null, new IOException("close"), new SecurityException("close"), new AssertionError("close")};
        for (Throwable call : calls) for (Throwable close : closes) {
            AtomicInteger called = new AtomicInteger(), closed = new AtomicInteger(), discarded = new AtomicInteger();
            Object reply = new Object();
            BridgeTransport.Client<Object> client = new BridgeTransport.Client<>() {
                public Object call() throws Exception { called.incrementAndGet(); raise(call); return reply; }
                public void close() throws Exception { closed.incrementAndGet(); raise(close); }
            };
            if (call == null && close == null) {
                BridgeTransport.Result<Object> result = BridgeTransport.exchange(() -> client, r -> discarded.incrementAndGet(), FAILURES);
                check(result.reply == reply, "success preserves reply");
                check(result.transport.acquired, "success acquired");
                equal(BridgeDiagnostics.ClientClose.CLOSED, result.transport.close, "success closed");
                check(result.transport.acquireMillis >= 0 && result.transport.acquireMillis <= 600000, "timing bounded");
            } else {
                Throwable failure = fails(Throwable.class, () -> BridgeTransport.exchange(() -> client, r -> {
                    check(r == reply, "dispose exact reply"); discarded.incrementAndGet();
                }, FAILURES));
                Throwable primary = call != null ? call : close;
                if (primary instanceof Error) {
                    check(failure == primary, "fatal error retained");
                } else {
                    BridgeDiagnostics.Failure typed = (BridgeDiagnostics.Failure) failure;
                    equal(call == null ? BridgeDiagnostics.Stage.IPC_CLOSE : BridgeDiagnostics.Stage.IPC_CALL, typed.stage, "precise failure stage");
                    equal(call == null ? "IPC_CLOSE_FAILED" : call instanceof SecurityException ? "IPC_SECURITY_REJECTED" : "IPC_CALL_FAILED", typed.code, "precise failure code");
                    check(typed.getCause() == primary, "original cause retained");
                    equal(close == null ? BridgeDiagnostics.ClientClose.CLOSED : BridgeDiagnostics.ClientClose.FAILED, typed.transport.close, "close metadata");
                    check(typed.transport.acquired, "acquired metadata retained");
                }
                if (call != null && close != null && !(call instanceof Error)) {
                    equal(1, failure.getSuppressed().length, "close failure suppressed once");
                    equal("IPC_CLOSE_FAILED", ((BridgeDiagnostics.Failure) failure.getSuppressed()[0]).code, "suppressed close code");
                    check(failure.getSuppressed()[0].getCause() == close, "suppressed close cause retained");
                }
            }
            equal(1, called.get(), "one call only");
            equal(1, closed.get(), "client always closed once");
            equal(call == null && close != null ? 1 : 0, discarded.get(), "reply disposal only after successful call and failed close");
        }
        for (Throwable disposeError : new Throwable[]{new IOException("discard"), new AssertionError("discard")}) {
            IOException close = new IOException("close");
            BridgeDiagnostics.Failure result = fails(BridgeDiagnostics.Failure.class, () -> BridgeTransport.exchange(
                    () -> new BridgeTransport.Client<Object>() {
                        public Object call() { return new Object(); }
                        public void close() throws Exception { throw close; }
                    }, r -> raise(disposeError), FAILURES));
            equal("IPC_CLOSE_FAILED", result.code, "close primary even if disposal fails");
            equal(1, result.getSuppressed().length, "disposal failure retained once");
            BridgeDiagnostics.Failure secondary = (BridgeDiagnostics.Failure) result.getSuppressed()[0];
            equal("IPC_REPLY_CLOSE_FAILED", secondary.code, "disposal failure classified");
            check(secondary.getCause() == disposeError, "disposal cause retained");
        }
        AtomicInteger nullDisposals = new AtomicInteger();
        fails(BridgeDiagnostics.Failure.class, () -> BridgeTransport.exchange(() -> new BridgeTransport.Client<Object>() {
            public Object call() { return null; }
            public void close() throws Exception { throw new IOException(); }
        }, r -> nullDisposals.incrementAndGet(), FAILURES));
        equal(0, nullDisposals.get(), "null reply not disposed");
    }

    static void readinessAndDiagnostics() {
        equal("FIRST_LAUNCH_NEEDED", BridgeReadiness.failureCode(true, true, false), "missing root is first-launch");
        equal("UNRECOGNIZED_SAVE_LAYOUT", BridgeReadiness.failureCode(true, false, false), "unknown layout not empty");
        equal("SAVE_NOT_READY", BridgeReadiness.failureCode(false, false, null), "unready cannot pass");
        equal(null, BridgeReadiness.failureCode(true, false, true), "ready accepted");
        for (InstallationReadiness.AccessStatus access : InstallationReadiness.AccessStatus.values()) {
            InstallationReadiness state = new InstallationReadiness(InstallationReadiness.PackageStatus.VERIFIED,
                    InstallationReadiness.PackageStatus.VERIFIED, true, false, false, access);
            equal(access == InstallationReadiness.AccessStatus.READY, state.canContinue(), "continue requires readiness");
            equal(access == InstallationReadiness.AccessStatus.FIRST_LAUNCH_NEEDED, state.canInitializeGame(), "initial launch safety");
            equal(access == InstallationReadiness.AccessStatus.IPC_ERROR,
                    HelperActivationPolicy.canOffer(state, "PROVIDER_ACQUIRE_NULL", false), "activation only for null acquisition");
            check(!HelperActivationPolicy.canOffer(state, "IPC_SECURITY_REJECTED", false), "activation never bypasses security error");
            check(!HelperActivationPolicy.canOffer(state.withSystemRequest(true), "PROVIDER_ACQUIRE_NULL", false), "no overlapping system action");
        }
        InstallationReadiness invalid = new InstallationReadiness(InstallationReadiness.PackageStatus.NOT_CHECKED,
                InstallationReadiness.PackageStatus.VERIFIED, true, false, false, InstallationReadiness.AccessStatus.READY);
        check(!invalid.installationVerified() && !invalid.canContinue(), "caller cannot assert readiness for unchecked game");
        equal(InstallationReadiness.AccessStatus.NOT_CHECKED, invalid.access, "invalid readiness downgraded");
        HelperActivationPolicy.requireActivity("helper", HelperActivationPolicy.ACTIVITY, "helper", "helper", HelperActivationPolicy.PERMISSION, true, true, true, 42, 42);
        fails(SecurityException.class, () -> HelperActivationPolicy.requireActivity("helper", HelperActivationPolicy.ACTIVITY, "helper", "helper", HelperActivationPolicy.PERMISSION, true, true, true, 42, 43));
        fails(SecurityException.class, () -> HelperActivationPolicy.requireActivity("helper", HelperActivationPolicy.ACTIVITY, "helper", "helper:other", HelperActivationPolicy.PERMISSION, true, true, true, 42, 42));
        equal(0L, BridgeDiagnostics.boundedAcquireMillis(-1), "negative duration bounded");
        equal(600000L, BridgeDiagnostics.boundedAcquireMillis(Long.MAX_VALUE), "maximum duration bounded");
        IOException secret = new IOException("password=private-value https://user:secret@example.test/private");
        secret.setStackTrace(new StackTraceElement[]{new StackTraceElement("android.content.ContentProviderClient", "call", "secret-path", 1)});
        BridgeDiagnostics.Failure failure = new BridgeDiagnostics.Failure(BridgeDiagnostics.Stage.IPC_CALL, "IPC_CALL_FAILED", "private-message", secret);
        String report = BridgeDiagnostics.report(failure, 36, "test", 13, "game", "helper", "authority");
        check(report.contains("IPC_CALL_FAILED") && report.contains("android.content.ContentProviderClient.call"), "diagnostic retains safe stage/origin");
        for (String privateValue : List.of("private-value", "private-message", "secret@example", "secret-path")) check(!report.contains(privateValue), "diagnostic redacts " + privateValue);
        equal("UNKNOWN_BRIDGE_FAILURE", new BridgeDiagnostics.Failure(null, "untrusted-code", "", null).code, "unknown codes constrained");
        equal("", BridgeDiagnostics.remoteOrigins("java.lang.Exception", "Remote stack trace:\nat android.content.ContentProviderClient.call(secret)"), "remote origin only from RemoteException");
        equal("android.content.ContentProviderClient.call", BridgeDiagnostics.remoteOrigins("android.os.RemoteException", "Remote stack trace:\n at android.content.ContentProviderClient.call(secret)\n at evil.private.Method(secret)"), "remote origin allowlist");
    }

    static void packageTrust() throws Exception {
        TrustFixture valid = new TrustFixture();
        check(!PackageTrust.requireBridgeForExchange(valid.context, valid.pair, valid.pin), "bound helper passes exact metadata fixture");
        equal(134217728 | 128, valid.lastFlags, "reads signer and metadata flags");
        List<java.util.function.Consumer<TrustFixture>> corruptions = List.of(
                f -> f.pin = "invalid",
                f -> f.game.signingInfo.multiple = true,
                f -> f.game.signingInfo.signatures = new android.content.pm.Signature[0],
                f -> f.helper.signingInfo = null,
                f -> f.helper.signingInfo.signatures = new android.content.pm.Signature[]{new android.content.pm.Signature(new byte[]{9})},
                f -> f.helper.applicationInfo.uid = 300,
                f -> f.main.applicationInfo.uid = 200,
                f -> f.game.sharedUserId = "other",
                f -> f.helper.sharedUserId = "other",
                f -> f.sharedMembers = new String[]{"game", "helper", "intruder"},
                f -> f.sharedMembers = new String[]{"game", "game"},
                f -> f.sharedMembers = null,
                f -> f.mainMembers = new String[]{f.mainName, "other"},
                f -> f.mainMembers = null,
                f -> f.provider = null,
                f -> f.provider.packageName = "other",
                f -> f.provider.name = "other.Provider",
                f -> f.provider.exported = false,
                f -> f.provider.grantUriPermissions = true,
                f -> f.provider.readPermission = "other",
                f -> f.provider.writePermission = null,
                f -> f.helper.applicationInfo.metaData = null,
                f -> f.helper.applicationInfo.metaData.put("io.github.xyz1001.pdsync.BOUND_GAME_PACKAGE", "other"),
                f -> f.helper.applicationInfo.metaData.put("io.github.xyz1001.pdsync.BOUND_ORIGIN_IDENTITY", "other"),
                f -> f.helper.applicationInfo.metaData.put("io.github.xyz1001.pdsync.BOUND_SHARED_USER_ID", "other"),
                f -> f.helper.applicationInfo.metaData.put("io.github.xyz1001.pdsync.BOUND_SOURCE_APK_SHA256", "other"));
        for (java.util.function.Consumer<TrustFixture> mutation : corruptions) {
            TrustFixture changed = new TrustFixture();
            mutation.accept(changed);
            fails(SecurityException.class, () -> PackageTrust.requireBridgeForExchange(changed.context, changed.pair, changed.pin));
        }
        fails(SecurityException.class, () -> PackageTrust.requireBridgeForExchange(valid.context, null, valid.pin));
        String a = "a".repeat(64), b = "b".repeat(64);
        equal(ApkSetIdentity.digest(List.of(a,b)), ApkSetIdentity.digest(List.of(b,a)), "APK identity independent of split ordering");
        check(!ApkSetIdentity.digest(List.of(a)).equals(ApkSetIdentity.digest(List.of(a,a))), "duplicate APK count affects exact identity");
        fails(IllegalArgumentException.class, () -> ApkSetIdentity.digest(List.of("A".repeat(64))));
        check(!PackageTrust.legacyRecoveryIdentityAllowed(false,true,true,"com.shatteredpixel.shatteredpixeldungeon","io.github.xyz1001.pdsync.bridge","io.github.xyz1001.pdsync.shared","io.github.xyz1001.pdsync.bridge.saves","32cd68da7bd2cdb467330cf33ca7c0e9b18eaae94ecf0b4b944313def1732f89"), "no legacy bypass without recovery marker");
        System.out.println("PackageTrust: real policy against host-only fake package metadata; installed Android identity is not runtime-tested.");
    }

    static final class TrustFixture extends android.content.pm.PackageManager {
        final String mainName = "io.github.xyz1001.pdsync.android";
        final io.github.xyz1001.pdsync.android.prepare.GamePreparationService.PreparedPair pair = new io.github.xyz1001.pdsync.android.prepare.GamePreparationService.PreparedPair();
        final android.content.pm.PackageInfo game = info("game", 200), helper = info("helper", 200), main = info(mainName, 100);
        android.content.pm.ProviderInfo provider = new android.content.pm.ProviderInfo();
        String[] sharedMembers = {"game", "helper"}, mainMembers = {mainName};
        String pin;
        int lastFlags;
        final android.content.Context context = new android.content.Context() {
            public android.content.pm.PackageManager getPackageManager() { return TrustFixture.this; }
            public String getPackageName() { return mainName; }
            public java.io.File getFilesDir() { throw new AssertionError("No filesystem needed for bound trust fixture"); }
        };
        TrustFixture() throws Exception {
            pair.gamePackage = "game"; pair.helperPackage = "helper"; pair.sharedUid = "shared";
            pair.authority = "helper.saves"; pair.originIdentity = "a".repeat(64); pair.sourceApkSetSha256 = "b".repeat(64);
            pin = PackageTrust.signer(game);
            helper.applicationInfo.metaData = new android.os.Bundle();
            helper.applicationInfo.metaData.put("io.github.xyz1001.pdsync.BOUND_GAME_PACKAGE", pair.gamePackage);
            helper.applicationInfo.metaData.put("io.github.xyz1001.pdsync.BOUND_ORIGIN_IDENTITY", pair.originIdentity);
            helper.applicationInfo.metaData.put("io.github.xyz1001.pdsync.BOUND_SHARED_USER_ID", pair.sharedUid);
            helper.applicationInfo.metaData.put("io.github.xyz1001.pdsync.BOUND_SOURCE_APK_SHA256", pair.sourceApkSetSha256);
            provider.packageName = "helper"; provider.name = "io.github.xyz1001.pdsync.bridge.SaveBridgeProvider";
            provider.readPermission = PackageTrust.ACCESS_BRIDGE; provider.writePermission = PackageTrust.ACCESS_BRIDGE; provider.exported = true;
        }
        static android.content.pm.PackageInfo info(String name, int uid) {
            android.content.pm.PackageInfo p = new android.content.pm.PackageInfo();
            p.packageName = name; p.sharedUserId = "shared"; p.applicationInfo = new android.content.pm.ApplicationInfo(); p.applicationInfo.uid = uid;
            p.signingInfo = new android.content.pm.SigningInfo(); p.signingInfo.signatures = new android.content.pm.Signature[]{new android.content.pm.Signature(new byte[]{1,2,3})};
            return p;
        }
        public android.content.pm.PackageInfo getPackageInfo(String name, int flags) throws NameNotFoundException {
            lastFlags = flags;
            if ("game".equals(name)) return game;
            if ("helper".equals(name)) return helper;
            if (mainName.equals(name)) return main;
            throw new NameNotFoundException();
        }
        public String[] getPackagesForUid(int uid) { return uid == 200 ? sharedMembers : mainMembers; }
        public android.content.pm.ProviderInfo resolveContentProvider(String authority, int flags) { return provider; }
    }
}
