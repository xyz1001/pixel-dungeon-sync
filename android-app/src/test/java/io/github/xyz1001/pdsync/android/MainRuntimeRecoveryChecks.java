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
        connectionPersistence();
        connectionProviderMetadata();
        officialRootMigration();
        officialMigrationCannotOverwriteLaterSave();
        endpointPolicy();
        legacyConnection();
        autoSyncPolicy();
        installConfirmation();
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
        for (String archiveStage : List.of("ARCHIVE_DESCRIPTOR_STAT", "ARCHIVE_DESCRIPTOR_FLAGS",
                "ARCHIVE_DESCRIPTOR_READ", "ARCHIVE_DESCRIPTOR_WRITE",
                "ARCHIVE_DESCRIPTOR_RECHECK", "EXTRACT_ARCHIVE")) {
            Throwable detail = BridgeDiagnostics.helperFileDetail(archiveStage, "ANDROID_ERRNO", 13);
            check(detail != null, "new archive phase accepted: " + archiveStage);
            String archiveReport = BridgeDiagnostics.report(new BridgeDiagnostics.Failure(
                    BridgeDiagnostics.Stage.RESTORE, "SAVE_IO_ERROR", "private archive path", detail),
                    26, "test", 20, "game", "helper", "authority");
            check(archiveReport.contains("helper_file_stage=" + archiveStage), "precise archive phase retained");
            check(archiveReport.contains("helper_file_error=ANDROID_ERRNO")
                    && archiveReport.contains("helper_file_errno=13"), "bounded archive errno retained");
            check(!archiveReport.contains("private archive path"), "archive failure message redacted");
        }
        check(BridgeDiagnostics.helperFileDetail("private/path", "IO", -1) == null, "unknown archive stage rejected");
        check(BridgeDiagnostics.helperFileDetail("ARCHIVE_DESCRIPTOR_FLAGS", "IO", 4096) == null, "invalid archive errno rejected");
        equal("UNKNOWN_BRIDGE_FAILURE", new BridgeDiagnostics.Failure(null, "untrusted-code", "", null).code, "unknown codes constrained");
        equal("", BridgeDiagnostics.remoteOrigins("java.lang.Exception", "Remote stack trace:\nat android.content.ContentProviderClient.call(secret)"), "remote origin only from RemoteException");
        equal("android.content.ContentProviderClient.call", BridgeDiagnostics.remoteOrigins("android.os.RemoteException", "Remote stack trace:\n at android.content.ContentProviderClient.call(secret)\n at evil.private.Method(secret)"), "remote origin allowlist");
    }


    static void connectionPersistence() throws Exception {
        Path root = Files.createTempDirectory("pdsync-connection-check-");
        try {
            WebDavConnectionFile store = new WebDavConnectionFile(root.toFile());
            check(!store.exists(), "absent configuration reported absent");
            equal(null, store.load(), "absent configuration loads as null, not corrupt");
            equal(null, store.loadOrMigrate(() -> null), "absent legacy configuration is not an error");
            WebDavConnectionFile.Value old = new WebDavConnectionFile.Value("https://dav.example.test/path", "old-user", "old-password", "profile",
                    WebDavConnectionFile.Value.PROVIDER_OFFICIAL);
            store.save(old);
            equal("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve(WebDavConnectionFile.FILE_NAME))), "saved credentials are owner-only");
            sameConnection(old, store.load());
            byte[] original = Files.readAllBytes(root.resolve(WebDavConnectionFile.FILE_NAME));
            WebDavConnectionFile.Value updated = new WebDavConnectionFile.Value("https://dav.example.test/next", "", "", "new-profile");
            AtomicInteger saved = new AtomicInteger();
            fails(IOException.class, () -> ConnectionUpdatePolicy.apply(true, false, () -> { throw new IOException("failed test"); }, () -> { saved.incrementAndGet(); store.save(updated); }));
            equal(0, saved.get(), "failed connection test never saves");
            check(Arrays.equals(original, Files.readAllBytes(root.resolve(WebDavConnectionFile.FILE_NAME))), "failed test preserves original bytes");
            ConnectionUpdatePolicy.apply(false, false, () -> {}, () -> { saved.incrementAndGet(); store.save(updated); });
            equal(0, saved.get(), "non-saving or cancelled edit does not persist");
            ConnectionUpdatePolicy.apply(true, true, () -> {}, () -> { saved.incrementAndGet(); store.save(updated); });
            equal(0, saved.get(), "temporary test never persists");
            for (int mode = 0; mode < 4; mode++) {
                final int failureMode = mode;
                final byte[][] suppliedBytes = new byte[1][];
                WebDavConnectionFile broken = new WebDavConnectionFile(root.toFile(), new WebDavConnectionFile.FileWriter() {
                    public void writeAndSync(Path file, byte[] bytes) throws IOException {
                        suppliedBytes[0] = bytes;
                        equal("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)), "temporary credentials private before write");
                        Files.write(file, bytes);
                        if (failureMode == 0) throw new IOException("disk full");
                        if (failureMode == 2) throw new SecurityException("write denied");
                        if (failureMode == 3) throw new AssertionError("fatal write");
                    }
                    public void replace(Path from, Path to) throws IOException {
                        throw new java.nio.file.AtomicMoveNotSupportedException(from.toString(), to.toString(), "simulated unsupported filesystem");
                    }
                });
                if (mode == 3) fails(AssertionError.class, () -> broken.save(updated));
                else fails(IOException.class, () -> broken.save(updated));
                check(Arrays.equals(original, Files.readAllBytes(root.resolve(WebDavConnectionFile.FILE_NAME))), "failed save preserves exact old bytes");
                sameConnection(old, new WebDavConnectionFile(root.toFile()).load());
                check(suppliedBytes[0] != null, "writer exercised");
                check(Arrays.equals(new byte[suppliedBytes[0].length], suppliedBytes[0]), "serialized credential buffer wiped after failure");
                try (java.util.stream.Stream<Path> children = Files.list(root)) {
                    equal(1L, children.count(), "failed save cleans credential temporary file");
                }
            }
            WebDavConnectionFile.Value temporary = new WebDavConnectionFile.Value("http://127.0.0.1:8765/dav", "", "", "test");
            fails(IOException.class, () -> store.save(temporary));
            fails(IOException.class, () -> store.save(new WebDavConnectionFile.Value(temporary.endpoint, "", "", "test",
                    WebDavConnectionFile.Value.PROVIDER_OFFICIAL)));
            check(Arrays.equals(original, Files.readAllBytes(root.resolve(WebDavConnectionFile.FILE_NAME))), "loopback test cannot overwrite saved HTTPS");
            sameConnection(old, store.load());
            ConnectionUpdatePolicy.apply(true, false, () -> {}, () -> store.save(updated));
            sameConnection(updated, store.load());
            check(!updated.toString().contains(updated.endpoint), "connection string hides endpoint and credentials");
            store.forget();
            equal(null, store.load(), "forget removes saved config");
            sameConnection(old, store.loadOrMigrate(() -> old));
            sameConnection(old, store.loadOrMigrate(() -> { throw new AssertionError("new file takes precedence"); }));

            Path config = root.resolve(WebDavConnectionFile.FILE_NAME);
            byte[][] malformed = {
                    new byte[]{(byte)0xff},
                    "version=2\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "version=1\nendpoint=https://example.test\nprofile=test\nusername=\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "version=1\nendpoint=http://127.0.0.1\nprofile=test\nusername=\npassword=\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    new byte[65537]
            };
            for (byte[] invalid : malformed) {
                Files.write(config, invalid);
                fails(IOException.class, () -> store.loadOrMigrate(() -> { throw new AssertionError("corrupt current file must not silently migrate"); }));
                check(Arrays.equals(invalid, Files.readAllBytes(config)), "invalid configuration preserved for recovery");
            }
            Files.delete(config);
            Files.createDirectory(config);
            fails(IOException.class, store::load);
            Files.delete(config);
            Path outside = root.resolve("outside");
            Files.writeString(outside, "private content");
            Files.createSymbolicLink(config, outside);
            fails(IOException.class, store::load);
            equal("private content", Files.readString(outside), "symlink read never alters target");
            store.forget();
            check(Files.exists(outside), "forget only unlinks configuration symlink");
        } finally { deleteTree(root); }
    }

    static void sameConnection(WebDavConnectionFile.Value expected, WebDavConnectionFile.Value actual) {
        check(actual != null, "connection exists");
        equal(expected.endpoint, actual.endpoint, "endpoint round trip");
        equal(expected.username, actual.username, "username round trip including empty");
        equal(expected.password, actual.password, "password round trip including empty");
        equal(expected.profile, actual.profile, "profile round trip");
        equal(expected.provider, actual.provider, "provider round trip");
    }

    static void connectionProviderMetadata() throws Exception {
        Path root = Files.createTempDirectory("pdsync-provider-check-");
        try {
            Path config = root.resolve(WebDavConnectionFile.FILE_NAME);
            WebDavConnectionFile store = new WebDavConnectionFile(root.toFile());
            WebDavConnectionFile.Value custom = new WebDavConnectionFile.Value("https://dav.example.test/path", "user", "", "profile");
            equal(WebDavConnectionFile.Value.PROVIDER_CUSTOM, custom.provider, "four-argument constructor defaults to custom");
            custom.validate();
            store.save(custom);
            check(Files.readString(config).contains("provider=custom"), "saved custom connection includes provider");
            sameConnection(custom, new WebDavConnectionFile(root.toFile()).load());

            WebDavConnectionFile.Value official = new WebDavConnectionFile.Value(custom.endpoint, custom.username, "synthetic-password", custom.profile,
                    WebDavConnectionFile.Value.PROVIDER_OFFICIAL);
            store.save(official);
            check(Files.readString(config).contains("provider=official"), "saved official connection includes provider");
            sameConnection(official, new WebDavConnectionFile(root.toFile()).load());
            // Both providers can describe identical connection coordinates; URLs never choose the marker.
            equal(custom.endpoint, official.endpoint, "provider does not rewrite endpoint");
            equal(custom.profile, official.profile, "provider does not rewrite profile");

            String legacy = "version=1\nendpoint=https://dav.example.test/path\nusername=user\npassword=\nprofile=profile\n";
            Files.writeString(config, legacy);
            sameConnection(custom, store.load());
            equal(legacy, Files.readString(config), "reading an old unmarked file does not rewrite it");
            for (String unknown : new String[]{"", "future-provider", "OFFICIAL", "official ", "https://dav.example.test/path"}) {
                Files.writeString(config, legacy + "provider=" + unknown + "\n");
                sameConnection(custom, store.load());
                equal(WebDavConnectionFile.Value.PROVIDER_CUSTOM,
                        new WebDavConnectionFile.Value(custom.endpoint, custom.username, custom.password, custom.profile, unknown).provider,
                        "unrecognized constructor marker safely defaults to custom");
            }
            equal(WebDavConnectionFile.Value.PROVIDER_CUSTOM,
                    new WebDavConnectionFile.Value(custom.endpoint, custom.username, custom.password, custom.profile, null).provider,
                    "null provider defaults to custom");
            store.forget();
            sameConnection(custom, store.loadOrMigrate(() -> custom));
            sameConnection(custom, new WebDavConnectionFile(root.toFile()).load());
            check(Files.readString(config).contains("provider=custom"), "legacy four-field migration persists custom metadata");

            for (String provider : new String[]{WebDavConnectionFile.Value.PROVIDER_CUSTOM, WebDavConnectionFile.Value.PROVIDER_OFFICIAL}) {
                fails(IOException.class, () -> new WebDavConnectionFile.Value("http://example.test", "user", "password", "profile", provider).validate());
                fails(IOException.class, () -> new WebDavConnectionFile.Value(custom.endpoint, "user", "password", "../bad", provider).validate());
            }
        } finally { deleteTree(root); }
    }
    static void officialRootMigration() throws Exception {
        Path root = Files.createTempDirectory("pdsync-official-root-check-");
        try {
            Path config = root.resolve(WebDavConnectionFile.FILE_NAME);
            String old = WebDavConnectionFile.LEGACY_OFFICIAL_ENDPOINT;
            String current = WebDavConnectionFile.OFFICIAL_ROOT_ENDPOINT;
            String fields = "version=1\nendpoint=" + old + "\nusername=synthetic\npassword=test-only\nprofile=hero\nextraSetting=preserve-me\n";
            WebDavConnectionFile store = new WebDavConnectionFile(root.toFile());
            for (String provider : new String[]{"", "provider=custom\n", "provider=OFFICIAL\n", "provider=official \n"}) {
                String original = fields + provider;
                Files.writeString(config, original);
                WebDavConnectionFile.Value value = store.load();
                equal(old, value.endpoint, "unmarked and custom old URL remain unchanged");
                equal(original, Files.readString(config), "unmarked and custom settings retain exact bytes");
                check(!value.retainsLegacyOfficialScope(), "custom source never receives an alias");
            }
            for (String endpoint : new String[]{old + "child/", "https://other.invalid/dav/", "https://webdav.xyz1001.top:8443/dav", old + " "}) {
                String original = fields.replace(old, endpoint) + "provider=official\n";
                Files.writeString(config, original); store.load();
                equal(original, Files.readString(config), "nearby or other official-marked endpoint is not migrated");
            }
            String original = fields + "provider=official\n";
            for (boolean failReplace : new boolean[]{false, true}) {
                Files.writeString(config, original);
                WebDavConnectionFile broken = new WebDavConnectionFile(root.toFile(), new WebDavConnectionFile.FileWriter() {
                    public void writeAndSync(Path path, byte[] bytes) throws IOException {
                        if (!failReplace) throw new IOException("Synthetic write failure");
                        WebDavConnectionFile.SYSTEM_WRITER.writeAndSync(path, bytes);
                    }
                    public void replace(Path from, Path to) throws IOException { throw new IOException("Synthetic replace failure"); }
                });
                fails(IOException.class, broken::load);
                equal(original, Files.readString(config), "failed migration retains original record exactly");
                try (java.util.stream.Stream<Path> files = Files.list(root)) {
                    equal(1L, files.count(), "failed migration cleans temporary record");
                }
            }
            WebDavConnectionFile.Value migrated = store.load();
            equal(current, migrated.endpoint, "official exact old endpoint migrates to root");
            equal(old, migrated.stateEndpoint(), "official root keeps original local identity");
            equal("synthetic", migrated.username, "username preserved");
            equal("test-only", migrated.password, "password preserved");
            equal("hero", migrated.profile, "profile and namespace are not reset");
            java.util.Properties saved = new java.util.Properties();
            saved.load(new java.io.StringReader(Files.readString(config)));
            equal("preserve-me", saved.getProperty("extraSetting"), "unrelated saved settings preserved");
            byte[] once = Files.readAllBytes(config); sameConnection(migrated, store.load());
            check(Arrays.equals(once, Files.readAllBytes(config)), "migration is idempotent");
            WebDavConnectionFile.Value relogin = new WebDavConnectionFile.Value(current, "synthetic", "new-test-only", "hero", "official");
            store.save(relogin);
            equal(old, store.load().stateEndpoint(), "official relogin/save naturally preserves local identity");
            WebDavConnectionFile.Value custom = new WebDavConnectionFile.Value(current, "synthetic", "test-only", "hero");
            store.save(custom); byte[] customBytes = Files.readAllBytes(config);
            equal(current, store.load().stateEndpoint(), "custom root does not inherit official local identity");
            check(Arrays.equals(customBytes, Files.readAllBytes(config)), "custom root settings retain exact bytes");
        } finally { deleteTree(root); }
    }

    static void officialMigrationCannotOverwriteLaterSave() throws Exception {
        Path root = Files.createTempDirectory("pdsync-migration-race-");
        java.util.concurrent.CountDownLatch migrationWriting = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseMigration = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch saveStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread migration = null, save = null;
        try {
            Files.writeString(root.resolve(WebDavConnectionFile.FILE_NAME), "version=1\nendpoint="
                    + WebDavConnectionFile.LEGACY_OFFICIAL_ENDPOINT
                    + "\nusername=old-user\npassword=test-only\nprofile=hero\nprovider=official\n");
            WebDavConnectionFile reader = new WebDavConnectionFile(root.toFile(), new WebDavConnectionFile.FileWriter() {
                public void writeAndSync(Path path, byte[] bytes) throws IOException {
                    WebDavConnectionFile.SYSTEM_WRITER.writeAndSync(path, bytes);
                    migrationWriting.countDown();
                    try {
                        if (!releaseMigration.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("Synthetic latch timed out");
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                }
                public void replace(Path from, Path to) throws IOException { WebDavConnectionFile.SYSTEM_WRITER.replace(from, to); }
            });
            WebDavConnectionFile writer = new WebDavConnectionFile(root.toFile());
            WebDavConnectionFile.Value updated = new WebDavConnectionFile.Value("https://custom.invalid/", "new-user", "test-only", "other");
            migration = new Thread(() -> { try { reader.load(); } catch (Throwable e) { failure.set(e); } });
            migration.start(); check(migrationWriting.await(5, java.util.concurrent.TimeUnit.SECONDS), "migration reaches write barrier");
            save = new Thread(() -> { saveStarted.countDown(); try { writer.save(updated); } catch (Throwable e) { failure.set(e); } });
            save.start(); check(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS), "later save starts on another store instance");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (save.isAlive() && save.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield();
            check(save.getState() == Thread.State.BLOCKED, "later save serializes behind in-flight migration");
            releaseMigration.countDown(); migration.join(5000); save.join(5000);
            check(!migration.isAlive() && !save.isAlive(), "serialized migration and save finish");
            raise(failure.get()); sameConnection(updated, new WebDavConnectionFile(root.toFile()).load());
        } finally {
            releaseMigration.countDown();
            if (migration != null) migration.join(5000);
            if (save != null) save.join(5000);
            deleteTree(root);
        }
    }

    static void deleteTree(Path root) throws Exception {
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
    static void endpointPolicy() throws Exception {
        for (String valid : List.of("https://example.test", "HTTPS://example.test:443/a%20b", "http://127.0.0.1", "http://127.0.0.1:65535/a")) {
            ConnectionEndpointPolicy.validate(valid, "", ""); assertions++;
        }
        for (String invalid : List.of("http://localhost", "http://127.1", "http://2130706433", "http://127.0.0.2", "http://[::1]", "http://127.0.0.1.example.test", "http://127.0.0.1:0", "http://127.0.0.1:65536", "https://user:pass@example.test", "https://example.test/?secret=x", "https://example.test/#fragment", "https://example.test/a//b", "https://example.test/../b", "https://example.test/%2e%2e", "https://example.test/%2f", "https://example.test/%5c", "https://example.test/%252e", "https://example.test/%00", "https://example.test/\\path")) {
            fails(IOException.class, () -> ConnectionEndpointPolicy.validate(invalid, "", ""));
        }
        fails(IOException.class, () -> ConnectionEndpointPolicy.validate("http://127.0.0.1", "name", ""));
        fails(IOException.class, () -> ConnectionEndpointPolicy.validate("http://127.0.0.1", "", "password"));
        fails(IOException.class, () -> ConnectionEndpointPolicy.validate("https://example.test", "user:name", ""));
        fails(IOException.class, () -> ConnectionEndpointPolicy.validate("https://example.test", "", "secret\nheader"));
        fails(IOException.class, () -> new WebDavConnectionFile.Value("https://example.test", "", "", "../bad").validate());
    }

    static void legacyConnection() throws Exception {
        Path root = Files.createTempDirectory("pdsync-legacy-check-");
        Path old = root.resolve("webdav.enc");
        javax.crypto.SecretKey key = new javax.crypto.spec.SecretKeySpec(new byte[16], "AES"); // Synthetic test input only.
        try {
            equal(null, LegacyWebDavConnectionFile.read(old.toFile(), () -> key), "missing legacy file is normal");
            Files.write(root.resolve("webdav.enc.new"), new byte[]{1});
            fails(IOException.class, () -> LegacyWebDavConnectionFile.read(old.toFile(), () -> key));
            check(Files.exists(root.resolve("webdav.enc.new")), "interrupted legacy write preserved");
            Files.delete(root.resolve("webdav.enc.new"));
            byte[] plain = "synthetic-test-value".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] iv = new byte[12];
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, new javax.crypto.spec.GCMParameterSpec(128, iv));
            cipher.updateAAD("pdsync:main:webdav:v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plain);
            try (java.io.DataOutputStream output = new java.io.DataOutputStream(Files.newOutputStream(old))) {
                output.writeInt(1); output.writeInt(iv.length); output.write(iv); output.writeInt(encrypted.length); output.write(encrypted);
            }
            byte[] valid = Files.readAllBytes(old);
            check(Arrays.equals(plain, LegacyWebDavConnectionFile.read(old.toFile(), () -> key)), "legacy AES-GCM round trip");
            Files.copy(old, root.resolve("webdav.enc.bak"));
            Files.write(old, new byte[]{0});
            check(Arrays.equals(plain, LegacyWebDavConnectionFile.read(old.toFile(), () -> key)), "AtomicFile backup has precedence");
            Files.delete(root.resolve("webdav.enc.bak"));
            Files.write(old, valid);
            fails(IOException.class, () -> LegacyWebDavConnectionFile.read(old.toFile(), () -> { throw new IOException("missing key"); }));
            check(Arrays.equals(valid, Files.readAllBytes(old)), "failed decryption does not destroy original");
            byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
            Files.write(old, trailing);
            fails(IOException.class, () -> LegacyWebDavConnectionFile.read(old.toFile(), () -> key));
            Files.write(old, new byte[]{0});
            long descriptorsBefore = descriptorCount();
            for (int i = 0; i < 64; i++) fails(IOException.class, () -> LegacyWebDavConnectionFile.read(old.toFile(), () -> key));
            long descriptorsAfter = descriptorCount();
            if (descriptorsBefore >= 0 && descriptorsAfter >= 0) check(descriptorsAfter <= descriptorsBefore + 2, "invalid legacy reads close descriptors on failure");
        } finally { deleteTree(root); }
    }
    static long descriptorCount() throws Exception {
        if (!Files.isDirectory(Path.of("/proc/self/fd"))) return -1;
        try (java.util.stream.Stream<Path> paths = Files.list(Path.of("/proc/self/fd"))) { return paths.count(); }
    }


    static void autoSyncPolicy() throws Exception {
        AutoSyncPolicy waiting = new AutoSyncPolicy(1000, false);
        equal(AutoSyncPolicy.Step.WAIT, waiting.observe(true, false, 61000), "no guessed exit at timeout boundary");
        equal(AutoSyncPolicy.Step.STOP_UNOBSERVED, waiting.observe(true, false, 61001), "unobserved foreground times out");
        AutoSyncPolicy unknown = new AutoSyncPolicy(0, true);
        for (int i = 0; i < 19; i++) equal(AutoSyncPolicy.Step.WAIT, unknown.observe(false, false, 999999), "unreliable observation never publishes");
        equal(AutoSyncPolicy.Step.STOP_UNKNOWN, unknown.observe(false, false, 999999), "unknown observation budget finite");
        AutoSyncPolicy reset = new AutoSyncPolicy(0, false);
        for (int i = 0; i < 19; i++) reset.observe(false, false, 100);
        equal(AutoSyncPolicy.Step.WAIT, reset.observe(true, true, 100), "foreground resets unknown counter");
        check(reset.hasSeenForeground(), "foreground observation retained");
        equal(AutoSyncPolicy.Step.WAIT, reset.observe(false, false, 200), "unknown state after foreground does not infer exit");
        equal(AutoSyncPolicy.Step.UPLOAD, reset.observe(true, false, 300), "reliable observed foreground exit permits upload");
        AutoSyncPolicy returned = new AutoSyncPolicy(0, false);
        returned.knownReturn();
        equal(AutoSyncPolicy.Step.WAIT, returned.observe(false, false, 100), "known return still needs reliable game observation");
        equal(AutoSyncPolicy.Step.UPLOAD, returned.observe(true, false, 200), "known return and reliable background permits check");
        AutoSyncPolicy active = new AutoSyncPolicy(0, false);
        active.knownReturn();
        equal(AutoSyncPolicy.Step.WAIT, active.observe(true, true, 200), "known return cannot override observed foreground");
        for (int i = 1; i <= 3; i++) equal(i * 3000L, active.nextTransientRetryMillis(), "bounded retry backoff");
        for (int i = 0; i < 10; i++) equal(-1L, active.nextTransientRetryMillis(), "retries stay exhausted");
        for (boolean stopped : new boolean[]{false,true}) for (boolean enabled : new boolean[]{false,true}) {
            equal(!stopped && enabled, AutoSyncPolicy.mayPublish(stopped, enabled, "token", "token"), "publication gates");
            check(!AutoSyncPolicy.mayPublish(stopped, enabled, "old-token", "new-token"), "changed session blocks publication");
            check(!AutoSyncPolicy.mayPublish(stopped, enabled, null, null), "uninitialized session cannot publish");
        }
        for (String code : List.of("COMPARISON_FAILED", "HISTORY_CONFLICT", "LEGACY_PENDING_UPLOAD", "PENDING_UPLOAD",
                "RESTORE_RECOVERY_REQUIRED", "TARGET_CHANGED", "REMOTE_INCOMPLETE", "BRIDGE_UNAVAILABLE", "BASELINE_UNVERIFIED",
                "REMOTE_ONLY", "INTERRUPTED", "REMOTE_INTEGRITY_FAILED", "TARGET_UNVERIFIED", "UNSUPPORTED_SAVE_FORMAT",
                "FIRST_LAUNCH_NEEDED", "UPLOAD_NOT_CONFIRMED", "SAVE_NOT_STABLE", "LEGACY_REMOTE_PROTOCOL")) {
            equal(code, AutoSyncMessages.knownCode(code), "known reason is preserved");
            check(AutoSyncMessages.blocked(code).contains("（" + code + "）"), "message carries precise safe code");
        }
        equal("COMPARISON_FAILED", AutoSyncMessages.knownCode(null), "null reason safe fallback");
        equal("COMPARISON_FAILED", AutoSyncMessages.knownCode("password=secret"), "unknown reason cannot leak untrusted text");
        check(!AutoSyncMessages.blocked("password=secret").contains("secret"), "unknown message redacted");
        equal("RESTORE_RECOVERY_REQUIRED", AutoSyncMessages.bridgeReasonCode("RECOVERY", "RECOVERY_REQUIRED"), "pending recovery reason preserved");
        equal("TARGET_CHANGED", AutoSyncMessages.bridgeReasonCode("TARGET_IDENTITY", "INSTALLED_APK_SET_MISMATCH"), "changed installed bytes are not transient network issue");
        equal("TARGET_UNVERIFIED", AutoSyncMessages.bridgeReasonCode("PACKAGE_TRUST", "unknown"), "unverified identity reason");
        equal("SAVE_NOT_STABLE", AutoSyncMessages.bridgeReasonCode("SAVE_READINESS", "PROCESS_STATE_UNKNOWN"), "unknown running state remains unsafe");
    }


    static void installConfirmation() throws Exception {
        for (boolean first : new boolean[]{false,true}) for (boolean second : new boolean[]{false,true}) {
            equal(first && !second, InstallProgressPolicy.mayResetExpiredConfirmation(first, second), "reset needs user acknowledgement and expired token");
            equal(first && !second, InstallProgressPolicy.requiresRebindAfterLegacyRecovery(first, second), "legacy recovery completion requires rebind");
            equal(!first || second, InstallProgressPolicy.uninstallSettled(first, second), "still-installed package needs terminal result");
        }
        for (boolean closed : new boolean[]{false,true}) for (boolean embedded : new boolean[]{false,true})
                for (boolean target : new boolean[]{false,true}) for (boolean uninstall : new boolean[]{false,true})
                for (boolean unchanged : new boolean[]{false,true}) {
            equal(closed && !embedded && target && (!uninstall || unchanged),
                    InstallProgressPolicy.mayCancelObsoleteLegacy(closed, embedded, target, uninstall, unchanged),
                    "legacy cleanup requires explicit closed-dialog confirmation, exact target and original uninstall identity");
        }
        check(InstallProgressPolicy.cleanupTargetMatches(false,"game","helper","game"),"legacy game cleanup target accepted");
        check(InstallProgressPolicy.cleanupTargetMatches(false,"game","helper","helper"),"legacy helper cleanup target accepted without install permission");
        check(!InstallProgressPolicy.cleanupTargetMatches(true,"game","helper","helper"),"embedded receipt cannot authorize helper cleanup");
        check(InstallProgressPolicy.cleanupTargetMatches(true,"game",null,"game"),"embedded game cleanup accepted");
        for (String rejectedTarget : new String[]{null,"","other"})
            check(!InstallProgressPolicy.cleanupTargetMatches(false,"game","helper",rejectedTarget),"unrelated or missing cleanup target rejected");
        for (boolean uninstall : new boolean[]{false,true}) {
            check(!InstallProgressPolicy.acceptsCallback(uninstall,7,7,null,null), "empty request cannot receive callback");
            check(!InstallProgressPolicy.acceptsCallback(uninstall,7,7,"", ""), "empty nonce rejected");
            check(!InstallProgressPolicy.acceptsCallback(uninstall,7,7,"expected","other"), "mismatched nonce rejected");
            check(InstallProgressPolicy.acceptsCallback(uninstall,7,7,"expected","expected"), "matching callback accepted");
        }
        check(!InstallProgressPolicy.acceptsCallback(false,7,8,"nonce","nonce"), "install session must match");
        check(!InstallProgressPolicy.acceptsCallback(false,-1,-1,"nonce","nonce"), "install session must exist");
        check(InstallProgressPolicy.acceptsCallback(true,-1,-2,"nonce","nonce"), "uninstall callback has no installer session");
        equal(134217728 | (android.os.Build.VERSION.SDK_INT >= 31 ? 33554432 : 0),
                AndroidCompat.mutableCallbackFlags(), "mutable update-current callback behavior retained across SDKs");
        android.os.Bundle bundle = new android.os.Bundle();
        android.os.ParcelFileDescriptor descriptor = new android.os.ParcelFileDescriptor();
        bundle.put("fd",descriptor);
        check(AndroidCompat.descriptor(bundle,"fd") == descriptor, "descriptor type checked");
        bundle.put("fd",new android.content.Intent());
        equal(null,AndroidCompat.descriptor(bundle,"fd"),"non-descriptor parcelable rejected");

        ConfirmationContext context = new ConfirmationContext();
        android.app.PendingIntent.reset();
        android.content.Intent confirmation = new android.content.Intent("system-confirm").setPackage("system-installer").putExtra("session-secret","do-not-serialize");
        String retained = SystemConfirmationToken.retain(context,"nonce",confirmation);
        equal(1,android.app.PendingIntent.created,"retaining confirmation creates one token");
        equal(0,android.app.PendingIntent.sent,"retaining confirmation never launches it");
        check(!new String(java.util.Base64.getDecoder().decode(retained),java.nio.charset.StandardCharsets.UTF_8).contains("do-not-serialize"),"stored filter excludes extras");
        android.app.PendingIntent found = SystemConfirmationToken.find(context,"nonce",retained);
        check(found != null && found.intent == confirmation,"find recovers original system token with extras");
        equal(603979776,android.app.PendingIntent.lastFlags,"find uses immutable NO_CREATE token lookup");
        equal(null,SystemConfirmationToken.find(context,"wrong-nonce",retained),"wrong nonce cannot recover token");
        equal(1,android.app.PendingIntent.created,"lookups do not recreate confirmation");
        SystemConfirmationToken.cancel(context,"nonce",retained);
        equal(null,SystemConfirmationToken.find(context,"nonce",retained),"cancel removes live system token");
        retained = SystemConfirmationToken.retain(context,"nonce",confirmation);
        android.app.PendingIntent.loseSystemTokens();
        equal(null,SystemConfirmationToken.find(context,"nonce",retained),"stale in-memory cache never replays lost system token");
        equal(2,android.app.PendingIntent.created,"lost token lookup does not rebuild incomplete Intent");
        equal(null,SystemConfirmationToken.find(context,null,retained),"null nonce rejected");
        equal(null,SystemConfirmationToken.find(context,"",retained),"empty nonce rejected");
        equal(null,SystemConfirmationToken.find(context,"nonce",null),"missing retained filter rejected");

        android.app.PendingIntent.reset();
        InstallResultReceiver receiver = new InstallResultReceiver();
        for (android.content.Intent rejected : List.of(callback("wrong", "nonce", 7, -1),callback("nonce", "wrong", 7, -1),callback("nonce", "nonce", 8, -1))) {
            receiver.onReceive(context,rejected);
            equal(0,context.prefs.commits,"uncorrelated result never updates checkpoint");
            equal(0,context.broadcasts,"uncorrelated result never announces installation");
        }
        android.content.Intent pending = callback("nonce","nonce",7,-1).putExtra("android.intent.extra.INTENT",confirmation);
        receiver.onReceive(context,pending);
        equal("PENDING_CONFIRMATION",context.prefs.getString("state",null),"receiver stores pending confirmation");
        check(context.prefs.getString("confirmation",null)!=null,"receiver retains opaque system token identity");
        equal(0,android.app.PendingIntent.sent,"receiver never silently opens or approves system confirmation");
        equal(1,context.broadcasts,"committed checkpoint gets internal change broadcast");
        equal(context.getPackageName(),context.lastBroadcast.getPackage(),"change broadcast limited to this app");
        receiver.onReceive(context,callback("nonce","nonce",7,0).putExtra("android.content.pm.extra.PACKAGE_NAME","untrusted-extra-package"));
        equal("RESULT",context.prefs.getString("state",null),"success status remains unverified result for coordinator reconciliation");
        equal("pending-game",context.prefs.getString("package",null),"callback cannot change checkpoint target package");
        equal(null,context.prefs.getString("confirmation",null),"terminal result clears confirmation");
        equal(0,android.app.PendingIntent.sent,"terminal callback never installs or opens UI");
        ConfirmationContext noToken = new ConfirmationContext();
        receiver.onReceive(noToken,callback("nonce","nonce",7,-1));
        equal("RESULT",noToken.prefs.getString("state",null),"missing system Intent is not treated as successful installation");
        ConfirmationContext failedCommit = new ConfirmationContext(); failedCommit.prefs.commitSucceeds=false;
        receiver.onReceive(failedCommit,callback("nonce","nonce",7,0));
        equal(0,failedCommit.broadcasts,"failed checkpoint persistence never announces success");
        System.out.println("Confirmation checks use host test doubles; Android PendingIntent persistence and system install UI remain device-only checks.");
    }
    static android.content.Intent callback(String actionNonce,String extraNonce,int session,int status) {
        return new android.content.Intent("io.github.xyz1001.pdsync.android.INSTALL_CHANGED."+actionNonce)
                .putExtra("requestNonce",extraNonce).putExtra("android.content.pm.extra.SESSION_ID",session).putExtra("android.content.pm.extra.STATUS",status);
    }
    static final class ConfirmationContext extends android.content.Context {
        final MemoryPreferences prefs = new MemoryPreferences();
        int broadcasts; android.content.Intent lastBroadcast;
        public android.content.pm.PackageManager getPackageManager() { throw new AssertionError("Receiver must not inspect or install packages"); }
        public String getPackageName() { return "io.github.xyz1001.pdsync.android"; }
        public java.io.File getFilesDir() { throw new AssertionError("No receiver filesystem operations expected"); }
        public android.content.SharedPreferences getSharedPreferences(String name,int mode) { equal("install-checkpoint-v1",name,"correct checkpoint namespace"); return prefs; }
        public void sendBroadcast(android.content.Intent intent) { broadcasts++;lastBroadcast=intent; }
    }
    static final class MemoryPreferences implements android.content.SharedPreferences {
        final java.util.Map<String,Object> values=new java.util.HashMap<>();
        int commits; boolean commitSucceeds=true;
        MemoryPreferences() { values.put("nonce","nonce");values.put("session",7);values.put("operation","install");values.put("package","pending-game"); }
        public String getString(String key,String fallback) { return (String)values.getOrDefault(key,fallback); }
        public int getInt(String key,int fallback) { return (Integer)values.getOrDefault(key,fallback); }
        public Editor edit() {
            return new Editor() {
                final java.util.Map<String,Object> updates=new java.util.HashMap<>();
                public Editor putString(String key,String value) { updates.put(key,value);return this; }
                public Editor putInt(String key,int value) { updates.put(key,value);return this; }
                public Editor remove(String key) { updates.put(key,null);return this; }
                public boolean commit() { commits++;if(!commitSucceeds)return false;updates.forEach((k,v)->{if(v==null)values.remove(k);else values.put(k,v);});return true; }
            };
        }
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
