package io.github.xyz1001.pdsync.android;

import pdsync.i18n.CoreSessionMessages;
import pdsync.i18n.UiLocale;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
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
    private static void refreshedHomeAvailabilityFailsClosed() {
        TrustedEntitlementCache.Record active = record("refresh-ui", 200L, true, 100L, 10, 2);
        check(TrustedEntitlementCache.currentAccessAvailable(active, ENDPOINT, "refresh-ui", 10, 2), "Active account may combine with existing save capabilities");
        TrustedEntitlementCache.beginRefresh(ENDPOINT, "refresh-ui");
        check(!TrustedEntitlementCache.currentAccessAvailable(active, ENDPOINT, "refresh-ui", 10, 2), "Pending or failed refresh cannot reuse old active state even when saves exist");
        check(!active.expired(10, 2), "Refresh uncertainty does not manufacture expiration");
        TrustedEntitlementCache.confirmedRefresh(ENDPOINT, "another-user");
        check(!TrustedEntitlementCache.currentAccessAvailable(active, ENDPOINT, "refresh-ui", 10, 2), "Another account cannot clear the failure");
        TrustedEntitlementCache.confirmedRefresh(ENDPOINT, "refresh-ui");
        check(TrustedEntitlementCache.currentAccessAvailable(active, ENDPOINT, "refresh-ui", 10, 2), "Only current successful refresh restores UI eligibility");
        check(!TrustedEntitlementCache.currentAccessAvailable(active, ENDPOINT, "refresh-ui", 110, 2), "Expired blocks both actions regardless of saves");
        check(!TrustedEntitlementCache.currentAccessAvailable(null, ENDPOINT, "refresh-ui", 10, 2), "Missing metadata blocks actions");
        check(!TrustedEntitlementCache.currentAccessAvailable(record("refresh-ui", null, false, 100L, 10, 2), ENDPOINT, "refresh-ui", 10, 2), "Unknown cannot enable actions");
        check(!TrustedEntitlementCache.currentAccessAvailable(active, ENDPOINT, "refresh-ui", 10, 3), "Changed boot is unknown");
        check(TrustedEntitlementCache.currentAccessAvailable(record("refresh-ui", null, true, 100L, 10, 2), ENDPOINT, "refresh-ui", 10, 2), "Confirmed unlimited supports existing capabilities");
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
            check(first != null && first.expiresMs == 200L && first.serverTimeMs == 100L && first.active
                    && first.enabled && first.hasConfirmedExpiry, "Reload preserves exact authenticated fields");
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
    private static Path writeVersionOne(Path directory, String endpoint, String user) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest((endpoint + "\n" + user).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder key = new StringBuilder();
        for (byte value : digest) key.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        Path file = directory.resolve(key + ".bin");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(bytes)) {
            out.writeInt(1); out.writeUTF(endpoint); out.writeUTF(user);
            out.writeBoolean(true); out.writeLong(1_830_000_000_000L); out.writeBoolean(true);
            out.writeBoolean(true); out.writeLong(1_790_000_000_000L); out.writeLong(10); out.writeInt(2);
        }
        Files.write(file, bytes.toByteArray());
        return file;
    }

    private static void ambiguousVersionOneHistoryIsRejected() throws Exception {
        Path directory = Files.createTempDirectory("synthetic-legacy-entitlement-");
        try {
            String old = "https://webdav.xyz1001.top/dav/", user = "account-a";
            TrustedEntitlementCache cache = new TrustedEntitlementCache(directory);
            Path legacy = writeVersionOne(directory, old, user);
            byte[] original = Files.readAllBytes(legacy);
            check(cache.read(ENDPOINT, user) == null,
                    "Version 1 /dav raw expiry is unknown, never migrated or subtracted speculatively");
            Path root = writeVersionOne(directory, ENDPOINT, user);
            byte[] originalRoot = Files.readAllBytes(root);
            check(cache.read(ENDPOINT, user) == null,
                    "Version 1 root raw expiry is unknown, never displayed as normalized history");
            check(cache.read(old, user) == null && cache.read(ENDPOINT, "account-b") == null
                    && cache.read("https://other.invalid/", user) == null,
                    "Rejected old cache cannot be consumed under another identity");
            check(Arrays.equals(original, Files.readAllBytes(legacy))
                    && Arrays.equals(originalRoot, Files.readAllBytes(root)),
                    "Rejected old cache bytes remain unchanged during reads");
            cache.write(record(user, 500L, true, 101L, 20, 2));
            check(cache.read(ENDPOINT, user).expiresMs == 500,
                    "A fresh normalized response replaces an ambiguous newer-looking raw deadline");
            check(Arrays.equals(original, Files.readAllBytes(legacy)), "Legacy /dav file is not rewritten");
        } finally {
            try (Stream<Path> files = Files.walk(directory)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    private static void versionTwoRoundTripsStatusAndNormalization() throws Exception {
        Path directory = Files.createTempDirectory("synthetic-normalized-entitlement-");
        try {
            TrustedEntitlementCache cache = new TrustedEntitlementCache(directory);
            for (boolean enabled : new boolean[] {false, true}) {
                for (boolean confirmed : new boolean[] {false, true}) {
                    String user = "account-" + enabled + "-" + confirmed;
                    TrustedEntitlementCache.Record source = new TrustedEntitlementCache.Record(
                            ENDPOINT, user, null, false, 100L, 10, 2, enabled, confirmed);
                    cache.write(source);
                    TrustedEntitlementCache.Record read = new TrustedEntitlementCache(directory).read(ENDPOINT, user);
                    check(read != null && read.expiresMs == null && !read.active && read.enabled == enabled
                            && read.hasConfirmedExpiry == confirmed && read.serverTimeMs == 100L
                            && read.elapsedMs == 10 && read.bootCount == 2,
                            "Version 2 preserves disabled and unconfirmed independently from unlimited: " + user);
                    check(!read.expired(Long.MAX_VALUE, 2), "Unknown or unlimited records never invent expiry");
                }
            }
            cache.write(new TrustedEntitlementCache.Record(ENDPOINT, "disabled", 50L, false, 100L, 10, 2, false, true));
            check(!cache.read(ENDPOINT, "disabled").expired(1000, 2), "Explicit disabled state is not displayed as elapsed expiry");
            cache.write(new TrustedEntitlementCache.Record(ENDPOINT, "early-epoch", -100L, false, 0L, 10, 2, true, true));
            TrustedEntitlementCache.Record early = cache.read(ENDPOINT, "early-epoch");
            check(early != null && early.expiresMs == -100L && early.expired(0, -1),
                    "Already-normalized pre-epoch deadline round-trips without validation loss or another subtraction");
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
        check(flow.view().expiresMs == 1000L && "account-a".equals(flow.view().localUsername) && flow.view().entitlementLoaded, "Background preserves confirmed account and expiry display");
        flow.close(); flow.foreground(true);
        check(flow.view().expiresMs == null && flow.refresh() == null, "Closed page cannot resurrect cache or callbacks");
    }

    private static boolean currentExpired(TrustedEntitlementCache.Record record, long elapsed, int boot) {
        return TrustedEntitlementCache.currentExpired(record, ENDPOINT, "account-a", elapsed, boot);
    }

    private static void homeExpirySurvivesNavigationAndOperationFeedback() {
        String expired = CoreSessionMessages.SERVICE_EXPIRED.text();
        TrustedEntitlementCache.Record cached = record("account-a", 200L, true, 100L, 10, 2);
        for (String notice : new String[] {"", null, expired, ""}) {
            check(TrustedEntitlementCache.withoutExpiryNotice(notice).isEmpty() && currentExpired(cached, 110, 2),
                    "Settings and appreciation return keep expiry in the home status independently of cleared feedback");
        }

        String[] feedback = {
                CoreSessionMessages.REMOTE_SNAPSHOT_LOADED.text(),
                CoreSessionMessages.THE_NETWORK_CONNECTION_WAS_INTERRUPTED.text(),
                CoreSessionMessages.THE_QUERY_TIMED_OUT_REFRESH_LATER.text(),
                CoreSessionMessages.UNABLE_TO_SIGN_IN_CHECK_YOUR_ACCOUNT_OR_RENEWAL_STATUS.text(),
                CoreSessionMessages.UNABLE_TO_VERIFY_ACCOUNT_INFORMATION_SIGN_IN_AGAIN.text(),
                CoreSessionMessages.THE_SERVICE_DID_NOT_ALLOW_ACCESS_TO_THIS_INFORMATION.text(),
                CoreSessionMessages.TOO_MANY_REQUESTS_REFRESH_LATER.text(),
                CoreSessionMessages.THE_BUILT_IN_SERVICE_IS_TEMPORARILY_UNAVAILABLE.text(),
                CoreSessionMessages.THE_SERVICE_RETURNED_AN_INVALID_RESPONSE.text()
        };
        for (String notice : feedback) {
            check(notice.equals(TrustedEntitlementCache.withoutExpiryNotice(notice)) && currentExpired(cached, 110, 2),
                    "Known home expiry survives operation feedback without replacing it: " + notice);
            check(notice.equals(TrustedEntitlementCache.withoutExpiryNotice(expired + "\n" + notice)),
                    "A previous combined notice keeps only operation feedback after expiry moves into home status: " + notice);
            check(notice.equals(TrustedEntitlementCache.withoutExpiryNotice(notice)) && !currentExpired(null, 110, 2),
                    "Operation feedback alone never fabricates expired entitlement: " + notice);
        }

        String success = feedback[0], error = feedback[1];
        String duplicate = success + "\n" + expired + "\n" + error + "\n" + expired;
        String cleaned = TrustedEntitlementCache.withoutExpiryNotice(duplicate);
        check((success + "\n" + error).equals(cleaned), "All standalone expiry lines are removed without reordering other feedback");
        check(cleaned.equals(TrustedEntitlementCache.withoutExpiryNotice(cleaned)), "Removing obsolete expiry notices is idempotent");
        check(TrustedEntitlementCache.withoutExpiryNotice(expired + "\n" + expired).isEmpty(), "Repeated expiry-only notices leave no duplicate status text");
        String embedded = success + " / " + expired;
        check(embedded.equals(TrustedEntitlementCache.withoutExpiryNotice(embedded)),
                "Only complete expiry lines are removed; other text is preserved verbatim");
        String spaced = " " + expired + " ";
        check(spaced.equals(TrustedEntitlementCache.withoutExpiryNotice(spaced)), "Whitespace-bearing nonmatching feedback is preserved verbatim");
        String blankLines = "\n" + error + "\n\n";
        check(blankLines.equals(TrustedEntitlementCache.withoutExpiryNotice(blankLines)), "Unrelated feedback retains its original blank lines");

        String previousLanguage = UiLocale.isChinese()
                ? CoreSessionMessages.SERVICE_EXPIRED.english() : CoreSessionMessages.SERVICE_EXPIRED.chinese();
        check(TrustedEntitlementCache.withoutExpiryNotice(previousLanguage).isEmpty(),
                "Changing the system language removes a standalone expiry notice in the previous language");
        check(error.equals(TrustedEntitlementCache.withoutExpiryNotice(previousLanguage + "\n" + error))
                        && currentExpired(cached, 110, 2),
                "Changing language keeps current expiry in status while preserving unrelated operation feedback");
        String mixedLanguages = CoreSessionMessages.SERVICE_EXPIRED.chinese() + "\n" + success
                + "\n" + CoreSessionMessages.SERVICE_EXPIRED.english() + "\n" + error + "\n" + previousLanguage;
        check((success + "\n" + error).equals(TrustedEntitlementCache.withoutExpiryNotice(mixedLanguages)),
                "Mixed old and current language expiry lines are all removed without reordering other feedback");
        String embeddedPreviousLanguage = success + " / " + previousLanguage;
        check(embeddedPreviousLanguage.equals(TrustedEntitlementCache.withoutExpiryNotice(embeddedPreviousLanguage)),
                "Previous-language expiry wording embedded in other text is not removed");
        check((" " + previousLanguage + " ").equals(TrustedEntitlementCache.withoutExpiryNotice(" " + previousLanguage + " ")),
                "Previous-language expiry cleanup still requires an exact complete line");
    }

    private static void noCurrentHomeExpiry(TrustedEntitlementCache.Record cached, String endpoint, String user,
                                            long elapsed, int boot, String description) {
        String expired = CoreSessionMessages.SERVICE_EXPIRED.text();
        String error = CoreSessionMessages.THE_NETWORK_CONNECTION_WAS_INTERRUPTED.text();
        check(!TrustedEntitlementCache.currentExpired(cached, endpoint, user, elapsed, boot), description);
        check(error.equals(TrustedEntitlementCache.withoutExpiryNotice(expired + "\n" + error)),
                description + ": stale expiry feedback is removed while unrelated feedback survives");
    }

    private static void homeExpiryRequiresCurrentExactTrustedIdentity() {
        TrustedEntitlementCache.Record expired = record("account-a", 100L, false, 100L, 10, 2);
        noCurrentHomeExpiry(null, ENDPOINT, "account-a", 10, 2, "No authenticated record means expiry is unknown");
        for (String user : new String[] {"account-b", "ACCOUNT-A", "", null}) {
            noCurrentHomeExpiry(expired, ENDPOINT, user, 10, 2, "Only the exact current account may display cached expiry: " + user);
        }
        for (String endpoint : new String[] {"https://other.invalid/", ENDPOINT + "dav/", ENDPOINT.substring(0, ENDPOINT.length() - 1), "", null}) {
            noCurrentHomeExpiry(expired, endpoint, "account-a", 10, 2, "Only the exact official endpoint may display cached expiry: " + endpoint);
        }
        noCurrentHomeExpiry(expired, ENDPOINT, "account-a", 1000, 3, "OS reboot invalidates home expiry even for an already-expired observation");
        noCurrentHomeExpiry(expired, ENDPOINT, "account-a", 1000, -1, "Unknown current boot cannot carry home expiry forward");
        noCurrentHomeExpiry(record("account-a", 100L, false, 100L, 10, -1), ENDPOINT, "account-a", 1000, 2,
                "Unknown recorded boot cannot carry home expiry forward");
        noCurrentHomeExpiry(record("account-a", 100L, false, 100L, 10, -1), ENDPOINT, "account-a", 1000, -1,
                "Two unknown boot counts are not a trusted same-boot match");
        noCurrentHomeExpiry(expired, ENDPOINT, "account-a", 9, 2, "Elapsed rollback invalidates even an already-expired home observation");
        noCurrentHomeExpiry(expired, ENDPOINT, "account-a", -1, 2, "Negative elapsed time cannot supply a current trusted anchor");
        noCurrentHomeExpiry(record("account-a", null, false, 100L, 10, 2), ENDPOINT, "account-a", 1000, 2,
                "Inactive without a deadline does not mean expired");
        noCurrentHomeExpiry(record("account-a", null, true, 100L, 10, 2), ENDPOINT, "account-a", 1000, 2,
                "Unlimited service has no expiry status");
        noCurrentHomeExpiry(record("account-a", 50L, false, null, 10, 2), ENDPOINT, "account-a", Long.MAX_VALUE, 2,
                "A date without authenticated server time cannot invent home expiry");
        noCurrentHomeExpiry(new TrustedEntitlementCache.Record(ENDPOINT, "account-a", 50L, false, 100L, 10, 2, false, true),
                ENDPOINT, "account-a", 1000, 2, "Disabled service is not elapsed expiry");
        noCurrentHomeExpiry(new TrustedEntitlementCache.Record(ENDPOINT, "account-a", 50L, false, 100L, 10, 2, true, false),
                ENDPOINT, "account-a", 1000, 2, "Unconfirmed deadline is not home expiry");
        noCurrentHomeExpiry(record("account-a", 200L, false, 100L, 10, 2), ENDPOINT, "account-a", 109, 2,
                "Inactive before a trusted future deadline is not expiry");
        noCurrentHomeExpiry(record("account-a", 200L, true, 100L, 10, 2), ENDPOINT, "account-a", 109, 2,
                "Active before a trusted future deadline is not expiry");
    }

    private static void homeExpiryHonorsTimeBoundariesWithoutAuthority() {
        TrustedEntitlementCache.Record cached = record("account-a", 200L, true, 100L, 10, 2);
        check(!currentExpired(cached, 109, 2), "Home remains unexpired one millisecond before the trusted boundary");
        check(currentExpired(cached, 110, 2), "Home becomes expired at the exact trusted boundary");
        check(currentExpired(cached, 111, 2), "Home remains expired after the trusted boundary");
        check(currentExpired(record("account-a", 100L, false, 100L, 10, 2), 10, 2),
                "An authenticated server observation exactly at the deadline is expired immediately");
        check(currentExpired(record("account-a", -100L, false, 0L, 0, 0), 0, 0),
                "Already-normalized early-epoch expiry and boot zero are valid without another subtraction");
        TrustedEntitlementCache.Record maximum = record("account-a", Long.MAX_VALUE, true, Long.MAX_VALUE - 10, 10, 2);
        check(!currentExpired(maximum, 19, 2) && currentExpired(maximum, 20, 2),
                "Large server timestamps retain an exact non-overflowing home deadline");
        TrustedEntitlementCache.Record largeElapsed = record("account-a", 200L, true, 199L, Long.MAX_VALUE - 1, 2);
        check(!currentExpired(largeElapsed, Long.MAX_VALUE - 1, 2) && currentExpired(largeElapsed, Long.MAX_VALUE, 2),
                "Large monotonic anchors retain an exact non-overflowing home deadline");

        ManagedAccountFlow flow = new ManagedAccountFlow("account-a", cached, new ManagedAccountFlow.Clock() {
            public long elapsedMs() { return 110; }
            public int bootCount() { return 2; }
        });
        flow.foreground(true);
        check(currentExpired(cached, 110, 2) && !flow.view().entitlementLoaded
                        && flow.view().username == null && !flow.view().active,
                "Displaying expired home status cannot turn cached history into current authentication or access");
        check(cached.active && cached.expiresMs == 200L && cached.serverTimeMs == 100L
                        && cached.elapsedMs == 10 && cached.bootCount == 2,
                "Home presentation never mutates authenticated metadata");
    }

    private static void homeExpiryRestoresAndReplacesDiskHistory() throws Exception {
        Path directory = Files.createTempDirectory("synthetic-home-expiry-");
        try {
            String expired = CoreSessionMessages.SERVICE_EXPIRED.text();
            String error = CoreSessionMessages.THE_NETWORK_CONNECTION_WAS_INTERRUPTED.text();
            TrustedEntitlementCache cache = new TrustedEntitlementCache(directory);
            cache.write(record("account-a", 200L, true, 100L, 10, 2));
            TrustedEntitlementCache.Record restarted = new TrustedEntitlementCache(directory).read(ENDPOINT, "account-a");
            check(restarted != null && !currentExpired(restarted, 109, 2) && currentExpired(restarted, 110, 2),
                    "Same-boot process restart restores the exact expiry boundary from disk without a live refresh");
            check(error.equals(TrustedEntitlementCache.withoutExpiryNotice(error)) && currentExpired(restarted, 111, 2),
                    "Network failure after process restart cannot erase disk-confirmed home expiry");
            noCurrentHomeExpiry(restarted, ENDPOINT, "account-a", 110, 3, "Persisted history does not survive an OS reboot as current home expiry");

            cache.write(record("account-a", 1000L, true, 201L, 120, 2));
            TrustedEntitlementCache.Record renewed = new TrustedEntitlementCache(directory).read(ENDPOINT, "account-a");
            check(!currentExpired(renewed, 120, 2) && TrustedEntitlementCache.withoutExpiryNotice(expired).isEmpty(),
                    "Authenticated renewal removes expired home status and the old expiry-only notice");
            check(error.equals(TrustedEntitlementCache.withoutExpiryNotice(expired + "\n" + error + "\n" + expired))
                            && !currentExpired(renewed, 120, 2),
                    "Authenticated renewal clears stale expiry without discarding unrelated feedback");
            for (String oldExpiry : new String[] {CoreSessionMessages.SERVICE_EXPIRED.chinese(), CoreSessionMessages.SERVICE_EXPIRED.english()}) {
                check(TrustedEntitlementCache.withoutExpiryNotice(oldExpiry).isEmpty() && !currentExpired(renewed, 120, 2),
                        "Disk-restored renewal clears old expiry-only feedback after any supported system language change");
                check(error.equals(TrustedEntitlementCache.withoutExpiryNotice(oldExpiry + "\n" + error))
                                && !currentExpired(renewed, 120, 2),
                        "Disk-restored renewal clears previous-language expiry while preserving the operation error");
            }
            cache.write(record("account-a", 100L, false, 200L, 130, 2));
            check(!currentExpired(new TrustedEntitlementCache(directory).read(ENDPOINT, "account-a"), 130, 2),
                    "A delayed older response cannot restore expired home status after authenticated renewal");
            check(!currentExpired(renewed, 918, 2) && currentExpired(renewed, 919, 2),
                    "The renewed deadline later expires at its own anchor rather than the replaced deadline");

            cache.write(record("account-b", 100L, false, 300L, 140, 2));
            TrustedEntitlementCache.Record other = new TrustedEntitlementCache(directory).read(ENDPOINT, "account-b");
            check(TrustedEntitlementCache.currentExpired(other, ENDPOINT, "account-b", 140, 2),
                    "Another account retains its own independently expired home state");
            noCurrentHomeExpiry(other, ENDPOINT, "account-a", 140, 2, "Switching accounts cannot carry over the previous account expiry status");
            check(!currentExpired(new TrustedEntitlementCache(directory).read(ENDPOINT, "account-a"), 140, 2),
                    "Returning to the renewed account reconstructs its own non-expired state from disk");

            cache.write(record("account-a", null, true, 301L, 150, 2));
            check(!currentExpired(new TrustedEntitlementCache(directory).read(ENDPOINT, "account-a"), Long.MAX_VALUE, 2),
                    "An authenticated unlimited renewal permanently removes the old finite expiry status");
        } finally {
            try (Stream<Path> files = Files.walk(directory)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    private static void localizedHomeExpiryChecks() throws Exception {
        try {
            for (Locale locale : new Locale[] {Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH}) {
                UiLocale.setLocale(locale);
                homeExpirySurvivesNavigationAndOperationFeedback();
                homeExpiryRequiresCurrentExactTrustedIdentity();
                homeExpiryHonorsTimeBoundariesWithoutAuthority();
                homeExpiryRestoresAndReplacesDiskHistory();
            }
        } finally {
            UiLocale.useSystemLocale();
        }
    }

    public static void main(String[] args) throws Exception {
        refreshedHomeAvailabilityFailsClosed();
        trustedClockOnly(); atomicPrivateRecords(); ambiguousVersionOneHistoryIsRejected(); versionTwoRoundTripsStatusAndNormalization(); displayNeverCreatesAuthority(); localizedHomeExpiryChecks();
        System.out.println("TrustedEntitlementCacheChecks: " + checks + " checks passed; no network used.");
    }
}
