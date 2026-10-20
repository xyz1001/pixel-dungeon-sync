package io.github.xyz1001.pdsync.android;

import pdsync.i18n.CoreSessionMessages;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/** Appreciation display only. Public assets and local identity never establish DAV permission. */
final class ManagedAccountFlow {
    interface Clock { long elapsedMs(); int bootCount(); }
    static final class Ticket {
        private final ManagedAccountFlow owner;
        final long generation;
        private Ticket(ManagedAccountFlow owner, long generation) {
            this.owner = owner; this.generation = generation;
        }
    }
    static final class View {
        final long generation;
        final String username, localUsername, message;
        final Long expiresMs;
        final boolean busy, entitlementLoaded, active, managed, canRefresh, expired, cachedExpiry, expiryConfirmed;
        final byte[] image;
        private View(ManagedAccountFlow flow) {
            boolean shown = flow.visible;
            generation = flow.generation;
            username = shown ? flow.username : null;
            localUsername = shown ? flow.expectedUsername : null;
            message = flow.message;
            busy = flow.pending != null;
            entitlementLoaded = username != null;
            TrustedEntitlementCache.Record record = shown ? flow.record : null;
            expiresMs = record == null ? null : record.expiresMs;
            expiryConfirmed = record != null && record.hasConfirmedExpiry;
            cachedExpiry = record != null && !entitlementLoaded;
            expired = record != null && flow.expired(record);
            active = flow.active; managed = flow.managed;
            canRefresh = shown && flow.foreground && !busy;
            image = flow.image == null ? null : flow.image.clone();
        }
    }

    private final String expectedUsername;
    private final Clock clock;
    private TrustedEntitlementCache.Record record;
    private boolean currentSessionRecord;
    private long generation;
    private boolean visible = true, foreground;
    private Ticket pending;
    private String username, message = "";
    private String imageSha256;
    private boolean active, managed;
    private byte[] image;

    ManagedAccountFlow(String expectedUsername) {
        this(expectedUsername, null, new Clock() {
            public long elapsedMs() { return 0; }
            public int bootCount() { return -1; }
        });
    }

    ManagedAccountFlow(String expectedUsername, TrustedEntitlementCache.Record cached, Clock clock) {
        this.expectedUsername = Objects.requireNonNull(expectedUsername);
        if (!expectedUsername.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}"))
            throw new IllegalArgumentException("Missing account identity");
        this.clock = Objects.requireNonNull(clock);
        this.record = cached != null && cached.matches(TrustedEntitlementCache.OFFICIAL_ENDPOINT, expectedUsername)
                ? cached : null;
    }

    synchronized View view() { return new View(this); }
    synchronized void foreground(boolean value) {
        if (!visible) return;
        foreground = value;
    }
    synchronized Ticket refresh() {
        if (!visible || !foreground || pending != null) return null;
        invalidate(); clearEntitlement(); message = "";
        pending = new Ticket(this, generation);
        return pending;
    }
    synchronized boolean accepts(Ticket ticket) {
        return visible && ticket != null && ticket.owner == this
                && ticket == pending && ticket.generation == generation;
    }

    synchronized boolean entitlement(Ticket ticket, String serverUsername, Long expiresMs,
                                     boolean active, boolean managed) {
        return entitlement(ticket, serverUsername, expiresMs, active, managed, null);
    }

    /** Only a successful authenticated response can replace trusted account dates. */
    synchronized boolean entitlement(Ticket ticket, String serverUsername, Long expiresMs,
                                     boolean active, boolean managed, Long serverTimeMs) {
        return entitlement(ticket, serverUsername, expiresMs, active, managed, serverTimeMs, true);
    }

    synchronized boolean entitlement(Ticket ticket, String serverUsername, Long expiresMs,
                                     boolean active, boolean managed, Long serverTimeMs, boolean enabled) {
        return entitlement(ticket, serverUsername, expiresMs, active, managed, serverTimeMs, enabled, true);
    }

