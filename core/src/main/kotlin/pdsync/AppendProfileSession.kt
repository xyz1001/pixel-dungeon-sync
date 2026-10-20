package pdsync

import pdsync.i18n.CoreSessionMessages

import java.nio.file.Path
import kotlin.io.path.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/** Every branch is an explicitly identified snapshot; no time-based winner exists. */
data class AppendBranch(val commit: AppendCommit, val snapshot: Snapshot)
data class AppendConflict(
 val observedTips: Set<String>,
 val branches: List<AppendBranch>,
 val observedLocalFiles: Map<String, String>,
 val publishedLocalCommit: String? = null
)
data class AppendRemoteEntry(val commit: AppendCommit, val entry: HistoryEntry)
sealed interface AppendPushResult {
 data class Success(val commit: AppendCommit, val localDirty: Boolean, val remoteAhead: Boolean = false): AppendPushResult
 data class Diverged(val conflict: AppendConflict): AppendPushResult
}
sealed interface AutomaticPushResult {
 data class Published(val result: AppendPushResult): AutomaticPushResult
 /** No upload plan, object, commit or sync baseline was created by this skipped attempt. */
 data class Skipped(val comparison: OnboardingComparison): AutomaticPushResult
}

/** Reconstructed code91 HOME same-content result; no implicit baseline update. */
sealed interface HomePushResult {
 data class Unchanged(val history:SyncHistory):HomePushResult
 data class Published(val result:AppendPushResult):HomePushResult
}

/**
 * Desktop-only v2 facade. ProfileSession supplies filesystem safeguards and the same root OS lock,
 * but none of its Store/CAS methods are called. Old history remains readable; old retry work is
 * preserved until explicitly archived, never interpreted as an append-only baseline.
 */
