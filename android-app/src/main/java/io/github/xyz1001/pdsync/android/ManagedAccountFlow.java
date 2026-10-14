package io.github.xyz1001.pdsync.android;

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
        final boolean busy, entitlementLoaded, active, managed, canRefresh, expired, cachedExpiry;
        final byte[] image;
        private View(ManagedAccountFlow flow) {
            boolean shown = flow.visible && flow.foreground;
            generation = flow.generation;
            username = shown ? flow.username : null;
            localUsername = shown ? flow.expectedUsername : null;
            message = flow.message;
            busy = flow.pending != null;
            entitlementLoaded = username != null;
            TrustedEntitlementCache.Record record = shown ? flow.record : null;
            expiresMs = record == null ? null : record.expiresMs;
            cachedExpiry = record != null && !entitlementLoaded;
            expired = record != null && record.expired(flow.clock.elapsedMs(), flow.clock.bootCount());
            active = flow.active; managed = flow.managed;
            canRefresh = shown && !busy;
            image = flow.image == null ? null : flow.image.clone();
        }
    }

    private final String expectedUsername;
    private final Clock clock;
    private TrustedEntitlementCache.Record record;
    private long generation;
    private boolean visible = true, foreground;
    private Ticket pending;
    private String username, message = "请刷新查看。";
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
        if (!value) { invalidate(); clear(); message = "请刷新查看。"; }
    }
    synchronized Ticket refresh() {
        if (!visible || !foreground || pending != null) return null;
        invalidate(); clear(); message = "";
        pending = new Ticket(this, generation);
        return pending;
    }
    synchronized boolean accepts(Ticket ticket) {
        return visible && foreground && ticket != null && ticket.owner == this
                && ticket == pending && ticket.generation == generation;
    }

    synchronized boolean entitlement(Ticket ticket, String serverUsername, Long expiresMs,
                                     boolean active, boolean managed) {
        return entitlement(ticket, serverUsername, expiresMs, active, managed, null);
    }

    /** Only a successful authenticated response can replace trusted account dates. */
    synchronized boolean entitlement(Ticket ticket, String serverUsername, Long expiresMs,
                                     boolean active, boolean managed, Long serverTimeMs) {
        if (!accepts(ticket)) return false;
        if (!expectedUsername.equals(serverUsername) || (expiresMs != null && expiresMs < 0)
                || (serverTimeMs != null && serverTimeMs < 0)) {
            username = null; this.active = this.managed = false;
            pending = null;
            message = "账号信息无法确认，请重新登录。";
            return false;
        }
        this.username = serverUsername;
        this.active = active; this.managed = managed;
        this.record = new TrustedEntitlementCache.Record(TrustedEntitlementCache.OFFICIAL_ENDPOINT,
                serverUsername, expiresMs, active, serverTimeMs, clock.elapsedMs(), clock.bootCount());
        return true;
    }

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
        message = safeMessage == null || safeMessage.isEmpty() ? "暂时无法读取，请刷新。" : safeMessage;
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
        invalidate(); clear(); record = null; message = "";
    }
    static String expiry(View view) {
        if (view.expiresMs == null) return view.entitlementLoaded ? "有效期 未设置" : "有效期 未知";
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(view.expiresMs));
        return (view.cachedExpiry ? "上次确认有效至 " : "有效至 ") + date;
    }
    static String accessStatus(View view) { return view.expired ? "服务已到期" : ""; }
    private void invalidate() { generation++; pending = null; }
    private void clear() { username = null; active = managed = false; image = null; }
}
