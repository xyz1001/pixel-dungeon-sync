package io.github.xyz1001.pdsync.android;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/** Synthetic host-only cache/clock checks. No Android, service, credentials, or real accounts. */
public final class TrustedEntitlementCacheChecks {
    private static int checks;
    private static final String ENDPOINT = TrustedEntitlementCache.OFFICIAL_ENDPOINT;
    private static void check(boolean condition, String description) {
        checks++; if (!condition) throw new AssertionError(description);
    }
    private static TrustedEntitlementCache.Record record(String user, Long expiry, boolean active,
                                                         Long server, long elapsed, int boot) {
        return new TrustedEntitlementCache.Record(ENDPOINT, user, expiry, active, server, elapsed, boot);
    }
    private static void trustedClockOnly() {
        check(!record("account-a", null, false, 100L, 10, 2).expired(10000, 2), "Inactive without a deadline is not expiry");
        check(!record("account-a", 50L, false, null, 10, 2).expired(Long.MAX_VALUE, 2), "Old shape without server clock never invents expiry");
        check(!record("account-a", 200L, false, 100L, 10, 2).expired(10, 2), "Inactive alone is not expiry");
        check(record("account-a", 100L, false, 100L, 10, 2).expired(0, -1), "Authenticated already-expired observation survives reboot");
        TrustedEntitlementCache.Record future = record("account-a", 200L, true, 100L, 10, 2);
        check(!future.expired(109, 2) && future.expired(110, 2), "Same-boot monotonic deadline boundary");
        check(!future.expired(1_000_000, 3), "Reboot does not reuse monotonic anchor");
        check(!future.expired(1_000_000, -1), "Unavailable current boot count cannot infer future expiry");
        check(!record("account-a", 200L, true, 100L, 10, -1).expired(1_000_000, -1), "Unavailable observed boot count cannot infer expiry");
        check(!future.expired(9, 2), "Elapsed-clock rollback is unknown");
        check(record("account-a", Long.MAX_VALUE, true, Long.MAX_VALUE - 10, 10, 2).expired(20, 2), "Upper timestamps do not overflow");
        TimeZone old = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
        check(future.expired(110, 2), "Timezone and wall-clock presentation never alter expiry policy");
        TimeZone.setDefault(old);
    }
    private static void atomicPrivateRecords() throws Exception {
        Path directory = Files.createTempDirectory("synthetic-entitlement-cache-");
        try {
            TrustedEntitlementCache cache = new TrustedEntitlementCache(directory);
            check(cache.read(ENDPOINT, "account-a") == null, "New device has no trusted metadata");
            cache.write(record("account-a", 200L, true, 100L, 10, 2));
            TrustedEntitlementCache.Record first = new TrustedEntitlementCache(directory).read(ENDPOINT, "account-a");
            check(first != null && first.expiresMs == 200L && first.serverTimeMs == 100L && first.active, "Reload preserves exact authenticated fields");
            check(cache.read(ENDPOINT, "account-b") == null && cache.read(ENDPOINT, "ACCOUNT-A") == null,
                    "Username and case match exactly");
            check(cache.read("https://other.invalid/dav/", "account-a") == null, "Custom endpoint never reuses official cache");
            cache.write(record("account-a", 100L, false, 201L, 20, 2));
            check(cache.read(ENDPOINT, "account-a").expired(0, -1), "Confirmed expired state reloads");
            cache.write(record("account-a", 500L, true, 202L, 30, 2));
            check(!cache.read(ENDPOINT, "account-a").expired(30, 2), "Authenticated renewal replaces expired display");
            cache.write(record("account-a", 100L, false, 201L, 40, 2));
            check(cache.read(ENDPOINT, "account-a").expiresMs == 500L, "Delayed older authenticated response cannot roll back renewal");
            cache.write(record("account-b", 900L, true, 300L, 30, 2));
            check(cache.read(ENDPOINT, "account-a").expiresMs == 500L && cache.read(ENDPOINT, "account-b").expiresMs == 900L,
                    "Accounts are stored independently");
            Path[] paths;
            try (Stream<Path> files = Files.list(directory)) { paths = files.toArray(Path[]::new); }
            check(paths.length == 2 && Arrays.stream(paths).allMatch(p -> p.getFileName().toString().matches("[0-9a-f]{64}\\.bin")),
                    "Only hashed record files remain, no plaintext account filename or temporary file");
            Path accountA = Arrays.stream(paths).filter(p -> {
                try { return new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.ISO_8859_1).contains("account-a"); }
                catch (Exception e) { throw new AssertionError(e); }
            }).findFirst().orElseThrow();
            byte[] valid = Files.readAllBytes(accountA);
            String stored = new String(valid, java.nio.charset.StandardCharsets.ISO_8859_1);
            check(!stored.contains("password") && !stored.contains("token") && !stored.contains("Authorization"), "Persisted record contains no credential fields");
            Files.write(accountA, Arrays.copyOf(valid, 8));
            check(cache.read(ENDPOINT, "account-a") == null, "Truncated cache is unknown");
            Files.write(accountA, new byte[4097]);
            check(cache.read(ENDPOINT, "account-a") == null, "Oversized cache is unknown");
            Files.write(accountA, Arrays.copyOf(valid, valid.length + 1));
            check(cache.read(ENDPOINT, "account-a") == null, "Trailing bytes cannot become a trusted record");
            cache.write(record("account-a", 1000L, true, null, 0, -1));
            check(cache.read(ENDPOINT, "account-a").serverTimeMs == null, "Legacy authenticated refresh preserves date without fabricated clock");
        } finally {
            try (Stream<Path> files = Files.walk(directory)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }
    private static void displayNeverCreatesAuthority() {
        AtomicLong now = new AtomicLong(10);
        ManagedAccountFlow.Clock clock = new ManagedAccountFlow.Clock() {
            public long elapsedMs() { return now.get(); }
            public int bootCount() { return 2; }
        };
        TrustedEntitlementCache.Record cached = record("account-a", 200L, true, 100L, 10, 2);
        ManagedAccountFlow flow = new ManagedAccountFlow("account-a", cached, clock);
        flow.foreground(true);
        check(flow.view().cachedExpiry && !flow.view().entitlementLoaded && flow.view().username == null,
                "Cached verified date is not present authentication proof");
        now.set(110);
        check(flow.view().expired && !flow.view().entitlementLoaded, "Display-only monotonic expiry does not authenticate");
        ManagedAccountFlow other = new ManagedAccountFlow("account-b", cached, clock); other.foreground(true);
        check(other.view().expiresMs == null && !other.view().expired, "Cross-account cached date is never displayed");
        ManagedAccountFlow.Ticket request = flow.refresh();
        flow.entitlement(request, "account-a", 1000L, true, true, 201L); flow.complete(request);
        check(!flow.view().expired && !flow.view().cachedExpiry && flow.view().entitlementLoaded, "Live authenticated renewal removes stale display");
        flow.foreground(false);
        check(flow.view().expiresMs == null && flow.view().localUsername == null, "Background hides live and cached account details");
        flow.close(); flow.foreground(true);
        check(flow.view().expiresMs == null && flow.refresh() == null, "Closed page cannot resurrect cache or callbacks");
    }
    public static void main(String[] args) throws Exception {
        trustedClockOnly(); atomicPrivateRecords(); displayNeverCreatesAuthority();
        System.out.println("TrustedEntitlementCacheChecks: " + checks + " checks passed; no network used.");
    }
}
