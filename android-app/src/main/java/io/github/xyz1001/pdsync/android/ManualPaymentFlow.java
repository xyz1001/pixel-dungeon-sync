package io.github.xyz1001.pdsync.android;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Sandbox-only, single-flight presentation state. No HTTP, Android, credentials or prices.
 * The controller executes returned tickets, cancels its transport on leave/background, and
 * returns results on this model. Account scope must identify the authenticated service/account,
 * never be a password or token. All times supplied by the controller are monotonic milliseconds.
 */
public final class ManualPaymentFlow {
    public static final long NO_POLL = -1L;
    public static final long POLL_BUDGET_MILLIS = 120_000L;
    public static final int MAX_POLL_REQUESTS = 8;
    private static final long[] BACKOFF = {2_000L, 4_000L, 8_000L, 16_000L, 30_000L};

    public enum Action { LOAD_PLANS, LOAD_MINE, LOAD_ENTITLEMENT, CREATE, SUBMIT, CANCEL, STATUS, DAV_TEST }
    public enum Phase { DISABLED, LOADING, SELECT_PLAN, CREATED, REVIEW, APPLYING, APPROVED,
        CANCELLED, REJECTED, ERROR, DAV_TESTING, READY }

    /** Immutable request identity; possession does not authenticate any service operation. */
    public static final class Ticket {
        public final Action action;
        public final String accountScope, planId, requestKey, orderId;
        public final long generation;
        private Ticket(Action action, String scope, long generation, String planId,
                       String requestKey, String orderId) {
            this.action = action;
            this.accountScope = scope;
            this.generation = generation;
            this.planId = planId;
            this.requestKey = requestKey;
            this.orderId = orderId;
        }
        @Override public String toString() { return "Sandbox ticket " + action + " #" + generation; }
    }

