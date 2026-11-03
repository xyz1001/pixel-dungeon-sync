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
    static final String OFFICIAL_ENDPOINT = "https://webdav.xyz1001.top/";
    private static final String LEGACY_OFFICIAL_ENDPOINT = "https://webdav.xyz1001.top/dav/";
    private static final Object LOCK = new Object();
    // Process-local UI uncertainty; never persisted and never used as a DAV authorization lease.
    private static final java.util.Set<String> pendingOrFailedRefreshes = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static void beginRefresh(String endpoint, String username) { pendingOrFailedRefreshes.add(endpoint + "|" + username); }
    static void confirmedRefresh(String endpoint, String username) { pendingOrFailedRefreshes.remove(endpoint + "|" + username); }
    // Version 1 may contain the server's unadjusted transport deadline. Its meaning cannot
    // be inferred safely after the client-side calendar-year correction, so never reuse it.
    private static final int VERSION = 2;
    private final Path directory;

    static final class Record {
        final String endpoint, username;
        // Already normalized service deadline. A finite early-epoch server deadline can
        // normalize below zero; confirmed null means unlimited. Never subtract again here.
        final Long expiresMs, serverTimeMs;
        final boolean active, enabled, hasConfirmedExpiry;
        final long elapsedMs;
        final int bootCount;

        Record(String endpoint, String username, Long expiresMs, boolean active,
               Long serverTimeMs, long elapsedMs, int bootCount) {
            this(endpoint, username, expiresMs, active, serverTimeMs, elapsedMs, bootCount, true);
        }

        Record(String endpoint, String username, Long expiresMs, boolean active,
               Long serverTimeMs, long elapsedMs, int bootCount, boolean enabled) {
            this(endpoint, username, expiresMs, active, serverTimeMs, elapsedMs, bootCount, enabled, true);
        }

        Record(String endpoint, String username, Long expiresMs, boolean active,
               Long serverTimeMs, long elapsedMs, int bootCount, boolean enabled, boolean hasConfirmedExpiry) {
            if (!OFFICIAL_ENDPOINT.equals(endpoint) || username == null
                    || !username.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}")
                    || (serverTimeMs != null && serverTimeMs < 0)
                    || elapsedMs < 0 || bootCount < -1) throw new IllegalArgumentException("Invalid account metadata");
            this.endpoint = endpoint; this.username = username; this.expiresMs = expiresMs;
            this.active = active; this.enabled = enabled; this.hasConfirmedExpiry = hasConfirmedExpiry;
            this.serverTimeMs = serverTimeMs;
            this.elapsedMs = elapsedMs; this.bootCount = bootCount;
        }

        boolean matches(String endpoint, String username) {
            return this.endpoint.equals(endpoint) && this.username.equals(username);
        }

        /** Wall-clock changes and reboot cannot invent an expiry observation. */
        boolean expired(long nowElapsedMs, int currentBootCount) {
            if (!enabled || !hasConfirmedExpiry || expiresMs == null || serverTimeMs == null) return false;
            if (serverTimeMs >= expiresMs) return true;
            if (bootCount < 0 || currentBootCount != bootCount || nowElapsedMs < elapsedMs) return false;
            // Subtraction avoids overflow even for schema-valid Long.MAX_VALUE timestamps.
            return nowElapsedMs - elapsedMs >= expiresMs - serverTimeMs;
        }
    }

    /** Same-boot authenticated metadata for display only. Never grants sync access. */
    static boolean currentExpired(Record record, String endpoint, String username,
                                  long nowElapsedMs, int bootCount) {
        return record != null && record.matches(endpoint, username)
                && record.bootCount >= 0 && record.bootCount == bootCount
                && nowElapsedMs >= record.elapsedMs && record.expired(nowElapsedMs, bootCount);
    }

    /** Button availability only. Unknown, failed or pending refreshes cannot reuse old active metadata. */
    static boolean currentAccessAvailable(Record record, String endpoint, String username,
                                          long nowElapsedMs, int bootCount) {
        return record != null && record.matches(endpoint, username)
                && !pendingOrFailedRefreshes.contains(endpoint + "|" + username)
                && record.enabled && record.hasConfirmedExpiry && record.active && record.serverTimeMs != null
                && record.bootCount >= 0 && record.bootCount == bootCount && nowElapsedMs >= record.elapsedMs
                && !record.expired(nowElapsedMs, bootCount);
    }

    /** Expiry now belongs to the status area, not transient operation feedback. */
    static String withoutExpiryNotice(String transientNotice) {
        pdsync.i18n.UiText expiredText = pdsync.i18n.CoreSessionMessages.SERVICE_EXPIRED;
        String notice = transientNotice == null ? "" : transientNotice;
        return java.util.Arrays.stream(notice.split("\\n", -1))
                .filter(line -> !line.equals(expiredText.chinese()) && !line.equals(expiredText.english())).collect(java.util.stream.Collectors.joining("\n"));
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
                Long expires = nullable(in);
                boolean active = in.readBoolean(), enabled = in.readBoolean(), confirmed = in.readBoolean();
                Record record = new Record(OFFICIAL_ENDPOINT, username, expires, active,
                        nullable(in), in.readLong(), in.readInt(), enabled, confirmed);
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
                nullable(out, record.expiresMs); out.writeBoolean(record.active); out.writeBoolean(record.enabled); out.writeBoolean(record.hasConfirmedExpiry);
                nullable(out, record.serverTimeMs);
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
