package pdsync

/** Accept only a verified linear series of semantic no-op snapshots/restores above a real baseline. */
internal fun unchangedDescendantTip(graph: CommitGraph, baselineId: String?, hasPending: Boolean): AppendCommit? {
 if (graph.verification != RemoteVerification.OBJECTS_VERIFIED) return null
 if (hasPending || baselineId == null || graph.pending.isNotEmpty() || graph.commits.size > MAX_COMMITS) return null
 if (graph.commits.any { (id, commit) -> id != commit.id || commit.parents.any { it !in graph.commits } }) return null
 val base = graph.commits[baselineId] ?: return null
 val tip = graph.tips.singleOrNull()?.let(graph.commits::get) ?: return null
 if (tip.id == base.id || base.snapshot.files.isEmpty()) return null
 val seen = mutableSetOf<String>()
 var current = tip
 while (current.id != base.id) {
  if (!seen.add(current.id) || current.parents.size != 1 || current.kind !in setOf("snapshot", "restore") ||
   !sameBaselineSemantics(base.snapshot, current.snapshot)) return null
  current = graph.commits[current.parents.single()] ?: return null
 }
 return tip
}
internal fun unchangedDescendantTip(comparison: OnboardingComparison): AppendCommit? {
 if (comparison.verification != RemoteVerification.OBJECTS_VERIFIED) return null
 if (comparison.kind !in setOf(OnboardingKind.IDENTICAL, OnboardingKind.DIFFERENT)) return null
 val graph = comparison.graph ?: return null
 return unchangedDescendantTip(graph, comparison.baselineId, comparison.retryablePending)
}
private fun sameBaselineSemantics(base: Manifest, candidate: Manifest): Boolean =
 base.schema == candidate.schema && base.game == candidate.game && base.profile == candidate.profile &&
 base.files == candidate.files && base.directoryMetadata == candidate.directoryMetadata &&
 base.gamePackage == candidate.gamePackage && base.gameSource == candidate.gameSource &&
 base.version == candidate.version && base.saveVersions == candidate.saveVersions
