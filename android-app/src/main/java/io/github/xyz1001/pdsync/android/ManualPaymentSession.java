package io.github.xyz1001.pdsync.android;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

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

    private final ManualPaymentFlow flow;
    private final ManualOrderClient client;
    private final ExecutorService io;
    private final Ui ui;
    private final DavVerifier dav;
    private Listener listener;
    private Future<?> task;
    private Future<?> imageTask;
    private long attachment;
    private boolean visible, foreground, closed;
    private String accountScope;
    private long imageGeneration;
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
    public byte[] paymentImage() { return image == null || !Objects.equals(imageKey, currentImageKey()) ? null : image.clone(); }
    public String imageMessage() { return imageMessage; }
    public void listener(Listener listener) { this.listener = listener; }

    public void enter(String accountScope) {
        if (closed) return;
        stopRequests();
        if (!Objects.equals(this.accountScope, accountScope)) clearImage();
        this.accountScope = Objects.requireNonNull(accountScope);
        visible = true;
        foreground = true;
        dispatch(flow.enter(accountScope, ui.nowMillis()));
        changed();
    }

    public void leave() {
        visible = false;
        stopRequests();
        flow.leave();
        changed();
    }

    public void foreground(boolean foreground) {
        if (closed) return;
        this.foreground = foreground;
        if (!foreground) {
            stopRequests();
            flow.setForeground(false, ui.nowMillis());
            changed();
        } else if (visible && accountScope != null) {
            // Reload mine/entitlement rather than assuming a paused order stayed unchanged.
            flow.setForeground(true, ui.nowMillis());
            dispatch(flow.resume(ui.nowMillis()));
            changed();
        }
    }

    public void selectPlan(String planId) {
        if (available() && flow.selectPlan(planId)) { clearImage(); changed(); }
    }
    public void create() { if (available()) dispatch(flow.create()); }
    public void submit() { if (available()) dispatch(flow.submit(ui.nowMillis())); }
    public void cancel() { if (available()) dispatch(flow.cancel()); }
    /** Bind a visible confirmation to this account/order/page, never a later replacement. */
    public Runnable cancellationConfirmation() {
        final long epoch = attachment;
        final String scope = accountScope;
        final ManualOrderClient.Order order = view().order;
        return () -> {
            ManualOrderClient.Order current = view().order;
            if (available() && epoch == attachment && Objects.equals(scope, accountScope)
                    && order != null && current != null && order.id.equals(current.id)) cancel();
        };
    }
    public void refresh() {
        if (!available()) return;
        clearImage();
        dispatch(flow.refresh(ui.nowMillis()));
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
                    case LOAD_MINE: value = client.mine(); break;
                    case LOAD_ENTITLEMENT: value = client.entitlement(); break;
                    case CREATE: value = client.create(ticket.planId, ticket.requestKey); break;
                    case SUBMIT: value = client.submit(ticket.orderId); break;
                    case CANCEL: value = client.cancel(ticket.orderId); break;
                    case STATUS: value = client.status(ticket.orderId); break;
                    case DAV_TEST: dav.verify(); value = Boolean.TRUE; break;
                    default: throw new IllegalStateException("Unsupported sandbox action");
                }
                final Object result = value;
                ui.post(() -> accepted(epoch, ticket, result));
            } catch (Exception failure) {
                String code = failure instanceof ManualOrderClient.OrderException
                        ? ((ManualOrderClient.OrderException) failure).errorCode : "SERVICE_UNAVAILABLE";
                ui.post(() -> failed(epoch, ticket, code));
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
            case LOAD_PLANS: next = flow.acceptPlans(ticket, (List<ManualOrderClient.Plan>) value); break;
            case LOAD_MINE: next = flow.acceptMine(ticket, (List<ManualOrderClient.Order>) value); break;
            case LOAD_ENTITLEMENT: next = flow.acceptEntitlement(ticket, (ManualOrderClient.Entitlement) value); break;
            case DAV_TEST: flow.acceptDavSuccess(ticket); break;
            default: flow.acceptOrder(ticket, (ManualOrderClient.Order) value); break;
        }
        if (next != null) dispatch(next);
        else settle();
    }
    private void failed(long epoch, ManualPaymentFlow.Ticket ticket, String message) {
        if (!available() || epoch != attachment || !flow.isCurrent(ticket)) return;
        task = null;
        flow.fail(ticket, message);
        settle();
    }
    private void settle() {
        loadImageIfNeeded();
        changed();
        ui.remove(poll);
        long delay = flow.nextDelayMillis(ui.nowMillis());
        if (available() && delay != ManualPaymentFlow.NO_POLL) ui.after(delay, poll);
    }
    private void loadImageIfNeeded() {
        ManualPaymentFlow.View view = view();
        if (!available() || view.busy || view.order == null || !view.paymentDisplayMatches
                || view.order.state != ManualOrderClient.State.CREATED || view.selectedPlan == null) return;
        ManualOrderClient.Plan plan = view.selectedPlan;
        String key = accountScope + "\n" + view.order.id + "\n" + plan.paymentImagePath;
        if (key.equals(imageKey)) return;
        clearImage();
        imageKey = key;
        final long imageEpoch = imageGeneration;
        final long epoch = attachment;
        try { imageTask = io.submit(() -> {
            try {
                byte[] bytes = client.paymentImage(plan);
                ui.post(() -> {
                    if (available() && epoch == attachment && imageEpoch == imageGeneration && key.equals(imageKey)) {
                        imageTask = null; image = bytes.clone(); changed();
                    }
                });
            } catch (Exception ignored) {
                ui.post(() -> {
                    if (available() && epoch == attachment && imageEpoch == imageGeneration && key.equals(imageKey)) {
                        imageTask = null; imageMessage = "收款图暂不可用，请刷新。"; changed();
                    }
                });
            }
        }); } catch (RejectedExecutionException rejected) {
            imageMessage = "收款图暂不可用，请刷新。";
        }
    }
    private String currentImageKey() {
        ManualPaymentFlow.View v = view();
        return v.order == null || v.selectedPlan == null || !v.paymentDisplayMatches
                || v.order.state != ManualOrderClient.State.CREATED ? null
                : accountScope + "\n" + v.order.id + "\n" + v.selectedPlan.paymentImagePath;
    }
    private void clearImage() {
        imageGeneration++;
        if (imageTask != null) { imageTask.cancel(true); imageTask = null; }
        imageKey = null; image = null; imageMessage = "";
    }
    private void changed() { if (!closed && listener != null) listener.changed(); }
    private void stopRequests() {
        attachment++;
        ui.remove(poll);
        if (task != null) { task.cancel(true); task = null; }
        if (imageTask != null) { imageTask.cancel(true); imageTask = null; clearImage(); }
        client.cancelPending();
    }
    @Override public void close() {
        if (closed) return;
        stopRequests();
        flow.leave();
        visible = foreground = false;
        listener = null;
        closed = true;
        io.shutdownNow();
        clearImage();
    }
}
