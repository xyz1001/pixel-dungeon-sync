package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Explicitly injected sandbox session. No default endpoint, credentials or production authenticator. */
public final class ManualPaymentSession implements AutoCloseable {
    public interface Ui {
        long nowMillis();
        void post(Runnable action);
        void after(long delayMillis, Runnable action);
        void remove(Runnable action);
    }
    public interface DavVerifier { void verify() throws Exception; }
    public interface Listener { void changed(); }

    /** Non-secret snapshot from a successful login/registration response, never editable form text. */
    public static final class AuthenticatedIdentity {
        private final String accountScope, username;

        private AuthenticatedIdentity(OfficialServiceRegistrationClient.Account account) {
            accountScope = account.endpoint + "\n" + account.username;
            username = account.username;
        }

        static AuthenticatedIdentity fromOfficialAccount(OfficialServiceRegistrationClient.Account account) {
            return new AuthenticatedIdentity(Objects.requireNonNull(account));
        }

        @Override public String toString() { return "Authenticated identity [private fields hidden]"; }
    }

    private final ManualPaymentFlow flow;
    private final ManualOrderClient client;
    private final ExecutorService io;
    private final Ui ui;
    private final DavVerifier dav;
    private Listener listener;
    private Future<?> task;
    private long attachment;
    private boolean visible, foreground, closed;
    private String accountScope;
    private AuthenticatedIdentity identity;
    private String imageKey;
    private byte[] image;
    private String imageMessage = "";
    private final Runnable poll = this::pollNow;

    /** All methods/callbacks belong to one UI thread. The supplied executor must be dedicated to this session. */
    public ManualPaymentSession(ManualPaymentFlow flow, ManualOrderClient client,
                                ExecutorService io, Ui ui, DavVerifier dav) {
        this.flow = Objects.requireNonNull(flow);
        this.client = Objects.requireNonNull(client);
        this.io = Objects.requireNonNull(io);
        this.ui = Objects.requireNonNull(ui);
        this.dav = Objects.requireNonNull(dav);
    }

    public ManualPaymentFlow.View view() { return flow.snapshot(); }
    public byte[] paymentImage() { return !available() || image == null || !Objects.equals(imageKey, currentImageKey()) ? null : image.clone(); }
    public String imageMessage() { return imageMessage; }
    public void listener(Listener listener) { this.listener = listener; }

    /** Explicit sandbox injection only. Replace on account changes; null revokes the current identity. */
    public void enter(AuthenticatedIdentity identity) {
        if (closed) return;
        stopRequests();
        clearImage();
        this.identity = null;
        this.accountScope = null;
        visible = foreground = false;
        flow.leave();
        if (identity == null || !view().sandbox) { changed(); return; }
        this.identity = identity;
        this.accountScope = identity.accountScope;
        visible = true;
        foreground = true;
        dispatch(flow.enter(accountScope, ui.nowMillis()));
        changed();
    }

    public String authenticatedUsername() {
        return available() && identity != null && Objects.equals(accountScope, identity.accountScope)
                ? identity.username : null;
    }

    /** A retained or delayed button callback cannot copy after account, page or lifecycle changes. */
    public Runnable usernameCopyAction(Consumer<String> clipboard) {
        Objects.requireNonNull(clipboard);
        final long epoch = attachment;
        final AuthenticatedIdentity expected = identity;
        final String username = authenticatedUsername();
        return () -> {
            if (username != null && epoch == attachment && expected == identity
                    && username.equals(authenticatedUsername())) clipboard.accept(username);
        };
    }

    public void leave() {
        visible = false;
        identity = null;
        accountScope = null;
        stopRequests();
        clearImage();
        flow.leave();
        changed();
    }

    public void foreground(boolean foreground) {
        if (closed || this.foreground == foreground) return;
        this.foreground = foreground;
        if (!foreground) {
            stopRequests();
            flow.setForeground(false, ui.nowMillis());
            changed();
        } else if (visible && accountScope != null) {
            // Reload the code and entitlement without assuming paused server state stayed unchanged.
            clearImage();
            flow.setForeground(true, ui.nowMillis());
            dispatch(flow.resume(ui.nowMillis()));
            changed();
        }
    }

    public void refresh() {
        if (!available()) return;
        ManualPaymentFlow.Ticket ticket = flow.refresh(ui.nowMillis());
        if (ticket == null) return;
        clearImage();
        dispatch(ticket);
    }
    public void testDav() { if (available()) dispatch(flow.beginDavTest()); }
    public boolean canContinue() { return available() && view().canContinue; }

