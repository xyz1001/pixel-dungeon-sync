package pdsync.android.app

import pdsync.i18n.CoreMessages
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.*
import java.util.Collections
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import pdsync.*

/** Platform-neutral sync facade. The helper owns actual Android files; core owns history. */
class AndroidSyncFacade internal constructor(
 workspaceRoot: Path, private val connection: AndroidConnection, private val bridge: AndroidGameBridge,
 val identity: AndroidGameIdentity, private val suppliedStore: AppendStore?, private val quietMillis: Long,
 private val controlledSessions:AndroidControlledSessions=sharedControlledSessions,
 private val beforeRequest:WebDavRequestCheck=WebDavRequestCheck {}
) {
 constructor(root:Path, connection:AndroidConnection, bridge:AndroidGameBridge, identity:AndroidGameIdentity):
  this(root,connection,bridge,identity,null,0)
 constructor(root:Path, connection:AndroidConnection, bridge:AndroidGameBridge, identity:AndroidGameIdentity, beforeRequest:WebDavRequestCheck):
  this(root,connection,bridge,identity,null,0,sharedControlledSessions,beforeRequest)
 private val validatedConnection=AppConfig(endpoint=connection.endpoint,profile=connection.profile,username=connection.username).validated()
 private val validatedStateConnection=validatedConnection.copy(endpoint=connection.stateEndpoint).validated()
 private val adapter=AndroidGameAdapter(identity)
 private val scope=hash(listOf(AndroidGameIdentity.LOCAL_STATE_GENERATION,validatedStateConnection.endpoint,validatedConnection.profile,connection.username,identity.packageName,identity.originalSourceIdentity,identity.namespace).joinToString("\n").encodeToByteArray())
 private val base=SafePaths.checked(workspaceRoot).also { Files.createDirectories(it) }
 private val workspace=SafePaths.checked(base.resolve("connections").resolve(scope)).also { Files.createDirectories(it) }
 private val mirror=SafePaths.checked(workspace.resolve("mirror")).also { Files.createDirectories(it) }
 private val state=workspace.resolve("state")
 private val scratch=SafePaths.checked(workspace.resolve("transfers")).also { Files.createDirectories(it) }
 private val journals=SafePaths.checked(base.resolve("restore-journals").resolve(AndroidGameIdentity.LOCAL_STATE_GENERATION)).also { Files.createDirectories(it) }
 private val journalPath=journals.resolve("${identity.originalSourceIdentity}.json")
 private val owner=Any()
 private val config=validatedStateConnection.copy(saveRoot=mirror.toString(),targetGame="android-package:${identity.packageName}")
 private var pinnedTarget:AndroidGameTarget?=null
 private val versions=AndroidProfileVersions(identity,adapter,::verifiedTarget)
 private val store:AppendStore by lazy {
  val delegate=suppliedStore ?: AppendOnlyWebDav(validatedConnection.endpoint,adapter.id,config.profile,connection.username,connection.password,beforeRequest=beforeRequest)
  object:AppendStore by delegate {
   override fun writeObject(id:String,bytes:ByteArray) { verifiedTarget();delegate.writeObject(id,bytes) }
   override fun writeCommit(id:String,bytes:ByteArray) { verifiedTarget();delegate.writeCommit(id,bytes) }
  }
 }
 private val session by lazy { AppendProfileSession(config,state,adapter,versions,quietMillis) { "Android" } }
 private val mirrorStopped:()->Boolean={verifiedTarget();true}

 class ConnectionStatus(val isSuccess:Boolean,val message:String,val hasRemote:Boolean)
 class Version(val snapshotId:String,val commitId:String?,val displayName:String,val versionSummary:String,val fileCount:Int,val capturedAt:String?,val deviceLabel:String?,val isTip:Boolean,val reason:String) {
  override fun toString()=CoreMessages.ANDROID_1_S_2_S_FILES_3_S.text(displayName, fileCount, versionSummary)
 }
 class Inspection internal constructor(internal val owner:Any,internal val comparison:OnboardingComparison?,val kind:String,val message:String,val localFileCount:Int,remoteVersions:List<Version>,val isRetryablePending:Boolean,val canAdoptRemote:Boolean,val needsRestoreRecovery:Boolean,internal val blockReason:SyncBlockReason?=comparison?.blockReason,internal val history:SyncHistory?=null) {
  val remoteVersions=immutable(remoteVersions)
  /** Closed, non-identifying reason for diagnostic adapters; never expose the comparison payload. */
  fun diagnosticBlockReason():String? = blockReason?.name
  val canAutoUpload get()=!needsRestoreRecovery && comparison?.let(AutomaticUploadEligibility::allows)==true
  val canCompleteWithoutTransfer get()=!needsRestoreRecovery && comparison?.let { it.kind==OnboardingKind.BOTH_EMPTY || it.kind==OnboardingKind.IDENTICAL && it.baselineLineage==OnboardingLineage.SAME_TIP }==true
  val canKeepLocalProgress get()=!needsRestoreRecovery && comparison?.let { it.kind==OnboardingKind.DIFFERENT && it.baselineLineage==OnboardingLineage.SAME_TIP && it.baselineId!=null && it.observedTips==setOf(it.baselineId) && !it.retryablePending }==true
 }
 class OperationResult(val message:String,val isConflict:Boolean,val isPending:Boolean,val snapshotId:String?)
 class AutoUploadResult(val status:String,val message:String,val snapshotId:String?,val needsUserChoice:Boolean,reason:SyncBlockReason?=null) {
  val reasonCode=reason?.name
 }
 class MergePreview(val isAllowed:Boolean,val message:String,val slotCount:Int,decisions:List<String>) { val decisions=immutable(decisions) }
 class LocalPreview(val fileCount:Int,val summary:SaveSummary?,val message:String)
 class ControlledObservation internal constructor(internal val proof:AndroidControlledSessions.ObservationProof?)
 @Serializable private data class RestoreJournal(val schema:Int=2,val profileScope:String,val gameSourceIdentity:String,val gamePackage:String,val installedApkSha256:String,val sourceApkSha256:String,val expectedBefore:Map<String,String>,val expectedAfter:Map<String,String>,val action:String,val ready:Boolean=false)

 fun hasUnresolvedRestore()=locked { anyUnresolvedRestore() }
 fun testConnection()=connectionCheck(false)
 fun connect()=connectionCheck(true)
 private fun connectionCheck(create:Boolean)=locked {
  if(suppliedStore!=null) {
   require(!create){CoreMessages.ANDROID_NAMESPACE_CREATION_REQUIRES_THE_WEBDAV_TRANSPORT.text()}
   val graph=AppendRepository(state.resolve("connection-probe"),adapter,config.profile).discover(store).also {it.requireSettled()}
   ConnectionStatus(true,CoreMessages.ANDROID_CONNECTION_VERIFIED.text(),graph.commits.isNotEmpty())
  } else {
   val transportConfig=config.copy(endpoint=validatedConnection.endpoint)
   val setup=ConnectionSetup(transportConfig,adapter.id,beforeRequest=beforeRequest);setup.update(transportConfig,connection.password)
   when(val result=if(create)setup.connect() else setup.testConnection()) {
    is ConnectionTestResult.Success -> ConnectionStatus(true,if(create)CoreMessages.ANDROID_WEBDAV_CONNECTED_THE_SYNC_FOLDERS_ARE_READY.text() else CoreMessages.ANDROID_WEBDAV_CONNECTION_VERIFIED.text(),result.hasRemote)
    is ConnectionTestResult.Failure -> ConnectionStatus(false,result.message,false)
   }
  }
 }
 /** Freeze only already-known identities before Android starts the selected game. */
 fun beginControlledSession():String=locked {
  requireNoRecovery();controlledSessions.cancel(base)
  refreshMirror()
  val snapshot=capture(mirror);val sidecar=(DirectoryMetadataStore(mirror).read() as? SidecarRead.Valid)?.value
  val runs=sidecar?.runs?.filterValues {it.runUUID!=null}.orEmpty()
  // Stale/unknown metadata is not re-confirmed by starting the game.
  val known=runs.filter {(slot,run)->runContentHashes(snapshot.manifest.files)[slot]==run.contentSha256}
  controlledSessions.begin(base,workspace,verifiedTarget(),snapshot.manifest.files,sidecar?.runs.orEmpty(),Snapshots.verify(snapshot,adapter).filterKeys {name->name.substringBefore('/') in known})
 }
 fun observeControlledSession(token:String,reliable:Boolean,foreground:Boolean) {controlledSessions.observe(base,workspace,token,reliable,foreground)}
 fun beginControlledObservation(token:String)=ControlledObservation(controlledSessions.beginObservation(base,workspace,token))
 fun observeControlledSession(observation:ControlledObservation,reliable:Boolean,foreground:Boolean) {controlledSessions.observe(base,workspace,observation.proof,reliable,foreground)}
 fun cancelControlledSession(token:String) {controlledSessions.cancel(base,token)}
 fun inspect()=locked { inspectLocked() }
 private fun inspectLocked():Inspection {
  if(anyUnresolvedRestore()) return Inspection(owner,null,"BLOCKED",CoreMessages.ANDROID_A_GAME_RESTORE_NEEDS_VERIFICATION_OR_RECOVERY_UPLOAD_IS_BLOCKED_UNTIL_IT_IS_RESOLVED.text(),currentFiles().size,emptyList(),false,false,true,SyncBlockReason.RESTORE_RECOVERY_REQUIRED)
  refreshMirror()
  val comparison=session.compareOnboarding(store,mirrorStopped)
  val remote=comparison.remoteEntries.map { version(it.commit,true,it.entry.reason) }
  return try {
   comparison.remoteEntries.forEach { versions.detect(session.loadSnapshot(it.commit.snapshot.id)) }
   // Capture the same verified observation for presentation. Rendering never re-exports files.
   val history=if(comparison.kind==OnboardingKind.BLOCKED)null else session.syncHistory()
   Inspection(owner,comparison,comparison.kind.name,comparison.blocker ?: description(comparison),currentFiles().size,remote,comparison.retryablePending,comparison.canAdoptRemote,false,history=history)
  } catch(e:UnsupportedAndroidSaveFormatException) {
   Inspection(owner,null,"BLOCKED",e.message ?: CoreMessages.ANDROID_UNSUPPORTED_CLOUD_SAVE_FORMAT.text(),currentFiles().size,remote,false,false,false,SyncBlockReason.UNSUPPORTED_SAVE_FORMAT)
  }
 }
 fun autoUpload():AutoUploadResult=locked {
  try {
   var inspection=inspectLocked()
   var comparison=inspection.comparison ?: return@locked autoBlocked("SKIPPED_UNCERTAIN",inspection.blockReason ?: SyncBlockReason.COMPARISON_FAILED,true)
   if(unchangedDescendantTip(comparison)!=null) {
    checkActualUnchanged(comparison.localFiles)
    if(session.advanceUnchangedBaseline(comparison,store,mirrorStopped)) {
     inspection=inspectLocked();comparison=inspection.comparison ?: return@locked autoBlocked("SKIPPED_UNCERTAIN",inspection.blockReason ?: SyncBlockReason.COMPARISON_FAILED,true)
    }
   }
   if(comparison.kind in setOf(OnboardingKind.IDENTICAL,OnboardingKind.BOTH_EMPTY)) return@locked AutoUploadResult("UNCHANGED",CoreMessages.ANDROID_NO_CHANGED_LOCAL_SNAPSHOT_NEEDS_UPLOADING.text(),null,false)
   if(!AutomaticUploadEligibility.allows(comparison)) {
    val choice=comparison.observedTips.size>1 || comparison.kind==OnboardingKind.DIFFERENT || comparison.retryablePending
    val reason=comparison.blockReason ?: when {choice->SyncBlockReason.HISTORY_CONFLICT;comparison.kind==OnboardingKind.REMOTE_ONLY->SyncBlockReason.REMOTE_ONLY;comparison.baselineLineage==OnboardingLineage.BLOCKED->SyncBlockReason.BASELINE_UNVERIFIED;else->SyncBlockReason.COMPARISON_FAILED}
    return@locked autoBlocked(if(choice)"SKIPPED_CONFLICT" else "SKIPPED_UNCERTAIN",reason,choice || comparison.kind==OnboardingKind.REMOTE_ONLY)
   }
   checkActualUnchanged(comparison.localFiles)
   when(val result=session.onboardingUpload(comparison,store,mirrorStopped)) {
    is AppendPushResult.Success -> AutoUploadResult("UPLOADED",if(result.remoteAhead)CoreMessages.ANDROID_SNAPSHOT_UPLOADED_BUT_THE_CLOUD_ADVANCED_REVIEW_BEFORE_RESTORING.text() else CoreMessages.ANDROID_BACKGROUND_SNAPSHOT_UPLOADED_AND_VERIFIED.text(),result.commit.snapshot.id,result.remoteAhead)
    is AppendPushResult.Diverged -> AutoUploadResult("SKIPPED_CONFLICT",CoreMessages.ANDROID_A_REMOTE_BRANCH_ARRIVED_DURING_UPLOAD_BOTH_IMMUTABLE_BRANCHES_ARE_PRESERVED_CHOOSE_EXPLICI.text(),result.conflict.branches.find {it.commit.id==result.conflict.publishedLocalCommit}?.snapshot?.manifest?.id,true,SyncBlockReason.HISTORY_CONFLICT)
   }
  } catch(e:Exception) {
   if(e is io.github.xyz1001.pdsync.android.OfficialSyncAccess.AccessException) throw e
   var reason=SyncBlockReason.fromFailure(e)
   if(e is UnsupportedAndroidSaveFormatException || e is AndroidTargetChangedException) return@locked autoBlocked("SKIPPED_UNCERTAIN",reason,true)
   if(e is InterruptedException)Thread.currentThread().interrupt()
   else if(!anyUnresolvedRestore()) {
    val observation=runCatching {session.compareOnboarding(store,mirrorStopped)}.getOrNull()
    if(observation!=null && !AutomaticUploadEligibility.allows(observation) && (observation.observedTips.size>1 || observation.kind==OnboardingKind.DIFFERENT || observation.retryablePending)) return@locked autoBlocked("SKIPPED_CONFLICT",observation.blockReason ?: SyncBlockReason.HISTORY_CONFLICT,true)
   }
   if(reason==SyncBlockReason.COMPARISON_FAILED)reason=SyncBlockReason.UPLOAD_NOT_CONFIRMED
   autoBlocked("RETRY_LATER",reason,false)
  }
 }
 private fun autoBlocked(status:String,reason:SyncBlockReason,needsChoice:Boolean)=AutoUploadResult(status,reason.safeMessage,null,needsChoice,reason)
 fun upload(inspection:Inspection)=locked { val comparison=checked(inspection,true);checkActualUnchanged(comparison.localFiles);pushResult(session.onboardingUpload(comparison,store,mirrorStopped)) }
 fun listVersions():List<Version> = locked {
  requireNoRecovery();verifiedTarget();session.readRemote(store)
  val history=session.syncHistory();val cloud=history.cloud.map {version(it,it.id in history.graph.tips,CoreMessages.ANDROID_VERIFIED_CLOUD_HISTORY.text())};val ids=cloud.map {it.snapshotId}.toSet()
  immutable(cloud+session.history().filter {it.manifest.id !in ids}.map {version(it.manifest,null,false,it.reason)})
 }
 fun restoreSelected(inspection:Inspection,snapshotId:String,allowUniqueReplacement:Boolean)=locked {
  val comparison=checked(inspection);checkActualUnchanged(comparison.localFiles)
  val history=session.syncHistory();val commit=history.graph.snapshotCommit(snapshotId)
  val snapshot=if(commit!=null)AppendRepository(session.directory.resolve("append-v2"),adapter,config.profile).download(store,commit) else session.loadSnapshot(snapshotId)
  versions.checkRestore(snapshot,config.targetGame)
  require(allowUniqueReplacement || comparison.localFiles.isEmpty() || history.currentProtected){CoreMessages.ANDROID_CURRENT_GAME_PROGRESS_HAS_NO_VERIFIED_CLOUD_COPY_EXPLICIT_REPLACEMENT_CONFIRMATION_IS_REQU.text()}
  var result:OnboardingRestoreResult?=null
  prepareGameRestore(comparison.localFiles,snapshot.manifest.files,"restore") {
   when {commit!=null && commit.id in comparison.observedTips -> result=session.onboardingRestore(comparison,snapshotId,store,mirrorStopped)
    commit!=null -> session.restoreCloudHistory(snapshotId,store,allowUniqueReplacement,comparison.localFiles,mirrorStopped)
    else -> session.restoreHistory(snapshotId,mirrorStopped)}
  }
  if(result==null) {
   // Historical restore only stages a frozen plan in core. Publish its first attempt here,
   // after the helper has applied and verified the actual game files and cleared recovery.
   // A fresh comparison binds the post-restore fingerprint; never reuse the pre-restore one.
   // Failure leaves that exact plan available for explicit retry, without restoring again.
   requireNoRecovery();checkActualUnchanged(snapshot.manifest.files)
   val restored=session.compareOnboarding(store,mirrorStopped)
   require(restored.retryablePending && restored.pendingCommit?.let {it.kind=="restore" && it.snapshot.files==snapshot.manifest.files}==true) {
    restored.blocker ?: CoreMessages.ANDROID_THE_FROZEN_HISTORICAL_RESTORE_PLAN_COULD_NOT_BE_VERIFIED_COMPARE_AGAIN_BEFORE_RETRYING.text()
   }
   return@locked pushResult(session.onboardingUpload(restored,store,mirrorStopped),CoreMessages.ANDROID_SELECTED_VERSION_RESTORED_VERIFIED_IN_THE_GAME_AND_PUBLISHED.text())
  }
  val published=result as? OnboardingRestoreResult.Published
  if(published?.result is AppendPushResult.Diverged)pushResult(published.result)
  else OperationResult(if(session.hasPending())CoreMessages.ANDROID_SELECTED_VERSION_RESTORED_AND_VERIFIED_IN_THE_GAME_A_RESTORE_REVISION_IS_PENDING_UPLOAD_PU.text() else CoreMessages.ANDROID_SELECTED_VERSION_RESTORED_AND_VERIFIED_IN_THE_GAME.text(),false,session.hasPending(),snapshotId)
 }
 /** Explicit caller-selected download mode, e.g. an authenticated expired official account.
  * Account eligibility is the caller's responsibility; transport errors never select this mode. */
 fun restoreSelectedReadOnly(inspection:Inspection,snapshotId:String,allowUniqueReplacement:Boolean)=locked {
  val comparison=checked(inspection);checkActualUnchanged(comparison.localFiles)
  val history=session.syncHistory();val commit=history.graph.snapshotCommit(snapshotId)
  val snapshot=if(commit!=null)AppendRepository(session.directory.resolve("append-v2"),adapter,config.profile).download(store,commit) else session.loadSnapshot(snapshotId)
  versions.checkRestore(snapshot,config.targetGame)
  require(allowUniqueReplacement || comparison.localFiles.isEmpty() || history.currentProtected){CoreMessages.ANDROID_CURRENT_GAME_PROGRESS_HAS_NO_VERIFIED_CLOUD_COPY_EXPLICIT_REPLACEMENT_CONFIRMATION_IS_REQU.text()}
  prepareGameRestore(comparison.localFiles,snapshot.manifest.files,"read-only-restore") {
   session.restoreSelectedReadOnly(comparison,snapshotId,store,allowUniqueReplacement,mirrorStopped)
  }
  requireNoRecovery();checkActualUnchanged(snapshot.manifest.files)
  val conflict=comparison.observedTips.size>1
  OperationResult(if(conflict)CoreMessages.ANDROID_SELECTED_VERSION_DOWNLOADED_RESTORED_AND_VERIFIED_IN_THE_GAME_CLOUD_BRANCHES_REMAIN_UNRESO.text()
   else CoreMessages.ANDROID_SELECTED_VERSION_DOWNLOADED_RESTORED_AND_VERIFIED_IN_THE_GAME.text(),conflict,false,snapshotId)
 }
 fun resolveConflict(inspection:Inspection,snapshotId:String)=locked {
  val comparison=checked(inspection);checkActualUnchanged(comparison.localFiles);val conflict=currentConflict(comparison)
  val branch=conflict.snapshotBranch(snapshotId) ?: error(CoreMessages.ANDROID_SELECT_A_SNAPSHOT_FROM_THE_CURRENTLY_OBSERVED_CONFLICT_BRANCHES.text())
  var result:AppendPushResult?=null
  prepareGameRestore(comparison.localFiles,branch.snapshot.manifest.files,"resolution") {result=session.resolve(conflict,snapshotId,store,mirrorStopped)}
  pushResult(requireNotNull(result),CoreMessages.ANDROID_BRANCH_CHOICE_APPLIED_TO_THE_GAME_AND_PUBLISHED.text())
 }
 fun previewMerge(inspection:Inspection)=locked {
  val comparison=checked(inspection);checkActualUnchanged(comparison.localFiles)
  try {versions.requireMerge();val result=session.previewOnboardingMerge(comparison,store,mirrorStopped);MergePreview(true,CoreMessages.ANDROID_SAFE_MERGE_IS_AVAILABLE_REVIEW_THE_DECISIONS_BEFORE_APPLYING.text(),result.slots,result.decisions)}
  catch(e:RunMergeException){MergePreview(false,e.message ?: CoreMessages.ANDROID_THIS_CONFLICT_CANNOT_BE_SAFELY_MERGED.text(),0,emptyList())}
 }
 fun mergeConflict(inspection:Inspection)=locked {
  val comparison=checked(inspection);checkActualUnchanged(comparison.localFiles);versions.requireMerge()
  val preview=session.previewOnboardingMerge(comparison,store,mirrorStopped);var result:AppendPushResult?=null
  prepareGameRestore(comparison.localFiles,preview.snapshot.manifest.files,"merge") {result=session.mergeCurrent(comparison,store,mirrorStopped)}
  pushResult(requireNotNull(result),CoreMessages.ANDROID_MERGED_GAME_SAVES_APPLIED_VERIFIED_AND_PUBLISHED.text())
 }
 fun adoptIdentical(inspection:Inspection)=locked {val c=checked(inspection);checkActualUnchanged(c.localFiles);OperationResult(CoreMessages.ANDROID_IDENTICAL_LOCAL_AND_CLOUD_SAVES_LINKED_NO_GAME_FILES_WERE_REPLACED.text(),false,false,session.adoptOnboardingRemote(c,store,mirrorStopped).snapshot.id)}
 fun confirmRunIdentity(inspection:Inspection,slot:String,sameRun:Boolean)=locked {val c=checked(inspection);checkActualUnchanged(c.localFiles);session.confirmRunIdentity(slot,c.localFiles,sameRun,mirrorStopped);OperationResult(CoreMessages.ANDROID_RUN_IDENTITY_RECORDED_COMPARE_AGAIN_BEFORE_MERGING.text(),false,session.hasPending(),null)}
 fun recoverRestore()=locked {val journal=readJournal();require(journal.ready){CoreMessages.ANDROID_RESTORE_PREPARATION_WAS_INTERRUPTED_ORIGINALS_AND_BACKUPS_ARE_RETAINED_EXPLICITLY_DISCARD.text()};require(currentFiles()==journal.expectedAfter){CoreMessages.ANDROID_THE_PREPARED_RESTORE_CHANGED_RECOVERY_IS_BLOCKED_BACKUPS_ARE_RETAINED.text()};applyPrepared(journal);OperationResult(CoreMessages.ANDROID_GAME_RESTORE_VERIFIED_ANY_FROZEN_UPLOAD_REMAINS_AVAILABLE_FOR_EXPLICIT_RETRY.text(),false,session.hasPending(),null)}
 fun discardInterruptedRestore()=locked {readJournal(false);freshExport {exported->val retained=Files.createDirectories(scratch.resolve("abandoned-${UUID.randomUUID()}"));copySaveFiles(mirror,retained);session.archivePending();replaceMirror(exported);clearCompletedJournal()};OperationResult(CoreMessages.ANDROID_INTERRUPTED_OPERATION_ARCHIVED_CURRENT_GAME_FILES_WERE_LEFT_UNCHANGED_COMPARE_AGAIN.text(),false,false,null)}
 fun archivePending()=locked {requireNoRecovery();session.archivePending()}
 fun previewLocal()=locked {requireNoRecovery();freshExport {exported->val count=capture(exported).manifest.files.size;LocalPreview(count,runCatching {SaveSummary.read(exported)}.getOrNull(),if(count==0)CoreMessages.ANDROID_THE_SELECTED_GAME_S_SAVE_DIRECTORY_IS_EMPTY.text() else CoreMessages.ANDROID_VERIFIED_1_S_SAVE_FILES_FOR_2_S.text(count, identity.displayName))}}
 /** Standalone refresh explicitly creates a new observation. Use overload for an existing UI. */
 fun historyPresentation():SyncHistory=locked {val inspection=inspectLocked();requireNotNull(inspection.history){inspection.message}}
 fun historyPresentation(inspection:Inspection):SyncHistory=locked {checked(inspection);requireNotNull(inspection.history){inspection.message}}
 fun completeOnboarding(inspection:Inspection)=locked {val c=checked(inspection);checkActualUnchanged(c.localFiles);if(inspection.canKeepLocalProgress)session.confirmLocalProgressReady(c,store,mirrorStopped) else session.confirmOnboardingReady(c,store,mirrorStopped)}
 fun renameCurrent(inspection:Inspection,name:String)=locked {checkActualUnchanged(checked(inspection).localFiles);session.renameCurrent(name,mirrorStopped)}
 fun renameHistory(snapshotId:String,name:String)=locked {requireNoRecovery();verifiedTarget();session.renameHistory(snapshotId,name)}

 private fun verifiedTarget():AndroidGameTarget {
  val target=bridge.targetGame()
  if(target.identity!=identity){controlledSessions.cancel(base);throw AndroidTargetChangedException(CoreMessages.ANDROID_SELECTED_GAME_IDENTITY_OR_CLOUD_BINDING_CHANGED_REOPEN_THAT_GAME_AND_COMPARE_AGAIN.text())}
  if(pinnedTarget!=null && pinnedTarget!=target){controlledSessions.cancel(base);throw AndroidTargetChangedException(CoreMessages.ANDROID_THE_SELECTED_INSTALLED_APK_CHANGED_DURING_THIS_SYNC_SESSION_REOPEN_THE_GAME_SELECTION_AND.text())}
  if(pinnedTarget==null)pinnedTarget=target
  return target
 }
 private fun checked(inspection:Inspection,allowPending:Boolean=false):OnboardingComparison {
  requireNoRecovery();require(inspection.owner===owner){CoreMessages.ANDROID_THIS_COMPARISON_BELONGS_TO_ANOTHER_CONNECTION_COMPARE_AGAIN.text()}
  val c=requireNotNull(inspection.comparison){CoreMessages.ANDROID_COMPLETE_A_SAFE_COMPARISON_FIRST.text()}
  require(c.kind!=OnboardingKind.BLOCKED || allowPending && c.retryablePending){inspection.message}
  require(currentFiles()==c.localFiles){CoreMessages.ANDROID_THE_LOCAL_MIRROR_CHANGED_COMPARE_AGAIN.text()};return c
 }
 private fun currentConflict(c:OnboardingComparison):AppendConflict {
  val conflict=session.inspectConflict(store,mirrorStopped) ?: error(CoreMessages.ANDROID_THERE_IS_NO_CURRENT_CLOUD_CONFLICT_COMPARE_AGAIN.text())
  require(conflict.observedTips==c.observedTips && conflict.observedLocalFiles==c.localFiles){CoreMessages.ANDROID_CLOUD_BRANCHES_OR_CURRENT_GAME_FILES_CHANGED_COMPARE_AGAIN_BEFORE_SELECTING_A_BRANCH.text()};return conflict
 }
 private fun pushResult(result:AppendPushResult,success:String=CoreMessages.ANDROID_SNAPSHOT_UPLOADED_AND_VERIFIED.text())=when(result) {
  is AppendPushResult.Diverged -> OperationResult(CoreMessages.ANDROID_BOTH_BRANCHES_ARE_PRESERVED_COMPARE_AGAIN_AND_EXPLICITLY_CHOOSE_A_BRANCH_OR_PREVIEW_A_SAFE.text(),true,session.hasPending(),null)
  is AppendPushResult.Success -> OperationResult(when {result.remoteAhead->CoreMessages.ANDROID_SNAPSHOT_IS_PRESERVED_BUT_ANOTHER_CLOUD_BRANCH_ADVANCED_COMPARE_AGAIN.text();result.localDirty->CoreMessages.ANDROID_SNAPSHOT_UPLOADED_NEWER_LOCAL_CHANGES_REMAIN_COMPARE_AGAIN.text();else->success},false,session.hasPending(),result.commit.snapshot.id)
 }
 private fun prepareGameRestore(before:Map<String,String>,after:Map<String,String>,action:String,stage:()->Unit) {
  val target=verifiedTarget();val journal=RestoreJournal(profileScope=scope,gameSourceIdentity=identity.originalSourceIdentity,gamePackage=identity.packageName,installedApkSha256=target.apkSha256,sourceApkSha256=target.sourceApkSha256,expectedBefore=before,expectedAfter=after,action=action)
  writeJournal(journal)
  try {
   stage()
   when(currentFiles()) {
    after -> {if(before==after){clearCompletedJournal();return};val ready=journal.copy(ready=true);writeJournal(ready);applyPrepared(ready)}
    before -> clearCompletedJournal()
    else -> error(CoreMessages.ANDROID_UNEXPECTED_STAGED_FILES_RECOVERY_IS_REQUIRED_GAME_FILES_WERE_NOT_CHANGED.text())
   }
  }catch(e:Exception){val coreRecovery=Files.exists(session.directory.resolve("append-v2/apply-required.txt"));if(currentFiles()==after && !coreRecovery)writeJournal(journal.copy(ready=true)) else if(currentFiles()==before && !coreRecovery)Files.deleteIfExists(journalPath);throw e}
 }
 private fun applyPrepared(journal:RestoreJournal) {
  val staged=versions.annotate(Snapshots.capture(mirror,adapter,config.profile,CoreMessages.ANDROID_ANDROID_STAGED_RESTORE.text(),null))
  require(staged.manifest.files==journal.expectedAfter);versions.checkRestore(staged,config.targetGame)
  val actual=freshExport {capture(it).manifest.files}
  if(actual!=journal.expectedAfter) {
   require(actual==journal.expectedBefore){CoreMessages.ANDROID_GAME_PROGRESS_CHANGED_AFTER_CONFIRMATION_RECOVERY_WILL_NOT_OVERWRITE_IT_BACKUPS_ARE_RETAIN.text()}
   val temp=Files.createTempDirectory(scratch,"restore-")
   try {copySaveFiles(mirror,temp);verifiedTarget();bridge.restoreFrom(temp,immutableMap(journal.expectedBefore));verifiedTarget()}finally{deleteTree(temp)}
  }
  require(freshExport {capture(it).manifest.files}==journal.expectedAfter){CoreMessages.ANDROID_THE_HELPER_RESTORE_COULD_NOT_BE_VERIFIED_UPLOAD_REMAINS_BLOCKED_RECOVER_OR_INSPECT_RETAINE.text()}
  clearCompletedJournal()
 }
 private fun readJournal(requireOriginalBuild:Boolean=true):RestoreJournal {
  require(Files.exists(journalPath)){CoreMessages.ANDROID_RECOVERY_BELONGS_TO_ANOTHER_GAME_OR_AN_OLDER_INSTALLATION_REOPEN_THAT_ORIGINAL_SELECTION_T.text()}
  val target=verifiedTarget();val text=SafePaths.boundedRead(journalPath,2*1024*1024).decodeToString(throwOnInvalidSequence=true);requireUniqueJsonKeys(text)
  val journal=json.decodeFromString<RestoreJournal>(text)
  require(journal.schema==2 && journal.profileScope==scope && journal.gameSourceIdentity==identity.originalSourceIdentity && journal.gamePackage==identity.packageName){CoreMessages.ANDROID_THE_INTERRUPTED_RESTORE_BELONGS_TO_ANOTHER_GAME_OR_WEBDAV_CONNECTION_REOPEN_THAT_SELECTION.text()}
  require(!requireOriginalBuild || target.apkSha256==journal.installedApkSha256 && target.sourceApkSha256==journal.sourceApkSha256){CoreMessages.ANDROID_THE_INSTALLED_GAME_BUILD_CHANGED_DURING_RESTORE_RECOVERY_REOPEN_THE_ORIGINAL_BUILD_THE_REC.text()}
  contentSha256(journal.expectedBefore);contentSha256(journal.expectedAfter);require(journal.expectedAfter.isNotEmpty());return journal
 }
 private fun writeJournal(journal:RestoreJournal) {val bytes=json.encodeToString(journal).encodeToByteArray();require(bytes.size<=2*1024*1024){CoreMessages.ANDROID_RESTORE_RECOVERY_METADATA_EXCEEDS_THE_SUPPORTED_SIZE_NO_GAME_FILES_WERE_CHANGED.text()};SafePaths.atomicWrite(journalPath,bytes)}
 private fun clearCompletedJournal(){Files.delete(journalPath)}
 private fun requireNoRecovery(){require(!anyUnresolvedRestore()){ CoreMessages.ANDROID_A_GAME_RESTORE_NEEDS_RECOVERY_BEFORE_ANOTHER_SYNC_ACTION_REOPEN_THE_ORIGINAL_GAME_AND_CONN.text() }}
 private fun anyUnresolvedRestore()=hasUnresolvedRestore(base)
 private fun checkActualUnchanged(expected:Map<String,String>)=freshExport {if(capture(it).manifest.files!=expected)throw AndroidSaveChangedException(CoreMessages.ANDROID_ACTUAL_GAME_PROGRESS_CHANGED_SINCE_COMPARISON_COMPARE_AGAIN_NO_GAME_FILES_WERE_REPLACED.text())}
 private fun capture(root:Path)=Snapshots.capture(root,adapter,config.profile,CoreMessages.ANDROID_ANDROID_SAVE_EXPORT.text(),null)
 private fun currentFiles()=capture(mirror).manifest.files
 private fun <T> freshExport(block:(Path)->T):T {val temp=Files.createTempDirectory(scratch,"export-");try {verifiedTarget();bridge.exportTo(temp);verifiedTarget();validateExport(temp);val snapshot=capture(temp);Snapshots.verify(snapshot,adapter);versions.detect(snapshot);return block(temp)}finally{deleteTree(temp)}}
 private fun validateExport(root:Path) {var count=0;Files.walk(root).use {paths->paths.forEach {p->require(++count<=16384){CoreMessages.ANDROID_BRIDGE_EXPORT_HAS_TOO_MANY_ENTRIES.text()};require(!Files.isSymbolicLink(p)){CoreMessages.ANDROID_BRIDGE_EXPORT_CONTAINS_A_SYMBOLIC_LINK.text()};if(p!=root){val name=root.relativize(p).toString().replace('\\','/');require(adapter.includes(name)&&!isDirectoryMetadata(name)){CoreMessages.ANDROID_BRIDGE_EXPORTED_AN_UNRECOGNIZED_SAVE_ENTRY.text()};require(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))}}}}
 private fun refreshMirror() {
  val proof=controlledSessions.beginExport(base,workspace)
  try {freshExport {replaceMirror(it,proof)}}finally{controlledSessions.abandonExport(proof)}
 }
 private fun replaceMirror(exported:Path,proof:AndroidControlledSessions.ExportProof?=null) {
  // A fresh verified helper export with identical complete bytes is a filesystem no-op.
  // Do not replace directory identities/timestamps or the directory metadata sidecar.
  val before=currentFiles();val snapshot=capture(exported)
  val metadata=DirectoryMetadataStore(mirror);val old=(metadata.read() as? SidecarRead.Valid)?.value
  val continued=controlledSessions.consume(proof,base,workspace,verifiedTarget(),before,old?.runs.orEmpty(),snapshot.manifest.files,Snapshots.verify(snapshot,adapter))
  if(before==snapshot.manifest.files)return
  Files.walk(mirror).use {paths->paths.sorted(Comparator.reverseOrder()).forEach {p->if(p!=mirror){val name=mirror.relativize(p).toString().replace('\\','/');if(adapter.includes(name)&&!isDirectoryMetadata(name))Files.delete(p)}}}
  copySaveFiles(exported,mirror)
  if(old!=null && continued.isNotEmpty()) {
   val observed=metadata.observe(snapshot.manifest.files,initialImport=false,persist=false)
   metadata.write(old.copy(currentContentSha256=observed.contentSha256,runs=observed.runs+continued))
  }
 }
 private fun copySaveFiles(source:Path,destination:Path) {val snapshot=capture(source);Snapshots.verify(snapshot,adapter).forEach {(name,bytes)->val path=SafePaths.checked(destination.resolve(name));Files.createDirectories(path.parent);Files.write(path,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);Files.setLastModifiedTime(path,Files.getLastModifiedTime(source.resolve(name),LinkOption.NOFOLLOW_LINKS))};require(capture(destination).manifest.files==snapshot.manifest.files){CoreMessages.ANDROID_STAGING_COPY_FAILED_CONTENT_VERIFICATION.text()}}
 private fun <T> locked(action:()->T):T=synchronized(this) {FileChannel.open(SafePaths.checked(base.resolve("android-facade.lock")),StandardOpenOption.CREATE,StandardOpenOption.WRITE).use {channel->val lock=try {channel.tryLock()}catch(_:OverlappingFileLockException){null};checkNotNull(lock){CoreMessages.ANDROID_ANOTHER_ANDROID_SYNC_OPERATION_IS_RUNNING_WAIT_FOR_IT_TO_FINISH.text()};lock.use {action()}}}
 companion object {
  private val sharedControlledSessions=AndroidControlledSessions()
  @JvmStatic fun cancelControlledSession(root:Path,token:String){sharedControlledSessions.cancel(SafePaths.checked(root),token)}
  @JvmStatic fun hasUnresolvedRestore(root:Path):Boolean {val base=SafePaths.checked(root);val dir=SafePaths.checked(base.resolve("restore-journals").resolve(AndroidGameIdentity.LOCAL_STATE_GENERATION));if(!Files.exists(dir,LinkOption.NOFOLLOW_LINKS))return false;require(Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS)){CoreMessages.ANDROID_RESTORE_RECOVERY_STORAGE_IS_DAMAGED.text()};return Files.list(dir).use {it.anyMatch {p->p.fileName.toString().endsWith(".json")}}}
  private fun <T> immutable(values:List<T>):List<T> = Collections.unmodifiableList(ArrayList(values))
  private fun <K,V> immutableMap(values:Map<K,V>):Map<K,V> = Collections.unmodifiableMap(LinkedHashMap(values))
  private fun deleteTree(root:Path){if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return;Files.walk(root).use {it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)}}
  private fun description(c:OnboardingComparison)=when(c.kind) {OnboardingKind.BOTH_EMPTY->CoreMessages.ANDROID_NO_LOCAL_OR_CLOUD_SAVES_WERE_FOUND.text();OnboardingKind.LOCAL_ONLY->CoreMessages.ANDROID_ONLY_THIS_DEVICE_HAS_SAVES_UPLOAD_PRESERVES_THEM_IN_THE_CLOUD.text();OnboardingKind.REMOTE_ONLY->CoreMessages.ANDROID_CLOUD_SAVES_ARE_AVAILABLE_THE_LOCAL_GAME_SAVE_DIRECTORY_IS_EMPTY.text();OnboardingKind.IDENTICAL->CoreMessages.ANDROID_LOCAL_AND_CLOUD_SAVE_BYTES_MATCH.text();OnboardingKind.DIFFERENT->if(c.baselineLineage==OnboardingLineage.SAME_TIP)CoreMessages.ANDROID_LOCAL_PROGRESS_IS_AHEAD_OF_THE_SHARED_BASELINE_AUTOMATIC_UPLOAD_CAN_CONTINUE.text() else CoreMessages.ANDROID_LOCAL_AND_CLOUD_SAVES_DIFFER_REVIEW_VERSIONS_BEFORE_RESTORING_OR_MERGING.text();OnboardingKind.BRANCHED->CoreMessages.ANDROID_MULTIPLE_CLOUD_BRANCHES_EXIST_EACH_IS_PRESERVED_CHOOSE_EXPLICITLY_OR_PREVIEW_A_SAFE_MERGE.text();OnboardingKind.BLOCKED->c.blocker ?: "Comparison is blocked."}
  private fun version(commit:AppendCommit,tip:Boolean,reason:String)=version(commit.snapshot,commit.id,tip,reason)
  private fun version(m:Manifest,commitId:String?,tip:Boolean,reason:String)=Version(m.id,commitId,m.directoryMetadata?.displayName?.takeUnless(String::isBlank) ?: "Snapshot ${m.id.take(8)}",m.saveVersions?.summary() ?: m.version,m.files.size,m.metadata?.capturedAt,m.metadata?.device?.displayName,tip,reason)
 }
}

class AndroidTargetChangedException(message:String):IllegalStateException(message),ReasonedSyncFailure {override val syncBlockReason get()=SyncBlockReason.TARGET_CHANGED}
private class AndroidSaveChangedException(message:String):IllegalArgumentException(message),ReasonedSyncFailure {override val syncBlockReason get()=SyncBlockReason.SAVE_NOT_STABLE}
