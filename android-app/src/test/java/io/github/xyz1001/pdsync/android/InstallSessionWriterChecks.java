package io.github.xyz1001.pdsync.android;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Host tests execute the actual write verifier; no Android installation is performed. */
public final class InstallSessionWriterChecks {
    private static int assertions;
    interface Action { void run() throws Exception; }
    private static void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }
    private static void equal(Object expected, Object actual, String label) {
        check(java.util.Objects.equals(expected, actual), label + ": " + expected + " != " + actual);
    }
    private static <T extends Throwable> T fails(Class<T> type, Action action) throws Exception {
        try { action.run(); } catch (Throwable error) {
            check(type.isInstance(error), "Expected " + type.getName() + "; got " + error);
            return type.cast(error);
        }
        throw new AssertionError("Expected " + type.getName());
    }
    private static byte[] data(int size) {
        byte[] result = new byte[size];
        for (int i = 0; i < size; i++) result[i] = (byte) (i * 31 + i / 127);
        return result;
    }
    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder text = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes))
            text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }

    public static void main(String[] args) throws Exception {
        exactAcceptedWrites();
        invalidWrites();
        failingStreams();
        sourceAndSessionChecks();
        exactNames();
        diagnostics();
        System.out.println("Installer accepted-write checks passed: " + assertions + " assertions.");
        System.out.println("Boundary: host streams and source wiring only; no OEM Binder, SELinux, PackageInstaller or device execution.");
    }

    private static void exactAcceptedWrites() throws Exception {
        for (int length : new int[]{1, 65535, 65536, 65537, 150001}) {
            byte[] expected = data(length);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int[] synced = {0};
            String digest = InstallSessionWriter.writeVerified(new ByteArrayInputStream(expected), output,
                    length, sha256(expected), stream -> {
                        check(stream == output, "fsync receives the original session stream");
                        check(Arrays.equals(expected, output.toByteArray()), "all accepted bytes precede fsync");
                        synced[0]++;
                    });
            equal(sha256(expected), digest, "accepted-write digest");
            check(Arrays.equals(expected, output.toByteArray()), "exact bytes and ordering preserved");
            equal(1, synced[0], "one fsync per APK");
        }
    }

    private static void invalidWrites() throws Exception {
        byte[] expected = data(70000);
        byte[] altered = expected.clone(); altered[66000] ^= 1;
        for (byte[] input : new byte[][]{altered, Arrays.copyOf(expected, expected.length - 1),
                Arrays.copyOf(expected, expected.length + 1), new byte[0]}) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int[] synced = {0};
            InstallDiagnostics.StageException failure = fails(InstallDiagnostics.StageException.class, () ->
                    InstallSessionWriter.writeVerified(new ByteArrayInputStream(input), output,
                            expected.length, sha256(expected), stream -> synced[0]++));
            equal(InstallDiagnostics.Stage.READ_SOURCE_VERIFY, failure.stage, "changed source is rejected");
            equal(0, synced[0], "invalid bytes never report fsync success");
            check(output.size() <= expected.length, "oversized source never writes excess bytes");
        }
        fails(InstallDiagnostics.StageException.class, () -> InstallSessionWriter.writeVerified(
                new ByteArrayInputStream(expected), new ByteArrayOutputStream(), -1, sha256(expected), stream -> {}));
        fails(InstallDiagnostics.StageException.class, () -> InstallSessionWriter.writeVerified(
                new ByteArrayInputStream(expected), new ByteArrayOutputStream(), expected.length, "not-a-digest", stream -> {}));
    }

    private static void failingStreams() throws Exception {
        byte[] expected = data(70000);
        for (boolean partial : new boolean[]{false, true}) {
            ByteArrayOutputStream accepted = new ByteArrayOutputStream();
            IOException cause = new IOException("write failed");
            OutputStream output = new OutputStream() {
                int calls;
                public void write(int value) { throw new AssertionError("Bulk writes required"); }
                public void write(byte[] bytes, int offset, int count) throws IOException {
                    if (++calls == 2) {
                        if (partial) accepted.write(bytes, offset, count / 2);
                        throw cause;
                    }
                    accepted.write(bytes, offset, count);
                }
            };
            int[] synced = {0};
            InstallDiagnostics.StageException failure = fails(InstallDiagnostics.StageException.class, () ->
                    InstallSessionWriter.writeVerified(new ByteArrayInputStream(expected), output,
                            expected.length, sha256(expected), stream -> synced[0]++));
            equal(InstallDiagnostics.Stage.WRITE, failure.stage, "partial/failed write stage");
            check(failure.getCause() == cause, "write failure preserves original cause");
            equal(0, synced[0], "partial/failed write never reaches fsync");
            check(accepted.size() < expected.length, "partial write cannot succeed on a prefix");
        }
        IOException readCause = new IOException("read failed");
        InstallDiagnostics.StageException readFailure = fails(InstallDiagnostics.StageException.class, () ->
                InstallSessionWriter.writeVerified(new InputStream() {
                    public int read() throws IOException { throw readCause; }
                }, new ByteArrayOutputStream(), expected.length, sha256(expected), stream -> {}));
        equal(InstallDiagnostics.Stage.READ_SOURCE_VERIFY, readFailure.stage, "source read failure stage");
        check(readFailure.getCause() == readCause, "source read cause preserved");
        IOException syncCause = new IOException("fsync failed");
        InstallDiagnostics.StageException syncFailure = fails(InstallDiagnostics.StageException.class, () ->
                InstallSessionWriter.writeVerified(new ByteArrayInputStream(expected), new ByteArrayOutputStream(),
                        expected.length, sha256(expected), stream -> { throw syncCause; }));
        equal(InstallDiagnostics.Stage.FSYNC, syncFailure.stage, "fsync failure cannot authorize a commit");
        check(syncFailure.getCause() == syncCause, "fsync cause preserved");
    }

    private static final class WriteOnlySession implements InstallSessionWriter.Session {
        final Map<String, ByteArrayOutputStream> bytes = new LinkedHashMap<>();
        final Map<String, Long> lengths = new LinkedHashMap<>();
        int fsyncs, closes;
        Exception openFailure, fsyncFailure;
        boolean closeFailure;
        public OutputStream openWrite(String name, long length) throws Exception {
            if (openFailure != null) throw openFailure;
            ByteArrayOutputStream output = new ByteArrayOutputStream() {
                @Override public void close() throws IOException {
                    closes++;
                    if (closeFailure) throw new IOException("close failed");
                }
            };
            bytes.put(name, output); lengths.put(name, length); return output;
        }
        public void fsync(OutputStream output) throws Exception {
            check(bytes.containsValue(output), "sync exact session stream");
            fsyncs++;
            if (fsyncFailure != null) throw fsyncFailure;
        }
        // Deliberately has no read capability: API 26 OEMs may deny installer temp-file reads.
    }

    private static void sourceAndSessionChecks() throws Exception {
        Path directory = Files.createTempDirectory("install-write-check-");
        try {
            File base = directory.resolve("prepared-base.apk").toFile();
            File split = directory.resolve("prepared-config.apk").toFile();
            byte[] original = data(70000), config = data(23);
            Files.write(base.toPath(), original); Files.write(split.toPath(), config);
            List<File> files = Arrays.asList(base, split);
            InstallSessionWriter.Snapshot snapshot = InstallSessionWriter.inspect(files);
            equal(70023L, snapshot.length, "session total exact length");
            WriteOnlySession session = new WriteOnlySession();
            equal(snapshot.apkSetSha256, InstallSessionWriter.write(snapshot, session), "full accepted-write set verified");
            equal(Arrays.asList("base.apk", "split_1.apk"), List.copyOf(session.bytes.keySet()), "fixed session entry names");
            check(Arrays.equals(original, session.bytes.get("base.apk").toByteArray()), "base bytes unchanged");
            check(Arrays.equals(config, session.bytes.get("split_1.apk").toByteArray()), "split bytes unchanged");
            equal(70000L, session.lengths.get("base.apk"), "base declared length");
            equal(23L, session.lengths.get("split_1.apk"), "split declared length");
            equal(2, session.fsyncs, "all APKs fsynced"); equal(2, session.closes, "all streams closed");
            InstallSessionWriter.verifySources(snapshot);
            byte[] altered = original.clone(); altered[100] ^= 1;
            Files.write(base.toPath(), altered);
            fails(IOException.class, () -> InstallSessionWriter.verifySources(snapshot));
            WriteOnlySession changed = new WriteOnlySession();
            fails(InstallDiagnostics.StageException.class, () -> InstallSessionWriter.write(snapshot, changed));
            equal(0, changed.fsyncs, "pre-write changed source rejected");
            equal(1, changed.closes, "changed source still closes output");
            Files.write(base.toPath(), new byte[]{1});
            fails(IOException.class, () -> InstallSessionWriter.verifySources(snapshot));
            Files.write(base.toPath(), original);
            WriteOnlySession noOpen = new WriteOnlySession(); noOpen.openFailure = new IOException("openWrite denied");
            equal(InstallDiagnostics.Stage.WRITE, fails(InstallDiagnostics.StageException.class,
                    () -> InstallSessionWriter.write(snapshot, noOpen)).stage, "openWrite is write stage");
            WriteOnlySession noSync = new WriteOnlySession(); noSync.fsyncFailure = new IOException("fsync failed");
            equal(InstallDiagnostics.Stage.FSYNC, fails(InstallDiagnostics.StageException.class,
                    () -> InstallSessionWriter.write(snapshot, noSync)).stage, "session fsync failure propagates");
            equal(1, noSync.closes, "failed fsync closes owned output");
            WriteOnlySession noClose = new WriteOnlySession(); noClose.closeFailure = true;
            equal(InstallDiagnostics.Stage.CLOSE, fails(InstallDiagnostics.StageException.class,
                    () -> InstallSessionWriter.write(snapshot, noClose)).stage, "stream close failure propagates");
            Files.delete(base.toPath());
            fails(IOException.class, () -> InstallSessionWriter.verifySources(snapshot));
            equal(InstallDiagnostics.Stage.READ_SOURCE_VERIFY, fails(InstallDiagnostics.StageException.class,
                    () -> InstallSessionWriter.write(snapshot, new WriteOnlySession())).stage, "removed source rejected");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    private static void exactNames() throws Exception {
        InstallSessionWriter.requireNames(new String[]{"base.apk"}, 1);
        InstallSessionWriter.requireNames(new String[]{"split_2.apk", "base.apk", "split_1.apk"}, 3);
        for (String[] names : new String[][]{null, {}, {"base.apk"}, {"base.apk", "base.apk"},
                {"base.apk", "unexpected.apk"}, {"base.apk", "split_2.apk"},
                {"base.apk", null}, {"base.apk", "split_1.apk", "extra.apk"}})
            fails(IOException.class, () -> InstallSessionWriter.requireNames(names, 2));
        fails(IOException.class, () -> InstallSessionWriter.requireNames(new String[0], 0));
    }

    private static final class DeadSystemException extends Exception {
        DeadSystemException(String message) { super(message); }
    }

    private static void diagnostics() throws Exception {
        for (InstallDiagnostics.Stage stage : InstallDiagnostics.Stage.values()) {
            DeadSystemException cause = new DeadSystemException("password=secret; private-key=secret; token=secret");
            StackTraceElement[] frames = new StackTraceElement[80];
            for (int i = 0; i < frames.length; i++) frames[i] =
                    new StackTraceElement("android.content.pm.PackageInstaller", "method" + i, "PackageInstaller.java", i);
            cause.setStackTrace(frames);
            IOException wrapped = new IOException("https://private.example/secret", cause);
            wrapped.addSuppressed(new IOException("credentials=secret"));
            InstallDiagnostics.Failure failure = InstallDiagnostics.failure(stage, "com.example.game", 347452576,
                    26, stage == InstallDiagnostics.Stage.COMMIT || stage == InstallDiagnostics.Stage.CLOSE, wrapped);
            String report = InstallDiagnostics.report(failure);
            check(report.contains("stage=" + stage.label), "precise stage in report");
            check(report.contains("package=com.example.game\nsession_id=347452576\napi=26"), "public identity present");
            check(report.contains(DeadSystemException.class.getName()), "nested dead system cause visible as text");
            check(report.contains("method79(PackageInstaller.java:79)"), "full long stack preserved");
            check(report.contains("suppressed: java.io.IOException"), "suppressed failures included");
            check(!report.contains("secret") && !report.contains("private.example"), "arbitrary messages never leak");
            check(InstallDiagnostics.find(new IOException(failure)) == failure, "UI locates nested install diagnosis");
            check(failure.userMessage().contains(stage.label), "UI message identifies stage");
        }
        InstallDiagnostics.StageException original = InstallDiagnostics.at(InstallDiagnostics.Stage.FSYNC, new IOException());
        check(InstallDiagnostics.at(InstallDiagnostics.Stage.CLOSE, original) == original, "cleanup never replaces primary stage");
        InstallDiagnostics.Failure promoted = InstallDiagnostics.failure(InstallDiagnostics.Stage.OPEN,
                "com.example.game", 1, 26, false, original);
        equal(InstallDiagnostics.Stage.FSYNC, promoted.stage, "stream failure stage overrides outer open stage");
        IOException malformed = new IOException();
        malformed.setStackTrace(new StackTraceElement[]{new StackTraceElement("bad/secret", "bad/secret", "/private/secret", 1)});
        String safe = InstallDiagnostics.report(InstallDiagnostics.failure(InstallDiagnostics.Stage.CREATE,
                "package/secret", -1, 26, false, malformed));
        check(!safe.contains("secret"), "diagnostic public metadata rejects paths");
        IOException one = new IOException(), two = new IOException(one); one.initCause(two);
        check(InstallDiagnostics.find(one) == null, "cyclic cause search terminates");
        check(InstallDiagnostics.report(InstallDiagnostics.failure(InstallDiagnostics.Stage.CREATE,
                "com.example.game", -1, 26, false, one)).contains("already reported"), "cyclic cause report terminates");
    }
}
