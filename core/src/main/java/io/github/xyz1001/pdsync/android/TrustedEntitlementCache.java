package io.github.xyz1001.pdsync.android;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

/** Private, display-only history of authenticated account metadata. Never grants DAV access. */
final class TrustedEntitlementCache {
    static final String OFFICIAL_ENDPOINT = "https://webdav.xyz1001.top:8443/";
    private static final String LEGACY_OFFICIAL_ENDPOINT = "https://webdav.xyz1001.top:8443/dav/";
    private static final Object LOCK = new Object();
    private static final int VERSION = 1;
    private final Path directory;

    static final class Record {
        final String endpoint, username;
        final Long expiresMs, serverTimeMs;
        final boolean active;
        final long elapsedMs;
        final int bootCount;

        Record(String endpoint, String username, Long expiresMs, boolean active,
               Long serverTimeMs, long elapsedMs, int bootCount) {
            if (!OFFICIAL_ENDPOINT.equals(endpoint) || username == null
                    || !username.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}")
                    || (expiresMs != null && expiresMs < 0) || (serverTimeMs != null && serverTimeMs < 0)
                    || elapsedMs < 0 || bootCount < -1) throw new IllegalArgumentException("Invalid account metadata");
            this.endpoint = endpoint; this.username = username; this.expiresMs = expiresMs;
            this.active = active; this.serverTimeMs = serverTimeMs;
            this.elapsedMs = elapsedMs; this.bootCount = bootCount;
        }

        boolean matches(String endpoint, String username) {
            return this.endpoint.equals(endpoint) && this.username.equals(username);
        }

        /** Wall-clock changes and reboot cannot invent an expiry observation. */
        boolean expired(long nowElapsedMs, int currentBootCount) {
            if (expiresMs == null || serverTimeMs == null) return false;
            if (serverTimeMs >= expiresMs) return true;
            if (bootCount < 0 || currentBootCount != bootCount || nowElapsedMs < elapsedMs) return false;
            // Subtraction avoids overflow even for schema-valid Long.MAX_VALUE timestamps.
            return nowElapsedMs - elapsedMs >= expiresMs - serverTimeMs;
        }
    }

    TrustedEntitlementCache(Path directory) { this.directory = directory; }

    Record read(String endpoint, String username) {
        synchronized (LOCK) {
            // Only the current exact official identity can consume authenticated legacy history.
            if (!OFFICIAL_ENDPOINT.equals(endpoint)) return null;
            Record current = readFile(endpoint, username);
            return current != null ? current : readFile(LEGACY_OFFICIAL_ENDPOINT, username);
        }
    }

    private Record readFile(String storedEndpoint, String username) {
        try {
            Path file = path(storedEndpoint, username);
            if (!Files.isRegularFile(file) || Files.size(file) > 4096) return null;
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(file)))) {
                if (in.readInt() != VERSION || !storedEndpoint.equals(in.readUTF())
                        || !username.equals(in.readUTF())) return null;
                Record record = new Record(OFFICIAL_ENDPOINT, username, nullable(in), in.readBoolean(),
                        nullable(in), in.readLong(), in.readInt());
                return in.available() == 0 ? record : null;
            }
        } catch (IOException | RuntimeException invalid) { return null; }
    }

    /** Call only with a successfully authenticated response, never form input or an HTTP failure. */
    void write(Record record) throws IOException {
        synchronized (LOCK) {
            Record previous = read(record.endpoint, record.username);
            if (previous != null && previous.serverTimeMs != null && record.serverTimeMs != null
                    && previous.serverTimeMs > record.serverTimeMs) return;
            Files.createDirectories(directory);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(VERSION); out.writeUTF(record.endpoint); out.writeUTF(record.username);
                nullable(out, record.expiresMs); out.writeBoolean(record.active); nullable(out, record.serverTimeMs);
                out.writeLong(record.elapsedMs); out.writeInt(record.bootCount);
            }
            Path temporary = Files.createTempFile(directory, "expiry-", ".tmp");
            try {
                Files.write(temporary, bytes.toByteArray());
                Files.move(temporary, path(record.endpoint, record.username),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        }
    }

    private Path path(String endpoint, String username) throws IOException {
        if (!(OFFICIAL_ENDPOINT.equals(endpoint) || LEGACY_OFFICIAL_ENDPOINT.equals(endpoint)) || username == null
                || !username.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}")) throw new IOException("Invalid account identity");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((endpoint + "\n" + username).getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder();
            for (byte value : hash) key.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return directory.resolve(key + ".bin");
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException("Account cache unavailable"); }
    }

    private static Long nullable(DataInputStream in) throws IOException { return in.readBoolean() ? in.readLong() : null; }
    private static void nullable(DataOutputStream out, Long value) throws IOException {
        out.writeBoolean(value != null); if (value != null) out.writeLong(value);
    }
}
