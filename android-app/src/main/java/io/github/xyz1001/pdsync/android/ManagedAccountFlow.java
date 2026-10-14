package io.github.xyz1001.pdsync.android;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/** Read-only account display state. No credentials, connection proof, polling, or writes. */
final class ManagedAccountFlow {
    static final class Ticket {
        private final ManagedAccountFlow owner;
        final long generation;
        private Ticket(ManagedAccountFlow owner, long generation) {
            this.owner = owner;
            this.generation = generation;
        }
    }

    static final class View {
        final long generation;
        final String username, message;
        final Long expiresMs;
        final boolean busy, entitlementLoaded, active, managed, canRefresh;
        final byte[] image;
        private View(ManagedAccountFlow flow) {
            generation = flow.generation;
            username = flow.username;
            message = flow.message;
            expiresMs = flow.expiresMs;
            busy = flow.pending != null;
            entitlementLoaded = flow.username != null;
            active = flow.active;
            managed = flow.managed;
            canRefresh = flow.visible && flow.foreground && !busy;
            image = flow.image == null ? null : flow.image.clone();
        }
    }

    private final String expectedUsername;
    private long generation;
    private boolean visible = true, foreground;
    private Ticket pending;
    private String username, message = "请刷新查看。";
    private Long expiresMs;
    private boolean active, managed;
    private byte[] image;

    ManagedAccountFlow(String expectedUsername) {
        this.expectedUsername = Objects.requireNonNull(expectedUsername);
        if (expectedUsername.isEmpty()) throw new IllegalArgumentException("Missing account identity");
    }

    synchronized View view() { return new View(this); }

    synchronized void foreground(boolean value) {
        if (!visible) return;
        foreground = value;
        if (!value) { invalidate(); clear(); message = "请刷新查看。"; }
    }

    synchronized Ticket refresh() {
        if (!visible || !foreground || pending != null) return null;
        invalidate();
        clear();
        message = "";
        pending = new Ticket(this, generation);
        return pending;
    }

    synchronized boolean accepts(Ticket ticket) {
        return visible && foreground && ticket != null && ticket.owner == this
                && ticket == pending && ticket.generation == generation;
    }

    /** Only the authenticated server response may provide the displayed username and expiry. */
    synchronized boolean entitlement(Ticket ticket, String serverUsername, Long expiresMs,
                                     boolean active, boolean managed) {
        if (!accepts(ticket)) return false;
        if (!expectedUsername.equals(serverUsername) || (expiresMs != null && expiresMs < 0)) {
            clear();
            pending = null;
            message = "账号信息无法确认，请重新登录。";
            return false;
        }
        this.username = serverUsername;
        this.expiresMs = expiresMs;
        this.active = active;
        this.managed = managed;
        return true;
    }

    synchronized boolean image(Ticket ticket, byte[] png) {
        if (!accepts(ticket) || username == null || png == null || png.length == 0) return false;
        image = png.clone();
        pending = null;
        message = "";
        return true;
    }

    synchronized boolean fail(Ticket ticket, String safeMessage) {
        if (!accepts(ticket)) return false;
        pending = null;
        image = null;
        message = safeMessage == null || safeMessage.isEmpty() ? "暂时无法读取，请刷新。" : safeMessage;
        return true;
    }

    /** Captured buttons cannot copy a replaced, hidden, or background account. */
    synchronized boolean copyUsername(long viewGeneration, Consumer<String> copy) {
        if (!visible || !foreground || username == null || viewGeneration != generation) return false;
        copy.accept(username);
        return true;
    }

    synchronized void close() {
        visible = foreground = false;
        invalidate();
        clear();
        message = "";
    }

    static String expiry(View view) {
        if (!view.entitlementLoaded) return "有效期 待读取";
        if (view.expiresMs == null) return "有效期 未设置";
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(view.expiresMs));
        return "有效至 " + date + (view.active ? "" : "（已到期）");
    }

    private void invalidate() { generation++; pending = null; }
    private void clear() {
        username = null;
        expiresMs = null;
        active = managed = false;
        image = null;
    }
}
