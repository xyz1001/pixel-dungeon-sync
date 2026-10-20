package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;

/** Private, immutable one-APK handoff snapshots. Never exposes preparation folders or accepts paths. */
final class ExternalInstallerFiles {
    static final String DIRECTORY = "external-installer-handoff-v1";
    static final String APK = "prepared.apk";
    private static final String RECORD = "handoff.properties";
    private static final int MAX_RECORD = 4096;
    private ExternalInstallerFiles() { }

    static final class Record {
        final String nonce, packageName, signerSha256, receiptId, apkSha256, apkSetSha256;
        final long versionCode, length;
        final File apk;
        Record(String nonce, String packageName, long versionCode, String signerSha256,
                String receiptId, String apkSha256, long length, File apk) throws IOException {
            this.nonce = checkedNonce(nonce);
            if (packageName == null || !packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
                    || versionCode < 0 || length <= 0)
                throw new IOException(AndroidMessages.EXTERNAL_INSTALLER_FILES_01.text());
            this.packageName = packageName; this.versionCode = versionCode;
            this.signerSha256 = checkedHash(signerSha256); this.receiptId = checkedNonce(receiptId);
            this.apkSha256 = checkedHash(apkSha256); this.length = length; this.apk = apk;
            this.apkSetSha256 = ApkSetIdentity.digest(Collections.singletonList(apkSha256));
        }
    }

    static Record create(File parent, File source, long length, String sha256, String packageName,
            long versionCode, String signerSha256, String receiptId) throws Exception {
        File root = root(parent, true);
        String nonce = UUID.randomUUID().toString();
        File directory = child(root, nonce);
        if (!directory.mkdir()) throw new IOException(AndroidMessages.EXTERNAL_INSTALLER_FILES_02.text());
        boolean complete = false;
        try {
            File apk = child(directory, APK);
            Record record = new Record(nonce, packageName, versionCode, signerSha256, receiptId, sha256, length, apk);
            if (!source.isFile() || !source.getAbsoluteFile().equals(source.getCanonicalFile())
                    || Files.isSymbolicLink(source.toPath())) throw new IOException(AndroidMessages.EXTERNAL_INSTALLER_FILES_03.text());
            try (InputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(apk)) {
                InstallSessionWriter.writeVerified(input, output, length, sha256, stream -> output.getFD().sync());
            }
            // No public method rewrites these bytes. Each launch gets an independent random directory.
            if (!apk.setReadOnly()) throw new IOException(AndroidMessages.EXTERNAL_INSTALLER_FILES_04.text());
            Properties p = new Properties();
            p.setProperty("format", "1"); p.setProperty("nonce", nonce);
            p.setProperty("packageName", packageName); p.setProperty("versionCode", Long.toString(versionCode));
            p.setProperty("signerSha256", signerSha256); p.setProperty("receiptId", receiptId);
            p.setProperty("apkSha256", sha256); p.setProperty("length", Long.toString(length));
            File metadata = child(directory, RECORD);
            try (FileOutputStream output = new FileOutputStream(metadata)) { p.store(output, null); output.getFD().sync(); }
            if (!metadata.setReadOnly()) throw new IOException(AndroidMessages.EXTERNAL_INSTALLER_FILES_05.text());
            complete = true;
            return record;
        } finally {
            if (!complete) remove(parent, nonce);
        }
    }

