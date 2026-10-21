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
/** One selected object and a structural graph; unrelated history objects remain unverified. */
internal class VerifiedTipRestore(
 val owner:Any, val graph:CommitMetadataGraph, val selected:AppendCommit,
 val verified:VerifiedSnapshot, val before:SaveFingerprint, val baseline:String?
)
internal data class HomeReadState(
 val baseline:String?,val pending:AppendCommit?,val legacyId:String?,val needsApply:Boolean,
 val aliases:Map<String,String>,val sidecar:SidecarRead,val transfer:String?
)
internal class HomeReadContext(
 val owner:Any,val files:Map<String,String>,val summary:SaveSummary?,
 val state:HomeReadState,val seed:MetadataReadSeed
)
internal class HomeReadResult(val context:HomeReadContext,val metadata:DetachedMetadataRead)
internal class VerifiedSelectedRestore(
 val owner:Any,val graph:CommitMetadataGraph,val selected:AppendCommit?,val verified:VerifiedSnapshot,
 val before:Map<String,String>,val baseline:String?,val readOnly:Boolean,val historical:Boolean=false
) {
 // Equal bytes are a no-op only for the current single tip. Historical/branch selections still
 // carry an explicit local metadata choice, even when their game bytes happen to match.
 val unchanged:Boolean get() = !historical && selected!=null && graph.tips==setOf(selected.id) &&
  before==verified.snapshot.manifest.files &&
  (baseline==null || graph.displayGraph.descendsFrom(selected.id,baseline))
}
internal class SelectedRestoreStage(
 val selection:VerifiedSelectedRestore,val verified:VerifiedSnapshot,val graph:CommitMetadataGraph,
 val applied:Boolean=true,val localResult:RestoreResult?=null,
 val unchanged:Boolean=false
)
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
 private fun homeReadState():HomeReadState {
  val transfer=directory.resolve("baseline-transfer.txt")
  return HomeReadState(repository.baseline(),repository.pending()?.commit,local.legacyPending()?.manifest?.id,
   needsApply(),aliases(),local.directoryMetadata.read(),
   if(SafePaths.checked(transfer).exists())SafePaths.boundedRead(transfer,128).decodeToString() else null)
 }
 /** Capture only local control state; the supplied files came from the backend's complete read. */
 internal fun beginHomeRead(files:Map<String,String>,summary:SaveSummary?):HomeReadContext = local.locked {
  require(files.size<=MAX_SNAPSHOT_FILES)
  files.forEach { (name,digest)->require(safePath(name)&&adapter.includes(name)&&!isDirectoryMetadata(name)&&Regex("[0-9a-f]{64}").matches(digest)) }
  HomeReadContext(comparisonOwner,java.util.Collections.unmodifiableMap(LinkedHashMap(files)),summary,homeReadState(),repository.metadataReadSeed())
 }
 /** Pure network/CPU phase: no mirror, baseline, alias, or discovery cache is modified. */
 internal fun readHome(context:HomeReadContext,store:AppendStore,cancellation:MetadataReadCancellation):HomeReadResult {
  require(context.owner===comparisonOwner)
  val metadata=repository.readMetadata(store,context.seed,cancellation)
  metadata.graph.requireSettled();metadata.graph.commits.values.snapshotRepresentatives(metadata.graph.tips)
  context.state.baseline?.let { require(it in metadata.graph.commits) { CoreSessionMessages.THE_SYNC_BASELINE_IS_MISSING_OR_UNVERIFIABLE_NO_NEW_ROOT.text() } }
  return HomeReadResult(context,metadata)
 }
 /** Null means stale; no evidence is merged until the captured control state still matches. */
 internal fun acceptHome(result:HomeReadResult):Pair<OnboardingComparison,SyncHistory>? = local.locked {
  val context=result.context
  require(context.owner===comparisonOwner)
  if(homeReadState()!=context.state)return@locked null
  val state=context.state;val graph=result.metadata.graph.displayGraph
  repository.mergeMetadata(result.metadata)
  val lineage=when {
   state.baseline==null->OnboardingLineage.NONE
   graph.tips.size>1->OnboardingLineage.BRANCHED
   graph.tips.singleOrNull()==state.baseline->OnboardingLineage.SAME_TIP
   graph.tips.singleOrNull()?.let {graph.descendsFrom(it,state.baseline)}==true->OnboardingLineage.ANCESTOR_OF_TIP
   else->OnboardingLineage.BLOCKED
  }
  val reason=when {
   state.needsApply->SyncBlockReason.RESTORE_RECOVERY_REQUIRED
   state.legacyId!=null->SyncBlockReason.LEGACY_PENDING_UPLOAD
   state.pending!=null->SyncBlockReason.PENDING_UPLOAD
   else->null
  }
  val kind=when {
   reason!=null->OnboardingKind.BLOCKED
   graph.tips.size>1->OnboardingKind.BRANCHED
   context.files.isEmpty()&&graph.tips.isEmpty()->OnboardingKind.BOTH_EMPTY
   graph.tips.isEmpty()->OnboardingKind.LOCAL_ONLY
   context.files.isEmpty()->OnboardingKind.REMOTE_ONLY
   context.files==graph.commits.getValue(graph.tips.single()).snapshot.files->OnboardingKind.IDENTICAL
   else->OnboardingKind.DIFFERENT
  }
  val blocker=when(reason) {
   SyncBlockReason.RESTORE_RECOVERY_REQUIRED->CoreSessionMessages.THE_PREVIOUS_RESTORE_IS_NOT_CONFIRMED_COMPLETE_EXPLICITLY_ARCHIVE_IT.text()
   SyncBlockReason.LEGACY_PENDING_UPLOAD->CoreSessionMessages.A_LEGACY_PENDING_SNAPSHOT_IS_PRESERVED_EXPLICITLY_ARCHIVE_IT_FIRST.text()
   SyncBlockReason.PENDING_UPLOAD->CoreSessionMessages.A_FROZEN_UPLOAD_IS_PENDING_RETRYING_USES_ITS_ORIGINAL_SNAPSHOT.text()
   else->null
  }
  val comparison=OnboardingComparison(kind,context.files,state.baseline,lineage,emptyList(),graph.tips,
   blocker=blocker,blockReason=reason,retryablePending=state.pending!=null,pendingCommitId=state.pending?.id,
   owner=comparisonOwner,graph=graph,pendingCommit=state.pending,localSource=LocalObservationSource.BRIDGE_INVENTORY)
  val direction=state.transfer?.split(' ')?.takeIf {it.size==2&&it[0]==state.baseline&&it[1] in setOf("upload","restore")}?.get(1)
  val history=SyncHistory(graph,state.baseline,context.files,(state.sidecar as? SidecarRead.Valid)?.value?.displayName.orEmpty(),
   context.summary,state.aliases,direction,repository.verifiedCommitIds(graph),comparisonOwner)
  comparison to history
 }
 private fun historyView(graph:CommitGraph,summary:Pair<Map<String,String>,SaveSummary?>?=null):SyncHistory {
  val files=currentFiles();val baseline=repository.baseline()
  require(summary==null || summary.first==files) { CoreSessionMessages.LOCAL_SAVES_CHANGED_AFTER_COMPARISON_COMPARE_AGAIN.text() }
  val transfer=directory.resolve("baseline-transfer.txt")
  val direction=if(SafePaths.checked(transfer).exists())SafePaths.boundedRead(transfer,128).decodeToString().split(' ').takeIf { it.size==2&&it[0]==baseline&&it[1] in setOf("upload","restore") }?.get(1) else null
  val verifiedIds=if(graph.verification==RemoteVerification.OBJECTS_VERIFIED)graph.commits.keys.toSet() else repository.verifiedCommitIds(graph)
  return SyncHistory(graph,baseline,files,(local.directoryMetadata.read() as? SidecarRead.Valid)?.value?.displayName.orEmpty(),if(summary==null)runCatching { SaveSummary.read(root) }.getOrNull() else summary.second,aliases(),direction,verifiedIds,comparisonOwner)
 }
 /** User-selected historical cloud content is downloaded only on an explicit restore action. */
 fun restoreCloudHistory(snapshotId:String,store:AppendStore,allowUniqueReplacement:Boolean,expectedBefore:Map<String,String>?=null,stopped:()->Boolean):RestoreResult {
  val comparison=readLocked(store) {compareLocked(store,stopped,true)}
  require(expectedBefore==null || comparison.localFiles==expectedBefore) { CoreSessionMessages.LOCAL_SAVES_CHANGED_AFTER_CONFIRMATION_COMPARE_AGAIN.text() }
  val selection=prepareSelectedRestore(comparison,snapshotId,store,allowUniqueReplacement,false,stopped,historical=true)
  require(selection.selected!=null) { CoreSessionMessages.THE_SELECTED_CLOUD_SNAPSHOT_NO_LONGER_EXISTS_RELOAD_HISTORY.text() }
  return requireNotNull(stageSelectedRestore(selection,store,stopped).localResult)
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
  // Any failed/uncertain local apply retains this real recovery gate and incomplete-state marker. There
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
 /** Common clean/empty single-tip restore needs only the selected object, not every ancestor ZIP. */
 internal fun prepareTipRestore(comparison:OnboardingComparison,snapshotId:String,store:AppendStore,stopped:()->Boolean):VerifiedTipRestore? = readLocked(store) {
  require(comparison.owner===comparisonOwner) { CoreSessionMessages.THIS_COMPARISON_BELONGS_TO_A_DIFFERENT_PROFILE_COMPARE_AGAIN.text() }
  val observed=comparison.graph ?: return@readLocked null
  val selected=observed.snapshotCommit(snapshotId) ?: return@readLocked null
  val baseline=repository.baseline()
  val clean=if(baseline==null)comparison.localFiles.isEmpty() else comparison.localFiles==observed.commits[baseline]?.snapshot?.files
  if(observed.tips!=setOf(selected.id) || !clean || comparison.retryablePending ||
   baseline!=null && !observed.descendsFrom(selected.id,baseline))return@readLocked null
  local.guard(stopped);noLegacyPending();appliedOnly()
  require(repository.pending()==null && baseline==comparison.baselineId) { CoreSessionMessages.THE_SYNC_BASELINE_CHANGED_AFTER_COMPARISON_COMPARE_AGAIN.text() }
  val before=SaveStabilityGuard(root,adapter,stopped,captureQuietMillis).fingerprint()
  require(before.files==comparison.localFiles &&
   (comparison.localSource!=LocalObservationSource.FILESYSTEM || before==comparison.fingerprint)) {
   CoreSessionMessages.LOCAL_SAVES_CHANGED_AFTER_COMPARISON_COMPARE_AGAIN.text()
  }
  val graph=discoverMetadata(store)
  require(graph.commits==observed.commits && graph.pending==observed.pending && graph.tips==comparison.observedTips) {
   CoreSessionMessages.CLOUD_BRANCHES_CHANGED_AFTER_COMPARISON_THE_OLD_SELECTION_WAS_NOT.text()
  }
  val verified=repository.downloadVerified(store,selected)
  local.checkRestore(verified)
  VerifiedTipRestore(comparisonOwner,graph,selected,verified,before,baseline)
 }
 internal fun stageTipRestore(selection:VerifiedTipRestore,store:AppendStore,stopped:()->Boolean):RestoreResult = readLocked(store) {
  require(selection.owner===comparisonOwner) { CoreSessionMessages.THIS_COMPARISON_BELONGS_TO_A_DIFFERENT_PROFILE_COMPARE_AGAIN.text() }
  local.guard(stopped);noLegacyPending();appliedOnly()
  require(repository.pending()==null && repository.baseline()==selection.baseline) { CoreSessionMessages.THE_SYNC_BASELINE_CHANGED_AFTER_COMPARISON_COMPARE_AGAIN.text() }
  val graph=discoverMetadata(store)
  require(graph==selection.graph) { CoreSessionMessages.CLOUD_BRANCHES_CHANGED_AFTER_COMPARISON_THE_OLD_SELECTION_WAS_NOT.text() }
  SaveStabilityGuard(root,adapter,stopped,captureQuietMillis).requireUnchanged(selection.before)
  local.remember(selection.verified,CoreSessionMessages.SELECTED_CLOUD_HISTORY.text(),repository.observedAt(selection.selected.id))
  SafePaths.atomicWrite(applyMarker,"selected-tip-restore".encodeToByteArray())
  val restored=local.restore(selection.verified,stopped,selection.before.files)
  repository.setSelectedBaseline(selection.selected,graph,selection.verified)
  markDirectorySynced(selection.selected)
  SafePaths.checked(applyMarker).deleteExisting()
  restored
 }
 internal fun completedTipRestore(selection:VerifiedTipRestore):OnboardingComparison = local.locked {
  require(selection.owner===comparisonOwner && repository.baseline()==selection.selected.id && !needsApply() && repository.pending()==null)
  require(currentFiles()==selection.verified.snapshot.manifest.files) { CoreSessionMessages.LOCAL_SAVES_CHANGED_AFTER_COMPARISON_COMPARE_AGAIN.text() }
  OnboardingComparison(OnboardingKind.IDENTICAL,selection.verified.snapshot.manifest.files,selection.selected.id,
   OnboardingLineage.SAME_TIP,emptyList(),selection.graph.tips,owner=comparisonOwner,graph=selection.graph.displayGraph,
   localSource=LocalObservationSource.RESTORE_RECEIPT)
 }
 internal fun prepareSelectedRestore(comparison:OnboardingComparison,snapshotId:String,store:AppendStore,
  allowUniqueReplacement:Boolean,readOnly:Boolean,stopped:()->Boolean,historical:Boolean=false):VerifiedSelectedRestore = readLocked(store) {
  require(comparison.owner===comparisonOwner) { CoreSessionMessages.THIS_COMPARISON_BELONGS_TO_A_DIFFERENT_PROFILE_COMPARE_AGAIN.text() }
  local.guard(stopped);noLegacyPending();appliedOnly()
  require(repository.pending()==null && repository.baseline()==comparison.baselineId) { CoreSessionMessages.THE_SYNC_BASELINE_CHANGED_AFTER_COMPARISON_COMPARE_AGAIN.text() }
  val observed=requireNotNull(comparison.graph)
  val graph=discoverMetadata(store)
  require(graph.commits==observed.commits && graph.pending==observed.pending && graph.tips==comparison.observedTips) {
   CoreSessionMessages.CLOUD_BRANCHES_CHANGED_AFTER_COMPARISON_THE_OLD_SELECTION_WAS_NOT.text()
  }
  require(currentFiles()==comparison.localFiles) { CoreSessionMessages.LOCAL_SAVES_CHANGED_AFTER_COMPARISON_COMPARE_AGAIN.text() }
  val selected=graph.displayGraph.snapshotCommit(snapshotId)
  val verified=if(selected!=null)repository.downloadVerified(store,selected) else local.loadVerifiedSnapshot(snapshotId)
  local.checkRestore(verified)
  val verifiedIds=repository.verifiedCommitIds(graph.displayGraph)
  val protected=verified.snapshot.manifest.files==comparison.localFiles ||
   graph.commits.values.any {it.id in verifiedIds && it.snapshot.files==comparison.localFiles}
  require(allowUniqueReplacement || comparison.localFiles.isEmpty() || protected) {
   CoreSessionMessages.LOCAL_SAVES_CONTAIN_UNIQUE_PROGRESS_CONFIRM_OVERWRITE_EXPLICITLY_THE_ORIGINAL.text()
  }
  VerifiedSelectedRestore(comparisonOwner,graph,selected,verified,comparison.localFiles,comparison.baselineId,readOnly,historical)
 }
 internal fun stageSelectedRestore(selection:VerifiedSelectedRestore,store:AppendStore,stopped:()->Boolean):SelectedRestoreStage = readLocked(store) {
  require(selection.owner===comparisonOwner)
  local.guard(stopped);noLegacyPending();appliedOnly()
  require(repository.pending()==null && repository.baseline()==selection.baseline)
  // Preparation already read the current graph after the fresh game export. Publications use
  // these exact parents; completion discovers any concurrent branches without absorbing them.
  var graph=selection.graph
  require(currentFiles()==selection.before) { CoreSessionMessages.LOCAL_SAVES_CHANGED_AFTER_COMPARISON_NOTHING_WAS_RESTORED.text() }
  val selected=selection.selected
  var chosen=selection.verified
  if(selection.unchanged)return@readLocked SelectedRestoreStage(selection,chosen,graph,unchanged=true)
  local.remember(chosen,CoreSessionMessages.SELECTED_CLOUD_HISTORY.text(),selected?.let {repository.observedAt(it.id)})
  val isTip=!selection.historical && selected!=null && selected.id in graph.tips
  val before=selection.before
  // Overwritten local bytes are not preserved locally or published as insurance.
  if(selection.readOnly) {
   val prior=(local.directoryMetadata.read() as? SidecarRead.Valid)?.value
   val beforeRuns=runContentHashes(before);val afterRuns=runContentHashes(chosen.snapshot.manifest.files)
   val source=chosen.snapshot.manifest.directoryMetadata
   val used=source?.runs?.values?.mapNotNull {it.runUUID}?.toMutableSet() ?: mutableSetOf()
   val runs=afterRuns.mapValues { (slot,digest)->
    val remote=source?.runs?.get(slot)
    val known=prior?.runs?.get(slot)?.takeIf {it.runUUID!=null&&it.contentSha256==digest&&beforeRuns[slot]==digest&&it.runUUID !in used}
    if(remote?.runUUID!=null||known==null)remote ?: RunIdentity(null,digest) else known.also {used.add(requireNotNull(it.runUUID))}
   }
   val metadata=DirectorySnapshotMetadata(displayName=source?.displayName ?: "",contentSha256=contentSha256(chosen.snapshot.manifest.files),runs=runs).validated(chosen.snapshot.manifest.files)
   chosen=chosen.withManifest(chosen.snapshot.manifest.copy(directoryMetadata=metadata))
  }
  // Download/overwrite adopts the selected immutable commit, including historical commits.
  // It does not publish overwritten bytes or create a same-content restore record.
  SafePaths.atomicWrite(applyMarker,"selected-restore".encodeToByteArray())
  val restored=local.restore(chosen,stopped,before)
  if(selected!=null) {
   repository.setSelectedBaseline(selected,graph,selection.verified)
   markDirectorySynced(selected)
  }
  SafePaths.checked(applyMarker).deleteExisting()
  SelectedRestoreStage(selection,chosen,graph,localResult=restored)
 }
 internal fun finishSelectedRestore(stage:SelectedRestoreStage,store:AppendStore,stopped:()->Boolean):OnboardingComparison = readLocked(store) {
  require(stage.selection.owner===comparisonOwner);local.guard(stopped);appliedOnly()
  val graph=discoverMetadata(store)
  val files=if(stage.applied)stage.verified.snapshot.manifest.files else stage.selection.before
  val baseline=repository.baseline()
  val lineage=when {
   baseline==null->OnboardingLineage.NONE
   graph.tips.size>1->OnboardingLineage.BRANCHED
   graph.tips.singleOrNull()==baseline->OnboardingLineage.SAME_TIP
   graph.tips.singleOrNull()?.let {graph.displayGraph.descendsFrom(it,baseline)}==true->OnboardingLineage.ANCESTOR_OF_TIP
   else->OnboardingLineage.BLOCKED
  }
  val kind=when {
   graph.tips.size>1->OnboardingKind.BRANCHED
   graph.tips.isEmpty()->if(files.isEmpty())OnboardingKind.BOTH_EMPTY else OnboardingKind.LOCAL_ONLY
   files.isEmpty()->OnboardingKind.REMOTE_ONLY
   files==graph.commits.getValue(graph.tips.single()).snapshot.files->OnboardingKind.IDENTICAL
   else->OnboardingKind.DIFFERENT
  }
  OnboardingComparison(kind,files,baseline,lineage,emptyList(),graph.tips,owner=comparisonOwner,graph=graph.displayGraph,
   localSource=LocalObservationSource.RESTORE_RECEIPT)
 }
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

 /** Explicit restore validates the current files and adopts the selected cloud commit locally. */
 fun restoreRemote(snapshotId: String, store: AppendStore, stopped: () -> Boolean): RestoreResult {
  val comparison=readLocked(store) {compareLocked(store,stopped,true)}
  val selected=prepareTipRestore(comparison,snapshotId,store,stopped)
  return if(selected!=null)stageTipRestore(selected,store,stopped)
   else readLocked(store) {restoreRemoteLocked(snapshotId,store,stopped)}
 }
 private fun restoreRemoteLocked(snapshotId: String, store: AppendStore, stopped: () -> Boolean): RestoreResult {
  local.guard(stopped); noLegacyPending(); appliedOnly()
  require(repository.pending() == null) { CoreSessionMessages.RETRY_OR_EXPLICITLY_ARCHIVE_THE_PENDING_UPLOAD_FIRST.text() }
  val graph = discover(store)
  require(graph.tips.size == 1) { CoreSessionMessages.THE_CLOUD_HAS_MULTIPLE_BRANCHES_A_NORMAL_RESTORE_IS_UNAVAILABLE.text() }
  val commit = graph.commits.getValue(graph.tips.single())
  require(commit.snapshot.id == snapshotId) { CoreSessionMessages.THE_OBSERVED_BRANCH_CHANGED_NOTHING_WAS_RESTORED_RELOAD_THE_CLOUD.text() }
  val before = currentFiles()
  val chosen = repository.download(store, commit)
  local.remember(chosen, CoreSessionMessages.CLOUD_RESTORE_SNAPSHOT_VERIFIED.text(), repository.observedAt(commit.id))
  local.checkRestore(chosen.manifest.id)
  require(currentFiles() == before) { CoreSessionMessages.LOCAL_SAVES_CHANGED_DURING_VERIFICATION_NOTHING_WAS_RESTORED.text() }
  SafePaths.atomicWrite(applyMarker, "remote-restore".encodeToByteArray())
  // Keep the restart gate on any uncertain apply or baseline-write failure, without rollback.
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
   // A failed apply may leave a partial target. Keep the gate until explicit archive/review.
   plan?.let { preserve(it, CoreSessionMessages.INCOMPLETE_LOCAL_RESTORE_ARCHIVED.text()) }
   repository.clearPending()
   throw failure
  }
 }

 /** Restores cached history without manufacturing another upload of identical bytes. */
 fun restoreHistory(id: String, stopped: () -> Boolean): RestoreResult = local.locked {
  local.guard(stopped); noLegacyPending(); appliedOnly()
  require(repository.pending() == null) { CoreSessionMessages.RETRY_OR_EXPLICITLY_ARCHIVE_THE_PENDING_UPLOAD_FIRST.text() }
  val chosen=local.loadVerifiedSnapshot(id)
  local.checkRestore(chosen)
  val graph=repository.cachedGraph()
  val selected=graph.commits.values.singleOrNull { it.snapshot == chosen.snapshot.manifest }
  SafePaths.atomicWrite(applyMarker,"history-restore".encodeToByteArray())
  val restored=local.restore(chosen,stopped)
  if(selected!=null) {
   repository.setSelectedBaseline(selected,CommitMetadataGraph(graph.copy(verification=RemoteVerification.METADATA_ONLY)),chosen)
   markDirectorySynced(selected)
  }
  SafePaths.checked(applyMarker).deleteExisting()
  restored
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
 /** Preflight the merged outcome without publishing an extra local insurance branch. */
 fun mergeCurrent(store:AppendStore,stopped:()->Boolean):AppendPushResult = local.locked {
  val graph=discover(store);require(graph.tips.isNotEmpty()) { CoreSessionMessages.NO_CLOUD_BRANCHES_ARE_AVAILABLE_TO_MERGE.text() }
  val observed=AppendConflict(graph.tips,emptyList(),currentFiles())
  mergeCurrentLocked(observed,store,stopped)
 }
 private fun mergeCurrentLocked(observed:AppendConflict,store:AppendStore,stopped:()->Boolean,enforceObservedTips:Boolean=false):AppendPushResult {
  val preview=buildMerge(observed,store,stopped)
  local.checkRestore(preview.snapshot)
  val latest=discover(store)
  if(latest.tips!=observed.observedTips)return AppendPushResult.Diverged(preserveConflict(latest,store,stopped))
  require(currentFiles()==observed.observedLocalFiles) { CoreSessionMessages.LOCAL_CONTENT_CHANGED_AFTER_THE_PREFLIGHT_CHECK_COMPARE_AGAIN.text() }
  // Publish only the requested merged outcome, never an insurance copy of overwritten local data.
  val plan=prepareAndApply(preview.snapshot,latest.tips.sorted(),if(latest.tips.size>1)"resolution" else "snapshot",CoreSessionMessages.PER_RUN_MERGE_SLOTS.text(preview.slots)) { plan ->
   require(currentFiles()==observed.observedLocalFiles) { CoreSessionMessages.LOCAL_CONTENT_CHANGED_BEFORE_MERGING_NOTHING_WAS_OVERWRITTEN.text() }
   local.restore(snapshot(plan),stopped,observed.observedLocalFiles)
  }
  return publish(plan,store,stopped)
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
  // Choosing a cloud branch is a local restore. Existing cloud branches remain unchanged.
  val chosen = repository.download(store, branch.commit)
  local.remember(chosen,CoreSessionMessages.SELECTED_CLOUD_HISTORY.text(),repository.observedAt(branch.commit.id))
  local.checkRestore(chosen)
  val latest=discover(store)
  if(latest!=graph)return AppendPushResult.Diverged(preserveConflict(latest,store,stopped))
  require(currentFiles() == conflict.observedLocalFiles) { CoreSessionMessages.LOCAL_PROGRESS_CHANGED_DURING_VERIFICATION_NOTHING_WAS_OVERWRITTEN.text() }
  SafePaths.atomicWrite(applyMarker,"selected-branch-restore".encodeToByteArray())
  if(currentFiles()!=chosen.manifest.files)local.restore(chosen,stopped,conflict.observedLocalFiles)
  repository.setBaseline(branch.commit.id,latest)
  markDirectorySynced(branch.commit)
  SafePaths.checked(applyMarker).deleteExisting()
  return AppendPushResult.Diverged(preserveConflict(latest,store,stopped))
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
  require(fresh.localFiles==comparison.localFiles && (comparison.localSource!=LocalObservationSource.FILESYSTEM || fresh.fingerprint==comparison.fingerprint)) { CoreSessionMessages.LOCAL_SAVES_CHANGED_AFTER_COMPARISON_COMPARE_AGAIN.text() }
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

 /** Applies the selected cloud branch without publishing overwritten local bytes. */
 fun onboardingRestore(comparison: OnboardingComparison, snapshotId: String, store: AppendStore, stopped: () -> Boolean): OnboardingRestoreResult = readLocked(store) {
  val graph = revalidateComparison(comparison, store, stopped)
  val selected = graph.snapshotCommit(snapshotId)?.takeIf { it.id in graph.tips }
   ?: throw IllegalArgumentException(CoreSessionMessages.THE_SELECTED_SNAPSHOT_IS_NOT_IN_A_COMPARED_CLOUD_BRANCH.text())
  val chosen = repository.download(store, selected)
  local.remember(chosen, CoreSessionMessages.INITIAL_RESTORE_SELECTION_VERIFIED.text(), repository.observedAt(selected.id))
  if (chosen.manifest.files != comparison.localFiles) local.checkRestore(snapshotId)
  revalidateComparison(comparison, store, stopped)
  if (graph.tips.size > 1) {
   val conflict = preserveConflict(graph, store, stopped)
   require(conflict.observedLocalFiles == comparison.localFiles) { CoreSessionMessages.LOCAL_SAVES_CHANGED_DURING_SELECTION_NOTHING_WAS_RESTORED.text() }
   return@readLocked OnboardingRestoreResult.Published(resolveLocked(conflict, snapshotId, store, stopped))
  }
  OnboardingRestoreResult.Restored(restoreRemoteLocked(snapshotId, store, stopped))
 }

 /** Explicit confirmation removes retry markers only, after every original is kept in history. */
 fun archivePending() = local.locked {
  repository.pending()?.let { preserve(it, CoreSessionMessages.APPEND_ONLY_UPLOAD_ARCHIVED.text()) }
  local.archiveLegacyPending()
  repository.clearPending()
  SafePaths.checked(applyMarker).deleteIfExists()
 }
}