class AppendProfileSession(
 config: AppConfig, stateRoot: Path, adapter: GameAdapter = Shattered,
 versions: ProfileVersions = ShatteredProfileVersions, private val captureQuietMillis: Long = 2000,
 deviceLabel: (() -> String?)? = null
) {
 private val local = ProfileSession(config, stateRoot, adapter, versions, captureQuietMillis, deviceLabel)
 val config get() = local.config
 val root get() = local.root
 val adapter get() = local.adapter
 val directory get() = local.directory
 private val repository = AppendRepository(directory.resolve("append-v2"), adapter, config.profile)
 private val applyMarker = directory.resolve("append-v2/apply-required.txt")
 private val comparisonOwner = Any()
 private fun <T> readLocked(store:AppendStore, block:()->T):T = local.locked { store.withReadScope(directory.resolve("read-cache"),block) }

 fun history() = local.history()
 private val aliasesPath get()=directory.resolve("display-names.json")
 private fun aliases(): Map<String,String> {
  if(!SafePaths.checked(aliasesPath).exists())return emptyMap()
  val text=SafePaths.boundedRead(aliasesPath,MAX_MANIFEST_BYTES).decodeToString();requireUniqueJsonKeys(text)
  return json.decodeFromString<Map<String,String>>(text).also { values -> require(values.size<=MAX_COMMITS);values.forEach { (id,name)->require(validCommitId(id)&&name==sanitizeSnapshotName(name)) } }
 }
 fun renameHistory(snapshotId:String,name:String) = local.locked {
  require(repository.cachedGraph().commits.values.any { it.snapshot.id==snapshotId }) { CoreSessionMessages.HISTORY_ENTRY_NOT_FOUND.text() }
  SafePaths.atomicWrite(aliasesPath,json.encodeToString(aliases()+(snapshotId to sanitizeSnapshotName(name))).encodeToByteArray())
 }
 fun renameHistory(snapshotId:String,name:String,observedHistory:SyncHistory) = local.locked {
  require(observedHistory.owner === comparisonOwner) { CoreSessionMessages.THIS_COMPARISON_BELONGS_TO_A_DIFFERENT_PROFILE_COMPARE_AGAIN.text() }
  require(observedHistory.graph.commits.values.any {it.snapshot.id==snapshotId}) { CoreSessionMessages.HISTORY_ENTRY_NOT_FOUND.text() }
  SafePaths.atomicWrite(aliasesPath,json.encodeToString(aliases()+(snapshotId to sanitizeSnapshotName(name))).encodeToByteArray())
 }
 fun syncHistory(): SyncHistory = local.locked { historyView(repository.cachedGraph()) }
 fun syncHistory(observed:SyncHistory):SyncHistory = local.locked {
  require(observed.owner === comparisonOwner) { CoreSessionMessages.THIS_COMPARISON_BELONGS_TO_A_DIFFERENT_PROFILE_COMPARE_AGAIN.text() }
  historyView(observed.graph)
 }
 fun syncHistory(comparison:OnboardingComparison):SyncHistory = comparisonHistory(comparison)
 internal fun syncHistory(comparison:OnboardingComparison,summaryFiles:Map<String,String>,summary:SaveSummary?):SyncHistory = comparisonHistory(comparison,summaryFiles to summary)
 private fun comparisonHistory(comparison:OnboardingComparison,summary:Pair<Map<String,String>,SaveSummary?>?=null):SyncHistory = local.locked {
  require(comparison.owner === comparisonOwner) { CoreSessionMessages.THIS_COMPARISON_BELONGS_TO_A_DIFFERENT_PROFILE_COMPARE_AGAIN.text() }
  require(comparison.kind != OnboardingKind.BLOCKED) { CoreSessionMessages.COMPLETE_A_SAFE_COMPARISON_OR_RESOLVE_THE_BLOCKED_OPERATION_FIRST.text() }
  require(repository.baseline()==comparison.baselineId && currentFiles()==comparison.localFiles) { CoreSessionMessages.LOCAL_SAVES_CHANGED_AFTER_COMPARISON_COMPARE_AGAIN.text() }
  historyView(requireNotNull(comparison.graph),summary)
 }
 fun readHomeHistory(store:AppendStore):SyncHistory = readLocked(store) { historyView(discoverMetadata(store).displayGraph) }
 private fun historyView(graph:CommitGraph,summary:Pair<Map<String,String>,SaveSummary?>?=null):SyncHistory {
  val files=currentFiles();val baseline=repository.baseline()
  require(summary==null || summary.first==files) { CoreSessionMessages.LOCAL_SAVES_CHANGED_AFTER_COMPARISON_COMPARE_AGAIN.text() }
  val transfer=directory.resolve("baseline-transfer.txt")
  val direction=if(SafePaths.checked(transfer).exists())SafePaths.boundedRead(transfer,128).decodeToString().split(' ').takeIf { it.size==2&&it[0]==baseline&&it[1] in setOf("upload","restore") }?.get(1) else null
  val verifiedIds=if(graph.verification==RemoteVerification.OBJECTS_VERIFIED)graph.commits.keys.toSet() else repository.cachedGraph().commits.filter { (id,value)-> graph.commits[id]==value }.keys.toSet()
  return SyncHistory(graph,baseline,files,(local.directoryMetadata.read() as? SidecarRead.Valid)?.value?.displayName.orEmpty(),if(summary==null)runCatching { SaveSummary.read(root) }.getOrNull() else summary.second,aliases(),direction,verifiedIds,comparisonOwner)
 }
 /** User-selected historical cloud content is downloaded only on an explicit restore action. */
 fun restoreCloudHistory(snapshotId:String,store:AppendStore,allowUniqueReplacement:Boolean,expectedBefore:Map<String,String>?=null,stopped:()->Boolean):RestoreResult = readLocked(store) {
  local.guard(stopped);noLegacyPending();appliedOnly();require(repository.pending()==null){CoreSessionMessages.RESOLVE_THE_PENDING_UPLOAD_FIRST.text()}
  val graph=discover(store);val chosen=requireNotNull(graph.snapshotCommit(snapshotId)) { CoreSessionMessages.THE_SELECTED_CLOUD_SNAPSHOT_NO_LONGER_EXISTS_RELOAD_HISTORY.text() }
  val before=currentFiles()
  require(expectedBefore==null || before==expectedBefore) { CoreSessionMessages.LOCAL_SAVES_CHANGED_AFTER_CONFIRMATION_COMPARE_AGAIN.text() }
  require(allowUniqueReplacement || before.isEmpty() || graph.commits.values.any { it.snapshot.files==before }) { CoreSessionMessages.LOCAL_SAVES_CONTAIN_UNIQUE_PROGRESS_EXPLICITLY_CHOOSE_OVERWRITE_OR_MERGE.text() }
  val snapshot=repository.download(store,chosen);local.remember(snapshot,CoreSessionMessages.SELECTED_CLOUD_HISTORY.text(),repository.observedAt(chosen.id));local.checkRestore(snapshotId)
  var restored:RestoreResult?=null
  prepareAndApply(snapshot,listOfNotNull(repository.baseline()),"restore",CoreSessionMessages.RESTORE_CLOUD_HISTORY_PENDING_UPLOAD.text()) { plan ->
   restored=local.restore(this.snapshot(plan),stopped,before)
  }
  requireNotNull(restored)
 }
 /** Explicit download-only restore. It never publishes, resolves branches or creates an upload
  * plan. An observed tip can become the exact verified baseline; older/local history remains new
  * local work relative to the existing baseline, so a later upload still detects remote siblings. */
 fun restoreSelectedReadOnly(comparison:OnboardingComparison,snapshotId:String,store:AppendStore,allowUniqueReplacement:Boolean,stopped:()->Boolean):RestoreResult = readLocked(store) {
  var graph=revalidateComparison(comparison,store,stopped)
  val selected=graph.snapshotCommit(snapshotId)
  val chosen=if(selected!=null)repository.download(store,selected) else local.loadSnapshot(snapshotId)
  Snapshots.verify(chosen,adapter);local.checkRestore(chosen)
  require(allowUniqueReplacement || comparison.localFiles.isEmpty() || graph.commits.values.any {it.snapshot.files==comparison.localFiles}) {
   CoreSessionMessages.LOCAL_SAVES_CONTAIN_UNIQUE_PROGRESS_CONFIRM_OVERWRITE_EXPLICITLY_THE_ORIGINAL.text()
  }
  if(selected!=null)local.remember(chosen,CoreSessionMessages.READ_ONLY_DOWNLOAD_SELECTION_VERIFIED.text(),repository.observedAt(selected.id))
  // A legacy snapshot without portable identity must not erase a still-proven local run identity.
  // Only exact complete-run bytes, checked against both before and after, can retain that proof.
  val prior=(local.directoryMetadata.read() as? SidecarRead.Valid)?.value
  val beforeRuns=runContentHashes(comparison.localFiles)
  val afterRuns=runContentHashes(chosen.manifest.files)
  val source=chosen.manifest.directoryMetadata
  val used=source?.runs?.values?.mapNotNull {it.runUUID}?.toMutableSet() ?: mutableSetOf<String>()
  val runs=afterRuns.mapValues { (slot,digest) ->
   val remote=source?.runs?.get(slot)
   val known=prior?.runs?.get(slot)?.takeIf {it.runUUID!=null && it.contentSha256==digest && beforeRuns[slot]==digest && it.runUUID !in used}
   if(remote?.runUUID!=null || known==null)remote ?: RunIdentity(null,digest)
   else known.also {used.add(requireNotNull(it.runUUID))}
  }
  val metadata=DirectorySnapshotMetadata(displayName=source?.displayName ?: "",contentSha256=contentSha256(chosen.manifest.files),runs=runs).validated(chosen.manifest.files)
  val staged=chosen.copy(manifest=chosen.manifest.copy(directoryMetadata=metadata))
  graph=revalidateComparison(comparison,store,stopped)
  SafePaths.atomicWrite(applyMarker,"read-only-restore".encodeToByteArray())
  // Any failed/uncertain local apply retains this real recovery gate and original backup. There
  // is deliberately no pending upload to archive, retry or publish after an expired-account read.
  val restored=local.restore(staged,stopped,comparison.localFiles)
  if(selected!=null && selected.id in graph.tips) {
   repository.setBaseline(selected.id,graph)
   markDirectorySynced(selected)
  }
  SafePaths.checked(applyMarker).deleteExisting()
  restored
 }
 fun deviceIdentity() = local.deviceIdentity()
 fun directoryStatus(): SidecarRead = local.locked { local.directoryMetadata.read() }
 fun inspectDirectory(stopped: () -> Boolean): DirectorySnapshotMetadata = local.locked {
  requireNotNull(local.capture(stopped,null).manifest.directoryMetadata)
 }
 fun renameCurrent(name: String, stopped: () -> Boolean): DirectorySnapshotMetadata = local.locked {
  local.guard(stopped)
  val captured=local.capture(stopped,null)
  local.directoryMetadata.rename(name,captured.manifest.files)
 }
 fun confirmRunIdentity(slot: String, expectedFiles: Map<String,String>, sameRun: Boolean, stopped: () -> Boolean): DirectorySnapshotMetadata = local.locked {
  local.guard(stopped)
  val captured=local.capture(stopped,null)
  require(captured.manifest.files==expectedFiles) { CoreSessionMessages.CONTENT_CHANGED_DURING_CONFIRMATION_CHECK_AGAIN.text() }
  local.directoryMetadata.confirmRun(slot,expectedFiles,sameRun)
 }
 private fun markDirectorySynced(commit: AppendCommit,direction:String="restore") {
  local.directoryMetadata.markSynced(commit.snapshot.files,DirectorySyncBaseline(hash(directory.toString().encodeToByteArray()),commit.id,contentSha256(commit.snapshot.files)))
  SafePaths.atomicWrite(directory.resolve("baseline-transfer.txt"),(commit.id+" "+direction).encodeToByteArray())
 }
 fun observedAt(commitId: String) = repository.observedAt(commitId)
 fun baselineFiles(): Map<String, String>? = repository.baselineFiles()
 /** Read-only observation, deliberately not a mutation-lock contender. Any racing/incomplete
  * observation is discarded by the UI generation/busy check; mutation rechecks all gates under lock. */
 fun observedFiles(): Map<String, String> = currentFiles()
 fun retryablePending(): Boolean = !needsApply() && local.legacyPending() == null && repository.pending() != null
 /** The frozen plan is verified by pending(); a marker alone never proves an upload source. */
 fun retryablePendingSourceAvailable(): Boolean = local.locked {
  !needsApply() && local.legacyPending() == null && repository.pending()?.commit?.snapshot?.files?.isNotEmpty() == true
 }
 fun inspectConflict(store: AppendStore, stopped: () -> Boolean): AppendConflict? = local.locked {
  local.guard(stopped)
  val graph = discover(store)
  if (graph.tips.size > 1) preserveConflict(graph, store, stopped) else null
 }
 fun currentVersions() = local.currentVersions()
 fun targetInfo() = local.targetInfo()
 fun checkRestore(id: String) = local.checkRestore(id)
 fun loadSnapshot(id: String) = local.loadSnapshot(id)
 fun checkResolution(id: String) = local.locked {
  if (currentFiles() != local.loadSnapshot(id).manifest.files) local.checkRestore(id)
 }
 fun validateRoot() = local.validateRoot()
 fun createSnapshot(stopped: () -> Boolean): HistoryEntry = local.locked {
  local.remember(local.capture(stopped, null), CoreSessionMessages.LOCAL_SNAPSHOT.text())
 }
 fun hasPending(): Boolean = local.locked { repository.pending() != null || local.legacyPending() != null || needsApply() }
 fun baseline(): String? = local.locked { repository.baseline() }
 private fun needsApply() = SafePaths.checked(applyMarker).exists()
 private fun appliedOnly() {
  require(!needsApply()) { CoreSessionMessages.THE_PREVIOUS_RESTORE_IS_NOT_CONFIRMED_COMPLETE_ITS_SNAPSHOT_AND.text() }
 }
 private fun noLegacyPending() {
  require(local.legacyPending() == null) { CoreSessionMessages.A_LEGACY_PENDING_SNAPSHOT_IS_PRESERVED_EXPLICITLY_ARCHIVE_IT_BEFORE.text() }
 }
 private fun discover(store: AppendStore): CommitGraph = repository.discover(store).also {
  it.requireSettled()
  it.commits.values.snapshotRepresentatives(it.tips)
  repository.baseline()?.let { baseline -> require(baseline in it.commits) { CoreSessionMessages.THE_SYNC_BASELINE_IS_MISSING_OR_UNVERIFIABLE_NO_NEW_ROOT.text() } }
 }
 private fun discoverMetadata(store:AppendStore):CommitMetadataGraph = repository.discoverMetadata(store).also {
  it.requireSettled();it.commits.values.snapshotRepresentatives(it.tips)
  repository.baseline()?.let { baseline -> require(baseline in it.commits) { CoreSessionMessages.THE_SYNC_BASELINE_IS_MISSING_OR_UNVERIFIABLE_NO_NEW_ROOT.text() } }
 }
 private fun currentFiles():Map<String,String> = Snapshots.fileHashes(root,adapter)
 private fun snapshot(plan: UploadPlan) = Snapshot(plan.commit.snapshot, plan.zip)
 private fun preserve(plan: UploadPlan, reason: String) = local.remember(snapshot(plan), reason)
 private fun preserveConflict(graph: CommitGraph, store: AppendStore, stopped: () -> Boolean): AppendConflict {
  local.guard(stopped)
  val branches = graph.tips.sorted().map { id ->
   val commit = graph.commits.getValue(id)
   val snapshot = repository.download(store, commit)
   local.remember(snapshot, CoreSessionMessages.CONFLICT_OBSERVED_BRANCH.text(id.take(8)), repository.observedAt(id))
   AppendBranch(commit, snapshot)
  }
  // Record the real current files, not an older frozen upload. A subsequent choice must recheck.
  val current = currentFiles()
  local.guard(stopped)
  require(currentFiles() == current) { CoreSessionMessages.SAVES_CHANGED_WHILE_READING_CONFLICTS_EXIT_THE_GAME_AND_TRY.text() }
  return AppendConflict(graph.tips, branches, current, repository.baseline())
 }
 private fun publish(plan: UploadPlan, store: AppendStore, stopped: () -> Boolean): AppendPushResult {
  preserve(plan, CoreSessionMessages.APPEND_ONLY_SYNC.text(plan.commit.kind))
  repository.publish(store, plan)
  val graph = discover(store)
  require(plan.commit.id in graph.commits) { CoreSessionMessages.THE_PUBLISHED_COMMIT_IS_NOT_FULLY_VISIBLE_YET_THE_ORIGINAL.text() }
  local.remember(snapshot(plan), CoreSessionMessages.APPEND_ONLY_SYNC.text(plan.commit.kind), repository.observedAt(plan.commit.id))
  repository.setBaseline(plan.commit.id, graph)
  markDirectorySynced(plan.commit,"upload")
  repository.clearPending()
  return if (graph.tips.size > 1) AppendPushResult.Diverged(preserveConflict(graph, store, stopped))
   else AppendPushResult.Success(plan.commit, runCatching { currentFiles() != plan.commit.snapshot.files }.getOrDefault(true), graph.tips.singleOrNull() != plan.commit.id)
 }
 fun push(store: AppendStore, stopped: () -> Boolean): AppendPushResult = local.locked {
  local.guard(stopped); noLegacyPending(); appliedOnly()
  // Freeze stopped, stable local bytes and real parents BEFORE contacting the network. An offline
  // first attempt is durable, and restarting can retry these exact bytes without startup recapture.
  val plan = repository.pending() ?: run {
   val baseline = repository.baseline()
   val captured = local.capture(stopped, null)
   val previous = baseline?.let { repository.cachedGraph().commits[it] }
   // Identical content AND portable metadata is already protected by this verified baseline.
   // Still observe the graph so a remote advance/conflict is reported without creating a sibling.
   if (previous != null && previous.snapshot.files == captured.manifest.files &&
       previous.snapshot.directoryMetadata == captured.manifest.directoryMetadata) {
    val graph = discover(store)
    return@locked if (graph.tips.size > 1) AppendPushResult.Diverged(preserveConflict(graph, store, stopped))
     else AppendPushResult.Success(previous, currentFiles() != previous.snapshot.files, graph.tips.singleOrNull() != previous.id)
   }
   repository.prepare(captured, listOfNotNull(baseline), operation = local.operationMetadata())
  }
  publish(plan, store, stopped)
 }

 /** Verify every observed tip, presenting exact duplicate snapshots once. The graph retains all
  * commit IDs and tips; a listing is never a promise of a global latest save. */
 fun pushFromHome(observedHistory:SyncHistory?,store:AppendStore,stopped:()->Boolean):HomePushResult {
  val unchanged=local.locked {
   local.guard(stopped);noLegacyPending();appliedOnly()
   if(observedHistory==null || repository.pending()!=null)return@locked null
   require(observedHistory.owner === comparisonOwner) { CoreSessionMessages.THIS_COMPARISON_BELONGS_TO_A_DIFFERENT_PROFILE_COMPARE_AGAIN.text() }
   if(observedHistory.graph.verification!=RemoteVerification.METADATA_ONLY || observedHistory.graph.pending.isNotEmpty())return@locked null
   val candidate=observedHistory.graph.tips.singleOrNull()?.let(observedHistory.graph.commits::get) ?: return@locked null
   if(candidate.snapshot.files.isEmpty() || candidate.snapshot.files!=currentFiles())return@locked null
   val stability=SaveStabilityGuard(root,adapter,stopped,captureQuietMillis);val fingerprint=stability.awaitStable()
   if(candidate.snapshot.files!=fingerprint.files)return@locked null
   val fresh=discover(store);stability.requireUnchanged(fingerprint);noLegacyPending();appliedOnly()
   if(repository.pending()!=null)return@locked null
   val tip=fresh.tips.singleOrNull()?.let(fresh.commits::get) ?: return@locked null
   if(tip.snapshot.files!=fingerprint.files)return@locked null
   val history=historyView(fresh);stability.requireUnchanged(fingerprint);history
  }
  return unchanged?.let(HomePushResult::Unchanged) ?: HomePushResult.Published(push(store,stopped))
 }

 fun readRemote(store: AppendStore): List<AppendRemoteEntry> = local.locked {
  val graph = discover(store)
  graph.tips.sorted().map { id ->
   val commit = graph.commits.getValue(id)
   AppendRemoteEntry(commit, local.remember(repository.download(store, commit), CoreSessionMessages.OBSERVED_CLOUD_BRANCH.text(id.take(8)), repository.observedAt(id)))
  }.distinctBy { it.entry.manifest.id }
 }

 /** Ordinary fast-forward is allowed only for a clean baseline or a genuinely empty new target. */
 fun restoreRemote(snapshotId: String, store: AppendStore, stopped: () -> Boolean): RestoreResult = readLocked(store) {
  restoreRemoteLocked(snapshotId, store, stopped)
 }
 private fun restoreRemoteLocked(snapshotId: String, store: AppendStore, stopped: () -> Boolean): RestoreResult {
  local.guard(stopped); noLegacyPending(); appliedOnly()
  require(repository.pending() == null) { CoreSessionMessages.RETRY_OR_EXPLICITLY_ARCHIVE_THE_PENDING_UPLOAD_FIRST.text() }
  val graph = discover(store)
  require(graph.tips.size == 1) { CoreSessionMessages.THE_CLOUD_HAS_MULTIPLE_BRANCHES_A_NORMAL_RESTORE_IS_UNAVAILABLE.text() }
  val commit = graph.commits.getValue(graph.tips.single())
  require(commit.snapshot.id == snapshotId) { CoreSessionMessages.THE_OBSERVED_BRANCH_CHANGED_NOTHING_WAS_RESTORED_RELOAD_THE_CLOUD.text() }
  val before = currentFiles()
  val baseline = repository.baseline()
  if (baseline == null) require(before.isEmpty()) { CoreSessionMessages.LOCAL_SAVES_CONTAIN_UNSYNCED_PROGRESS_AND_CANNOT_BE_OVERWRITTEN_DIRECTLY.text() }
  else {
   require(before == graph.commits.getValue(baseline).snapshot.files) { CoreSessionMessages.LOCAL_PROGRESS_CHANGED_AND_CANNOT_BE_OVERWRITTEN_DIRECTLY_SYNC_TO.text() }
   require(graph.descendsFrom(commit.id, baseline)) { CoreSessionMessages.THE_SELECTED_BRANCH_DOES_NOT_DESCEND_FROM_THE_CURRENT_BASELINE.text() }
  }
  val chosen = repository.download(store, commit)
  local.remember(chosen, CoreSessionMessages.CLOUD_RESTORE_SNAPSHOT_VERIFIED.text(), repository.observedAt(commit.id))
  local.checkRestore(chosen.manifest.id)
  require(currentFiles() == before) { CoreSessionMessages.LOCAL_SAVES_CHANGED_DURING_VERIFICATION_NOTHING_WAS_RESTORED.text() }
  SafePaths.atomicWrite(applyMarker, "remote-restore".encodeToByteArray())
  // Keep the restart gate on any uncertain apply or baseline-write failure, even after rollback.
  val restored = local.restore(chosen, stopped,before)
  repository.setBaseline(commit.id, graph)
  markDirectorySynced(commit)
  SafePaths.checked(applyMarker).deleteExisting()
  return restored
 }

 /** A persisted restart gate precedes plan creation; this is not an fsync/power-loss transaction. */
 private fun prepareAndApply(source: Snapshot, parents: List<String>, kind: String, reason: String, apply: (UploadPlan) -> Unit): UploadPlan {
  appliedOnly()
  SafePaths.atomicWrite(applyMarker, kind.encodeToByteArray())
  var plan: UploadPlan? = null
  try {
   plan = repository.prepare(source, parents, kind, operation = local.operationMetadata())
   preserve(plan, reason)
   apply(plan)
   SafePaths.checked(applyMarker).deleteExisting()
   return plan
  } catch (failure: Exception) {
   // A failed apply may include failed rollback. Keep the gate until explicit archive/review.
   plan?.let { preserve(it, CoreSessionMessages.INCOMPLETE_LOCAL_RESTORE_ARCHIVED.text()) }
   repository.clearPending()
   throw failure
  }
 }

 /** A history restore is new work parented at the actual baseline, not a rewind to the old ID. */
 fun restoreHistory(id: String, stopped: () -> Boolean): RestoreResult = local.locked {
  local.guard(stopped); noLegacyPending(); appliedOnly()
  require(repository.pending() == null) { CoreSessionMessages.RETRY_OR_EXPLICITLY_ARCHIVE_THE_PENDING_UPLOAD_FIRST.text() }
  local.checkRestore(id)
  var restored: RestoreResult? = null
  prepareAndApply(local.loadSnapshot(id), listOfNotNull(repository.baseline()), "restore", CoreSessionMessages.HISTORY_RESTORE_PENDING_PUBLICATION.text()) { plan ->
   restored = local.restore(snapshot(plan), stopped)
  }
  requireNotNull(restored)
 }

 /** Explicit whole-snapshot choice; every observed tip is retained as a resolution parent. */
 fun resolve(conflict: AppendConflict, chosenSnapshotId: String, store: AppendStore, stopped: () -> Boolean): AppendPushResult = readLocked(store) {
  resolveLocked(conflict, chosenSnapshotId, store, stopped)
 }
 /** Preview and apply both repeat validation. Preview never changes game bytes or publishes data. */
 fun previewMerge(conflict: AppendConflict, store: AppendStore, stopped: () -> Boolean): RunMergeResult = local.locked {
  buildMerge(conflict,store,stopped)
 }
 /** The setup dialog previews only its exact session-bound comparison. The proof and merge
  * preflight are checked under one physical-root lock; no save bytes or remote data are written. */
 fun previewOnboardingMerge(comparison: OnboardingComparison, store: AppendStore, stopped: () -> Boolean): RunMergeResult = local.locked {
  revalidateComparison(comparison,store,stopped)
  buildMerge(AppendConflict(comparison.observedTips,emptyList(),comparison.localFiles),store,stopped)
 }
 /** A confirmed setup preview cannot silently switch to newly observed local files or tips. */
 fun mergeCurrent(comparison: OnboardingComparison, store: AppendStore, stopped: () -> Boolean): AppendPushResult = local.locked {
  revalidateComparison(comparison,store,stopped)
  mergeCurrentLocked(AppendConflict(comparison.observedTips,emptyList(),comparison.localFiles),store,stopped,enforceObservedTips=true)
 }
 /** Preflight before publishing a unique local branch; then retain both sources in the merge DAG. */
 fun mergeCurrent(store:AppendStore,stopped:()->Boolean):AppendPushResult = local.locked {
  val graph=discover(store);require(graph.tips.isNotEmpty()) { CoreSessionMessages.NO_CLOUD_BRANCHES_ARE_AVAILABLE_TO_MERGE.text() }
  val observed=AppendConflict(graph.tips,emptyList(),currentFiles())
  mergeCurrentLocked(observed,store,stopped)
 }
 private fun mergeCurrentLocked(observed:AppendConflict,store:AppendStore,stopped:()->Boolean,enforceObservedTips:Boolean=false):AppendPushResult {
  val preview=buildMerge(observed,store,stopped)
  local.checkRestore(preview.snapshot)
  val captured=local.capture(stopped,null)
  require(captured.manifest.files==observed.observedLocalFiles) { CoreSessionMessages.LOCAL_CONTENT_CHANGED_AFTER_THE_PREFLIGHT_CHECK_COMPARE_AGAIN.text() }
  val localPlan=repository.prepare(captured,listOfNotNull(repository.baseline()),operation=local.operationMetadata())
  val expectedTips=(observed.observedTips-localPlan.commit.parents.toSet())+localPlan.commit.id
  val uploaded=publish(localPlan,store,stopped)
  if(enforceObservedTips) {
   // Publishing our preserved local branch does not authorize merging a newly arrived device.
   // Keep the real branches and require a fresh preview before any local apply/resolution.
   val graph=discover(store)
   if(graph.tips!=expectedTips)return AppendPushResult.Diverged(preserveConflict(graph,store,stopped))
  }
  return when(uploaded) {
   is AppendPushResult.Success -> uploaded
   is AppendPushResult.Diverged -> mergeLocked(uploaded.conflict,store,stopped)
  }
 }
 private fun buildMerge(conflict: AppendConflict, store: AppendStore, stopped: () -> Boolean): RunMergeResult {
  local.guard(stopped);noLegacyPending();appliedOnly()
  require(repository.pending()==null) { CoreSessionMessages.RESOLVE_THE_PENDING_UPLOAD_FIRST.text() }
  val graph=discover(store)
  require(graph.tips==conflict.observedTips && graph.tips.isNotEmpty()) { CoreSessionMessages.CONFLICT_BRANCHES_CHANGED_CHECK_AGAIN.text() }
  require(currentFiles()==conflict.observedLocalFiles) { CoreSessionMessages.LOCAL_CONTENT_CHANGED_CHECK_AGAIN.text() }
  val baseline=repository.baseline() ?: throw RunMergeException(CoreSessionMessages.NO_COMMON_BASELINE_EXISTS_RUN_IDENTITIES_CANNOT_BE_INFERRED.text())
  val children=graph.tips+baseline
  val common=graph.commits.keys.filter { ancestor -> children.all { graph.descendsFrom(it,ancestor) } }
  val nearest=common.filter { candidate -> common.none { it!=candidate&&graph.descendsFrom(it,candidate) } }
  if(nearest.size!=1)throw RunMergeException(CoreSessionMessages.NO_UNIQUE_VERIFIABLE_COMMON_ANCESTOR_EXISTS.text())
  val base=repository.download(store,graph.commits.getValue(nearest.single()))
  val current=local.capture(stopped,null,persistDirectoryMetadata=false)
  require(current.manifest.files==conflict.observedLocalFiles) { CoreSessionMessages.CONTENT_CHANGED_DURING_CAPTURE_NOTHING_WAS_MERGED.text() }
  val branches=graph.tips.sorted().map { id ->
   val commit=graph.commits.getValue(id)
   val downloaded=repository.download(store,commit)
   // Current local work continues this baseline tip; it is not a parallel branch of that tip.
   if(id==baseline)current else downloaded
  }.toMutableList()
  if(branches.none { it===current })branches.add(0,current) else { branches.remove(current);branches.add(0,current) }
  val operation=local.operationMetadata()
  return RunMergePlanner.plan(base,branches,local.targetInfo() ?: throw RunMergeException(CoreSessionMessages.NO_VERIFIED_GAME_SELECTED.text()),SnapshotCaptureContext(operation.device,operation.preparedAt),adapter)
 }
 fun merge(conflict: AppendConflict, store: AppendStore, stopped: () -> Boolean): AppendPushResult = local.locked {
  mergeLocked(conflict,store,stopped)
 }
 private fun mergeLocked(conflict:AppendConflict,store:AppendStore,stopped:()->Boolean):AppendPushResult {
  require(conflict.observedTips.size>=2) { CoreSessionMessages.CONFLICT_BRANCHES_CHANGED_CHECK_AGAIN.text() }
  val result=buildMerge(conflict,store,stopped)
  // Capacity/identity/global/version checks have all completed before an apply marker or writes.
  local.remember(result.snapshot,CoreSessionMessages.PER_RUN_MERGE_PREFLIGHT_COMPLETE.text())
  local.checkRestore(result.snapshot.manifest.id)
  val latest=discover(store)
  if(latest.tips!=conflict.observedTips)return AppendPushResult.Diverged(preserveConflict(latest,store,stopped))
  require(currentFiles()==conflict.observedLocalFiles) { CoreSessionMessages.LOCAL_CONTENT_CHANGED_AFTER_PREFLIGHT_NOTHING_WAS_MERGED.text() }
  val plan=prepareAndApply(result.snapshot,latest.tips.sorted(),"resolution",CoreSessionMessages.PER_RUN_MERGE_SLOTS.text(result.slots)) { plan ->
   require(currentFiles()==conflict.observedLocalFiles) { CoreSessionMessages.LOCAL_CONTENT_CHANGED_BEFORE_MERGING_NOTHING_WAS_OVERWRITTEN.text() }
   local.restore(snapshot(plan),stopped,conflict.observedLocalFiles)
  }
  // A network failure after verified local apply leaves this exact plan pending for retry.
  // It is not advertised as an atomic transaction across the local disk and WebDAV.
  return publish(plan,store,stopped)
 }
 private fun resolveLocked(conflict: AppendConflict, chosenSnapshotId: String, store: AppendStore, stopped: () -> Boolean): AppendPushResult {
  local.guard(stopped); noLegacyPending(); appliedOnly()
  require(repository.pending() == null) { CoreSessionMessages.RETRY_OR_EXPLICITLY_ARCHIVE_THE_PENDING_UPLOAD_FIRST.text() }
  val graph = discover(store)
  if (graph.tips != conflict.observedTips) return AppendPushResult.Diverged(preserveConflict(graph, store, stopped))
  require(graph.tips.size >= 2) { CoreSessionMessages.CONFLICT_BRANCHES_CHANGED_RELOAD_THE_CLOUD.text() }
  require(currentFiles() == conflict.observedLocalFiles) { CoreSessionMessages.LOCAL_PROGRESS_CHANGED_DURING_SELECTION_NOTHING_WAS_OVERWRITTEN_SYNC_AGAIN.text() }
  val branch = requireNotNull(conflict.snapshotBranch(chosenSnapshotId)) { CoreSessionMessages.THE_SELECTED_SNAPSHOT_IS_NOT_IN_AN_OBSERVED_BRANCH.text() }
  require(branch.commit.id in graph.tips && graph.commits[branch.commit.id] == branch.commit) { CoreSessionMessages.THE_SELECTION_IS_NOT_IN_AN_OBSERVED_BRANCH.text() }
  // Re-download exact immutable bytes and preserve all branches before preparing a resolution.
  conflict.branches.forEach { local.remember(repository.download(store, it.commit), CoreSessionMessages.CONFLICT_ORIGINAL_BRANCH.text(), repository.observedAt(it.commit.id)) }
  val chosen = repository.download(store, branch.commit)
  if (currentFiles() != chosen.manifest.files) local.checkRestore(chosen.manifest.id)
  val plan = prepareAndApply(chosen, graph.tips.sorted(), "resolution", CoreSessionMessages.CONFLICT_RESOLUTION_EXPLICITLY_SELECTED.text(branch.commit.id.take(8))) { plan ->
   require(currentFiles() == conflict.observedLocalFiles) { CoreSessionMessages.LOCAL_PROGRESS_CHANGED_DURING_VERIFICATION_NOTHING_WAS_OVERWRITTEN.text() }
   if (currentFiles() != chosen.manifest.files) local.restore(snapshot(plan), stopped,conflict.observedLocalFiles)
  }
  // Apply precedes publication. Failed network work can only retry this exact chosen snapshot.
  // Changes after this point remain dirty relative to the published baseline.
  return publish(plan, store, stopped)
 }

 /** Read-only save comparison. Remote verification/history caches may be populated; saves and
  * baseline never change, and no remote object/commit is published by this operation. */
 fun compareOnboarding(store:AppendStore,stopped:()->Boolean):OnboardingComparison = compareObservation(store,stopped,false)
 fun compareHomeMetadata(store:AppendStore,stopped:()->Boolean):OnboardingComparison = compareObservation(store,stopped,true)
 private fun compareObservation(store:AppendStore,stopped:()->Boolean,metadataOnly:Boolean):OnboardingComparison = try {
  readLocked(store) { compareLocked(store,stopped,metadataOnly) }
 } catch(failure:Exception) { comparisonFailure(failure) }
 private fun compareLocked(store:AppendStore,stopped:()->Boolean,metadataOnly:Boolean):OnboardingComparison {
   local.guard(stopped); local.validateRoot()
   val stability = SaveStabilityGuard(root, adapter, stopped, captureQuietMillis)
   val fingerprint = if(metadataOnly) stability.fingerprint() else stability.awaitStable()
   val baseline = repository.baseline()
   if (needsApply()) return blockedComparison(SyncBlockReason.RESTORE_RECOVERY_REQUIRED, CoreSessionMessages.THE_PREVIOUS_RESTORE_IS_NOT_CONFIRMED_COMPLETE_EXPLICITLY_ARCHIVE_IT.text(), fingerprint, baseline)
   if (local.legacyPending() != null) return blockedComparison(SyncBlockReason.LEGACY_PENDING_UPLOAD, CoreSessionMessages.A_LEGACY_PENDING_SNAPSHOT_IS_PRESERVED_EXPLICITLY_ARCHIVE_IT_FIRST.text(), fingerprint, baseline)
   val pending = repository.pending()
   // A failed/missing page or object must not become an apparently empty cloud.
   val graph = if(metadataOnly) DiagnosticTiming.measure(DiagnosticPhase.REMOTE_METADATA) { discoverMetadata(store).displayGraph } else discover(store)
   val entries = if(metadataOnly) emptyList() else graph.tips.sorted().map { id ->
    val commit = graph.commits.getValue(id)
    AppendRemoteEntry(commit, local.remember(repository.download(store, commit), CoreSessionMessages.INITIAL_COMPARISON_CLOUD_BRANCH_VERIFIED.text(id.take(8)), repository.observedAt(id)))
   }.distinctBy { it.entry.manifest.id }
   stability.requireUnchanged(fingerprint)
   val lineage = when {
    baseline == null -> OnboardingLineage.NONE
    graph.tips.size > 1 -> OnboardingLineage.BRANCHED
    graph.tips.singleOrNull() == baseline -> OnboardingLineage.SAME_TIP
    graph.tips.singleOrNull()?.let { graph.descendsFrom(it, baseline) } == true -> OnboardingLineage.ANCESTOR_OF_TIP
    else -> OnboardingLineage.BLOCKED
   }
   val kind = when {
    pending != null -> OnboardingKind.BLOCKED
    graph.tips.size > 1 -> OnboardingKind.BRANCHED
    fingerprint.files.isEmpty() && graph.tips.isEmpty() -> OnboardingKind.BOTH_EMPTY
    graph.tips.isEmpty() -> OnboardingKind.LOCAL_ONLY
    fingerprint.files.isEmpty() -> OnboardingKind.REMOTE_ONLY
    fingerprint.files == graph.commits.getValue(graph.tips.single()).snapshot.files -> OnboardingKind.IDENTICAL
    else -> OnboardingKind.DIFFERENT
   }
   return OnboardingComparison(kind, fingerprint.files, baseline, lineage, entries, graph.tips,
    blocker = if (pending == null) null else CoreSessionMessages.A_FROZEN_UPLOAD_IS_PENDING_RETRYING_USES_ITS_ORIGINAL_SNAPSHOT.text(),
    blockReason = if (pending == null) null else SyncBlockReason.PENDING_UPLOAD,
    canAdoptRemote = !metadataOnly && kind == OnboardingKind.IDENTICAL && lineage in setOf(OnboardingLineage.NONE, OnboardingLineage.SAME_TIP, OnboardingLineage.ANCESTOR_OF_TIP),
    retryablePending = pending != null, pendingCommitId = pending?.commit?.id,
    owner = comparisonOwner, fingerprint = fingerprint, graph = graph, pendingCommit = pending?.commit)
   }
 private fun comparisonFailure(failure:Exception):OnboardingComparison {

  if (failure is io.github.xyz1001.pdsync.android.OfficialSyncAccess.AccessException) throw failure
  if (failure is InterruptedException) Thread.currentThread().interrupt()
  val reason = SyncBlockReason.fromFailure(failure)
  val message = when (reason) {
   SyncBlockReason.SAVE_NOT_STABLE -> CoreSessionMessages.THE_GAME_IS_RUNNING_ITS_STATE_IS_UNKNOWN_OR_SAVES.text()
   SyncBlockReason.LEGACY_REMOTE_PROTOCOL -> CoreSessionMessages.A_LEGACY_CLOUD_PROTOCOL_WAS_FOUND_COMPARISON_STOPPED_IT_WAS.text()
   SyncBlockReason.REMOTE_INCOMPLETE -> CoreSessionMessages.CLOUD_COMMITS_ARE_MISSING_INCOMPLETE_OR_UNVERIFIABLE_COMPARISON_STOPPED_TRY.text()
   SyncBlockReason.REMOTE_INTEGRITY_FAILED -> CoreSessionMessages.IMMUTABLE_CLOUD_HISTORY_FAILED_VERIFICATION_COMPARISON_STOPPED_NEITHER_SIDE_WAS.text()
   SyncBlockReason.TARGET_CHANGED -> CoreSessionMessages.THE_SELECTED_GAME_INSTALLED_VERSION_OR_SOURCE_BINDING_CHANGED_SELECT.text()
   SyncBlockReason.UNSUPPORTED_SAVE_FORMAT -> CoreSessionMessages.SAVES_ARE_UNREADABLE_OR_USE_AN_UNSUPPORTED_FORMAT_THEY_WERE.text()
   SyncBlockReason.INTERRUPTED -> CoreSessionMessages.COMPARISON_WAS_INTERRUPTED_UPLOAD_SUCCESS_IS_UNCONFIRMED_CHECK_AGAIN.text()
   else -> CoreSessionMessages.UNABLE_TO_SAFELY_COMPARE_LOCAL_SAVES_WITH_COMPLETE_CLOUD_HISTORY.text()
  }
  return blockedComparison(reason, message)
 }

 private fun blockedComparison(reason: SyncBlockReason, message: String, fingerprint: SaveFingerprint? = null, baseline: String? = null) =
  OnboardingComparison(OnboardingKind.BLOCKED, fingerprint?.files.orEmpty(), baseline, OnboardingLineage.BLOCKED,
   emptyList(), emptySet(), blocker = message, blockReason = reason, owner = comparisonOwner, verification = RemoteVerification.METADATA_ONLY)

 fun verifyComparison(comparison:OnboardingComparison,store:AppendStore,stopped:()->Boolean):OnboardingComparison = readLocked(store) {
  require(comparison.owner === comparisonOwner) { CoreSessionMessages.THIS_COMPARISON_BELONGS_TO_A_DIFFERENT_PROFILE_COMPARE_AGAIN.text() }
  if(comparison.verification==RemoteVerification.OBJECTS_VERIFIED)return@readLocked comparison
  require(comparison.kind!=OnboardingKind.BLOCKED || comparison.retryablePending) { CoreSessionMessages.COMPLETE_A_SAFE_COMPARISON_OR_RESOLVE_THE_BLOCKED_OPERATION_FIRST.text() }
  val before=requireNotNull(comparison.graph)
  val fresh=compareLocked(store,stopped,false);val graph=requireNotNull(fresh.graph);graph.requireObjectsVerified()
  require(graph.commits==before.commits && graph.pending==before.pending && fresh.observedTips==comparison.observedTips) { CoreSessionMessages.CLOUD_BRANCHES_CHANGED_AFTER_COMPARISON_THE_OLD_SELECTION_WAS_NOT.text() }
  require(fresh.localFiles==comparison.localFiles && fresh.fingerprint==comparison.fingerprint) { CoreSessionMessages.LOCAL_SAVES_CHANGED_AFTER_COMPARISON_COMPARE_AGAIN.text() }
  require(fresh.baselineId==comparison.baselineId) { CoreSessionMessages.THE_SYNC_BASELINE_CHANGED_AFTER_COMPARISON_COMPARE_AGAIN.text() }
  require(fresh.pendingCommit==comparison.pendingCommit) { CoreSessionMessages.THE_PENDING_UPLOAD_CHANGED_COMPARE_AGAIN.text() }
  fresh
 }

 /** Never trust UI-visible collections or an old comparison without repeating every safety gate. */
 private fun revalidateComparison(comparison: OnboardingComparison, store: AppendStore, stopped: () -> Boolean,
  allowPending: Boolean = false): CommitGraph {
  require(comparison.owner === comparisonOwner) { CoreSessionMessages.THIS_COMPARISON_BELONGS_TO_A_DIFFERENT_PROFILE_COMPARE_AGAIN.text() }
  require(comparison.verification == RemoteVerification.OBJECTS_VERIFIED) { CoreSessionMessages.COMPLETE_A_SAFE_COMPARISON_OR_RESOLVE_THE_BLOCKED_OPERATION_FIRST.text() }
  comparison.graph?.requireObjectsVerified()
  require(comparison.kind != OnboardingKind.BLOCKED || (allowPending && comparison.retryablePending)) { CoreSessionMessages.COMPLETE_A_SAFE_COMPARISON_OR_RESOLVE_THE_BLOCKED_OPERATION_FIRST.text() }
  local.guard(stopped); noLegacyPending(); appliedOnly(); local.validateRoot()
  val expected = requireNotNull(comparison.fingerprint) { CoreSessionMessages.COMPARISON_IS_INCOMPLETE_TRY_AGAIN.text() }
  val stability = SaveStabilityGuard(root, adapter, stopped, captureQuietMillis)
  require(stability.awaitStable() == expected) { CoreSessionMessages.LOCAL_SAVES_CHANGED_AFTER_COMPARISON_THE_OLD_SELECTION_WAS_NOT.text() }
  require(repository.baseline() == comparison.baselineId) { CoreSessionMessages.THE_SYNC_BASELINE_CHANGED_AFTER_COMPARISON_COMPARE_AGAIN.text() }
  val pending = repository.pending()
  require(pending?.commit == comparison.pendingCommit) { CoreSessionMessages.THE_PENDING_UPLOAD_CHANGED_COMPARE_AGAIN.text() }
  require(allowPending || pending == null) { CoreSessionMessages.RETRY_OR_EXPLICITLY_ARCHIVE_THE_PENDING_UPLOAD_FIRST.text() }
  val graph = discover(store)
  require(graph == comparison.graph) { CoreSessionMessages.CLOUD_BRANCHES_CHANGED_AFTER_COMPARISON_THE_OLD_SELECTION_WAS_NOT.text() }
  stability.requireUnchanged(expected)
  return graph
 }

 /** Finish a no-action first-run choice only while its exact empty/already-linked state remains
  * current. This does not create a baseline or enable automatic uploads itself. */
 fun confirmOnboardingReady(comparison: OnboardingComparison, store: AppendStore, stopped: () -> Boolean) = local.locked {
  revalidateComparison(comparison, store, stopped)
  require(comparison.kind == OnboardingKind.BOTH_EMPTY ||
   (comparison.kind == OnboardingKind.IDENTICAL && comparison.baselineLineage == OnboardingLineage.SAME_TIP)) {
   CoreSessionMessages.EXPLICITLY_CHOOSE_UPLOAD_RESTORE_OR_LINK_TO_CLOUD_FOR_THIS.text()
  }
 }

 /** Complete an explicit keep-local onboarding choice while ordinary local progress is still
  * based on the unique verified cloud tip. No baseline, sidecar, plan or remote write occurs. */
 fun confirmLocalProgressReady(comparison: OnboardingComparison, store: AppendStore, stopped: () -> Boolean) = local.locked {
  revalidateComparison(comparison, store, stopped)
  require(comparison.kind == OnboardingKind.DIFFERENT && comparison.baselineLineage == OnboardingLineage.SAME_TIP &&
   comparison.baselineId != null && comparison.observedTips.singleOrNull() == comparison.baselineId) {
   CoreSessionMessages.THIS_COMPARISON_DOES_NOT_REPRESENT_LOCAL_PROGRESS_ON_THE_SAME.text()
  }
 }

 /** Advance only repository ancestry after proving every intervening commit is a semantic no-op.
  * The caller must inspect again; no save, sidecar, pending plan or remote content is written. */
 fun advanceUnchangedBaseline(comparison: OnboardingComparison, store: AppendStore, stopped: () -> Boolean): Boolean = local.locked {
  val graph = revalidateComparison(comparison, store, stopped)
  val tip = unchangedDescendantTip(graph, comparison.baselineId, repository.pending() != null) ?: return@locked false
  repository.setBaseline(tip.id, graph)
  true
 }

 /** Explicit association of equal bytes with an exact verified tip, never hash-based ancestry. */
 fun adoptOnboardingRemote(comparison: OnboardingComparison, store: AppendStore, stopped: () -> Boolean): AppendCommit = local.locked {
  var graph = revalidateComparison(comparison, store, stopped)
  require(comparison.kind == OnboardingKind.IDENTICAL && comparison.canAdoptRemote) { CoreSessionMessages.BRANCHES_WITHOUT_SHARED_HISTORY_CANNOT_BE_LINKED_AUTOMATICALLY_EXPLICITLY_PRESERVE.text() }
  require(graph.tips.size == 1)
  val commit = graph.commits.getValue(graph.tips.single())
  require(commit.snapshot.files == comparison.localFiles)
  comparison.baselineId?.let { require(graph.descendsFrom(commit.id, it)) { CoreSessionMessages.THE_CLOUD_BRANCH_DOES_NOT_DESCEND_FROM_THE_CURRENT_BASELINE.text() } }
  local.remember(repository.download(store, commit), CoreSessionMessages.EXPLICITLY_LINKED_TO_CLOUD.text(), repository.observedAt(commit.id))
  // No game bytes are written or loaded. Restore-only target/version checks would incorrectly
  // reject equal global-record-only saves; future restores still use the unchanged version gate.
  graph = revalidateComparison(comparison, store, stopped)
  local.directoryMetadata.adopt(commit.snapshot.directoryMetadata,commit.snapshot.files)
  repository.setBaseline(commit.id, graph)
  markDirectorySynced(commit)
  commit
 }

 /** Automatic work must compare complete live history before freezing or retrying any upload.
  * The same session-bound proof is revalidated by onboardingUpload under the physical-root lock. */
 fun automaticPush(store: AppendStore, stopped: () -> Boolean): AutomaticPushResult {
  val comparison = compareOnboarding(store, stopped)
  if (!AutomaticUploadEligibility.allows(comparison)) return AutomaticPushResult.Skipped(comparison)
  return AutomaticPushResult.Published(onboardingUpload(comparison, store, stopped))
 }

 /** Upload follows an explicit choice or the strict automatic gate, including a frozen retry. Never freeze empty saves. */
 fun onboardingUpload(comparison: OnboardingComparison, store: AppendStore, stopped: () -> Boolean): AppendPushResult = local.locked {
  revalidateComparison(comparison, store, stopped, allowPending = true)
  val plan = repository.pending() ?: run {
   require(comparison.localFiles.isNotEmpty()) { CoreSessionMessages.THERE_ARE_NO_LOCAL_SAVES_AN_EMPTY_SNAPSHOT_CANNOT_BE.text() }
   val captured = local.capture(stopped, null)
   require(captured.manifest.files == comparison.localFiles) { CoreSessionMessages.LOCAL_SAVES_CHANGED_AFTER_COMPARISON_COMPARE_AGAIN.text() }
   SaveStabilityGuard(root, adapter, stopped, captureQuietMillis).requireUnchanged(requireNotNull(comparison.fingerprint))
   repository.prepare(captured, listOfNotNull(comparison.baselineId), operation = local.operationMetadata())
  }
  publish(plan, store, stopped)
 }

 /**
  * A first-run remote choice never discards populated unbased/dirty local bytes. Publish those
  * bytes as their own branch first; then resolve only the exact branch set the user observed plus
  * that new local commit. Late siblings are returned as a fresh conflict, never silently absorbed.
  */
 fun onboardingRestore(comparison: OnboardingComparison, snapshotId: String, store: AppendStore, stopped: () -> Boolean): OnboardingRestoreResult = readLocked(store) {
  var graph = revalidateComparison(comparison, store, stopped)
  val selected = graph.snapshotCommit(snapshotId)?.takeIf { it.id in graph.tips }
   ?: throw IllegalArgumentException(CoreSessionMessages.THE_SELECTED_SNAPSHOT_IS_NOT_IN_A_COMPARED_CLOUD_BRANCH.text())
  val chosen = repository.download(store, selected)
  local.remember(chosen, CoreSessionMessages.INITIAL_RESTORE_SELECTION_VERIFIED.text(), repository.observedAt(selected.id))
  if (chosen.manifest.files != comparison.localFiles) local.checkRestore(snapshotId)
  graph = revalidateComparison(comparison, store, stopped)
  val before = comparison.localFiles
  val baselineFiles = comparison.baselineId?.let { graph.commits.getValue(it).snapshot.files }
  val preserveLocal = before.isNotEmpty() && (comparison.baselineId == null || before != baselineFiles)
  var preservedLocalCommit: AppendCommit? = null
  if (preserveLocal) {
   val captured = local.capture(stopped, null)
   require(captured.manifest.files == before) { CoreSessionMessages.LOCAL_SAVES_CHANGED_AFTER_COMPARISON_NOTHING_WAS_RESTORED.text() }
   SaveStabilityGuard(root, adapter, stopped, captureQuietMillis).requireUnchanged(requireNotNull(comparison.fingerprint))
   val plan = repository.prepare(captured, listOfNotNull(comparison.baselineId), operation = local.operationMetadata())
   val expectedTips = (graph.tips - plan.commit.parents.toSet()) + plan.commit.id
   publish(plan, store, stopped)
   preservedLocalCommit = plan.commit
   graph = discover(store)
   if (graph.tips != expectedTips) return@readLocked OnboardingRestoreResult.Published(AppendPushResult.Diverged(preserveConflict(graph, store, stopped)))
   SaveStabilityGuard(root, adapter, stopped, captureQuietMillis).requireUnchanged(requireNotNull(comparison.fingerprint))
  }
  if (graph.tips.size > 1) {
   val conflict = preserveConflict(graph, store, stopped)
   require(conflict.observedLocalFiles == before) { CoreSessionMessages.LOCAL_SAVES_CHANGED_DURING_SELECTION_NOTHING_WAS_RESTORED.text() }
   if (selected.id !in graph.tips) {
    // Only this call's verified local preservation may replace the originally selected baseline
    // tip. Ordinary conflict resolution must still reject historical, non-tip selections.
    val preserved = requireNotNull(preservedLocalCommit) { CoreSessionMessages.THE_SELECTED_BRANCH_CHANGED_COMPARE_AGAIN.text() }
    require(selected.id == comparison.baselineId && selected.id in comparison.observedTips &&
     comparison.graph?.commits?.get(selected.id) == selected && preserved.parents == listOf(selected.id) &&
     preserved.snapshot.files == before && repository.baseline() == preserved.id) { CoreSessionMessages.THE_SELECTED_BRANCH_DOES_NOT_BELONG_TO_THIS_PRESERVATION_OPERATION.text() }
    val expectedTips = (comparison.observedTips - selected.id) + preserved.id
    require(graph.tips == expectedTips && graph.commits[selected.id] == selected && graph.commits[preserved.id] == preserved) {
     CoreSessionMessages.THE_SELECTED_HISTORY_OR_PRESERVED_BRANCH_CHANGED_NOTHING_WAS_RESTORED.text()
    }
    val verified = repository.download(store, selected)
    require(verified.manifest == chosen.manifest && verified.zip.contentEquals(chosen.zip)) { CoreSessionMessages.THE_SELECTED_SNAPSHOT_CONTENT_CHANGED_NOTHING_WAS_RESTORED.text() }
    local.checkRestore(chosen)
    val stability = SaveStabilityGuard(root, adapter, stopped, captureQuietMillis)
    val fingerprint = requireNotNull(comparison.fingerprint)
    stability.requireUnchanged(fingerprint)
    // Downloads and validation can take time. A late branch requires a new explicit choice;
    // it must never become an extra resolution parent or cause a local overwrite here.
    val latest = discover(store)
    if (latest.tips != expectedTips) return@readLocked OnboardingRestoreResult.Published(AppendPushResult.Diverged(preserveConflict(latest, store, stopped)))
    require(latest.commits[selected.id] == selected && latest.commits[preserved.id] == preserved) { CoreSessionMessages.THE_SELECTED_HISTORY_OR_PRESERVED_BRANCH_CHANGED_NOTHING_WAS_RESTORED.text() }
    val plan = prepareAndApply(chosen, latest.tips.sorted(), "resolution", CoreSessionMessages.INITIAL_RESTORE_PRESERVED_LOCAL_SAVES_AND_SELECTED_THE_ORIGINAL_BASELINE.text()) { plan ->
     stability.requireUnchanged(fingerprint)
     local.restore(snapshot(plan), stopped, before)
    }
    return@readLocked OnboardingRestoreResult.Published(publish(plan, store, stopped))
   }
   return@readLocked OnboardingRestoreResult.Published(resolveLocked(conflict, snapshotId, store, stopped))
  }
  if (!preserveLocal && (comparison.baselineId == null || before == baselineFiles)) {
   return@readLocked OnboardingRestoreResult.Restored(restoreRemoteLocked(snapshotId, store, stopped))
  }
  // Local publication may have advanced the selected former tip. Retain that progress and record
  // the explicit older-snapshot choice as a new restore commit, never rewind the baseline. An
  // empty dirty target also creates only a nonempty chosen restore, never an empty local upload.
  val expectedGraph = graph
  require(graph.commits[selected.id] == selected) { CoreSessionMessages.THE_SELECTED_HISTORY_CHANGED_NOTHING_WAS_RESTORED.text() }
  val parent = repository.baseline() ?: graph.tips.single()
  val currentFingerprint = SaveStabilityGuard(root, adapter, stopped, captureQuietMillis).awaitStable()
  require(currentFingerprint.files == before) { CoreSessionMessages.LOCAL_SAVES_CHANGED_DURING_SELECTION_NOTHING_WAS_RESTORED.text() }
  require(discover(store) == expectedGraph) { CoreSessionMessages.CLOUD_BRANCHES_CHANGED_COMPARE_AGAIN.text() }
  val plan = prepareAndApply(chosen, listOf(parent), "restore", CoreSessionMessages.INITIAL_RESTORE_PRESERVED_LOCAL_SAVES_AND_SELECTED_CLOUD_SAVES.text()) { plan ->
   SaveStabilityGuard(root, adapter, stopped, captureQuietMillis).requireUnchanged(currentFingerprint)
   if (before != chosen.manifest.files) local.restore(snapshot(plan), stopped)
  }
  OnboardingRestoreResult.Published(publish(plan, store, stopped))
 }

 /** Explicit confirmation removes retry markers only, after every original is kept in history. */
 fun archivePending() = local.locked {
  repository.pending()?.let { preserve(it, CoreSessionMessages.APPEND_ONLY_UPLOAD_ARCHIVED.text()) }
  local.archiveLegacyPending()
  repository.clearPending()
  SafePaths.checked(applyMarker).deleteIfExists()
 }
}
