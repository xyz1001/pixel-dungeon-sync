package io.github.xyz1001.pdsync.android;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Read-only appreciation display for an explicitly injected sandbox. Server entitlement is
 * authoritative for both trial and renewal; only a separate successful DAV test enables Next.
 * Requests are single-flight and bound to the visible authenticated account generation.
 */
public final class ManualPaymentFlow {
    public static final long NO_POLL = -1L;
    public static final long POLL_BUDGET_MILLIS = 120_000L;
    public static final int MAX_POLL_REQUESTS = 8;
    private static final long[] BACKOFF = {2_000L, 4_000L, 8_000L, 16_000L, 30_000L};

    public enum Action { LOAD_PLANS, LOAD_IMAGE, LOAD_ENTITLEMENT, DAV_TEST }
    public enum Phase { DISABLED, LOADING, SHOWING, ACTIVE, ERROR, DAV_TESTING, READY }

    /** Request identity is not authentication and contains no secret or order identity. */
    public static final class Ticket {
        public final Action action;
        public final String accountScope;
        public final long generation;
        public final ManualOrderClient.Plan plan;
        private Ticket(Action action, String scope, long generation, ManualOrderClient.Plan plan) {
            this.action = action;
            this.accountScope = scope;
            this.generation = generation;
            this.plan = plan;
        }
        @Override public String toString() { return "Sandbox ticket " + action + " #" + generation; }
    }

    /** Legacy local-file shape only. The read-only flow never loads, saves or replays a draft. */
    public static final class Draft {
        public final String accountScope, planId, requestKey, orderId;
        public Draft(String accountScope, String planId, String requestKey, String orderId) {
            this.accountScope = nonEmpty(accountScope, "account scope", 1024);
            this.planId = nonEmpty(planId, "plan id", 256);
            if (requestKey != null && !requestKey.matches("[A-Za-z0-9_-]{16,80}"))
                throw new IllegalArgumentException("Invalid request key");
            if (orderId != null) nonEmpty(orderId, "order id", 256);
            if (requestKey == null && orderId == null) throw new IllegalArgumentException("Missing draft identity");
            this.requestKey = requestKey;
            this.orderId = orderId;
        }
        @Override public String toString() { return "Legacy sandbox draft [private fields hidden]"; }
    }
    public interface DraftStore {
        Draft load(String accountScope) throws IOException;
        void save(Draft draft) throws IOException;
    }

    public static final class View {
        public final List<ManualOrderClient.Plan> plans;
        public final ManualOrderClient.Plan selectedPlan;
        public final ManualOrderClient.Entitlement entitlement;
        public final String message;
        public final Phase phase;
        public final boolean sandbox, busy, canContinue, canTestDav;
        private View(ManualPaymentFlow f) {
            plans = f.plans;
            selectedPlan = f.selectedPlan();
            entitlement = f.entitlement;
            sandbox = f.enabled;
            busy = f.current != null;
            phase = f.phase();
            message = f.message();
            canTestDav = f.usable() && f.davEligible();
            canContinue = canTestDav && f.davPassed;
        }
    }

    private final boolean enabled;
    private String scope, notice;
    private long generation, pollDeadline, nextPollAt;
    private int pollRequests;
    private boolean active, foreground, plansLoaded, entitlementLoaded, davPassed, unsupportedContract;
    private Ticket current;
    private List<ManualOrderClient.Plan> plans = Collections.emptyList();
    private ManualOrderClient.Entitlement entitlement;

    public ManualPaymentFlow() { this(false); }
    public ManualPaymentFlow(boolean sandboxEnabled) { enabled = sandboxEnabled; }
    /** Compatibility for old injection code; existing drafts are deliberately left untouched. */
    public ManualPaymentFlow(DraftStore ignored) { this(false); }
    public ManualPaymentFlow(boolean sandboxEnabled, DraftStore ignored) { this(sandboxEnabled); }

