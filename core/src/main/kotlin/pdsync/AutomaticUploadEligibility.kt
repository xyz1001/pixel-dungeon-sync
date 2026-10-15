package pdsync

/** A fresh verified comparison, never setup completion or equal bytes, permits automatic writes. */
object AutomaticUploadEligibility {
 @JvmStatic fun allows(c: OnboardingComparison): Boolean {
  if (c.observedTips.size > 1) return false
  if (c.retryablePending) {
   val pending = c.pendingCommit ?: return false
   return c.observedTips == pending.parents.toSet() || c.observedTips == setOf(pending.id)
  }
  if (c.kind == OnboardingKind.BLOCKED || c.localFiles.isEmpty()) return false
  return c.kind == OnboardingKind.LOCAL_ONLY ||
   c.kind == OnboardingKind.DIFFERENT && c.baselineLineage == OnboardingLineage.SAME_TIP
 }
}