    synchronized boolean entitlement(Ticket ticket, String serverUsername, Long expiresMs,
                                     boolean active, boolean managed, Long serverTimeMs,
                                     boolean enabled, boolean hasConfirmedExpiry) {
        if (!accepts(ticket)) return false;
        // expiresMs is already normalized by the official client and can be before the epoch.
        if (!expectedUsername.equals(serverUsername) || (serverTimeMs != null && serverTimeMs < 0)) {
            username = null; this.active = this.managed = false;
            pending = null;
            message = CoreSessionMessages.UNABLE_TO_VERIFY_ACCOUNT_INFORMATION_SIGN_IN_AGAIN.text();
            return false;
        }
        this.username = serverUsername;
        this.active = active; this.managed = managed;
        this.record = new TrustedEntitlementCache.Record(TrustedEntitlementCache.OFFICIAL_ENDPOINT,
                serverUsername, expiresMs, active, serverTimeMs, clock.elapsedMs(), clock.bootCount(), enabled, hasConfirmedExpiry);
        currentSessionRecord = true;
        return true;
    }

    /** A known server asset change invalidates only the public image, never account authority. */
    synchronized boolean metadata(Ticket ticket, String sha256) {
        if (!accepts(ticket) || sha256 == null || !sha256.matches("[0-9a-f]{64}")) return false;
        if (!sha256.equals(imageSha256)) image = null;
        imageSha256 = sha256;
        return true;
    }
    synchronized boolean hasImage(Ticket ticket) { return accepts(ticket) && image != null; }

    /** The public image is independent of login, identity proof and private expiry metadata. */
    synchronized boolean image(Ticket ticket, byte[] png) {
        if (!accepts(ticket) || png == null || png.length == 0) return false;
        image = png.clone();
        return true;
    }
    synchronized boolean complete(Ticket ticket) {
        if (!accepts(ticket)) return false;
        pending = null; message = "";
        return true;
    }
    synchronized boolean fail(Ticket ticket, String safeMessage) {
        if (!accepts(ticket)) return false;
        pending = null;
        message = safeMessage == null || safeMessage.isEmpty() ? CoreSessionMessages.UNABLE_TO_READ_RIGHT_NOW.text() : safeMessage;
        return true;
    }

    /** A local username is explicitly unverified; neither copying it nor viewing QR proves login. */
    synchronized boolean copyUsername(long viewGeneration, Consumer<String> copy) {
        if (!visible || !foreground || viewGeneration != generation) return false;
        copy.accept(username == null ? expectedUsername : username);
        return true;
    }
    synchronized void close() {
        visible = foreground = false;
        invalidate(); clearEntitlement(); image = null; imageSha256 = null; record = null; currentSessionRecord = false; message = "";
    }
    static String expiry(View view) {
        if (view.expiresMs == null) return view.entitlementLoaded && view.expiryConfirmed ? CoreSessionMessages.EXPIRY_NOT_SET.text() : CoreSessionMessages.EXPIRY_UNKNOWN.text();
        String date = new SimpleDateFormat("yyyy-MM-dd", pdsync.i18n.UiLocale.formattingLocale()).format(new Date(view.expiresMs));
        return (view.cachedExpiry ? CoreSessionMessages.LAST_CONFIRMED_VALID_UNTIL : CoreSessionMessages.VALID_UNTIL).text(date);
    }
    static String accessStatus(View view) { return view.expired ? CoreSessionMessages.SERVICE_EXPIRED.text() : ""; }
    /** A live response shares this process's monotonic clock even when no boot ID is available. */
    private boolean expired(TrustedEntitlementCache.Record value) {
        long now = clock.elapsedMs();
        int boot = clock.bootCount();
        if (value.expired(now, boot)) return true;
        return currentSessionRecord && value.enabled && value.hasConfirmedExpiry && value.expiresMs != null && value.serverTimeMs != null
                && now >= value.elapsedMs && (value.bootCount < 0 || value.bootCount == boot)
                && now - value.elapsedMs >= value.expiresMs - value.serverTimeMs;
    }
    private void invalidate() { generation++; pending = null; }
    private void clearEntitlement() { username = null; active = managed = false; }
}
