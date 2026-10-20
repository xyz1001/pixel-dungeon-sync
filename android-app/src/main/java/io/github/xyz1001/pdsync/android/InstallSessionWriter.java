package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Verifies bytes accepted by the session write stream; does not claim staged-storage readback. */
final class InstallSessionWriter {
    private InstallSessionWriter() {}

    interface Session {
        OutputStream openWrite(String name, long length) throws Exception;
        void fsync(OutputStream output) throws Exception;
    }

    interface Sync { void fsync(OutputStream output) throws Exception; }

    static final class Entry {
        final File file;
        final long length;
        final String sha256;
        Entry(File file, long length, String sha256) {
            this.file = file; this.length = length; this.sha256 = sha256;
        }
    }

    static final class Snapshot {
        final List<Entry> entries;
        final long length;
        final String apkSetSha256;
        Snapshot(List<Entry> entries, long length, List<String> hashes) {
            this.entries = java.util.Collections.unmodifiableList(new ArrayList<>(entries)); this.length = length; this.apkSetSha256 = ApkSetIdentity.digest(hashes);
        }
    }

    static Snapshot inspect(List<File> files) throws Exception {
        if (files == null || files.isEmpty()) throw new IOException(AndroidMessages.PACKAGE_TRUST_20.text());
        ArrayList<Entry> entries = new ArrayList<>();
        ArrayList<String> hashes = new ArrayList<>();
        long total = 0;
        for (File file : files) {
            Entry entry = inspect(file);
            entries.add(entry); hashes.add(entry.sha256);
            total = Math.addExact(total, entry.length);
        }
        return new Snapshot(entries, total, hashes);
    }

    private static Entry inspect(File file) throws Exception {
        long length = file.length();
        if (!file.isFile() || length <= 0) throw new IOException(AndroidMessages.INSTALL_SESSION_WRITER_01.text());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long count = 0;
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = input.read(buffer)) != -1) {
                if (n > length - count) throw new IOException(AndroidMessages.INSTALL_SESSION_WRITER_02.text());
                digest.update(buffer, 0, n); count += n;
            }
        }
        if (count != length || file.length() != length) throw new IOException(AndroidMessages.INSTALL_SESSION_WRITER_02.text());
        return new Entry(file, count, hex(digest.digest()));
    }

    static String write(Snapshot snapshot, Session session) throws Exception {
        ArrayList<String> hashes = new ArrayList<>();
        for (int i = 0; i < snapshot.entries.size(); i++) {
            Entry entry = snapshot.entries.get(i);
            InstallDiagnostics.Stage stage = InstallDiagnostics.Stage.READ_SOURCE_VERIFY;
            try (InputStream input = new FileInputStream(entry.file)) {
                stage = InstallDiagnostics.Stage.WRITE;
                try (OutputStream output = session.openWrite(name(i), entry.length)) {
                    hashes.add(writeVerified(input, output, entry.length, entry.sha256, session::fsync));
                    stage = InstallDiagnostics.Stage.CLOSE;
                }
                stage = InstallDiagnostics.Stage.CLOSE;
            } catch (Exception error) {
                throw InstallDiagnostics.at(stage, error);
            }
        }
        String actual = ApkSetIdentity.digest(hashes);
        if (!snapshot.apkSetSha256.equals(actual)) throw new IOException(AndroidMessages.INSTALL_SESSION_WRITER_03.text());
        return actual;
    }

    static String writeVerified(InputStream input, OutputStream output, long length, String expected,
                                Sync sync) throws Exception {
        InstallDiagnostics.Stage stage = InstallDiagnostics.Stage.READ_SOURCE_VERIFY;
        try {
            if (length <= 0 || expected == null || !expected.matches("[0-9a-f]{64}"))
                throw new IOException(AndroidMessages.INSTALL_SESSION_WRITER_04.text());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long written = 0;
            byte[] buffer = new byte[65536]; int n;
            while ((n = input.read(buffer)) != -1) {
                if (n > length - written) throw new IOException(AndroidMessages.INSTALL_SESSION_WRITER_05.text());
                stage = InstallDiagnostics.Stage.WRITE;
                output.write(buffer, 0, n);
                // Only account for a whole write after OutputStream accepted it. Any partial
                // write followed by an exception fails the entire request, never its prefix.
                digest.update(buffer, 0, n); written += n;
                stage = InstallDiagnostics.Stage.READ_SOURCE_VERIFY;
            }
            String actual = hex(digest.digest());
            if (written != length || !actual.equals(expected))
                throw new IOException(AndroidMessages.INSTALL_SESSION_WRITER_06.text());
            stage = InstallDiagnostics.Stage.FSYNC;
            sync.fsync(output);
            return actual;
        } catch (Exception error) {
            throw InstallDiagnostics.at(stage, error);
        }
    }

    static void verifySources(Snapshot snapshot) throws Exception {
        for (Entry expected : snapshot.entries) {
            Entry actual = inspect(expected.file);
            if (expected.length != actual.length || !expected.sha256.equals(actual.sha256))
                throw new IOException(AndroidMessages.INSTALL_SESSION_WRITER_07.text());
        }
    }

    static void requireNames(String[] actual, int count) throws IOException {
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < count; i++) expected.add(name(i));
        if (count <= 0 || actual == null || actual.length != count
                || !expected.equals(new HashSet<>(Arrays.asList(actual))))
            throw new IOException(AndroidMessages.INSTALL_SESSION_WRITER_08.text());
    }

    private static String name(int index) { return index == 0 ? "base.apk" : "split_" + index + ".apk"; }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(digits[(value & 255) >>> 4]).append(digits[value & 15]);
        return text.toString();
    }
}