    /** Bounded metadata lookup; private bytes are hashed during prepare/loadVerified, never on UI queries. */
    static Record load(File parent, String nonce) throws IOException {
        File directory = child(root(parent, false), checkedNonce(nonce));
        if (!directory.isDirectory()) throw new IOException(AndroidMessages.EXTERNAL_INSTALLER_FILES_06.text());
        File metadata = child(directory, RECORD);
        if (!metadata.isFile() || metadata.length() <= 0 || metadata.length() > MAX_RECORD)
            throw new IOException(AndroidMessages.EXTERNAL_INSTALLER_FILES_07.text());
        Properties p = new Properties();
        try (InputStream input = new FileInputStream(metadata)) {
            byte[] bytes = new byte[MAX_RECORD + 1]; int used = 0, n;
            while (used < bytes.length && (n = input.read(bytes, used, bytes.length - used)) != -1) used += n;
            if (used > MAX_RECORD) throw new IOException(AndroidMessages.EXTERNAL_INSTALLER_FILES_08.text());
            p.load(new java.io.ByteArrayInputStream(bytes, 0, used));
        }
        if (!"1".equals(p.getProperty("format")) || !nonce.equals(p.getProperty("nonce")))
            throw new IOException(AndroidMessages.EXTERNAL_INSTALLER_FILES_09.text());
        try {
            File apk = child(directory, APK);
            Record result = new Record(nonce, p.getProperty("packageName"), Long.parseLong(p.getProperty("versionCode")),
                    p.getProperty("signerSha256"), p.getProperty("receiptId"), p.getProperty("apkSha256"),
                    Long.parseLong(p.getProperty("length")), apk);
            if (!apk.isFile() || apk.length() != result.length) throw new IOException(AndroidMessages.EXTERNAL_INSTALLER_FILES_10.text());
            return result;
        } catch (NumberFormatException invalid) { throw new IOException(AndroidMessages.EXTERNAL_INSTALLER_FILES_11.text(), invalid); }
    }

    static Record loadVerified(File parent, String nonce) throws Exception {
        Record record = load(parent, nonce);
        InstallSessionWriter.Snapshot current = InstallSessionWriter.inspect(Collections.singletonList(record.apk));
        if (current.length != record.length || !current.apkSetSha256.equals(record.apkSetSha256))
            throw new IOException(AndroidMessages.EXTERNAL_INSTALLER_FILES_12.text());
        return record;
    }

    static void remove(File parent, String nonce) throws IOException {
        File root = root(parent, false);
        File directory = child(root, checkedNonce(nonce));
        if (!directory.exists()) return;
        // Fixed leaves only; never recursively follow a path or touch retained preparation APKs.
        for (String name : new String[] {RECORD, APK}) {
            File file = child(directory, name);
            if (file.exists() && !file.delete()) throw new IOException(AndroidMessages.EXTERNAL_INSTALLER_FILES_13.text());
        }
        if (!directory.delete()) throw new IOException(AndroidMessages.EXTERNAL_INSTALLER_FILES_14.text());
    }

    static String nonceFromEncodedPath(String path) throws IOException {
        if (path == null || !path.matches("/apk/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/prepared\\.apk"))
            throw new IOException(AndroidMessages.EXTERNAL_INSTALLER_HANDOFF_07.text());
        return checkedNonce(path.substring(5, path.length() - ("/" + APK).length()));
    }

    static String checkedNonce(String nonce) throws IOException {
        if (nonce == null || !nonce.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IOException(AndroidMessages.EXTERNAL_INSTALLER_FILES_15.text());
        return nonce;
    }
    private static String checkedHash(String hash) throws IOException {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IOException(AndroidMessages.EXTERNAL_INSTALLER_FILES_16.text());
        return hash;
    }
    private static File root(File parent, boolean create) throws IOException {
        File trustedParent = parent.getCanonicalFile();
        if (!trustedParent.isDirectory()) throw new IOException(AndroidMessages.EXTERNAL_INSTALLER_FILES_17.text());
        File root = child(trustedParent, DIRECTORY);
        if (create && !root.exists() && !root.mkdir()) throw new IOException(AndroidMessages.EXTERNAL_INSTALLER_FILES_18.text());
        if (root.exists() && !root.isDirectory()) throw new IOException(AndroidMessages.EXTERNAL_INSTALLER_FILES_19.text());
        return root;
    }
    private static File child(File parent, String leaf) throws IOException {
        File child = new File(parent, leaf).getAbsoluteFile();
        if (!child.equals(child.getCanonicalFile()) || Files.isSymbolicLink(child.toPath())
                || !parent.getCanonicalFile().equals(child.getParentFile()))
            throw new IOException(AndroidMessages.EXTERNAL_INSTALLER_FILES_20.text());
        return child;
    }
}
