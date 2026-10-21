package pdsync

/** A fresh verified comparison, never setup completion or equal bytes, permits automatic writes. */
object AutomaticUploadEligibility {
 @JvmStatic fun allows(c: OnboardingComparison): Boolean {
  if (c.verification != RemoteVerification.OBJECTS_VERIFIED) return false
  if (c.observedTips.size > 1) return false
  if (c.retryablePending) {
   val pending = c.pendingCommit ?: return false
   val graph=c.graph ?: return false
   val parents=pending.parents.flatMap { parent ->
    val ids=graph.resolveParentIds(parent)
    if(ids.isEmpty())return false
    ids.map {id->contentSha256(graph.commits[id]?.snapshot?.files ?: return false)}
   }.toSet()
   val observed=c.observedTips.map {id->contentSha256(graph.commits[id]?.snapshot?.files ?: return false)}.toSet()
   return observed == parents || c.observedTips == setOf(pending.id)
  }
  if (c.kind == OnboardingKind.BLOCKED || c.localFiles.isEmpty()) return false
  return c.kind == OnboardingKind.LOCAL_ONLY ||
   c.kind == OnboardingKind.DIFFERENT && c.baselineLineage == OnboardingLineage.SAME_TIP
 }
}
