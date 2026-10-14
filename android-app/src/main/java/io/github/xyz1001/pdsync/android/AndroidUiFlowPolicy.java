package io.github.xyz1001.pdsync.android;

import java.util.Objects;

/** Pure presentation guards shared by the Activity and host regression checks. */
final class AndroidUiFlowPolicy {
    private AndroidUiFlowPolicy() {}

    /** Editing and then reverting must not resurrect a previous successful DAV test. */
    static String invalidateProofOnEdit(String proof, String beforeFingerprint, String afterFingerprint) {
        return Objects.equals(beforeFingerprint, afterFingerprint) ? proof : null;
    }

    static boolean canContinue(String proof, String currentFingerprint, boolean busy) {
        return !busy && proof != null && proof.equals(currentFingerprint);
    }

    enum IdenticalCompletion { NONE, CONFIRM, ADOPT }

    /** Hash identity comes only from the core inspection, never labels or file counts.
     * A missing/unrelated baseline needs the core's explicit association capability. */
    static IdenticalCompletion identicalCompletion(String kind, boolean cloudVerified,
            boolean needsRestoreRecovery, boolean retryablePending,
            boolean canCompleteWithoutTransfer, boolean canAdoptRemote) {
        if (!"IDENTICAL".equals(kind) || !cloudVerified || needsRestoreRecovery || retryablePending)
            return IdenticalCompletion.NONE;
        if (canCompleteWithoutTransfer) return IdenticalCompletion.CONFIRM;
        return canAdoptRemote ? IdenticalCompletion.ADOPT : IdenticalCompletion.NONE;
    }

    /** Missing, blocked, stale, or interrupted observations are never presented as empty success. */
    static boolean showEmptyCompletion(String kind, boolean cloudVerified,
            boolean needsRestoreRecovery, boolean retryablePending) {
        return "BOTH_EMPTY".equals(kind) && cloudVerified && !needsRestoreRecovery && !retryablePending;
    }

    static boolean canFinishEmpty(String kind, boolean cloudVerified,
            boolean needsRestoreRecovery, boolean retryablePending,
            boolean pageActive, boolean busy, boolean configured) {
        return showEmptyCompletion(kind, cloudVerified, needsRestoreRecovery, retryablePending)
                && pageActive && !busy && configured;
    }
}