    public synchronized Ticket enter(String accountScope, long now) {
        nonEmpty(accountScope, "account scope", 1024);
        invalidate();
        scope = accountScope;
        active = foreground = true;
        plans = Collections.emptyList();
        entitlement = null;
        plansLoaded = entitlementLoaded = davPassed = unsupportedContract = false;
        notice = null;
        resetPollWindow(now);
        return issue(Action.LOAD_PLANS);
    }

    /** Resume rechecks server state without extending the finite foreground polling window. */
    public synchronized Ticket resume(long now) {
        if (!enabled || !active || !foreground || current != null || scope == null) return null;
        davPassed = entitlementLoaded = false;
        notice = null;
        return issue(plansLoaded && !unsupportedContract
                ? selectedPlan() == null ? Action.LOAD_ENTITLEMENT : Action.LOAD_IMAGE : Action.LOAD_PLANS);
    }
    public synchronized void leave() {
        active = foreground = davPassed = entitlementLoaded = false;
        scope = null;
        entitlement = null;
        plans = Collections.emptyList();
        plansLoaded = false;
        invalidate();
    }
    /** The controller also cancels transport and its delayed runnable. */
    public synchronized void setForeground(boolean value, long now) {
        foreground = value;
        if (!value) { davPassed = entitlementLoaded = false; invalidate(); }
    }
    /** Explicit refresh reloads the code and server state; repeated refresh cannot overlap. */
    public synchronized Ticket refresh(long now) {
        if (!enabled || !active || !foreground || current != null) return null;
        notice = null;
        davPassed = plansLoaded = entitlementLoaded = unsupportedContract = false;
        resetPollWindow(now);
        return issue(Action.LOAD_PLANS);
    }
    public synchronized long nextDelayMillis(long now) {
        if (!enabled || !active || !foreground || unsupportedContract || current != null
                || !plansLoaded || !entitlementLoaded || davPassed) return NO_POLL;
        if (pollRequests >= MAX_POLL_REQUESTS || now >= pollDeadline || nextPollAt >= pollDeadline) return NO_POLL;
        return Math.max(0L, nextPollAt - now);
    }
    public synchronized Ticket poll(long now) {
        if (nextDelayMillis(now) != 0L) return null;
        pollRequests++;
        nextPollAt = safeAdd(now, BACKOFF[Math.min(pollRequests, BACKOFF.length - 1)]);
        return issue(Action.LOAD_ENTITLEMENT);
    }
    public synchronized Ticket beginDavTest() {
        if (!usable() || !davEligible()) return null;
        davPassed = false;
        notice = null;
        return issue(Action.DAV_TEST);
    }
    public synchronized void invalidateDav() {
        davPassed = false;
        if (current != null && current.action == Action.DAV_TEST) invalidate();
    }
    public synchronized boolean acceptDavSuccess(Ticket ticket) {
        if (!accepts(ticket, Action.DAV_TEST) || !davEligible()) return false;
        current = null;
        davPassed = true;
        notice = null;
        return true;
    }
    public synchronized Ticket acceptPlans(Ticket ticket, List<ManualOrderClient.Plan> result) {
        if (!accepts(ticket, Action.LOAD_PLANS)) return null;
        if (result == null) { fail(ticket, "INVALID_RESPONSE"); return null; }
        for (ManualOrderClient.Plan plan : result)
            if (plan == null) { fail(ticket, "INVALID_RESPONSE"); return null; }
        List<ManualOrderClient.Plan> copy = new ArrayList<>(result);
        for (int i = 0; i < copy.size(); i++) for (int j = i + 1; j < copy.size(); j++)
            if (copy.get(i).id.equals(copy.get(j).id)) { fail(ticket, "INVALID_RESPONSE"); return null; }
        plans = Collections.unmodifiableList(copy);
        plansLoaded = true;
        unsupportedContract = false;
        current = null;
        return issue(selectedPlan() == null ? Action.LOAD_ENTITLEMENT : Action.LOAD_IMAGE);
    }
    /** A missing image must not prevent querying an existing trial or renewed entitlement. */
    public synchronized Ticket imageFinished(Ticket ticket) {
        if (!accepts(ticket, Action.LOAD_IMAGE)) return null;
        current = null;
        return issue(Action.LOAD_ENTITLEMENT);
    }
    public synchronized Ticket acceptEntitlement(Ticket ticket, ManualOrderClient.Entitlement result) {
        if (!accepts(ticket, Action.LOAD_ENTITLEMENT)) return null;
        if (result == null) { fail(ticket, "INVALID_RESPONSE"); return null; }
        current = null;
        entitlement = result;
        entitlementLoaded = true;
        // Even a newly active or extended entitlement cannot stand in for DAV verification.
        davPassed = false;
        notice = null;
        return null;
    }
    /** Only safe fixed codes are mapped to visible copy. */
    public synchronized boolean fail(Ticket ticket, String code) {
        if (!isCurrent(ticket)) return false;
        current = null;
        davPassed = false;
        if (ticket.action == Action.LOAD_ENTITLEMENT) entitlementLoaded = false;
        if ("UNSUPPORTED_CONTRACT".equals(code)) unsupportedContract = true;
        if (ticket.action == Action.DAV_TEST) notice = "连接测试未通过";
        else if ("PAYMENTS_DISABLED".equals(code)) notice = "沙箱赞赏未开启";
        else if ("AUTHENTICATION_REQUIRED".equals(code) || "FORBIDDEN".equals(code)) notice = "请重新验证账号";
        else if ("UNSUPPORTED_CONTRACT".equals(code)) notice = "赞赏服务版本不支持";
        else if ("INVALID_RESPONSE".equals(code)) notice = "服务响应无法确认";
        else notice = "暂时无法查询，请刷新";
        return true;
    }
    public synchronized boolean isCurrent(Ticket ticket) {
        return enabled && active && foreground && ticket != null && ticket == current
                && ticket.generation == generation && Objects.equals(ticket.accountScope, scope);
    }
    public synchronized Ticket currentTicket() { return current; }
    public synchronized View snapshot() { return new View(this); }
    private Ticket issue(Action action) {
        if (!enabled || !active || !foreground || current != null || scope == null) return null;
        current = new Ticket(action, scope, ++generation, selectedPlan());
        return current;
    }
    private void invalidate() { generation++; current = null; }
    private boolean accepts(Ticket ticket, Action action) { return isCurrent(ticket) && ticket.action == action; }
    private boolean usable() { return enabled && active && foreground && !unsupportedContract && current == null
            && plansLoaded && entitlementLoaded; }
    private boolean davEligible() { return entitlementLoaded && entitlement != null && entitlement.active; }
    private ManualOrderClient.Plan selectedPlan() { return plans.isEmpty() ? null : plans.get(0); }
    private Phase phase() {
        if (!enabled) return Phase.DISABLED;
        if (current != null) return current.action == Action.DAV_TEST ? Phase.DAV_TESTING : Phase.LOADING;
        if (notice != null || unsupportedContract) return Phase.ERROR;
        if (davPassed && davEligible()) return Phase.READY;
        return davEligible() ? Phase.ACTIVE : Phase.SHOWING;
    }
    private String message() {
        if (!enabled) return "沙箱赞赏未开启";
        if (current != null) return current.action == Action.DAV_TEST ? "正在测试连接" : "正在查询";
        if (notice != null) return notice;
        return davPassed && davEligible() ? "连接已通过" : "";
    }
    private void resetPollWindow(long now) {
        pollRequests = 0;
        pollDeadline = safeAdd(now, POLL_BUDGET_MILLIS);
        nextPollAt = safeAdd(now, BACKOFF[0]);
    }
    private static long safeAdd(long a, long b) { return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b; }
    private static String nonEmpty(String value, String name, int max) {
        if (value == null || value.isEmpty() || value.length() > max || value.indexOf('\u0000') >= 0)
            throw new IllegalArgumentException("Invalid " + name);
        return value;
    }
}