    /** Non-secret idempotency metadata only; never store login credentials here. */
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
        @Override public String toString() { return "Sandbox order draft [private fields hidden]"; }
    }

    public interface DraftStore {
        Draft load(String accountScope) throws IOException;
        /** Must complete a durable, atomic replacement before returning. */
        void save(Draft draft) throws IOException;
    }

    public static final class View {
        public final List<ManualOrderClient.Plan> plans;
        public final String selectedPlanId, message;
        public final ManualOrderClient.Plan selectedPlan;
        public final ManualOrderClient.Order order;
        public final ManualOrderClient.Entitlement entitlement;
        public final Phase phase;
        public final boolean sandbox, busy, canContinue, canCreate, canSubmit, canCancel, canTestDav,
                paymentDisplayMatches, outcomeUnknown;
        private View(ManualPaymentFlow f) {
            plans = f.plans;
            selectedPlanId = f.selectedPlanId;
            selectedPlan = f.selectedPlan();
            order = f.order;
            entitlement = f.entitlement;
            sandbox = f.enabled;
            busy = f.current != null;
            phase = f.phase();
            message = f.message();
            boolean usable = f.usable();
            canContinue = usable && f.davPassed && f.davEligible();
            canCreate = f.canCreate();
            canSubmit = usable && f.created();
            canCancel = usable && (f.created() || f.state(ManualOrderClient.State.REVIEW));
            canTestDav = usable && f.davEligible();
            paymentDisplayMatches = order != null && matches(selectedPlan, order);
            outcomeUnknown = f.outcomeUnknown;
        }
    }

    private final boolean enabled;
    private final DraftStore store;
    private String scope, selectedPlanId, notice;
    private long generation, pollDeadline, nextPollAt;
    private int pollRequests;
    private boolean active, foreground, plansLoaded, mineLoaded, entitlementLoaded, storageReady,
            davPassed, outcomeUnknown, pollNeedsWindow, unsupportedContract;
    private Ticket current;
    private Draft draft;
    private List<ManualOrderClient.Plan> plans = Collections.emptyList();
    private ManualOrderClient.Order order;
    private ManualOrderClient.Entitlement entitlement;

    public ManualPaymentFlow(DraftStore store) { this(false, store); }
    public ManualPaymentFlow(boolean sandboxEnabled, DraftStore store) {
        this.enabled = sandboxEnabled;
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Explicit page entry. Re-entering preserves the account's draft but always rechecks server state. */
    public synchronized Ticket enter(String accountScope, long now) {
        nonEmpty(accountScope, "account scope", 1024);
        invalidate();
        active = true;
        foreground = true;
        davPassed = false;
        notice = null;
        if (!Objects.equals(scope, accountScope)) {
            scope = accountScope;
            selectedPlanId = null;
            draft = null;
            order = null;
            entitlement = null;
            plans = Collections.emptyList();
            outcomeUnknown = false;
        }
        plansLoaded = mineLoaded = entitlementLoaded = false;
        unsupportedContract = false;
        resetPollWindow(now);
        if (!enabled) return null;
        try {
            Draft loaded = store.load(scope);
            if (loaded != null && !scope.equals(loaded.accountScope)) throw new IOException("Draft scope mismatch");
            draft = loaded;
            storageReady = true;
            if (draft != null) {
                if (selectedPlanId == null || order == null || pending()
                        || !Objects.equals(order.id, draft.orderId)) selectedPlanId = draft.planId;
                outcomeUnknown = draft.orderId == null;
            }
        } catch (IOException | RuntimeException ignored) {
            storageReady = false;
            notice = "订单草稿无法读取";
            return null;
        }
        return issue(Action.LOAD_PLANS);
    }

    /** Refresh visibility state without extending an already-running order's poll budget. */
    public synchronized Ticket resume(long now) {
        if (!enabled || !active || !foreground || current != null || scope == null) return null;
        boolean preserve = waiting();
        long deadline = pollDeadline, next = nextPollAt;
        int requests = pollRequests;
        boolean needsWindow = pollNeedsWindow;
        Ticket ticket = enter(scope, now);
        if (preserve) {
            pollDeadline = deadline;
            nextPollAt = next;
            pollRequests = requests;
            pollNeedsWindow = needsWindow;
        }
        return ticket;
    }

    /** Also cancel the controller's current Call/Future and delayed poll runnable. */
    public synchronized void leave() {
        active = false;
        davPassed = false;
        invalidate();
    }

    /** Becoming visible does not start a request or extend the finite polling budget. */
    public synchronized void setForeground(boolean value, long now) {
        foreground = value;
        if (!value) {
            davPassed = false;
            if (current != null && current.action == Action.CREATE) outcomeUnknown = true;
            invalidate();
        }
    }

    public synchronized boolean selectPlan(String planId) {
        if (!usable() || pending() || unresolvedDraft() || findPlan(planId) == null) return false;
        if (!Objects.equals(selectedPlanId, planId)) davPassed = false;
        selectedPlanId = planId;
        notice = null;
        return true;
    }

    /** Persist owner+plan+stable key BEFORE allowing the first create HTTP request. */
    public synchronized Ticket create() {
        if (!canCreate()) return null;
        String key = draft != null && draft.orderId == null && Objects.equals(draft.planId, selectedPlanId)
                ? draft.requestKey : UUID.randomUUID().toString();
        Draft next = new Draft(scope, selectedPlanId, key, null);
        if (!saveDraft(next)) return null;
        order = null;
        davPassed = false;
        outcomeUnknown = true; // Only a matching server response establishes the outcome.
        notice = null;
        return issue(Action.CREATE);
    }

    public synchronized Ticket submit() {
        if (!usable() || !created()) return null;
        notice = null;
        return issue(Action.SUBMIT);
    }

    /** Clock-taking overload for controllers that timestamp every user action. */
    public synchronized Ticket submit(long now) {
        Ticket ticket = submit();
        if (ticket != null) resetPollWindow(now);
        return ticket;
    }

    public synchronized Ticket cancel() {
        if (!usable() || !(created() || state(ManualOrderClient.State.REVIEW))) return null;
        notice = null;
        return issue(Action.CANCEL);
    }

    /** An explicit refresh resets the bounded foreground poll window. */
    public synchronized Ticket refresh(long now) {
        if (!enabled || !active || !foreground || current != null) return null;
        notice = null;
        resetPollWindow(now);
        if (!storageReady) return enter(scope, now);
        if (unsupportedContract) {
            unsupportedContract = false;
            plansLoaded = mineLoaded = entitlementLoaded = false;
            return issue(Action.LOAD_PLANS);
        }
        if (!plansLoaded) return issue(Action.LOAD_PLANS);
        if (!mineLoaded || !entitlementLoaded || order == null) return issue(Action.LOAD_MINE);
        return issue(Action.STATUS);
    }

    public synchronized long nextDelayMillis(long now) {
        if (!enabled || !active || !foreground || unsupportedContract || current != null || !waiting()) return NO_POLL;
        if (pollNeedsWindow) resetPollWindow(now);
        if (pollRequests >= MAX_POLL_REQUESTS || now >= pollDeadline || nextPollAt >= pollDeadline)
            return NO_POLL;
        return Math.max(0L, nextPollAt - now);
    }

    public synchronized Ticket poll(long now) {
        if (nextDelayMillis(now) != 0L) return null;
        pollRequests++;
        nextPollAt = safeAdd(now, BACKOFF[Math.min(pollRequests, BACKOFF.length - 1)]);
        return issue(Action.STATUS);
    }

    public synchronized Ticket beginDavTest() {
        if (!usable() || !davEligible()) return null;
        davPassed = false;
        notice = null;
        return issue(Action.DAV_TEST);
    }

    /** The controller must call this whenever DAV endpoint/user/password inputs are edited. */
    public synchronized void invalidateDav() {
        davPassed = false;
        if (current != null && current.action == Action.DAV_TEST) invalidate();
    }

    public synchronized boolean acceptDavSuccess(Ticket ticket) {
        if (!accepts(ticket, Action.DAV_TEST) || !davEligible()
                || !Objects.equals(ticket.orderId, order == null ? null : order.id)) return false;
        current = null;
        davPassed = true;
        notice = null;
        return true;
    }

    public synchronized Ticket acceptPlans(Ticket ticket, List<ManualOrderClient.Plan> result) {
        if (!accepts(ticket, Action.LOAD_PLANS)) return null;
        if (result == null || hasNull(result)) { fail(ticket, "INVALID_RESPONSE"); return null; }
        List<ManualOrderClient.Plan> copy = new ArrayList<>(result);
        for (int i = 0; i < copy.size(); i++) for (int j = i + 1; j < copy.size(); j++)
            if (copy.get(i).id.equals(copy.get(j).id)) { fail(ticket, "INVALID_RESPONSE"); return null; }
        plans = Collections.unmodifiableList(copy);
        plansLoaded = true;
        current = null;
        if (selectedPlanId == null && !plans.isEmpty()) selectedPlanId = plans.get(0).id;
        return issue(Action.LOAD_MINE);
    }

    public synchronized Ticket acceptMine(Ticket ticket, List<ManualOrderClient.Order> result) {
        if (!accepts(ticket, Action.LOAD_MINE)) return null;
        if (result == null || hasNull(result)) { fail(ticket, "INVALID_RESPONSE"); return null; }
        ManualOrderClient.Order recovered = null, known = null;
        for (ManualOrderClient.Order candidate : result) {
            if (draft != null && Objects.equals(draft.orderId, candidate.id)) known = candidate;
            if (isPending(candidate)) {
                if (recovered != null) { fail(ticket, "INVALID_RESPONSE"); return null; }
                recovered = candidate;
            }
        }
        if (recovered == null) recovered = known;
        current = null;
        mineLoaded = true;
        if (recovered != null) {
            String key = draft != null && Objects.equals(draft.planId, recovered.planId)
                    && (draft.orderId == null || draft.orderId.equals(recovered.id)) ? draft.requestKey : null;
            if (!saveDraft(new Draft(scope, recovered.planId, key, recovered.id))) return null;
            if (!waiting() || order == null || !order.id.equals(recovered.id)) pollNeedsWindow = true;
            order = recovered;
            if (isPending(recovered) || selectedPlanId == null) selectedPlanId = recovered.planId;
            outcomeUnknown = false;
            davPassed = false;
        } else {
            order = null;
            davPassed = false;
        }
        return issue(Action.LOAD_ENTITLEMENT);
    }

    public synchronized Ticket acceptEntitlement(Ticket ticket, ManualOrderClient.Entitlement result) {
        if (!accepts(ticket, Action.LOAD_ENTITLEMENT)) return null;
        if (result == null) { fail(ticket, "INVALID_RESPONSE"); return null; }
        current = null;
        entitlement = result;
        entitlementLoaded = true;
        notice = null;
        if (order == null && draft != null && draft.orderId != null) return issue(Action.STATUS);
        return null;
    }

    public synchronized boolean acceptOrder(Ticket ticket, ManualOrderClient.Order result) {
        if (!acceptsOrder(ticket)) return false;
        if (result == null || (ticket.action == Action.CREATE && !Objects.equals(ticket.planId, result.planId))
                || (ticket.action != Action.CREATE && !Objects.equals(ticket.orderId, result.id))) {
            fail(ticket, "INVALID_RESPONSE");
            return false;
        }
        String key = draft != null && draft.planId.equals(result.planId) ? draft.requestKey : null;
        if (!saveDraft(new Draft(scope, result.planId, key, result.id))) {
            current = null;
            return false;
        }
        current = null;
        if (!waiting()) pollNeedsWindow = true;
        order = result;
        selectedPlanId = result.planId;
        outcomeUnknown = false;
        davPassed = false;
        notice = null;
        return true;
    }

    /** Supply a fixed error code, never raw exception/response text or credentials. */
    public synchronized boolean fail(Ticket ticket, String code) {
        if (!isCurrent(ticket)) return false;
        current = null;
        davPassed = false;
        if (ticket.action == Action.CREATE) outcomeUnknown = true;
        if ("UNSUPPORTED_CONTRACT".equals(code)) unsupportedContract = true;
        if (ticket.action == Action.DAV_TEST) notice = "连接测试未通过";
        else if ("PAYMENTS_DISABLED".equals(code)) notice = "沙箱赞赏未开启";
        else if ("AUTHENTICATION_REQUIRED".equals(code) || "FORBIDDEN".equals(code)) notice = "请重新验证账号";
        else if ("PENDING_ORDER_EXISTS".equals(code)) notice = "已有待处理订单，请刷新";
        else if ("UNSUPPORTED_CONTRACT".equals(code)) notice = "赞赏服务版本不支持";
        else if ("INVALID_RESPONSE".equals(code)) notice = "订单响应无法确认";
        else if (outcomeUnknown) notice = "结果待确认，请刷新或重试";
        else notice = "暂时无法查询，请重试";
        return true;
    }

    public synchronized boolean isCurrent(Ticket ticket) {
        return enabled && active && foreground && ticket != null && ticket == current
                && ticket.generation == generation && Objects.equals(ticket.accountScope, scope);
    }
    public synchronized Ticket currentTicket() { return current; }
    public synchronized View snapshot() { return new View(this); }

    private Ticket issue(Action action) {
        if (!enabled || !active || !foreground || current != null || !storageReady) return null;
        current = new Ticket(action, scope, ++generation, selectedPlanId,
                draft == null ? null : draft.requestKey,
                order != null ? order.id : draft == null ? null : draft.orderId);
        return current;
    }
    private void invalidate() { generation++; current = null; }
    private boolean accepts(Ticket ticket, Action action) { return isCurrent(ticket) && ticket.action == action; }
    private boolean acceptsOrder(Ticket t) {
        return isCurrent(t) && (t.action == Action.CREATE || t.action == Action.SUBMIT
                || t.action == Action.CANCEL || t.action == Action.STATUS);
    }
    private boolean saveDraft(Draft next) {
        try { store.save(next); draft = next; return true; }
        catch (IOException | RuntimeException ignored) {
            storageReady = false;
            notice = "订单草稿无法保存";
            return false;
        }
    }
    private boolean usable() { return enabled && active && foreground && !unsupportedContract && current == null && storageReady
            && plansLoaded && mineLoaded && entitlementLoaded; }
    private boolean canCreate() { return usable() && selectedPlan() != null && !pending()
            && !(draft != null && draft.orderId != null && order == null)
            && !(draft != null && draft.orderId == null && !Objects.equals(draft.planId, selectedPlanId)); }
    private boolean unresolvedDraft() { return draft != null && draft.orderId == null; }
    private boolean state(ManualOrderClient.State value) { return order != null && order.state == value; }
    private boolean created() { return state(ManualOrderClient.State.CREATED); }
    private boolean approved() { return state(ManualOrderClient.State.APPROVED); }
    private boolean davEligible() {
        return approved() || (entitlementLoaded && entitlement != null && entitlement.active
                && !pending() && !unresolvedDraft() && !(order == null && draft != null && draft.orderId != null));
    }
    private boolean waiting() { return state(ManualOrderClient.State.REVIEW) || state(ManualOrderClient.State.APPLYING); }
    private boolean pending() { return order != null && isPending(order); }
    private static boolean isPending(ManualOrderClient.Order value) {
        return value.state == ManualOrderClient.State.CREATED || value.state == ManualOrderClient.State.REVIEW
                || value.state == ManualOrderClient.State.APPLYING;
    }
    private ManualOrderClient.Plan selectedPlan() { return findPlan(selectedPlanId); }
    private ManualOrderClient.Plan findPlan(String id) {
        for (ManualOrderClient.Plan value : plans) if (value.id.equals(id)) return value;
        return null;
    }
    /** Equal price/limits cannot prove a versioned payment image: the v1 API has no image binding. */
    public static boolean matches(ManualOrderClient.Plan p, ManualOrderClient.Order o) {
        return p != null && o != null && p.id.equals(o.planId) && "donation".equals(p.paymentMode)
                && "sandbox_upload_only".equals(p.expiryPolicy)
                && Objects.equals(p.currency, o.currency)
                && Objects.equals(p.amountMinor, o.amountMinor) && p.days == o.days && p.quotaBytes == o.quotaBytes
                && p.uploadKbps == o.uploadKbps && p.downloadKbps == o.downloadKbps && p.term.equals(o.term);
    }
    private Phase phase() {
        if (!enabled) return Phase.DISABLED;
        if (unsupportedContract) return Phase.ERROR;
        if (current != null) return current.action == Action.DAV_TEST ? Phase.DAV_TESTING : Phase.LOADING;
        if (davPassed && davEligible()) return Phase.READY;
        if (order != null) return Phase.valueOf(order.state.name());
        return notice == null ? Phase.SELECT_PLAN : Phase.ERROR;
    }
    private String message() {
        if (!enabled) return "沙箱赞赏未开启";
        if (current != null) return current.action == Action.DAV_TEST ? "正在测试连接" : "正在查询";
        if (notice != null) return notice;
        if (davPassed && davEligible()) return "连接已通过";
        if (order == null && davEligible()) return "沙箱额度有效，请测试连接";
        if (order == null) return outcomeUnknown ? "结果待确认，请刷新或重试" : "请选择赞赏服务";
        switch (order.state) {
            case CREATED: return "待赞赏（沙箱）";
            case REVIEW: return "等待人工确认";
            case APPLYING: return "正在开通，请稍候";
            case APPROVED: return "沙箱已确认，请测试连接";
            case CANCELLED: return "订单已取消";
            case REJECTED: return "订单未通过";
            default: return "订单响应无法确认";
        }
    }
    private void resetPollWindow(long now) {
        pollNeedsWindow = false;
        pollRequests = 0;
        pollDeadline = safeAdd(now, POLL_BUDGET_MILLIS);
        nextPollAt = safeAdd(now, BACKOFF[0]);
    }
    private static boolean hasNull(List<?> values) {
        for (Object value : values) if (value == null) return true;
        return false;
    }
    private static long safeAdd(long a, long b) { return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b; }
    private static String nonEmpty(String value, String name, int max) {
        if (value == null || value.isEmpty() || value.length() > max || value.indexOf('\u0000') >= 0)
            throw new IllegalArgumentException("Invalid " + name);
        return value;
    }
}
