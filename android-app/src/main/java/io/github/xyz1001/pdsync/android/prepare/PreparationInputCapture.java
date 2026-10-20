package io.github.xyz1001.pdsync.android.prepare;

import java.io.*;
import java.security.*;
import java.util.Locale;

/** Reads a potentially non-repeatable SAF stream once; independently checks the staged copy. */
final class PreparationInputCapture {
    private PreparationInputCapture() { }
    static void copy(InputStream input, File output, long limit, PreparationInputEvidence evidence) throws IOException {
        long started = System.nanoTime();
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        if (input == null) throw new IOException("Source returned no data");
        if (!output.createNewFile()) throw new IOException("Staging output already exists");
        try (InputStream owned = input; FileOutputStream target = new FileOutputStream(output)) {
            byte[] buffer = new byte[65536]; int read;
            while ((read = owned.read(buffer)) != -1) {
                if (read == 0) { int single = owned.read(); if (single == -1) break; buffer[0] = (byte) single; read = 1; }
                evidence.bytes += read;
                if (evidence.bytes > limit) throw new IOException("Input exceeds safety size limit");
                digest.update(buffer, 0, read); target.write(buffer, 0, read);
            }
            evidence.eof = true;
            evidence.sha256 = hex(digest.digest());
            if (evidence.bytes == 0) throw new IOException("Source APK is empty");
            target.getFD().sync();
        } finally {
            if (!evidence.eof) evidence.sha256 = hex(digest.digest());
            evidence.copyMillis = (System.nanoTime() - started) / 1_000_000;
        }
        evidence.cacheBytes = output.length(); evidence.cacheSha256 = ApkArchive.sha256(output);
        evidence.cacheMatch = evidence.bytes == evidence.cacheBytes && evidence.sha256.equals(evidence.cacheSha256);
        if (!evidence.cacheMatch) throw new IOException("Source input cache digest mismatch");
    }
    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(); for (byte b : bytes) value.append(String.format(Locale.ROOT,"%02x", b & 255)); return value.toString();
    }
}
