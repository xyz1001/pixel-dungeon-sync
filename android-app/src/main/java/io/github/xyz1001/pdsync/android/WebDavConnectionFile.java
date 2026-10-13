package io.github.xyz1001.pdsync.android;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Properties;

/* loaded from: classes.dex */
final class WebDavConnectionFile {
    static final String FILE_NAME = "webdav.properties";
    private static final int MAX_BYTES = 65536;
    static final FileWriter SYSTEM_WRITER = new FileWriter() { // from class: io.github.xyz1001.pdsync.android.WebDavConnectionFile.1
        @Override // io.github.xyz1001.pdsync.android.WebDavConnectionFile.FileWriter
        public void writeAndSync(Path path, byte[] bArr) throws IOException {
            FileOutputStream fileOutputStream = new FileOutputStream(path.toFile());
            try {
                fileOutputStream.write(bArr);
                fileOutputStream.flush();
                fileOutputStream.getFD().sync();
                fileOutputStream.close();
            } catch (Throwable th) {
                try {
                    fileOutputStream.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
                throw th;
            }
        }

        @Override // io.github.xyz1001.pdsync.android.WebDavConnectionFile.FileWriter
        public void replace(Path path, Path path2) throws IOException {
            Files.move(path, path2, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    };
    private final Path path;
    private final FileWriter writer;

    interface FileWriter {
        void replace(Path path, Path path2) throws IOException;

        void writeAndSync(Path path, byte[] bArr) throws IOException;
    }

    interface LegacyReader {
        Value load() throws Exception;
    }

    static class Value {
        public static final String PROVIDER_CUSTOM = "custom";
        public static final String PROVIDER_OFFICIAL = "official";
        public final String endpoint;
        public final String password;
        public final String profile;
        public final String username;
        // UI origin only. This is not authentication, trust, or part of sync identity.
        public final String provider;

        Value(String str, String str2, String str3, String str4) {
            this(str, str2, str3, str4, PROVIDER_CUSTOM);
        }

        Value(String str, String str2, String str3, String str4, String provider) {
            this.endpoint = str.trim();
            this.username = str2;
            this.password = str3;
            this.profile = str4.trim();
            // Missing or unrecognized optional metadata must not hide a valid saved account.
            this.provider = PROVIDER_OFFICIAL.equals(provider) ? PROVIDER_OFFICIAL : PROVIDER_CUSTOM;
        }

        public void validate() throws IOException {
            ConnectionEndpointPolicy.validate(this.endpoint, this.username, this.password);
            if (!this.profile.matches("[A-Za-z0-9_-]{1,64}")) {
                throw new IOException("同步档案名只支持 1–64 位英文字母、数字、下划线和短横线。");
            }
        }

        public boolean isTemporary() {
            return this.endpoint.regionMatches(true, 0, "http:", 0, 5);
        }

        public String toString() {
            return "WebDAV connection [private fields hidden]";
        }
    }

    WebDavConnectionFile(File file) {
        this(file, SYSTEM_WRITER);
    }

    WebDavConnectionFile(File file, FileWriter fileWriter) {
        this.path = file.toPath().resolve("webdav.properties");
        this.writer = fileWriter;
    }

    boolean exists() {
        return Files.exists(this.path, LinkOption.NOFOLLOW_LINKS);
    }

    Value load() throws IOException {
        byte[] bytes = null;
        try {
            final BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException missing) {
                return null;
            }
            if (!attributes.isRegularFile() || attributes.size() > MAX_BYTES) {
                throw new IOException();
            }
            try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                try {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (output.size() + count > MAX_BYTES) throw new IOException();
                        output.write(buffer, 0, count);
                    }
                    bytes = output.toByteArray();
                } finally {
                    Arrays.fill(buffer, (byte) 0);
                }
            }
            Properties properties = new Properties();
            properties.load(new StringReader(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()));
            if (!"1".equals(properties.getProperty("version"))) throw new IOException();
            Value value = new Value(required(properties, "endpoint"), required(properties, "username"),
                    required(properties, "password"), required(properties, "profile"),
                    properties.getProperty("provider"));
            value.validate();
            if (value.isTemporary()) throw new IOException();
            return value;
        } catch (IOException | RuntimeException invalid) {
            throw new IOException("无法读取已保存的连接文件。原文件已保留；请重试读取，或重新填写并测试后保存。");
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    Value loadOrMigrate(LegacyReader legacyReader) throws Exception {
        Value valueLoad = load();
        if (valueLoad != null) {
            return valueLoad;
        }
        try {
            Value valueLoad2 = legacyReader.load();
            if (valueLoad2 == null) {
                return null;
            }
            save(valueLoad2);
            return valueLoad2;
        } catch (Exception unused) {
            throw new IOException("旧版连接迁移失败，原加密文件已保留。请重试读取，或重新填写并测试后保存。");
        }
    }

    void save(Value value) throws IOException {
        value.validate();
        if (value.isTemporary()) {
            throw new IOException("本机 HTTP 测试仅本次使用，不覆盖已保存的连接。");
        }
        Properties properties = new Properties();
        properties.setProperty("version", "1");
        properties.setProperty("endpoint", value.endpoint);
        properties.setProperty("username", value.username);
        properties.setProperty("password", value.password);
        properties.setProperty("profile", value.profile);
        properties.setProperty("provider", value.provider);
        StringWriter stringWriter = new StringWriter();
        properties.store(stringWriter, "PD Sync WebDAV settings. Plaintext; keep private. Do not share or export.");
        byte[] bytes = stringWriter.toString().getBytes(StandardCharsets.UTF_8);
        Path temporary = null;
        try {
            if (bytes.length > MAX_BYTES) throw new IOException();
            temporary = Files.createTempFile(path.getParent(), "webdav-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            writer.writeAndSync(temporary, bytes);
            writer.replace(temporary, path);
        } catch (IOException | RuntimeException failure) {
            throw new IOException("无法保存连接文件，原连接未更改。编辑内容已保留；请检查可用存储空间后重试。");
        } finally {
            Arrays.fill(bytes, (byte) 0);
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Do not hide the original write/replace failure.
                }
            }
        }
    }

    void forget() throws IOException {
        Files.deleteIfExists(this.path);
    }

    private static String required(Properties properties, String str) throws IOException {
        String property = properties.getProperty(str);
        if (property != null) {
            return property;
        }
        throw new IOException();
    }
}