    private boolean available() { return !closed && visible && foreground; }
    private void pollNow() {
        if (available()) dispatch(flow.poll(ui.nowMillis()));
    }
    private void dispatch(ManualPaymentFlow.Ticket ticket) {
        if (ticket == null || !available()) { changed(); return; }
        ui.remove(poll);
        final long epoch = attachment;
        changed();
        try { task = io.submit(() -> {
            try {
                Object value;
                switch (ticket.action) {
                    case LOAD_PLANS: value = client.plans(); break;
                    case LOAD_IMAGE: value = client.paymentImage(ticket.plan); break;
                    case LOAD_ENTITLEMENT: value = client.entitlement(); break;
                    case DAV_TEST: dav.verify(); value = Boolean.TRUE; break;
                    default: throw new IllegalStateException(AndroidMessages.MANUAL_PAYMENT_SESSION_01.text());
                }
                final Object result = value;
                ui.post(() -> accepted(epoch, ticket, result));
            } catch (Exception failure) {
                String code = "SERVICE_UNAVAILABLE";
                if (failure instanceof ManualOrderClient.OrderException) {
                    ManualOrderClient.OrderException orderFailure = (ManualOrderClient.OrderException) failure;
                    code = orderFailure.kind == ManualOrderClient.FailureKind.UNSUPPORTED_CONTRACT
                            ? "UNSUPPORTED_CONTRACT" : orderFailure.errorCode;
                }
                final String failureCode = code;
                ui.post(() -> failed(epoch, ticket, failureCode));
            }
        }); } catch (RejectedExecutionException rejected) {
            flow.fail(ticket, "SERVICE_UNAVAILABLE");
            settle();
        }
    }

    @SuppressWarnings("unchecked")
    private void accepted(long epoch, ManualPaymentFlow.Ticket ticket, Object value) {
        if (!available() || epoch != attachment || !flow.isCurrent(ticket)) return;
        task = null;
        ManualPaymentFlow.Ticket next = null;
        switch (ticket.action) {
            case LOAD_PLANS:
                next = flow.acceptPlans(ticket, (List<ManualOrderClient.Plan>) value);
                if (view().selectedPlan == null) imageMessage = AndroidMessages.MANUAL_PAYMENT_SESSION_02.text();
                break;
            case LOAD_IMAGE:
                image = ((byte[]) value).clone();
                imageKey = currentImageKey();
                imageMessage = "";
                next = flow.imageFinished(ticket);
                break;
            case LOAD_ENTITLEMENT: next = flow.acceptEntitlement(ticket, (ManualOrderClient.Entitlement) value); break;
            case DAV_TEST: flow.acceptDavSuccess(ticket); break;
            default: throw new IllegalStateException(AndroidMessages.MANUAL_PAYMENT_SESSION_03.text());
        }
        if (next != null) dispatch(next);
        else settle();
    }
    private void failed(long epoch, ManualPaymentFlow.Ticket ticket, String message) {
        if (!available() || epoch != attachment || !flow.isCurrent(ticket)) return;
        task = null;
        if (ticket.action == ManualPaymentFlow.Action.LOAD_IMAGE) {
            imageMessage = AndroidMessages.MANUAL_PAYMENT_SESSION_02.text();
            dispatch(flow.imageFinished(ticket));
        } else {
            flow.fail(ticket, message);
            settle();
        }
    }
    private void settle() {
        changed();
        ui.remove(poll);
        long delay = flow.nextDelayMillis(ui.nowMillis());
        if (available() && delay != ManualPaymentFlow.NO_POLL) ui.after(delay, poll);
    }
    private String currentImageKey() {
        ManualPaymentFlow.View v = view();
        return accountScope == null || v.selectedPlan == null ? null
                : accountScope + "\n" + v.selectedPlan.paymentImagePath;
    }
    private void clearImage() { imageKey = null; image = null; imageMessage = ""; }
    private void changed() { if (!closed && listener != null) listener.changed(); }
    private void stopRequests() {
        attachment++;
        ui.remove(poll);
        if (task != null) { task.cancel(true); task = null; }
        client.cancelPending();
    }
    @Override public void close() {
        if (closed) return;
        stopRequests();
        flow.leave();
        visible = foreground = false;
        identity = null;
        accountScope = null;
        listener = null;
        closed = true;
        io.shutdownNow();
        clearImage();
    }
}
