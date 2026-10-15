package io.github.xyz1001.pdsync.android;

import java.util.Objects;

/**
 * Memory-only ownership of an explicit comparison choice and its confirmation.
 * A ticket proves UI intent, not permission to write. The caller must still revalidate the
 * captured core inspection immediately before performing an operation.
 */
final class ComparisonProofState {
    static final class Context {
        final Object facade, connection, inspection;
        final String backendKey;

        Context(Object facade, Object connection, Object inspection, String backendKey) {
            this.facade = Objects.requireNonNull(facade);
            this.connection = Objects.requireNonNull(connection);
            this.inspection = Objects.requireNonNull(inspection);
            this.backendKey = Objects.requireNonNull(backendKey);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Context)) return false;
            Context value = (Context) other;
            return facade == value.facade && connection == value.connection
                    && inspection == value.inspection && backendKey.equals(value.backendKey);
        }

        @Override public int hashCode() {
            int result = System.identityHashCode(facade);
            result = 31 * result + System.identityHashCode(connection);
            result = 31 * result + System.identityHashCode(inspection);
            return 31 * result + backendKey.hashCode();
        }
    }

    enum Progress { LOCAL, CLOUD }
    enum Stage { CONFLICT, MERGE_REVIEW }

    static final class Ticket {
        final long token;
        final Context context;
        final String snapshotId;
        final Stage stage;

        private Ticket(long token, Context context, String snapshotId, Stage stage) {
            this.token = token;
            this.context = context;
            this.snapshotId = snapshotId;
            this.stage = stage;
        }
    }

    private long epoch;
    private Context context;
    private Progress progress;
    private String selectedId;
    private Ticket dialog, claimed;
    private Ticket failureOwner;
    private Context failureContext;

    /** Equal observations preserve intent through rerender; any new observation revokes it. */
    synchronized boolean bind(Context current) {
        if (Objects.equals(context, current)) return false;
        reset();
        context = current;
        return true;
    }

    /** Tokens are never recycled when leaving, pausing, or starting a new observation. */
    synchronized void reset() {
        invalidateTickets();
        context = null;
        progress = null;
        selectedId = null;
    }

    synchronized Progress progress() { return progress; }
    synchronized String selectedId() { return selectedId; }
    synchronized Ticket dialog() { return dialog; }

    /** A single cloud head is selected only by this explicit user choice. */
    synchronized void selectProgress(Progress choice, String singleRemoteId) {
        Objects.requireNonNull(choice);
        invalidateTickets();
        if (context == null) return;
        progress = choice;
        selectedId = choice == Progress.CLOUD ? singleRemoteId : null;
    }

    synchronized Ticket openConflict() {
        if (context == null) return null;
        invalidateTickets();
        dialog = new Ticket(epoch, context, selectedId, Stage.CONFLICT);
        return dialog;
    }

    /** Picking another head revokes every callback bound to the previous selection. */
    synchronized Ticket selectRemote(String snapshotId) {
        if (dialog == null || dialog.stage != Stage.CONFLICT) return null;
        invalidateTickets();
        selectedId = snapshotId;
        dialog = new Ticket(epoch, context, selectedId, Stage.CONFLICT);
        return dialog;
    }

    synchronized boolean matches(Context current) {
        return context != null && context.equals(current);
    }

    synchronized boolean current(long token, Context current, boolean foreground, boolean busy,
            Stage stage) {
        return foreground && !busy && matches(current) && dialog != null && claimed == null
                && token == epoch && token == dialog.token && dialog.stage == stage
                && dialog.context.equals(current);
    }

    /** A delayed preview cannot replace a newer dialog, choice, or observation. */
    synchronized Ticket review(Ticket original, Context current, boolean foreground, boolean busy,
            boolean allowed) {
        if (original == null || original != dialog
                || !current(original.token, current, foreground, busy, Stage.CONFLICT)) return null;
        invalidateTickets();
        dialog = new Ticket(epoch, context, selectedId,
                allowed ? Stage.MERGE_REVIEW : Stage.CONFLICT);
        return allowed ? dialog : null;
    }

    /** Dismissal removes the modal proof while retaining the user's explicit choice. */
    synchronized boolean cancel(long token, Context current, boolean foreground, boolean busy) {
        if (dialog == null || !current(token, current, foreground, busy, dialog.stage)) return false;
        invalidateTickets();
        return true;
    }

    /** Exactly one callback may transfer a visible dialog ticket to a worker. */
    synchronized Ticket claim(long token, Context current, boolean foreground, boolean busy,
            Stage stage) {
        if (!current(token, current, foreground, busy, stage)) return null;
        claimed = dialog;
        dialog = null;
        return claimed;
    }

    /** Captures an explicit nonmodal Finish without manufacturing a default progress choice. */
    synchronized Ticket capture(Context current, boolean foreground, boolean busy) {
        if (!foreground || busy || !matches(current) || dialog != null || claimed != null) return null;
        invalidateTickets();
        claimed = new Ticket(epoch, context, selectedId, Stage.CONFLICT);
        return claimed;
    }

    /** The claimed worker owns hard busy now, so busy is deliberately not a validation input. */
    synchronized boolean claimedCurrent(Ticket candidate, Context current, boolean foreground) {
        return foreground && candidate != null && candidate == claimed && candidate.token == epoch
                && matches(current) && candidate.context.equals(current);
    }

    /** A re-read discards action permission but retains ownership of this work's later failure. */
    synchronized boolean beginReinspection(Ticket candidate, Context current, boolean foreground) {
        if (!claimedCurrent(candidate, current, foreground)) return false;
        reset();
        failureOwner = candidate;
        return true;
    }

    /** Binds only failure ownership to the new observation, never the old user's action choice. */
    synchronized boolean finishReinspection(Ticket candidate, Context current, boolean foreground) {
        if (!foreground || candidate == null || candidate != failureOwner || failureContext != null
                || context != null || current == null) return false;
        Context original = candidate.context;
        if (original.facade != current.facade || original.connection != current.connection
                || !original.backendKey.equals(current.backendKey)
                || original.inspection == current.inspection) return false;
        context = current;
        failureContext = current;
        return true;
    }

    /** Routine completion releases only its own claim; invalid observations use invalidateFailure. */
    synchronized boolean releaseClaim(Ticket candidate) {
        if (candidate == null || !((candidate == claimed && candidate.token == epoch)
                || candidate == failureOwner)) return false;
        invalidateTickets();
        return true;
    }

    /** A current failed operation revokes its observation; stale failures cannot erase newer work. */
    synchronized boolean invalidateFailure(Ticket candidate, Context current, boolean pageActive) {
        boolean ownsReinspection = pageActive && candidate != null && candidate == failureOwner
                && failureContext != null && failureContext.equals(current) && matches(current);
        if (!claimedCurrent(candidate, current, pageActive) && !ownsReinspection) return false;
        reset();
        return true;
    }

    /** A failed re-read with no inspection may report failure only for its still-pending target. */
    synchronized boolean invalidateUninspectedFailure(Ticket candidate, Object facade,
            Object connection, String backendKey, boolean pageActive) {
        if (!pageActive || candidate == null || candidate != failureOwner
                || failureContext != null || context != null || backendKey == null) return false;
        Context original = candidate.context;
        if (original.facade != facade || original.connection != connection
                || !original.backendKey.equals(backendKey)) return false;
        reset();
        return true;
    }

    private void invalidateTickets() {
        epoch++;
        dialog = null;
        claimed = null;
        failureOwner = null;
        failureContext = null;
    }
}
