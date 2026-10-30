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
 val publishedLocalCommit: String? = null,
 val overwriteTargetSnapshotId:String? = null
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
 data class Success(val commit: AppendCommit, val localDirty: Boolean, val remoteAhead: Boolean = false, val reused: Boolean = false): AppendPushResult
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
  return json.decodeFromString<Map<String,String>>(text).also { values -> require(values.size<=MAX_COMMITS);values.forEach { (id,name)->require(validRecordId(id)&&name==sanitizeSnapshotName(name)) } }
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
 private class UploadReceiptScope(var snapshot:VerifiedSnapshot?=null)
 private val uploadReceipt=ThreadLocal<UploadReceiptScope>()
 internal fun <T> withUploadReceiptScope(action:()->T):T {
  if(uploadReceipt.get()!=null)return action()
  uploadReceipt.set(UploadReceiptScope())
  try {return action()} finally {uploadReceipt.remove()}
 }
 internal fun uploadReceiptFiles():Map<String,String>?=uploadReceipt.get()?.snapshot?.snapshot?.manifest?.files
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
  require(repository.baseline()==comparison.baselineId && (uploadReceiptFiles() ?: currentFiles())==comparison.localFiles) { CoreSessionMessages.LOCAL_SAVES_CHANGED_AFTER_COMPARISON_COMPARE_AGAIN.text() }
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
  context.state.baseline?.let { require(it in metadata.graph.commits || it in metadata.retired) { CoreSessionMessages.THE_SYNC_BASELINE_IS_MISSING_OR_UNVERIFIABLE_NO_NEW_ROOT.text() } }
  return HomeReadResult(context,metadata)
 }
 /** Null means stale; no evidence is merged until the captured control state still matches. */
 internal fun acceptHome(result:HomeReadResult):Pair<OnboardingComparison,SyncHistory>? = local.locked {
  val context=result.context
  require(context.owner===comparisonOwner)
  if(homeReadState()!=context.state)return@locked null
  val state=if(context.state.baseline in result.metadata.retired)context.state.copy(baseline=null,transfer=null) else context.state;val graph=result.metadata.graph.displayGraph
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
   context.summary,state.aliases,direction,repository.verifiedCommitIds(graph),comparisonOwner,displayAnchor(graph,context.files,state.baseline))
  comparison to history
 }
 /** Preserve a previously displayed association without inventing an operational baseline. */
 private fun displayAnchor(graph:CommitGraph,files:Map<String,String>,baseline:String?):String? {
  val path=directory.resolve("history-display-anchor.txt")
  val selected=baseline?.let(graph.commits::get) ?: graph.commits.values
   .filter {it.snapshot.files==files && files.isNotEmpty()}.sortedBy {it.id}.firstOrNull()
  fun stamp(commit:AppendCommit)=commit.id+" "+hash(json.encodeToString(commit).encodeToByteArray())
  if(selected!=null) {
   val value=stamp(selected)
   if(!SafePaths.checked(path).exists() || SafePaths.boundedRead(path,256).decodeToString()!=value)
    SafePaths.atomicWrite(path,value.encodeToByteArray())
   return selected.id
  }
  if(!SafePaths.checked(path).exists())return null
  val old=SafePaths.boundedRead(path,256).decodeToString().split(' ')
  if(old.size!=2 || !validRecordId(old[0]))return null
  val commit=graph.commits[old[0]] ?: return null
  return commit.id.takeIf {stamp(commit)==old.joinToString(" ")}
 }
 private fun historyView(graph:CommitGraph,summary:Pair<Map<String,String>,SaveSummary?>?=null):SyncHistory = DiagnosticTiming.measure(DiagnosticPhase.UPLOAD_HISTORY_PRESENTATION) {
  val receipt=uploadReceipt.get()?.snapshot
  val files=receipt?.snapshot?.manifest?.files ?: currentFiles();val baseline=repository.baseline()
  require(summary==null || summary.first==files) { CoreSessionMessages.LOCAL_SAVES_CHANGED_AFTER_COMPARISON_COMPARE_AGAIN.text() }
  val transfer=directory.resolve("baseline-transfer.txt")
  val direction=if(SafePaths.checked(transfer).exists())SafePaths.boundedRead(transfer,128).decodeToString().split(' ').takeIf { it.size==2&&it[0]==baseline&&it[1] in setOf("upload","restore") }?.get(1) else null
  val verifiedIds=if(graph.verification==RemoteVerification.OBJECTS_VERIFIED)graph.commits.keys.toSet() else repository.verifiedCommitIds(graph)
  SyncHistory(graph,baseline,files,(local.directoryMetadata.read() as? SidecarRead.Valid)?.value?.displayName.orEmpty(),if(summary!=null)summary.second else if(receipt!=null)SaveSummary.fromVerified(receipt) else runCatching { SaveSummary.read(root) }.getOrNull(),aliases(),direction,verifiedIds,comparisonOwner,displayAnchor(graph,files,baseline))
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
  requireNotNull(captureContent(stopped).manifest.directoryMetadata)
 }
 fun renameCurrent(name: String, stopped: () -> Boolean): DirectorySnapshotMetadata = local.locked {
  local.guard(stopped)
  val captured=captureContent(stopped)
  local.directoryMetadata.rename(name,captured.manifest.files)
 }
 fun confirmRunIdentity(slot: String, expectedFiles: Map<String,String>, sameRun: Boolean, stopped: () -> Boolean): DirectorySnapshotMetadata = local.locked {
  local.guard(stopped)
  val captured=captureContent(stopped)
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
 fun retryablePending(): Boolean = !needsApply() && local.legacyPending() == null && (repository.pending() != null || hasOverwritePending())
 /** The frozen plan is verified by pending(); a marker alone never proves an upload source. */
 fun retryablePendingSourceAvailable(): Boolean = local.locked {
  !needsApply() && local.legacyPending() == null && (repository.pending()?.commit?.snapshot?.files?.isNotEmpty() == true || hasOverwritePending())
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
  local.remember(captureContent(stopped), CoreSessionMessages.LOCAL_SNAPSHOT.text())
 }
 fun hasOverwritePending():Boolean = ConfirmedBranchOverwrite(repository.overwriteDirectory(),adapter,config.profile).pending()!=null
 fun hasPending(): Boolean = local.locked { repository.pending() != null || local.legacyPending() != null || needsApply() || hasOverwritePending() }
 fun baseline(): String? = local.locked { repository.baseline() }
 private fun needsApply() = SafePaths.checked(applyMarker).exists()
 private fun appliedOnly() {
  require(!needsApply()) { CoreSessionMessages.THE_PREVIOUS_RESTORE_IS_NOT_CONFIRMED_COMPLETE_ITS_SNAPSHOT_AND.text() }
 }
 private fun noLegacyPending() {
  require(local.legacyPending() == null) { CoreSessionMessages.A_LEGACY_PENDING_SNAPSHOT_IS_PRESERVED_EXPLICITLY_ARCHIVE_IT_BEFORE.text() }
 }
 private fun discover(store: AppendStore,retain:Set<String> = emptySet(),proofs:MutableMap<String,VerifiedSnapshot>?=null): CommitGraph = (if(proofs==null)repository.discover(store) else repository.discoverRetaining(store,retain,proofs) {commit->
  try {local.loadKnownUploadArchive(commit.snapshot.id)}
  catch(e:java.util.concurrent.CancellationException){throw e}
  catch(e:java.io.InterruptedIOException){throw e}
  catch(e:java.nio.file.NoSuchFileException){null}
 }).also {
  it.requireSettled()
  it.commits.values.snapshotRepresentatives(it.tips)
  repository.baseline()?.let { baseline -> require(baseline in it.commits) { CoreSessionMessages.THE_SYNC_BASELINE_IS_MISSING_OR_UNVERIFIABLE_NO_NEW_ROOT.text() } }
 }
 private fun discoverMetadata(store:AppendStore):CommitMetadataGraph = repository.discoverMetadata(store).also {
  it.requireSettled();it.commits.values.snapshotRepresentatives(it.tips)
  repository.baseline()?.let { baseline -> require(baseline in it.commits) { CoreSessionMessages.THE_SYNC_BASELINE_IS_MISSING_OR_UNVERIFIABLE_NO_NEW_ROOT.text() } }
 }
 /** Normalize only newly created local work. Downloaded legacy manifests retain their real IDs.
  * Identity metadata changes in memory; archive bytes are neither reread nor repacked. */
 private fun contentSnapshot(snapshot:Snapshot):Snapshot = snapshot.copy(manifest=snapshot.manifest.copy(
  id=contentSha256(snapshot.manifest.files),parent=null))
 private fun captureContent(stopped:()->Boolean,persistDirectoryMetadata:Boolean=true):Snapshot =
  contentSnapshot(local.capture(stopped,null,persistDirectoryMetadata=persistDirectoryMetadata))
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
  if(selection.unchanged) {
   // An explicit verified restore confirms provenance even when game bytes need no replacement.
   if(selection.baseline==null && selected!=null) {
    repository.setSelectedBaseline(selected,graph,selection.verified)
    markDirectorySynced(selected)
   }
   return@readLocked SelectedRestoreStage(selection,chosen,graph,unchanged=true)
  }
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
  return AppendConflict(graph.tips, branches, current, repository.baseline(),cloudOverwriteSelection(graph,repository.baseline(),current)?.let {graph.commits.getValue(it.target).snapshot.id})
 }
 private fun publish(plan: UploadPlan, store: AppendStore, stopped: () -> Boolean, expected:CommitGraph?=null,uploadDelta:Boolean=false,optimistic:Boolean=false,acknowledgeNew:Boolean=false,verifiedPlan:VerifiedUploadPlan?=null): AppendPushResult {
  // A new acknowledged upload already has a durable pending ZIP/manifest/marker.
  // Archive it once after publication; failures retain that exact pending source.
  if(!acknowledgeNew)preserve(plan, CoreSessionMessages.APPEND_ONLY_SYNC.text(plan.commit.kind))
  val publication=DiagnosticTiming.measure(DiagnosticPhase.UPLOAD_PUBLISH) {if(verifiedPlan!=null)repository.publishAcknowledged(store,verifiedPlan,requireNotNull(expected)) else repository.publishResult(store,plan,expected,acknowledgeNew)}
  val commit=publication.commit
  if(publication.reused) {
   // Publication verified the winning object. Its real record (including legacy ID) is returned;
   // reusing content never moves ancestry, adds a parent, or recaptures the current directory.
   val graph=if(optimistic)CommitGraph(requireNotNull(expected).commits+(commit.id to commit),emptyMap(),expected.verification) else if(uploadDelta)DiagnosticTiming.measure(DiagnosticPhase.UPLOAD_DELTA) {repository.discoverUploadDelta(store,requireNotNull(expected),commit)} else repository.cachedGraph()
   graph.requireSettled()
   finishReusedPending(commit,graph,plan)
   return AppendPushResult.Success(commit,currentFiles()!=commit.snapshot.files,graph.tips.singleOrNull()!=commit.id,reused=true)
  }
  val graph = if(optimistic)CommitGraph(requireNotNull(expected).commits+(commit.id to commit),emptyMap(),expected.verification) else if(uploadDelta)DiagnosticTiming.measure(DiagnosticPhase.UPLOAD_DELTA) {repository.discoverUploadDelta(store,requireNotNull(expected),commit)} else discover(store)
  graph.requireSettled()
  require(graph.commits[commit.id]==commit) { CoreSessionMessages.THE_PUBLISHED_COMMIT_IS_NOT_FULLY_VISIBLE_YET_THE_ORIGINAL.text() }
  DiagnosticTiming.measure(DiagnosticPhase.UPLOAD_ARCHIVE) {if(verifiedPlan!=null && commit==verifiedPlan.plan.commit)local.remember(verifiedPlan.verified,CoreSessionMessages.APPEND_ONLY_SYNC.text(commit.kind),repository.observedAt(commit.id))
  else local.remember(if(hash(plan.zip)==commit.snapshot.sha256)Snapshot(commit.snapshot,plan.zip) else repository.download(store,commit), CoreSessionMessages.APPEND_ONLY_SYNC.text(commit.kind), repository.observedAt(commit.id))
  }
  DiagnosticTiming.measure(DiagnosticPhase.UPLOAD_BASELINE) {repository.setBaseline(commit.id, graph)}
  DiagnosticTiming.measure(DiagnosticPhase.UPLOAD_DIRECTORY_MARK) {markDirectorySynced(commit,"upload")}
  repository.clearPending()
  if(acknowledgeNew && verifiedPlan!=null)uploadReceipt.get()?.snapshot=verifiedPlan.verified
  return if (graph.tips.size > 1) AppendPushResult.Diverged(preserveConflict(graph, store, stopped))
   else AppendPushResult.Success(commit, if(acknowledgeNew)false else runCatching { currentFiles() != commit.snapshot.files }.getOrDefault(true), graph.tips.singleOrNull() != commit.id)
 }
 /** Content identity is the complete normalized game-file map, never capture/portable metadata.
  * This helper consumes existing object evidence and performs no discovery or object reads. */
 private fun matchingUpload(graph:CommitGraph,files:Map<String,String>):AppendCommit? {
  if(files.isEmpty() || graph.verification==RemoteVerification.METADATA_ONLY || graph.pending.isNotEmpty())return null
  val baseline=repository.baseline()
  return graph.commits.values.filter {it.snapshot.files==files && (graph.verification==RemoteVerification.OBJECTS_VERIFIED || it.id in graph.tips)}
   .sortedWith(compareBy<AppendCommit> {it.id!=baseline}.thenBy {it.id !in graph.tips}.thenBy {it.id}).firstOrNull()
 }
 internal fun hasVerifiedUploadContent(comparison:OnboardingComparison):Boolean {
  require(comparison.owner===comparisonOwner)
  if(comparison.retryablePending || comparison.kind==OnboardingKind.BLOCKED)return false
  return comparison.graph?.let {matchingUpload(it,comparison.localFiles)}!=null
 }
 private fun associateUpload(commit:AppendCommit,graph:CommitGraph) {
  val receipt=DirectorySyncBaseline(hash(directory.toString().encodeToByteArray()),commit.id,contentSha256(commit.snapshot.files))
  val sidecar=local.directoryMetadata.read()
  val receiptMatches=sidecar !is SidecarRead.Valid || sidecar.value.lastSynced==receipt
  val transfer=SafePaths.checked(directory.resolve("baseline-transfer.txt"))
  if(repository.baseline()==commit.id && receiptMatches && transfer.exists() &&
   SafePaths.boundedRead(transfer,128).decodeToString()==commit.id+" upload")return
  repository.setBaseline(commit.id,graph)
  // Only the synchronization receipt changes. Preserve the user's bytes, display name and run metadata.
  markDirectorySynced(commit,"upload")
 }
 private fun finishReusedPending(commit:AppendCommit,graph:CommitGraph,pending:UploadPlan) {
  associateUpload(commit,graph)
  repository.clearPending()
 }
 private fun reuseUpload(commit:AppendCommit,graph:CommitGraph,pending:UploadPlan?=null):AppendPushResult.Success {
  pending?.let {preserve(it,CoreSessionMessages.APPEND_ONLY_UPLOAD_ARCHIVED.text())}
  associateUpload(commit,graph)
  if(pending!=null)repository.clearPending()
  return AppendPushResult.Success(commit,currentFiles()!=commit.snapshot.files,graph.tips.singleOrNull()!=commit.id,reused=true)
 }
 /** Associate a fresh, already object-verified automatic comparison without another cloud read. */
 internal fun reuseVerifiedUploadContent(comparison:OnboardingComparison,stopped:()->Boolean):AppendCommit? = local.locked {
  if(!hasVerifiedUploadContent(comparison))return@locked null
  local.guard(stopped);noLegacyPending();appliedOnly()
  require(repository.pending()==null && repository.baseline()==comparison.baselineId) {
   CoreSessionMessages.THE_SYNC_BASELINE_CHANGED_AFTER_COMPARISON_COMPARE_AGAIN.text()
  }
  SaveStabilityGuard(root,adapter,stopped,captureQuietMillis).requireUnchanged(requireNotNull(comparison.fingerprint))
  val graph=requireNotNull(comparison.graph)
  val commit=matchingUpload(graph,comparison.localFiles) ?: return@locked null
  associateUpload(commit,graph)
  commit
 }
 fun push(store: AppendStore, stopped: () -> Boolean): AppendPushResult = local.locked {
  local.guard(stopped); noLegacyPending(); appliedOnly()
  val pending=repository.pending()
  val stability=SaveStabilityGuard(root,adapter,stopped,captureQuietMillis)
  val fingerprint=if(pending==null)stability.awaitStable() else null
  val files=pending?.commit?.snapshot?.files ?: requireNotNull(fingerprint).files
  fun freeze():UploadPlan {
   val captured = DiagnosticTiming.measure(DiagnosticPhase.UPLOAD_CAPTURE) {captureContent(stopped)}
   require(captured.manifest.files==files) { CoreSessionMessages.LOCAL_SAVES_CHANGED_AFTER_COMPARISON_COMPARE_AGAIN.text() }
   fingerprint?.let(stability::requireUnchanged)
   return repository.prepare(captured,listOfNotNull(repository.baseline()),operation=local.operationMetadata())
  }
  // The publication graph is acquired once, before capture. Metadata/UUID/time differences
  // cannot manufacture a ZIP for content already verified anywhere in live cloud history.
  val graph=try { discover(store) } catch(error:java.io.IOException) {
   // Keep the established offline retry contract without treating unavailable evidence as a
   // match. Integrity/settlement failures are not transport failures and never enter this path.
   if(error is io.github.xyz1001.pdsync.android.OfficialSyncAccess.AccessException)throw error
   if(pending==null)preserve(freeze(),CoreSessionMessages.APPEND_ONLY_SYNC.text("normal"))
   throw error
  }
  fingerprint?.let(stability::requireUnchanged)
  // A previously prepared upload already froze these exact observed parents. It may be
  // explicitly published without another overwrite prompt, but never absorb a new tip.
  // An already verified matching record only clears the pending receipt below: no replay.
  val pendingCoversTips=pending!=null && (pending.commit.parents.toSet()==graph.tips || matchingUpload(graph,files)!=null)
  if(!pendingCoversTips && uploadNeedsOverwrite(graph,repository.baseline(),files))return@locked AppendPushResult.Diverged(preserveConflict(graph,store,stopped))
  matchingUpload(graph,files)?.let {return@locked reuseUpload(it,graph,pending)}
  val plan=pending ?: freeze()
  publish(plan,store,stopped,graph)
 }

 /** Verify every observed tip, presenting exact duplicate snapshots once. The graph retains all
  * commit IDs and tips; a listing is never a promise of a global latest save. */
 fun pushFromHome(observedHistory:SyncHistory?,store:AppendStore,stopped:()->Boolean):HomePushResult {
  if(hasOverwritePending())return readLocked(store) {
   val fresh=compareLocked(store,stopped,false)
   overwriteCloudFresh(fresh,fresh,"",store,stopped,{})
   HomePushResult.Unchanged(historyView(repository.cachedGraph()))
  }
  val unchanged=local.locked {
   local.guard(stopped);noLegacyPending();appliedOnly()
   if(observedHistory==null || repository.pending()!=null)return@locked null
   require(observedHistory.owner === comparisonOwner) { CoreSessionMessages.THIS_COMPARISON_BELONGS_TO_A_DIFFERENT_PROFILE_COMPARE_AGAIN.text() }
   if(observedHistory.graph.pending.isNotEmpty())return@locked null
   val files=currentFiles()
   val candidate=observedHistory.graph.commits.values.firstOrNull {it.snapshot.files.isNotEmpty() && it.snapshot.files==files} ?: return@locked null
   val stability=SaveStabilityGuard(root,adapter,stopped,captureQuietMillis);val fingerprint=stability.awaitStable()
   if(candidate.snapshot.files!=fingerprint.files)return@locked null
   val fresh=discover(store);stability.requireUnchanged(fingerprint);noLegacyPending();appliedOnly()
   if(repository.pending()!=null)return@locked null
   if(uploadNeedsOverwrite(fresh,repository.baseline(),fingerprint.files))return@locked null
   val matched=matchingUpload(fresh,fingerprint.files) ?: return@locked null
   associateUpload(matched,fresh)
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
 private fun compareLocked(store:AppendStore,stopped:()->Boolean,metadataOnly:Boolean,retain:Set<String> = emptySet(),proofs:MutableMap<String,VerifiedSnapshot>?=null,uploadMetadata:Boolean=false):OnboardingComparison {
   local.guard(stopped); local.validateRoot()
   val stability = SaveStabilityGuard(root, adapter, stopped, captureQuietMillis)
   val fingerprint = if(metadataOnly) stability.fingerprint() else stability.awaitStable()
   val baseline = repository.baseline()
   if (needsApply()) return blockedComparison(SyncBlockReason.RESTORE_RECOVERY_REQUIRED, CoreSessionMessages.THE_PREVIOUS_RESTORE_IS_NOT_CONFIRMED_COMPLETE_EXPLICITLY_ARCHIVE_IT.text(), fingerprint, baseline)
   if (local.legacyPending() != null) return blockedComparison(SyncBlockReason.LEGACY_PENDING_UPLOAD, CoreSessionMessages.A_LEGACY_PENDING_SNAPSHOT_IS_PRESERVED_EXPLICITLY_ARCHIVE_IT_FIRST.text(), fingerprint, baseline)
   val pending = repository.pending()
   // A failed/missing page or object must not become an apparently empty cloud.
   val graph = if(uploadMetadata)repository.discoverUploadMetadata(store) else if(metadataOnly) DiagnosticTiming.measure(DiagnosticPhase.REMOTE_METADATA) { discoverMetadata(store).displayGraph } else discover(store,retain,proofs)
   val entries = if(metadataOnly || uploadMetadata) emptyList() else graph.tips.sorted().map { id ->
    val commit = graph.commits.getValue(id)
    val proof=proofs?.get(id)
    AppendRemoteEntry(commit,if(proof!=null)HistoryEntry(proof.snapshot.manifest,java.time.Instant.now().toString(),CoreSessionMessages.INITIAL_COMPARISON_CLOUD_BRANCH_VERIFIED.text(id.take(8)),repository.observedAt(id))
     else local.remember(repository.download(store,commit),CoreSessionMessages.INITIAL_COMPARISON_CLOUD_BRANCH_VERIFIED.text(id.take(8)),repository.observedAt(id)))
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
  if (reuseVerifiedUploadContent(comparison,stopped)!=null || !AutomaticUploadEligibility.allows(comparison)) return AutomaticPushResult.Skipped(comparison)
  return AutomaticPushResult.Published(onboardingUpload(comparison, store, stopped))
 }

 /** Confirmed keep-local choice appends a successor; it never deletes cloud history. */
 internal fun overwriteCloudSuccessors(comparison:OnboardingComparison,selectedSnapshot:String,store:AppendStore,stopped:()->Boolean,validate:(OnboardingComparison)->Unit):OnboardingComparison = readLocked(store) {
  overwriteCloudSuccessorsLocked(comparison,selectedSnapshot,store,stopped,validate)
 }
 private fun overwriteCloudSuccessorsLocked(comparison:OnboardingComparison,selectedSnapshot:String,store:AppendStore,stopped:()->Boolean,validate:(OnboardingComparison)->Unit):OnboardingComparison {
  require(comparison.owner===comparisonOwner)
  require(!hasOverwritePending() && repository.pending()==null) {CoreSessionMessages.RETRY_OR_EXPLICITLY_ARCHIVE_THE_PENDING_UPLOAD_FIRST.text()}
  val fresh=compareLocked(store,stopped,false,uploadMetadata=true)
  val graph=requireNotNull(fresh.graph);graph.requireSettled()
  require(fresh.localFiles==comparison.localFiles && graph.tips==comparison.observedTips) {"Conflict changed after confirmation"}
  val selection=requireNotNull(cloudOverwriteSelection(graph,fresh.baselineId,fresh.localFiles))
  require(graph.commits.getValue(selection.target).snapshot.id==selectedSnapshot)
  validate(fresh)
  val captured=SafePaths.bufferedUploadWrites {local.captureVerified(stopped)}
  val plan=repository.prepareVerified(captured,graph.tips.sorted(),if(graph.tips.size>1)"resolution" else "snapshot",local.operationMetadata(),persist=false)
  try {SafePaths.bufferedUploadWrites {publish(plan.plan,store,stopped,graph,optimistic=true,acknowledgeNew=true,verifiedPlan=plan)}}
  catch(error:Exception) {try {repository.preserveFailedUpload(plan.plan)}catch(save:Exception){error.addSuppressed(save)};throw error}
  val after=graph.copy(commits=graph.commits+(plan.plan.commit.id to plan.plan.commit))
  return OnboardingComparison(OnboardingKind.IDENTICAL,fresh.localFiles,plan.plan.commit.id,OnboardingLineage.SAME_TIP,
   emptyList(),after.tips,owner=comparisonOwner,graph=after.copy(verification=RemoteVerification.METADATA_ONLY),localSource=LocalObservationSource.RESTORE_RECEIPT)
 }
 fun overwriteCloudConflict(conflict:AppendConflict,selectedSnapshot:String,store:AppendStore,stopped:()->Boolean):OnboardingComparison = readLocked(store) {
  val fresh=compareLocked(store,stopped,false,uploadMetadata=true)
  require(fresh.observedTips==conflict.observedTips && fresh.localFiles==conflict.observedLocalFiles) {"Conflict changed after confirmation"}
  overwriteCloudSuccessorsLocked(fresh,selectedSnapshot,store,stopped,{})
 }
 private fun overwriteCloudFresh(comparison:OnboardingComparison,fresh:OnboardingComparison,selectedSnapshot:String,store:AppendStore,stopped:()->Boolean,validate:(OnboardingComparison)->Unit):OnboardingComparison {
  val deletion=ConfirmedBranchOverwrite(repository.overwriteDirectory(),adapter,config.profile)
  val retry=deletion.pending()
  val graph=requireNotNull(fresh.graph);graph.requireObjectsVerified();graph.requireSettled()
  require(fresh.localFiles==comparison.localFiles) {"Local saves changed after confirmation"}
  if(retry==null)require(graph.commits==comparison.graph?.commits) {"Cloud history changed after confirmation"}
  // A deletion retry is bound to the already published replacement, never new local progress.
  // Reject before validation callbacks or uploadWithGraph can initiate any further cloud writes.
  if(retry!=null)require(graph.commits[retry.replacement]?.snapshot?.files==fresh.localFiles) {
   "Local saves changed while confirmed cloud deletion is pending"
  }
  validate(fresh)
  val existing=graph.findByContent(fresh.localFiles)
  val selection=if(retry==null)requireNotNull(cloudOverwriteSelection(graph,fresh.baselineId,fresh.localFiles)) {"No unambiguous confirmed cloud line"} else null
  if(selection!=null)require(graph.commits.getValue(selection.target).snapshot.id==selectedSnapshot) {"Confirmed cloud target changed"}
  val base=retry?.base ?: requireNotNull(selection).base
  require(base in graph.commits)
  if(existing==null)uploadWithGraph(fresh,graph,store,stopped,true)
  val after=repository.cachedGraph();after.requireSettled()
  val replacement=retry?.replacement ?: requireNotNull(after.findByContent(fresh.localFiles)).id
  require(after.commits[replacement]?.snapshot?.files==fresh.localFiles)
  val selected=selection?.target
  val plan=retry ?: deletion.prepare(store,after,base,requireNotNull(selected) {"Select one replaceable cloud line"},replacement)
  deletion.finish(store,plan,after,repository::forgetDeleted,local::forgetOverwrittenSnapshot)
  val complete=repository.cachedGraph();complete.requireObjectsVerified();complete.requireSettled();repository.setBaseline(replacement,complete)
  repository.clearPending();markDirectorySynced(complete.commits.getValue(replacement),"upload")
  return compareLocked(store,stopped,true)
 }

 /** Upload follows an explicit choice or the strict automatic gate, including a frozen retry. Never freeze empty saves. */
 fun onboardingUpload(comparison: OnboardingComparison, store: AppendStore, stopped: () -> Boolean): AppendPushResult = local.locked {
  val graph=revalidateComparison(comparison,store,stopped,allowPending=true)
  val pending=repository.pending()
  val pendingCoversTips=pending!=null && (pending.commit.parents.toSet()==graph.tips || matchingUpload(graph,pending.commit.snapshot.files)!=null)
  if(!pendingCoversTips && uploadNeedsOverwrite(graph,comparison.baselineId,comparison.localFiles))
   return@locked AppendPushResult.Diverged(preserveConflict(graph,store,stopped))
  uploadWithGraph(comparison,graph,store,stopped,false)
 }
 /** Fresh proof is acquired and consumed under one physical-root lock, never supplied by UI. */
 internal fun uploadFresh(comparison:OnboardingComparison,store:AppendStore,stopped:()->Boolean,beforePublish:(OnboardingComparison,Map<String,VerifiedSnapshot>)->Unit):Pair<AppendPushResult,OnboardingComparison> = readLocked(store) {
  require(comparison.owner===comparisonOwner)
  val proofs=linkedMapOf<String,VerifiedSnapshot>()
  // Normal append has one tip. Bound retention; branch/conflict paths keep ordinary validation.
  val retain=comparison.observedTips.takeIf {it.size==1}.orEmpty()
  val normal=repository.pending()==null
  val fresh=DiagnosticTiming.measure(DiagnosticPhase.UPLOAD_PREFLIGHT) {compareLocked(store,stopped,false,retain,proofs,uploadMetadata=normal)}
  val graph=requireNotNull(fresh.graph);graph.requireUploadVerified();graph.requireSettled()
  beforePublish(fresh,proofs)
  val pending=repository.pending()
  val pendingCoversTips=pending!=null && (pending.commit.parents.toSet()==graph.tips || matchingUpload(graph,pending.commit.snapshot.files)!=null)
  if(!pendingCoversTips && uploadNeedsOverwrite(graph,fresh.baselineId,fresh.localFiles)) {
   val conflict=if(normal)AppendConflict(graph.tips,emptyList(),fresh.localFiles,fresh.baselineId,
    cloudOverwriteSelection(graph,fresh.baselineId,fresh.localFiles)?.let {graph.commits.getValue(it.target).snapshot.id})
    else preserveConflict(graph,store,stopped)
   return@readLocked AppendPushResult.Diverged(conflict) to fresh
  }
  val result=uploadWithGraph(fresh,graph,store,stopped,false,optimistic=true)
  val after=if(result is AppendPushResult.Success)graph.copy(commits=graph.commits+(result.commit.id to result.commit)) else graph
  after.requireUploadVerified();after.requireSettled()
  val files=uploadReceiptFiles() ?: currentFiles();val baseline=repository.baseline()
  val lineage=when {baseline==null->OnboardingLineage.NONE;after.tips.size>1->OnboardingLineage.BRANCHED;after.tips.singleOrNull()==baseline->OnboardingLineage.SAME_TIP;after.tips.singleOrNull()?.let {after.descendsFrom(it,baseline)}==true->OnboardingLineage.ANCESTOR_OF_TIP;else->OnboardingLineage.BLOCKED}
  val kind=when {after.tips.size>1->OnboardingKind.BRANCHED;after.tips.isEmpty()->if(files.isEmpty())OnboardingKind.BOTH_EMPTY else OnboardingKind.LOCAL_ONLY;files.isEmpty()->OnboardingKind.REMOTE_ONLY;files==after.commits.getValue(after.tips.single()).snapshot.files->OnboardingKind.IDENTICAL;else->OnboardingKind.DIFFERENT}
  // Presentation uses a metadata view of fully verified operation evidence, not another network scan.
  result to OnboardingComparison(kind,files,baseline,lineage,emptyList(),after.tips,owner=comparisonOwner,
   graph=after.copy(verification=RemoteVerification.METADATA_ONLY),localSource=LocalObservationSource.RESTORE_RECEIPT)
 }
 private fun uploadWithGraph(comparison:OnboardingComparison,graph:CommitGraph,store:AppendStore,stopped:()->Boolean,uploadDelta:Boolean,optimistic:Boolean=false):AppendPushResult {
  val pending=repository.pending()
  val files=pending?.commit?.snapshot?.files ?: comparison.localFiles
  matchingUpload(graph,files)?.let {return reuseUpload(it,graph,pending)}
  // Historical identical content is adopted, never republished. Selected-object
  // evidence does not upgrade the rest of the metadata-only graph.
  if(pending==null) graph.findByContent(files)?.let { existing ->
   val selected=repository.downloadVerified(store,existing)
   repository.setSelectedBaseline(existing,CommitMetadataGraph(graph.copy(verification=RemoteVerification.METADATA_ONLY)),selected)
   markDirectorySynced(existing,"upload")
   return AppendPushResult.Success(existing,currentFiles()!=existing.snapshot.files,graph.tips.singleOrNull()!=existing.id,reused=true)
  }
  var verifiedPlan:VerifiedUploadPlan?=null
  val plan = pending ?: run {
   require(comparison.localFiles.isNotEmpty()) { CoreSessionMessages.THERE_ARE_NO_LOCAL_SAVES_AN_EMPTY_SNAPSHOT_CANNOT_BE.text() }
   if(optimistic) {
    val captured=DiagnosticTiming.measure(DiagnosticPhase.UPLOAD_CAPTURE) {SafePaths.bufferedUploadWrites {local.captureVerified(stopped)}}
    require(captured.snapshot.manifest.files==comparison.localFiles) {CoreSessionMessages.LOCAL_SAVES_CHANGED_AFTER_COMPARISON_COMPARE_AGAIN.text()}
    verifiedPlan=DiagnosticTiming.measure(DiagnosticPhase.UPLOAD_PREPARE_PLAN) {repository.prepareVerified(captured,listOfNotNull(comparison.baselineId),operation=local.operationMetadata(),persist=false)}
    return@run requireNotNull(verifiedPlan).plan
   }
   val captured = DiagnosticTiming.measure(DiagnosticPhase.UPLOAD_CAPTURE) {captureContent(stopped)}
   require(captured.manifest.files == comparison.localFiles) { CoreSessionMessages.LOCAL_SAVES_CHANGED_AFTER_COMPARISON_COMPARE_AGAIN.text() }
   if(!optimistic)SaveStabilityGuard(root, adapter, stopped, captureQuietMillis).requireUnchanged(requireNotNull(comparison.fingerprint))
   repository.prepare(captured, listOfNotNull(comparison.baselineId), operation = local.operationMetadata())
  }
  try {
   return if(optimistic && pending==null)SafePaths.bufferedUploadWrites {
    publish(plan,store,stopped,graph,uploadDelta,true,acknowledgeNew=true,verifiedPlan=verifiedPlan)
   } else publish(plan,store,stopped,graph,uploadDelta,optimistic,acknowledgeNew=false,verifiedPlan=verifiedPlan)
  }catch(error:Exception) {
   // Slow failure path preserves uncertain publication for explicit reconciliation only.
   if(verifiedPlan?.persisted==false)try{repository.preserveFailedUpload(plan)}catch(save:Exception){error.addSuppressed(save)}
   throw error
  }
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
