package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/* loaded from: classes.dex */
final class LegacyWebDavConnectionFile {
    private static final byte[] AAD = "pdsync:main:webdav:v1".getBytes(StandardCharsets.UTF_8);

    interface KeyReader {
        SecretKey read() throws Exception;
    }

    private LegacyWebDavConnectionFile() {
    }

    static boolean exists(File file) {
        if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        if (Files.exists(new File(file.getPath() + ".bak").toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(file.getPath());
        sb.append(".new");
        return Files.exists(new File(sb.toString()).toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    static byte[] read(File file, KeyReader keyReader) throws Exception {
        Path path = new File(file.getPath() + ".bak").toPath();
        BasicFileAttributes basicFileAttributesAttributes = attributes(path);
        if (basicFileAttributesAttributes == null) {
            path = file.toPath();
            basicFileAttributesAttributes = attributes(path);
        }
        if (basicFileAttributesAttributes == null) {
            if (exists(file)) {
                throw new IOException(AndroidMessages.LEGACY_WEB_DAV_CONNECTION_FILE_01.text());
            }
            return null;
        }
        if (!basicFileAttributesAttributes.isRegularFile() || basicFileAttributesAttributes.size() > 32804) {
            throw new IOException(AndroidMessages.LEGACY_WEB_DAV_CONNECTION_FILE_02.text());
        }
        byte[] ciphertext = null;
        try (DataInputStream input = new DataInputStream(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS))) {
            if (input.readInt() != 1) throw new IOException(AndroidMessages.LEGACY_WEB_DAV_CONNECTION_FILE_03.text());
            int ivLength = input.readInt();
            if (ivLength != 12) throw new IOException(AndroidMessages.LEGACY_WEB_DAV_CONNECTION_FILE_02.text());
            byte[] iv = new byte[ivLength];
            input.readFully(iv);
            int length = input.readInt();
            if (length < 16 || length > 32768) throw new IOException(AndroidMessages.LEGACY_WEB_DAV_CONNECTION_FILE_02.text());
            ciphertext = new byte[length];
            input.readFully(ciphertext);
            if (input.read() != -1) throw new IOException(AndroidMessages.LEGACY_WEB_DAV_CONNECTION_FILE_02.text());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keyReader.read(), new GCMParameterSpec(128, iv));
            cipher.updateAAD(AAD);
            return cipher.doFinal(ciphertext);
        } finally {
            if (ciphertext != null) Arrays.fill(ciphertext, (byte) 0);
        }
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException unused) {
            return null;
        }
    }
}
