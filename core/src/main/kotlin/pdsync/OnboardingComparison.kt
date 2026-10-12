package pdsync

import java.util.Collections

/** Content classification only. A blocked observation must never be interpreted as empty. */
enum class OnboardingKind { BOTH_EMPTY, LOCAL_ONLY, REMOTE_ONLY, IDENTICAL, DIFFERENT, BRANCHED, BLOCKED }
enum class OnboardingLineage { NONE, SAME_TIP, ANCESTOR_OF_TIP, BRANCHED, BLOCKED }

/**
 * An immutable observation, not permission to use stale bytes. Actions validate this session-bound
 * proof again under the same physical-root lock. No credentials or service addresses are retained.
 */
class OnboardingComparison internal constructor(
 val kind: OnboardingKind,
 localFiles: Map<String, String>,
 val baselineId: String?,
 val baselineLineage: OnboardingLineage,
 remoteEntries: List<AppendRemoteEntry>,
 observedTips: Set<String>,
 val blocker: String? = null,
 val blockReason: SyncBlockReason? = null,
 val canAdoptRemote: Boolean = false,
 val retryablePending: Boolean = false,
 val pendingCommitId: String? = null,
 internal val owner: Any,
 fingerprint: SaveFingerprint? = null,
 graph: CommitGraph? = null,
 pendingCommit: AppendCommit? = null
) {
 val localFiles: Map<String, String> = immutableMap(localFiles)
 /** Actual file mtime from this guarded observation; not a capture/upload/server receipt time. */
 val localLatestModifiedAt: String? = fingerprint?.metadata?.filter { (path, metadata) -> path in localFiles && !metadata.directory }
  ?.values?.maxOfOrNull { it.modified.toInstant() }?.toString()
 val remoteEntries: List<AppendRemoteEntry> = immutableList(remoteEntries.map { entry ->
  AppendRemoteEntry(immutableCommit(entry.commit), entry.entry.copy(manifest = immutableManifest(entry.entry.manifest)))
 })
 val observedTips: Set<String> = Collections.unmodifiableSet(LinkedHashSet(observedTips))
 internal val fingerprint: SaveFingerprint? = fingerprint?.let { SaveFingerprint(immutableMap(it.files), immutableMap(it.metadata)) }
 internal val graph: CommitGraph? = graph?.let { CommitGraph(immutableMap(it.commits.mapValues { (_, value) -> immutableCommit(value) }), immutableMap(it.pending)) }
 internal val pendingCommit: AppendCommit? = pendingCommit?.let(::immutableCommit)
}

sealed interface OnboardingRestoreResult {
 data class Restored(val result: RestoreResult): OnboardingRestoreResult
 data class Published(val result: AppendPushResult): OnboardingRestoreResult
}

private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(values))
private fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
private fun immutableManifest(manifest: Manifest): Manifest = manifest.copy(
 files = immutableMap(manifest.files),
 saveVersions = manifest.saveVersions?.let { it.copy(runs = immutableMap(it.runs), inactiveSlots = immutableList(it.inactiveSlots)) },
 metadata = manifest.metadata?.let { it.copy(fileModifiedAt = immutableMap(it.fileModifiedAt)) }
)
private fun immutableCommit(commit: AppendCommit): AppendCommit = commit.copy(
 parents = immutableList(commit.parents), snapshot = immutableManifest(commit.snapshot)
)
