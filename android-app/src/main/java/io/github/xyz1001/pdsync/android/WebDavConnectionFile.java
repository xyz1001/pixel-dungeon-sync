package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

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
    static final String OFFICIAL_ROOT_ENDPOINT = "https://webdav.xyz1001.top/";
    static final String LEGACY_OFFICIAL_ENDPOINT = "https://webdav.xyz1001.top/dav/";
    private static final int MAX_BYTES = 65536;
    // All instances share the app-private connection record; migrations must not race user saves.
    private static final Object FILE_LOCK = new Object();
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
        // Explicit UI provenance, not authentication. Only the exact official root retains its old local key.
        public final String provider;

        public boolean retainsLegacyOfficialScope() {
            return PROVIDER_OFFICIAL.equals(provider) && OFFICIAL_ROOT_ENDPOINT.equals(endpoint);
        }
        public String stateEndpoint() { return retainsLegacyOfficialScope() ? LEGACY_OFFICIAL_ENDPOINT : endpoint; }

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
                throw new IOException(AndroidMessages.WEB_DAV_CONNECTION_FILE_01.text());
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
        synchronized (FILE_LOCK) {
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
                // Never infer provenance from the URL. Unmarked/custom and near-match URLs stay intact.
                if (Value.PROVIDER_OFFICIAL.equals(value.provider)
                        && LEGACY_OFFICIAL_ENDPOINT.equals(properties.getProperty("endpoint"))) {
                    Value migrated = new Value(OFFICIAL_ROOT_ENDPOINT, value.username, value.password,
                            value.profile, value.provider);
                    properties.setProperty("endpoint", migrated.endpoint);
                    // Preserve all other settings; commit atomically before exposing the new endpoint.
                    writeProperties(properties);
                    return migrated;
                }
                return value;
            } catch (IOException | RuntimeException invalid) {
                throw new IOException(AndroidMessages.WEB_DAV_CONNECTION_FILE_02.text());
            } finally {
                if (bytes != null) Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    Value loadOrMigrate(LegacyReader legacyReader) throws Exception {
        synchronized (FILE_LOCK) {
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
                throw new IOException(AndroidMessages.WEB_DAV_CONNECTION_FILE_03.text());
            }
        }
    }

    void save(Value value) throws IOException {
        synchronized (FILE_LOCK) {
            value.validate();
            if (value.isTemporary()) {
                throw new IOException(AndroidMessages.WEB_DAV_CONNECTION_FILE_04.text());
            }
            Properties properties = new Properties();
            properties.setProperty("version", "1");
            properties.setProperty("endpoint", value.endpoint);
            properties.setProperty("username", value.username);
            properties.setProperty("password", value.password);
            properties.setProperty("profile", value.profile);
            properties.setProperty("provider", value.provider);
            writeProperties(properties);
        }
    }

    private void writeProperties(Properties properties) throws IOException {
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
            throw new IOException(AndroidMessages.WEB_DAV_CONNECTION_FILE_05.text());
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
        synchronized (FILE_LOCK) {
            Files.deleteIfExists(this.path);
        }
    }

    private static String required(Properties properties, String str) throws IOException {
        String property = properties.getProperty(str);
        if (property != null) {
            return property;
        }
        throw new IOException();
    }
}
