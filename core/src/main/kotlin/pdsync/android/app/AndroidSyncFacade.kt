package pdsync.android.app

import pdsync.i18n.CoreMessages
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.*
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import pdsync.*

/** Platform-neutral sync facade. The helper owns actual Android files; core owns history. */
class AndroidSyncFacade internal constructor(
 workspaceRoot: Path, private val connection: AndroidConnection, private val bridge: AndroidGameBridge,
 val identity: AndroidGameIdentity, private val suppliedStore: AppendStore?,
 private val controlledSessions:AndroidControlledSessions=sharedControlledSessions,
 private val beforeRequest:WebDavRequestCheck=WebDavRequestCheck {},
  private val suppliedSimpleStore:SimpleSaveStore?=null,
  private val deviceLabel:()->String?={"Android"}
) {
  constructor(root:Path, connection:AndroidConnection, bridge:AndroidGameBridge, identity:AndroidGameIdentity):
   this(root,connection,bridge,identity,null)
  constructor(root:Path, connection:AndroidConnection, bridge:AndroidGameBridge, identity:AndroidGameIdentity, deviceLabel:String):
   this(root,connection,bridge,identity,null,deviceLabel={deviceLabel})
  constructor(root:Path, connection:AndroidConnection, bridge:AndroidGameBridge, identity:AndroidGameIdentity, beforeRequest:WebDavRequestCheck):
   this(root,connection,bridge,identity,null,sharedControlledSessions,beforeRequest)
  constructor(root:Path, connection:AndroidConnection, bridge:AndroidGameBridge, identity:AndroidGameIdentity, beforeRequest:WebDavRequestCheck, deviceLabel:String):
   this(root,connection,bridge,identity,null,sharedControlledSessions,beforeRequest,deviceLabel={deviceLabel})
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
 private val mutationEpoch=sharedMutationEpochs.computeIfAbsent(base) { AtomicLong() }
 private val config=validatedStateConnection.copy(saveRoot=mirror.toString(),targetGame="android-package:${identity.packageName}")
 private var pinnedTarget:AndroidGameTarget?=null
 private val versions=AndroidProfileVersions(identity,adapter,::verifiedTarget)
 private val store:AppendStore by lazy {
  val delegate=suppliedStore ?: AppendOnlyWebDav(validatedConnection.endpoint,adapter.id,config.profile,connection.username,connection.password,beforeRequest=beforeRequest)
  object:AppendStore by delegate {
   override fun writeObject(id:String,bytes:ByteArray) { verifiedTarget();delegate.writeObject(id,bytes) }
   override fun writeCommit(id:String,bytes:ByteArray) { verifiedTarget();delegate.writeCommit(id,bytes) }
   override fun deleteCommit(id:String) {verifiedTarget();delegate.deleteCommit(id)}
   override fun deleteObject(id:String) {verifiedTarget();delegate.deleteObject(id)}
   override fun writeDeletion(id:String,bytes:ByteArray) {verifiedTarget();delegate.writeDeletion(id,bytes)}
  }
 }
 private var simpleCurrentName:String?=null
 private var simpleCurrentNameHash:String?=null
 private val simpleEnabled get()=suppliedStore==null || suppliedSimpleStore!=null
 private val simpleTransport by lazy {AppendOnlyWebDav(validatedConnection.endpoint,adapter.id,config.profile,connection.username,connection.password,beforeRequest=beforeRequest)}
 private val simpleStore by lazy {suppliedSimpleStore ?: simpleTransport.simpleStore()}
  private val simpleDeviceMetadata by lazy {DeviceMetadata(base,hostname=deviceLabel)}
  private val simpleSync by lazy {SimpleSaveSync(simpleStore,workspace.resolve("baseline-hash"),simpleDeviceMetadata::operation)}
  internal data class SimpleObservation(val files:Map<String,String>,val latest:String?,val nodes:List<SaveNode>,val summary:SaveSummary?,val localModifiedAt:String?)
 private val session by lazy { AppendProfileSession(config,state,adapter,versions) { "Android" } }
 private val mirrorStopped:()->Boolean={verifiedTarget();true}

 class ConnectionStatus(val isSuccess:Boolean,val message:String,val hasRemote:Boolean,private val diagnosticFailure:DiagnosticFailure=DiagnosticFailure.OTHER) {
  fun requireSuccess(failureMessage:String) {if(!isSuccess)throw ConnectionDiagnosticException(failureMessage,diagnosticFailure)}
 }
 class Version(val snapshotId:String,val commitId:String?,val displayName:String,val versionSummary:String,val fileCount:Int,val capturedAt:String?,val deviceLabel:String?,val isTip:Boolean,val reason:String) {
  override fun toString()=CoreMessages.ANDROID_1_S_2_S_FILES_3_S.text(displayName, fileCount, versionSummary)
 }
 class Inspection internal constructor(internal val owner:Any,internal val comparison:OnboardingComparison?,val kind:String,val message:String,val localFileCount:Int,remoteVersions:List<Version>,val isRetryablePending:Boolean,val canAdoptRemote:Boolean,val needsRestoreRecovery:Boolean,internal val blockReason:SyncBlockReason?=comparison?.blockReason,internal val history:SyncHistory?=null,internal val homeObservation:HomeReadContext?=null,internal val simpleObservation:SimpleObservation?=null) {
  val remoteVersions=immutable(remoteVersions)
  /** Bind overwrite to the sole non-local cloud tip; never choose an arbitrary sibling. */
  val overwriteTargetSnapshotId:String? get()=simpleObservation?.latest ?: comparison?.let { c ->
   c.graph?.let { g -> cloudOverwriteSelection(g,c.baselineId,c.localFiles)?.let {g.commits.getValue(it.target).snapshot.id} }
  }
  val isOverwriteConflict:Boolean get()=if(simpleObservation!=null) kind=="BRANCHED" else comparison?.let { c -> c.graph?.let {uploadNeedsOverwrite(it,c.baselineId,c.localFiles)} }==true
  /** Closed, non-identifying reason for diagnostic adapters; never expose the comparison payload. */
  fun diagnosticBlockReason():String? = blockReason?.name
  val isObjectVerified get()=simpleObservation!=null || comparison?.graph!=null && comparison.verification==RemoteVerification.OBJECTS_VERIFIED
  val canAutoUpload get()=isObjectVerified && !needsRestoreRecovery && comparison?.let(AutomaticUploadEligibility::allows)==true
  val canCompleteWithoutTransfer get()=if(simpleObservation!=null)kind in setOf("IDENTICAL","BOTH_EMPTY") else isObjectVerified && !needsRestoreRecovery && comparison?.let { it.kind==OnboardingKind.BOTH_EMPTY || it.kind==OnboardingKind.IDENTICAL && it.baselineLineage==OnboardingLineage.SAME_TIP }==true
  val canKeepLocalProgress get()=if(simpleObservation!=null)kind=="DIFFERENT" else isObjectVerified && !needsRestoreRecovery && comparison?.let(::keepsLocalProgress)==true
 }
 class OperationResult @JvmOverloads constructor(val message:String,val isConflict:Boolean,val isPending:Boolean,val snapshotId:String?,
  val refreshedInspection:Inspection?=null,val configurationComplete:Boolean=false)
 class AutoUploadResult(val status:String,val message:String,val snapshotId:String?,val needsUserChoice:Boolean,reason:SyncBlockReason?=null) {
  val reasonCode=reason?.name
 }
 class LocalPreview(val fileCount:Int,val summary:SaveSummary?,val message:String)
 class ControlledObservation internal constructor(internal val proof:AndroidControlledSessions.ObservationProof?)
 @Serializable private data class RestoreJournal(val schema:Int=2,val profileScope:String,val gameSourceIdentity:String,val gamePackage:String,val installedApkSha256:String,val sourceApkSha256:String,val expectedBefore:Map<String,String>,val expectedAfter:Map<String,String>,val action:String,val ready:Boolean=false)

 fun hasUnresolvedRestore()=locked(mutation=false) { anyUnresolvedRestore() }
 fun testConnection()=connectionCheck(false)
 fun connect()=connectionCheck(true)
 private fun connectionCheck(create:Boolean)=locked {
  if(simpleEnabled) {if(create && suppliedSimpleStore==null)simpleTransport.ensureSimpleNamespace();return@locked ConnectionStatus(true,CoreMessages.ANDROID_CONNECTION_VERIFIED.text(),simpleStore.latest()!=null)}
  require(!create){CoreMessages.ANDROID_NAMESPACE_CREATION_REQUIRES_THE_WEBDAV_TRANSPORT.text()}
  val graph=AppendRepository(state.resolve("connection-probe"),adapter,config.profile).discover(store).also {it.requireSettled()}
  ConnectionStatus(true,CoreMessages.ANDROID_CONNECTION_VERIFIED.text(),graph.commits.isNotEmpty())
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
 /** One immutable backend observation. It never grants write authority or owns the mirror. */
 class HomeReadContext internal constructor(internal val owner:Any,internal val target:AndroidGameTarget,
  internal val epoch:Long,internal val observation:AndroidSaveObservation,internal val sessionContext:pdsync.HomeReadContext,
  internal val mirrorBefore:Map<String,String>,internal val sidecarBefore:SidecarRead,
  internal val continuity:HomeContinuity?) : AutoCloseable {
  internal val cancellation=MetadataReadCancellation()
  internal val accepted=AtomicBoolean(false)
  internal val materialized=AtomicBoolean(false)
  internal var materializedReceipt:AndroidRestoreReceipt?=null
  override fun close()=cancellation.cancel()
 }
 internal class HomeContinuity(val target:AndroidGameTarget,val epoch:Long,val before:Map<String,String>,val sidecar:SidecarRead,
  val after:Map<String,String>,val runs:Map<String,RunIdentity>) {
  val consumed=AtomicBoolean(false)
  fun matches(target:AndroidGameTarget,epoch:Long,before:Map<String,String>,sidecar:SidecarRead,after:Map<String,String>)=
   !consumed.get() && this.target==target && this.epoch==epoch && this.before==before && this.sidecar==sidecar && this.after==after
 }
 class HomeReadResult internal constructor(internal val context:HomeReadContext,internal val remote:pdsync.HomeReadResult)
 /** Short serialized capture; unsupported backends explicitly retain the old serialized path. */
 fun captureHomeReadContext():HomeReadContext?=locked(mutation=false) {
  if(simpleEnabled)return@locked null
  if(anyUnresolvedRestore())return@locked null
  val proof=controlledSessions.beginExport(base,workspace)
  try {
   val target=verifiedTarget()
   val observationTiming=DiagnosticTiming.start(DiagnosticPhase.SAVE_EXPORT)
   val observation=try {bridge.readSnapshot().also {if(it==null)observationTiming.skipped() else observationTiming.succeeded()}}
    catch(error:Throwable){observationTiming.failed(error);throw error}
   if(observation==null) {controlledSessions.releaseExport(proof);return@locked null}
   require(observation.target==target && verifiedTarget()==target) { CoreMessages.ANDROID_SELECTED_GAME_IDENTITY_OR_CLOUD_BINDING_CHANGED_REOPEN_THAT_GAME_AND_COMPARE_AGAIN.text() }
   versions.detectVerifiedEntries(observation.summaryEntries)
   val summary=DiagnosticTiming.measure(DiagnosticPhase.LOCAL_SUMMARY) {
    SaveSummary.fromObservation(observation.inventory.keys,observation.summaryEntries) { observation.inventory.getValue(it).modifiedMillis }
   }
   val before=immutableMap(currentFiles());val sidecar=DirectoryMetadataStore(mirror).read()
   val continued=controlledSessions.consume(proof,base,workspace,target,before,(sidecar as? SidecarRead.Valid)?.value?.runs.orEmpty(),observation.files,observation.summaryEntries)
   val epoch=mutationEpoch.get()
   val continuity=if(continued.isNotEmpty())HomeContinuity(target,epoch,before,sidecar,observation.files,immutableMap(continued)).also {sharedHomeContinuity[workspace]=it}
    else sharedHomeContinuity[workspace]?.takeIf {it.matches(target,epoch,before,sidecar,observation.files)}
   if(continuity==null)sharedHomeContinuity.remove(workspace)
   HomeReadContext(owner,target,epoch,observation,session.beginHomeRead(observation.files,summary),before,sidecar,continuity)
  } finally {controlledSessions.abandonExport(proof)}
 }
 /** Pure network/CPU phase: no facade lock, mirror, baseline or discovery-cache write. */
 fun readHomeRemote(context:HomeReadContext):HomeReadResult {
  require(context.owner===owner)
  context.cancellation.checkActive()
  return HomeReadResult(context,session.readHome(context.sessionContext,store,context.cancellation))
 }
 /** Short serialized acceptance. Stale/cancelled observations never merge remote evidence. */
 fun acceptHomeObservation(result:HomeReadResult):Inspection?=locked(mutation=false) {
  val context=result.context;require(context.owner===owner)
  context.cancellation.checkActive()
  if(context.accepted.get() || context.epoch!=mutationEpoch.get() || anyUnresolvedRestore())return@locked null
  require(verifiedTarget()==context.target) { CoreMessages.ANDROID_SELECTED_GAME_IDENTITY_OR_CLOUD_BINDING_CHANGED_REOPEN_THAT_GAME_AND_COMPARE_AGAIN.text() }
  if(currentFiles()!=context.mirrorBefore || DirectoryMetadataStore(mirror).read()!=context.sidecarBefore)return@locked null
  val (comparison,history)=session.acceptHome(result.remote) ?: return@locked null
  context.accepted.set(true)
  val remote=comparison.remoteCommits.map {version(it,true,CoreMessages.CLOUD_HISTORY.text())}
  Inspection(owner,comparison,comparison.kind.name,comparison.blocker ?: CoreMessages.CLOUD_HISTORY.text(),comparison.localFiles.size,
   remote,comparison.retryablePending || session.hasOverwritePending(),comparison.canAdoptRemote,false,history=if(comparison.kind==OnboardingKind.BLOCKED)null else history,homeObservation=context)
 }
 fun inspectHome():Inspection {
  val context=captureHomeReadContext() ?: return locked {inspectLocked(metadataOnly=true)}
  context.use { return requireNotNull(acceptHomeObservation(readHomeRemote(it))) { CoreMessages.ANDROID_THE_LOCAL_MIRROR_CHANGED_COMPARE_AGAIN.text() } }
 }
 /** Memory-only publication stamp; any completed serialized operation invalidates older reads. */
 fun inspectHomeHistory():Inspection=locked {
  val current=if(simpleEnabled)inspectSimple() else inspectLocked(metadataOnly=true)
  if(!simpleEnabled)return@locked current
  val observed=requireNotNull(current.simpleObservation)
   simpleInspection(observed.files,observed.latest,simpleNodes(observed.latest,observed.nodes),observed.summary,observed.localModifiedAt)
 }
 fun observationGeneration():Long=mutationEpoch.get()
 fun beginHomeReadIdentityScope():AutoCloseable=bridge.beginHomeReadIdentityScope()
 private fun simpleNodes(latest:String?,known:List<SaveNode> = emptyList()):List<SaveNode> =
  SimpleSaveProtocol.history(simpleStore,latest,known)
  private fun simpleInspection(files:Map<String,String>,latest:String?,nodes:List<SaveNode>,summary:SaveSummary?=null,localModifiedAt:String?=null):Inspection {
  val id=files.takeIf {it.isNotEmpty()}?.let(::contentSha256);val baseline=simpleSync.baseline()
  val kind=SimpleSaveProtocol.kind(files,baseline,latest)
  val message=when(kind){"IDENTICAL"->CoreMessages.ANDROID_LOCAL_AND_CLOUD_SAVE_BYTES_MATCH.text();"BOTH_EMPTY"->CoreMessages.ANDROID_NO_LOCAL_OR_CLOUD_SAVES_WERE_FOUND.text();"LOCAL_ONLY"->CoreMessages.ANDROID_ONLY_THIS_DEVICE_HAS_SAVES_UPLOAD_PRESERVES_THEM_IN_THE_CLOUD.text();else->CoreMessages.ANDROID_LOCAL_AND_CLOUD_SAVES_DIFFER_REVIEW_VERSIONS_BEFORE_RESTORING_OR_MERGING.text()}
   val history=SyncHistory(CommitGraph(emptyMap(),emptyMap()),baseline,files,simpleCurrentName.takeIf {simpleCurrentNameHash==id}.orEmpty(),summary,nodes.associate {it.hashId to it.name},simple=SimpleHistory(nodes,latest,id,localModifiedAt,simpleDeviceMetadata.currentDevice()))
  val remote=nodes.filter {it.hashId==latest}.map {Version(it.hashId,it.hashId,it.name,"",0,null,null,true,CoreMessages.CLOUD_HISTORY.text())}
   return Inspection(owner,null,kind,message,files.size,remote,false,latest!=null,false,history=history,simpleObservation=SimpleObservation(files,latest,nodes,summary,localModifiedAt))
 }
 private fun inspectSimple():Inspection {
  requireNoRecovery();val own=uploadTarget.get()==null;if(own)uploadTarget.set(verifiedTarget())
  try {
   val observation=bridge.readSnapshot()
   val files=observation?.files ?: freshVerifiedExport {it.snapshot.manifest.files}
    val modified=observation?.inventory?.values?.maxOfOrNull {it.modifiedMillis}?.let {runCatching {java.time.Instant.ofEpochMilli(it).toString()}.getOrNull()}
    val summary=observation?.let {SaveSummary.fromObservation(it.inventory.keys,it.summaryEntries) {name->it.inventory.getValue(name).modifiedMillis}}
    val latest=simpleStore.latest();return simpleInspection(files,latest,latest?.let {listOf(requireNotNull(simpleStore.node(it)))} ?: emptyList(),summary,modified)
  }finally{if(own)uploadTarget.remove()}
 }
 private fun uploadSimple(inspection:Inspection?,confirmed:String?=null):OperationResult {
  requireNoRecovery();if(inspection!=null)require(inspection.owner===owner)
  val own=uploadTarget.get()==null;if(own)uploadTarget.set(verifiedTarget())
  try {
   var files=emptyMap<String,String>();var summary:SaveSummary?=null
   val result=simpleSync.upload({beforeRequest.check()},{
    verifiedTarget()
    val archive=DiagnosticTiming.measure(DiagnosticPhase.SAVE_EXPORT) {bridge.exportArchive()}
    verifiedTarget()
    val payload=if(archive!=null) DiagnosticTiming.measure(DiagnosticPhase.LOCAL_EXPORT_VERIFY) {
     val parsed=AndroidArchiveSnapshot.read(archive,adapter)
     val detected=versions.detectVerifiedEntries(parsed.entries)
     summary=SaveSummary.fromArchiveEntries(parsed.entries,parsed.modified,detected)
     SaveUpload(parsed.files,archive,"")
    } else freshVerifiedExport {exported->
     summary=SaveSummary.readVerified(exported.root,exported.entries)
     SaveUpload(exported.snapshot.manifest.files,exported.snapshot.zip,"")
    }
    files=payload.files;require(files.isNotEmpty())
      val name=simpleCurrentName.takeIf {simpleCurrentNameHash==contentSha256(files)}.orEmpty()
    payload.copy(name=name)
   },confirmed,{if(Thread.currentThread().isInterrupted)throw InterruptedException()})
   return when(result) {
    is SaveUploadResult.Conflict -> {
     val node=requireNotNull(simpleStore.node(result.latest));val view=simpleInspection(files,result.latest,listOf(node),summary)
     OperationResult(view.message,true,false,null,view,false)
    }
    is SaveUploadResult.Complete -> {
     // The receipt may only retain known ancestors of the published node, not the replaced tip.
      val nodes=knownAncestorChain(result.node,inspection?.simpleObservation?.nodes.orEmpty())
     val view=simpleInspection(files,result.node.hashId,nodes,summary)
     OperationResult(if(result.uploaded)CoreMessages.ANDROID_SNAPSHOT_UPLOADED_AND_VERIFIED.text() else CoreMessages.ANDROID_NO_CHANGED_LOCAL_SNAPSHOT_NEEDS_UPLOADING.text(),false,false,result.node.hashId,view,true)
    }
   }
  }finally{if(own)uploadTarget.remove()}
 }
 private fun restoreSimple(inspection:Inspection,id:String):OperationResult {
  requireNoRecovery();require(inspection.owner===owner);val observed=requireNotNull(inspection.simpleObservation)
  require(observed.nodes.any {it.hashId==id})
  val own=uploadTarget.get()==null;if(own)uploadTarget.set(verifiedTarget())
  try {
   val zip=simpleStore.objectBytes(id)
   val entries=SimpleSaveProtocol.restoreEntries(zip,adapter)
   versions.detectVerifiedEntries(entries);val files=entries.mapValues {hash(it.value)}
   val snapshot=Snapshot(Manifest(game=adapter.id,profile=config.profile,version=CoreMessages.ANDROID_ANDROID_SAVE_EXPORT.text(),id=id,parent=null,sha256=hash(zip),files=files),zip)
   val before=freshVerifiedExport {it.snapshot.manifest.files}
   requireNotNull(bridge.restoreVerified(snapshot,before)){"Verified restore is unsupported"};simpleSync.acceptBaseline(id)
   val view=simpleInspection(files,observed.latest,observed.nodes)
   return OperationResult(CoreMessages.ANDROID_GAME_RESTORE_VERIFIED_ANY_FROZEN_UPLOAD_REMAINS_AVAILABLE_FOR_EXPLICIT_RETRY.text(),false,false,id,view,true)
  }finally{if(own)uploadTarget.remove()}
 }

 private fun inspectLocked(metadataOnly:Boolean=false):Inspection {
  if(simpleEnabled)return inspectSimple()
  if(anyUnresolvedRestore()) return Inspection(owner,null,"BLOCKED",CoreMessages.ANDROID_A_GAME_RESTORE_NEEDS_VERIFICATION_OR_RECOVERY_UPLOAD_IS_BLOCKED_UNTIL_IT_IS_RESOLVED.text(),currentFiles().size,emptyList(),false,false,true,SyncBlockReason.RESTORE_RECOVERY_REQUIRED)
  // DEX order: refresh first, then compare the same mirror observation.
  val homeLocal=refreshMirror(metadataOnly)
  val comparison=DiagnosticTiming.measure(DiagnosticPhase.COMPARISON) {
   if(metadataOnly)session.compareHomeMetadata(store,mirrorStopped) else session.compareOnboarding(store,mirrorStopped)
  }
  return inspectionFromComparison(comparison,homeLocal)
 }
 private fun inspectionFromComparison(comparison:OnboardingComparison,homeLocal:HomeLocalRead?=null):Inspection {
  val remote=if(comparison.verification==RemoteVerification.OBJECTS_VERIFIED)comparison.remoteEntries.map { version(it.commit,true,it.entry.reason) }
   else comparison.remoteCommits.map {version(it,true,CoreMessages.CLOUD_HISTORY.text())}
  return try {
   if(comparison.verification==RemoteVerification.OBJECTS_VERIFIED)comparison.remoteEntries.forEach { versions.detect(session.loadSnapshot(it.commit.snapshot.id)) }
   val history=if(comparison.kind==OnboardingKind.BLOCKED)null else if(homeLocal!=null)session.syncHistory(comparison,homeLocal.files,homeLocal.summary) else session.syncHistory(comparison)
   Inspection(owner,comparison,comparison.kind.name,comparison.blocker ?: if(comparison.verification==RemoteVerification.METADATA_ONLY)CoreMessages.CLOUD_HISTORY.text() else description(comparison),(session.uploadReceiptFiles() ?: currentFiles()).size,remote,comparison.retryablePending || session.hasOverwritePending(),comparison.canAdoptRemote,false,history=history)
  } catch(e:UnsupportedAndroidSaveFormatException) {
   Inspection(owner,null,"BLOCKED",e.message ?: CoreMessages.ANDROID_UNSUPPORTED_CLOUD_SAVE_FORMAT.text(),currentFiles().size,remote,false,false,false,SyncBlockReason.UNSUPPORTED_SAVE_FORMAT)
  }
 }

 fun autoUpload():AutoUploadResult=locked {
  if(simpleEnabled) {val result=uploadSimple(null);return@locked AutoUploadResult(if(result.isConflict)"SKIPPED_CONFLICT" else "UPLOADED",result.message,result.snapshotId,result.isConflict)}
  if(session.hasOverwritePending())return@locked autoBlocked("SKIPPED_PENDING",SyncBlockReason.PENDING_UPLOAD,true)
  try {
   var inspection=inspectLocked()
   var comparison=inspection.comparison ?: return@locked autoBlocked("SKIPPED_UNCERTAIN",inspection.blockReason ?: SyncBlockReason.COMPARISON_FAILED,true)
   if(unchangedDescendantTip(comparison)!=null) {
    checkActualUnchanged(comparison.localFiles)
    if(session.advanceUnchangedBaseline(comparison,store,mirrorStopped)) {
     inspection=inspectLocked();comparison=inspection.comparison ?: return@locked autoBlocked("SKIPPED_UNCERTAIN",inspection.blockReason ?: SyncBlockReason.COMPARISON_FAILED,true)
    }
   }
   val reused=session.reuseVerifiedUploadContent(comparison,mirrorStopped)
   if(comparison.kind in setOf(OnboardingKind.IDENTICAL,OnboardingKind.BOTH_EMPTY) || reused!=null) return@locked AutoUploadResult("UNCHANGED",CoreMessages.ANDROID_NO_CHANGED_LOCAL_SNAPSHOT_NEEDS_UPLOADING.text(),reused?.snapshot?.id,false)
   if(!AutomaticUploadEligibility.allows(comparison)) {
    val choice=comparison.observedTips.size>1 || comparison.kind==OnboardingKind.DIFFERENT || comparison.retryablePending
    val reason=comparison.blockReason ?: when {choice->SyncBlockReason.HISTORY_CONFLICT;comparison.kind==OnboardingKind.REMOTE_ONLY->SyncBlockReason.REMOTE_ONLY;comparison.baselineLineage==OnboardingLineage.BLOCKED->SyncBlockReason.BASELINE_UNVERIFIED;else->SyncBlockReason.COMPARISON_FAILED}
    return@locked autoBlocked(if(choice)"SKIPPED_CONFLICT" else "SKIPPED_UNCERTAIN",reason,choice || comparison.kind==OnboardingKind.REMOTE_ONLY)
   }
   checkActualUnchanged(comparison.localFiles)
   when(val result=session.onboardingUpload(comparison,store,mirrorStopped)) {
    is AppendPushResult.Success -> AutoUploadResult(if(result.reused)"UNCHANGED" else "UPLOADED",if(result.reused)CoreMessages.ANDROID_NO_CHANGED_LOCAL_SNAPSHOT_NEEDS_UPLOADING.text() else if(result.remoteAhead)CoreMessages.ANDROID_SNAPSHOT_UPLOADED_BUT_THE_CLOUD_ADVANCED_REVIEW_BEFORE_RESTORING.text() else CoreMessages.ANDROID_BACKGROUND_SNAPSHOT_UPLOADED_AND_VERIFIED.text(),result.commit.snapshot.id,result.remoteAhead)
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
 fun upload(inspection:Inspection):OperationResult=uploadReadLocked {
  if(simpleEnabled)return@uploadReadLocked uploadSimple(inspection)
  if(session.hasOverwritePending())return@uploadReadLocked overwriteCloudSuccessorsLocked(inspection,"")
  val ownTarget=uploadTarget.get()==null
  if(ownTarget)uploadTarget.set(verifiedTarget())
  try {
  val comparison=presentationChecked(inspection)
  val (result,completed)=session.uploadFresh(comparison,store,mirrorStopped) {fresh,proofs->
   fresh.remoteEntries.forEach {entry->
    val proof=proofs[entry.commit.id]
    if(proof!=null)versions.detect(proof) else versions.detect(session.loadSnapshot(entry.commit.snapshot.id))
   }
  }
  // The user-selected optimistic upload uses its fixed export receipt; no second device read.
  val rendered=inspectionFromComparison(completed)
  val outcome=pushResult(result)
  OperationResult(outcome.message,outcome.isConflict,outcome.isPending,outcome.snapshotId,rendered,
   !outcome.isConflict && !outcome.isPending && completed.kind==OnboardingKind.IDENTICAL && completed.baselineLineage==OnboardingLineage.SAME_TIP)
  } finally { if(ownTarget)uploadTarget.remove() }
 }

 fun listVersions():List<Version> = locked {
  if(simpleEnabled){val latest=simpleStore.latest();return@locked simpleNodes(latest).map {Version(it.hashId,it.hashId,it.name,"",0,null,null,it.hashId==latest,CoreMessages.CLOUD_HISTORY.text())}}
  requireNoRecovery();verifiedTarget();session.readRemote(store)
  val history=session.syncHistory();val cloud=history.cloud.map {version(it,it.id in history.graph.tips,CoreMessages.ANDROID_VERIFIED_CLOUD_HISTORY.text())};val ids=cloud.map {it.snapshotId}.toSet()
  immutable(cloud+session.history().filter {it.manifest.id !in ids}.map {version(it.manifest,null,false,it.reason)})
 }
 fun restoreSelected(inspection:Inspection,snapshotId:String,allowUniqueReplacement:Boolean)=restoreReadLocked {
  restoreOtherSelection(inspection,snapshotId,allowUniqueReplacement,false)
 }
 /** Explicit caller-selected download mode, e.g. an authenticated expired official account. */
 fun restoreSelectedReadOnly(inspection:Inspection,snapshotId:String,allowUniqueReplacement:Boolean)=restoreReadLocked {
  restoreOtherSelection(inspection,snapshotId,allowUniqueReplacement,true)
 }
 private fun restoreOtherSelection(inspection:Inspection,snapshotId:String,allowUniqueReplacement:Boolean,readOnly:Boolean):OperationResult {
  if(simpleEnabled)return restoreSimple(inspection,snapshotId)
  val observed=presentationChecked(inspection)
  val before=inspection.homeObservation?.materializedReceipt ?: checkActualUnchanged(observed.localFiles)
  val selection=session.prepareSelectedRestore(observed,snapshotId,store,allowUniqueReplacement,readOnly,mirrorStopped)
  val after=selection.verified.snapshot.manifest.files
  var stage:SelectedRestoreStage?=null
  val applied=if(selection.unchanged) {
   // A complete selected archive and one fresh actual-game export prove this no-op. Do not
   // create a journal, replace the mirror, or publish. Explicit selection may remember
   // verified provenance; passive content equality never grants an operational baseline.
   stage=session.stageSelectedRestore(selection,store,mirrorStopped);null
  } else prepareGameRestore(observed.localFiles,after,if(readOnly)"read-only-restore" else "restore",
   verifiedProvider={stage?.verified}) {stage=session.stageSelectedRestore(selection,store,mirrorStopped)}
  val staged=requireNotNull(stage)
  if(staged.applied) {
   val receipt=applied ?: before.takeIf {it.files==after}
   require(receipt!=null && receipt.files==after && receipt.byteCount==staged.verified.byteCount) {
    CoreMessages.ANDROID_THE_HELPER_RESTORE_COULD_NOT_BE_VERIFIED_UPLOAD_REMAINS_BLOCKED_RECOVER_OR_INSPECT_RETAINE.text()
   }
  }
  val completed=session.finishSelectedRestore(staged,store,mirrorStopped)
  val summary=if(staged.applied)runCatching {SaveSummary.fromVerified(staged.verified)}.getOrNull() else inspection.history?.summary
  val refreshed=inspectionFromComparison(completed,HomeLocalRead(completed.localFiles,summary))
  val conflict=completed.observedTips.size>1
  val complete=staged.applied && completed.kind==OnboardingKind.IDENTICAL && completed.baselineLineage==OnboardingLineage.SAME_TIP
  val message=when {
   !staged.applied || conflict->CoreMessages.ANDROID_BOTH_BRANCHES_ARE_PRESERVED_COMPARE_AGAIN_AND_EXPLICITLY_CHOOSE_A_BRANCH_OR_PREVIEW_A_SAFE.text()
   staged.unchanged->CoreMessages.ANDROID_LOCAL_AND_CLOUD_SAVE_BYTES_MATCH.text()
   readOnly->CoreMessages.ANDROID_SELECTED_VERSION_DOWNLOADED_RESTORED_AND_VERIFIED_IN_THE_GAME.text()
   else->CoreMessages.ANDROID_SELECTED_VERSION_RESTORED_AND_VERIFIED_IN_THE_GAME.text()
  }
  return OperationResult(message,conflict,false,snapshotId,refreshed,complete)
 }
 fun overwriteCloudSuccessors(inspection:Inspection,snapshotId:String)=uploadReadLocked {overwriteCloudSuccessorsLocked(inspection,snapshotId)}
 private fun overwriteCloudSuccessorsLocked(inspection:Inspection,snapshotId:String):OperationResult {
  if(simpleEnabled) {require(snapshotId==inspection.simpleObservation?.latest);return uploadSimple(inspection,snapshotId)}
  val observed=presentationChecked(inspection)
  val completed=session.overwriteCloudSuccessors(observed,snapshotId,store,mirrorStopped) {fresh->
   fresh.remoteEntries.forEach {versions.detect(session.loadSnapshot(it.commit.snapshot.id))}
   checkActualUnchanged(fresh.localFiles)
  }
  checkActualUnchanged(completed.localFiles)
  return OperationResult("",false,false,
   completed.baselineId,inspectionFromComparison(completed),completed.kind==OnboardingKind.IDENTICAL)
 }
 fun resolveConflict(inspection:Inspection,snapshotId:String)=restoreReadLocked {
  val comparison=checked(inspection);checkActualUnchanged(comparison.localFiles);val conflict=currentConflict(comparison)
  val branch=conflict.snapshotBranch(snapshotId) ?: error(CoreMessages.ANDROID_SELECT_A_SNAPSHOT_FROM_THE_CURRENTLY_OBSERVED_CONFLICT_BRANCHES.text())
  var result:AppendPushResult?=null
  prepareGameRestore(comparison.localFiles,branch.snapshot.manifest.files,"resolution") {result=session.resolve(conflict,snapshotId,store,mirrorStopped)}
  pushResult(requireNotNull(result),CoreMessages.ANDROID_BRANCH_CHOICE_APPLIED_TO_THE_GAME_AND_PUBLISHED.text())
 }
 fun adoptIdentical(inspection:Inspection)=locked {val c=checked(inspection);checkActualUnchanged(c.localFiles);OperationResult(CoreMessages.ANDROID_IDENTICAL_LOCAL_AND_CLOUD_SAVES_LINKED_NO_GAME_FILES_WERE_REPLACED.text(),false,false,session.adoptOnboardingRemote(c,store,mirrorStopped).snapshot.id)}
 fun confirmRunIdentity(inspection:Inspection,slot:String,sameRun:Boolean)=locked {val c=checked(inspection);checkActualUnchanged(c.localFiles);session.confirmRunIdentity(slot,c.localFiles,sameRun,mirrorStopped);OperationResult(CoreMessages.ANDROID_RUN_IDENTITY_RECORDED_COMPARE_AGAIN_BEFORE_MERGING.text(),false,session.hasPending(),null)}
 fun recoverRestore()=locked {val journal=readJournal();require(journal.ready){CoreMessages.ANDROID_RESTORE_PREPARATION_WAS_INTERRUPTED_ORIGINALS_AND_BACKUPS_ARE_RETAINED_EXPLICITLY_DISCARD.text()};require(currentFiles()==journal.expectedAfter){CoreMessages.ANDROID_THE_PREPARED_RESTORE_CHANGED_RECOVERY_IS_BLOCKED_BACKUPS_ARE_RETAINED.text()};applyPrepared(journal);OperationResult(CoreMessages.ANDROID_GAME_RESTORE_VERIFIED_ANY_FROZEN_UPLOAD_REMAINS_AVAILABLE_FOR_EXPLICIT_RETRY.text(),false,session.hasPending(),null)}
 fun discardInterruptedRestore()=locked {readJournal(false);freshExport {exported->session.archivePending();replaceMirror(exported);clearCompletedJournal()};OperationResult(CoreMessages.ANDROID_INTERRUPTED_OPERATION_ARCHIVED_CURRENT_GAME_FILES_WERE_LEFT_UNCHANGED_COMPARE_AGAIN.text(),false,false,null)}
 fun archivePending()=locked {requireNoRecovery();session.archivePending()}
 fun previewLocal()=locked {requireNoRecovery();freshExport {exported->val count=capture(exported).manifest.files.size;LocalPreview(count,runCatching {SaveSummary.read(exported)}.getOrNull(),if(count==0)CoreMessages.ANDROID_THE_SELECTED_GAME_S_SAVE_DIRECTORY_IS_EMPTY.text() else CoreMessages.ANDROID_VERIFIED_1_S_SAVE_FILES_FOR_2_S.text(count, identity.displayName))}}
 /** Standalone refresh explicitly creates a new observation. Use overload for an existing UI. */
 fun historyPresentation():SyncHistory=locked {val inspection=inspectLocked(metadataOnly=true);requireNotNull(inspection.history){inspection.message}}
 fun historyPresentation(inspection:Inspection):SyncHistory=locked(mutation=false) {
  if(simpleEnabled){require(inspection.owner===owner);return@locked requireNotNull(inspection.history)}
  if(inspection.homeObservation!=null)validateHomePresentation(inspection) else presentationChecked(inspection)
  requireNotNull(inspection.history){inspection.message}
 }
 fun verifyHomeInspection(inspection:Inspection):Inspection=locked {
  if(simpleEnabled){require(inspection.owner===owner);return@locked inspection}
  val c=checked(inspection);checkActualUnchanged(c.localFiles)
  val verified=inspectionFromComparison(c)
  require(verified.isObjectVerified && verified.kind!="BLOCKED"){verified.message};verified
 }
 fun completeOnboarding(inspection:Inspection)=locked {if(simpleEnabled){require(inspection.owner===owner);return@locked Unit};val c=checked(inspection);checkActualUnchanged(c.localFiles);if(keepsLocalProgress(c))session.confirmLocalProgressReady(c,store,mirrorStopped) else session.confirmOnboardingReady(c,store,mirrorStopped)}
 fun renameCurrent(inspection:Inspection,name:String)=locked {if(simpleEnabled){require(inspection.owner===owner);val observed=requireNotNull(inspection.simpleObservation);val id=contentSha256(observed.files);val renamed=sanitizeSnapshotName(name);observed.nodes.find {it.hashId==id}?.let {simpleStore.rename(it.copy(name=renamed))};simpleCurrentName=renamed;simpleCurrentNameHash=id;return@locked Unit};checkActualUnchanged(checked(inspection).localFiles);session.renameCurrent(name,mirrorStopped)}
 fun renameHistory(snapshotId:String,name:String)=locked {if(simpleEnabled){val node=requireNotNull(simpleStore.node(snapshotId));simpleStore.rename(node.copy(name=sanitizeSnapshotName(name)));return@locked Unit};requireNoRecovery();verifiedTarget();session.renameHistory(snapshotId,name)}

 fun renameHistory(inspection:Inspection,snapshotId:String,name:String)=locked {if(simpleEnabled){require(inspection.owner===owner);val node=requireNotNull(inspection.simpleObservation?.nodes?.find {it.hashId==snapshotId});simpleStore.rename(node.copy(name=sanitizeSnapshotName(name)));return@locked Unit};presentationChecked(inspection);session.renameHistory(snapshotId,name,requireNotNull(inspection.history){inspection.message})}

 // Scoped to this synchronous locked upload. Root RPC still validates package/user/path.
 private val uploadTarget=ThreadLocal<AndroidGameTarget>()
 private fun verifiedTarget():AndroidGameTarget=uploadTarget.get() ?: DiagnosticTiming.measure(DiagnosticPhase.IDENTITY_VERIFICATION) {
  val target=bridge.targetGame()
  if(target.identity!=identity){controlledSessions.cancel(base);throw AndroidTargetChangedException(CoreMessages.ANDROID_SELECTED_GAME_IDENTITY_OR_CLOUD_BINDING_CHANGED_REOPEN_THAT_GAME_AND_COMPARE_AGAIN.text())}
  if(pinnedTarget!=null && pinnedTarget!=target){controlledSessions.cancel(base);throw AndroidTargetChangedException(CoreMessages.ANDROID_THE_SELECTED_INSTALLED_APK_CHANGED_DURING_THIS_SYNC_SESSION_REOPEN_THE_GAME_SELECTION_AND.text())}
  if(pinnedTarget==null)pinnedTarget=target
  target
 }
 private fun validateHomePresentation(inspection:Inspection) {
  requireNoRecovery();require(inspection.owner===owner) { CoreMessages.ANDROID_THIS_COMPARISON_BELONGS_TO_ANOTHER_CONNECTION_COMPARE_AGAIN.text() }
  val context=requireNotNull(inspection.homeObservation)
  require(context.accepted.get() && context.epoch==mutationEpoch.get() && verifiedTarget()==context.target &&
   currentFiles()==context.mirrorBefore && DirectoryMetadataStore(mirror).read()==context.sidecarBefore) {
   CoreMessages.ANDROID_THE_LOCAL_MIRROR_CHANGED_COMPARE_AGAIN.text()
  }
 }
 /** Inventory is display evidence. The first real action must acquire complete fresh bytes. */
 private fun materializeHome(inspection:Inspection) {
  val context=inspection.homeObservation ?: return
  validateHomePresentation(inspection)
  require(context.materialized.compareAndSet(false,true)) { CoreMessages.ANDROID_THE_LOCAL_MIRROR_CHANGED_COMPARE_AGAIN.text() }
  freshVerifiedExport { exported ->
   require(exported.snapshot.manifest.files==context.observation.files) {
    CoreMessages.ANDROID_ACTUAL_GAME_PROGRESS_CHANGED_SINCE_COMPARISON_COMPARE_AGAIN_NO_GAME_FILES_WERE_REPLACED.text()
   }
   require(verifiedTarget()==context.target && currentFiles()==context.mirrorBefore && DirectoryMetadataStore(mirror).read()==context.sidecarBefore) {
    CoreMessages.ANDROID_THE_LOCAL_MIRROR_CHANGED_COMPARE_AGAIN.text()
   }
   replaceMirror(exported,null)
   val old=(context.sidecarBefore as? SidecarRead.Valid)?.value
   val continuity=context.continuity
   if(old!=null && continuity!=null && continuity.matches(context.target,context.epoch,context.mirrorBefore,context.sidecarBefore,context.observation.files)
    && continuity.consumed.compareAndSet(false,true)) {
    sharedHomeContinuity.remove(workspace,continuity)
    val metadata=DirectoryMetadataStore(mirror);val observed=metadata.observe(context.observation.files,initialImport=false,persist=false)
    metadata.write(old.copy(currentContentSha256=observed.contentSha256,runs=observed.runs+continuity.runs))
   }
   context.materializedReceipt=AndroidRestoreReceipt(context.target,exported.snapshot.manifest.files,exported.entries.values.sumOf {it.size.toLong()})
  }
 }
 private fun presentationChecked(inspection:Inspection):OnboardingComparison {
  requireNoRecovery();require(inspection.owner===owner){CoreMessages.ANDROID_THIS_COMPARISON_BELONGS_TO_ANOTHER_CONNECTION_COMPARE_AGAIN.text()}
  val c=requireNotNull(inspection.comparison){CoreMessages.ANDROID_COMPLETE_A_SAFE_COMPARISON_FIRST.text()}
  materializeHome(inspection)
  verifiedTarget()
  require((session.uploadReceiptFiles() ?: currentFiles())==c.localFiles){CoreMessages.ANDROID_THE_LOCAL_MIRROR_CHANGED_COMPARE_AGAIN.text()};return c
 }
 private fun checked(inspection:Inspection,allowPending:Boolean=false):OnboardingComparison {
  val observed=presentationChecked(inspection)
  val c=if(observed.verification==RemoteVerification.METADATA_ONLY)session.verifyComparison(observed,store,mirrorStopped) else observed
  require(c.kind!=OnboardingKind.BLOCKED || allowPending && c.retryablePending){inspection.message}
  if(observed.verification==RemoteVerification.METADATA_ONLY)c.remoteEntries.forEach {versions.detect(session.loadSnapshot(it.commit.snapshot.id))}
  return c
 }
 private fun currentConflict(c:OnboardingComparison):AppendConflict {
  val conflict=session.inspectConflict(store,mirrorStopped) ?: error(CoreMessages.ANDROID_THERE_IS_NO_CURRENT_CLOUD_CONFLICT_COMPARE_AGAIN.text())
  require(conflict.observedTips==c.observedTips && conflict.observedLocalFiles==c.localFiles){CoreMessages.ANDROID_CLOUD_BRANCHES_OR_CURRENT_GAME_FILES_CHANGED_COMPARE_AGAIN_BEFORE_SELECTING_A_BRANCH.text()};return conflict
 }
 private fun pushResult(result:AppendPushResult,success:String=CoreMessages.ANDROID_SNAPSHOT_UPLOADED_AND_VERIFIED.text())=when(result) {
  is AppendPushResult.Diverged -> OperationResult(CoreMessages.ANDROID_BOTH_BRANCHES_ARE_PRESERVED_COMPARE_AGAIN_AND_EXPLICITLY_CHOOSE_A_BRANCH_OR_PREVIEW_A_SAFE.text(),true,session.hasPending(),null)
  is AppendPushResult.Success -> OperationResult(when {result.reused->CoreMessages.ANDROID_NO_CHANGED_LOCAL_SNAPSHOT_NEEDS_UPLOADING.text();result.remoteAhead->CoreMessages.ANDROID_SNAPSHOT_IS_PRESERVED_BUT_ANOTHER_CLOUD_BRANCH_ADVANCED_COMPARE_AGAIN.text();result.localDirty->CoreMessages.ANDROID_SNAPSHOT_UPLOADED_NEWER_LOCAL_CHANGES_REMAIN_COMPARE_AGAIN.text();else->success},false,session.hasPending(),result.commit.snapshot.id)
 }
 private fun prepareGameRestore(before:Map<String,String>,after:Map<String,String>,action:String,verified:VerifiedSnapshot?=null,verifiedProvider:(()->VerifiedSnapshot?)?=null,stage:()->Unit):AndroidRestoreReceipt? {
  val target=verifiedTarget();val journal=RestoreJournal(profileScope=scope,gameSourceIdentity=identity.originalSourceIdentity,gamePackage=identity.packageName,installedApkSha256=target.apkSha256,sourceApkSha256=target.sourceApkSha256,expectedBefore=before,expectedAfter=after,action=action)
  writeJournal(journal)
  try {
   stage()
   return when(currentFiles()) {
    after -> {if(before==after){clearCompletedJournal();null}else{val ready=journal.copy(ready=true);writeJournal(ready);applyPrepared(ready,verifiedProvider?.invoke() ?: verified)}}
    before -> {clearCompletedJournal();null}
    else -> error(CoreMessages.ANDROID_UNEXPECTED_STAGED_FILES_RECOVERY_IS_REQUIRED_GAME_FILES_WERE_NOT_CHANGED.text())
   }
  }catch(e:Exception){val coreRecovery=Files.exists(session.directory.resolve("append-v2/apply-required.txt"));if(currentFiles()==after && !coreRecovery)writeJournal(journal.copy(ready=true)) else if(currentFiles()==before && !coreRecovery)Files.deleteIfExists(journalPath);throw e}
 }
 private fun applyPrepared(journal:RestoreJournal,verifiedInput:VerifiedSnapshot?=null):AndroidRestoreReceipt {
  val staged=verifiedInput?.snapshot ?: versions.annotate(Snapshots.capture(mirror,adapter,config.profile,CoreMessages.ANDROID_ANDROID_STAGED_RESTORE.text(),null))
  val verified=verifiedInput ?: VerifiedSnapshot.read(staged,adapter)
  require(staged.manifest.files==journal.expectedAfter);versions.checkRestore(verified,config.targetGame)
  val target=verifiedTarget()
  val direct=bridge.restoreVerified(staged,immutableMap(journal.expectedBefore))
  if(direct!=null) {
   require(direct.target==target && verifiedTarget()==target && direct.files==journal.expectedAfter && direct.byteCount==verified.byteCount) {
    CoreMessages.ANDROID_THE_HELPER_RESTORE_COULD_NOT_BE_VERIFIED_UPLOAD_REMAINS_BLOCKED_RECOVER_OR_INSPECT_RETAINE.text()
   }
   clearCompletedJournal()
   return direct
  }
  val actual=freshExport {Snapshots.fileHashes(it,adapter)}
  if(actual!=journal.expectedAfter) {
   require(actual==journal.expectedBefore){CoreMessages.ANDROID_GAME_PROGRESS_CHANGED_AFTER_CONFIRMATION_RECOVERY_WILL_NOT_OVERWRITE_IT_BACKUPS_ARE_RETAIN.text()}
   val temp=Files.createTempDirectory(scratch,"restore-")
   try {copySaveFiles(mirror,temp);verifiedTarget();bridge.restoreFrom(temp,immutableMap(journal.expectedBefore));verifiedTarget()}finally{deleteTree(temp)}
  }
  val receipt=checkActualUnchanged(journal.expectedAfter)
  clearCompletedJournal()
  return receipt
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
 private fun checkActualUnchanged(expected:Map<String,String>)=freshVerifiedExport {
  if(it.snapshot.manifest.files!=expected)throw AndroidSaveChangedException(CoreMessages.ANDROID_ACTUAL_GAME_PROGRESS_CHANGED_SINCE_COMPARISON_COMPARE_AGAIN_NO_GAME_FILES_WERE_REPLACED.text())
  AndroidRestoreReceipt(verifiedTarget(),it.snapshot.manifest.files,it.entries.values.sumOf { bytes->bytes.size.toLong() })
 }
 private fun capture(root:Path)=Snapshots.capture(root,adapter,config.profile,CoreMessages.ANDROID_ANDROID_SAVE_EXPORT.text(),null)
 private fun currentFiles()=Snapshots.fileHashes(mirror,adapter)
 private fun <T> freshExport(block:(Path)->T):T {val temp=Files.createTempDirectory(scratch,"export-");try {verifiedTarget();DiagnosticTiming.measure(DiagnosticPhase.SAVE_EXPORT) {bridge.exportTo(temp)};verifiedTarget();validateExport(temp);val snapshot=capture(temp);Snapshots.verify(snapshot,adapter);versions.detect(snapshot);return block(temp)}finally{deleteTree(temp)}}
 private fun validateExport(root:Path) {var count=0;Files.walk(root).use {paths->paths.forEach {p->require(++count<=16384){CoreMessages.ANDROID_BRIDGE_EXPORT_HAS_TOO_MANY_ENTRIES.text()};require(!Files.isSymbolicLink(p)){CoreMessages.ANDROID_BRIDGE_EXPORT_CONTAINS_A_SYMBOLIC_LINK.text()};if(p!=root){val name=root.relativize(p).toString().replace('\\','/');require(adapter.includes(name)&&!isDirectoryMetadata(name)){CoreMessages.ANDROID_BRIDGE_EXPORTED_AN_UNRECOGNIZED_SAVE_ENTRY.text()};require(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))}}}}
 private class VerifiedExport(val root:Path,val snapshot:Snapshot,val entries:Map<String,ByteArray>)
 private class HomeLocalRead(val files:Map<String,String>,val summary:SaveSummary?)
 /** HOME alone retains verified entries; action exports keep their original lifetime. */
 private fun <T> freshVerifiedExport(block:(VerifiedExport)->T):T {
  val temp=Files.createTempDirectory(scratch,"export-")
  try {
   verifiedTarget();DiagnosticTiming.measure(DiagnosticPhase.SAVE_EXPORT) {bridge.exportTo(temp)};verifiedTarget()
   val exported=DiagnosticTiming.measure(DiagnosticPhase.LOCAL_EXPORT_VERIFY) {
    validateExport(temp)
    val read=Snapshots.readLocal(temp,adapter);val snapshot=Snapshots.capture(read,adapter,config.profile,CoreMessages.ANDROID_ANDROID_SAVE_EXPORT.text(),null);val entries=immutableMap(read.entries)
    versions.detectVerifiedEntries(entries);VerifiedExport(temp,snapshot,entries)
   }
   return block(exported)
  } finally {deleteTree(temp)}
 }
 private fun refreshMirror(reuseVerifiedExport:Boolean=false):HomeLocalRead? {
  val proof=controlledSessions.beginExport(base,workspace)
  try {
   if(reuseVerifiedExport)return freshVerifiedExport {exported->
    DiagnosticTiming.measure(DiagnosticPhase.MIRROR_COPY) {replaceMirror(exported,proof)}
    val files=immutableMap(exported.snapshot.manifest.files)
    val summary=runCatching {DiagnosticTiming.measure(DiagnosticPhase.LOCAL_SUMMARY) {SaveSummary.readVerified(mirror,exported.entries)}}.getOrNull()
    HomeLocalRead(files,summary)
   }
   freshExport {replaceMirror(it,proof)};return null
  }finally{controlledSessions.abandonExport(proof)}
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
 private fun copySaveFiles(source:Path,destination:Path) {val snapshot=capture(source);Snapshots.verify(snapshot,adapter).forEach {(name,bytes)->val path=SafePaths.checked(destination.resolve(name));Files.createDirectories(path.parent);Files.write(path,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);Files.setLastModifiedTime(path,Files.getLastModifiedTime(source.resolve(name),LinkOption.NOFOLLOW_LINKS))};require(Snapshots.fileHashes(destination,adapter)==snapshot.manifest.files){CoreMessages.ANDROID_STAGING_COPY_FAILED_CONTENT_VERIFICATION.text()}}
 private fun replaceMirror(exported:VerifiedExport,proof:AndroidControlledSessions.ExportProof?) {
  val before=currentFiles();requireExportUnchanged(exported)
  val metadata=DirectoryMetadataStore(mirror);val old=(metadata.read() as? SidecarRead.Valid)?.value
  val files=exported.snapshot.manifest.files
  val continued=controlledSessions.consume(proof,base,workspace,verifiedTarget(),before,old?.runs.orEmpty(),files,exported.entries)
  requireExportUnchanged(exported)
  if(before==files)return
  Files.walk(mirror).use {paths->paths.sorted(Comparator.reverseOrder()).forEach {p->if(p!=mirror){val name=mirror.relativize(p).toString().replace('\\','/');if(adapter.includes(name)&&!isDirectoryMetadata(name))Files.delete(p)}}}
  copySaveFiles(exported,mirror)
  if(old!=null && continued.isNotEmpty()) {
   val observed=metadata.observe(files,initialImport=false,persist=false)
   metadata.write(old.copy(currentContentSha256=observed.contentSha256,runs=observed.runs+continued))
  }
 }
 private fun requireExportUnchanged(exported:VerifiedExport) {
  validateExport(exported.root)
  if(uploadTarget.get()==null && Snapshots.fileHashes(exported.root,adapter)!=exported.snapshot.manifest.files)
   throw AndroidSaveChangedException(CoreMessages.ANDROID_ACTUAL_GAME_PROGRESS_CHANGED_SINCE_COMPARISON_COMPARE_AGAIN_NO_GAME_FILES_WERE_REPLACED.text())
 }
 private fun copySaveFiles(source:VerifiedExport,destination:Path) {
  source.entries.forEach {(name,bytes)->
   val path=SafePaths.checked(destination.resolve(name));Files.createDirectories(path.parent)
   Files.write(path,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)
   Files.setLastModifiedTime(path,Files.getLastModifiedTime(source.root.resolve(name),LinkOption.NOFOLLOW_LINKS))
  }
  if(uploadTarget.get()==null)require(Snapshots.fileHashes(destination,adapter)==source.snapshot.manifest.files){CoreMessages.ANDROID_STAGING_COPY_FAILED_CONTENT_VERIFICATION.text()}
 }
 fun <T> withRestoreReadScope(action:Callable<T>):T=store.withReadScope(scratch.resolve("read-cache")) {action.call()}
 /** Freeze object bytes only for this operation; publication still forces fresh reads. */
 fun <T> withUploadReadScope(action:Callable<T>):T {
  if(simpleEnabled) {val own=uploadTarget.get()==null;if(own)uploadTarget.set(verifiedTarget());try{return action.call()}finally{if(own)uploadTarget.remove()}}
  return store.withReadScope(scratch.resolve("read-cache")) {
  val ownTarget=uploadTarget.get()==null
  if(ownTarget)uploadTarget.set(verifiedTarget())
  try {session.withUploadReceiptScope {action.call()}} finally {if(ownTarget)uploadTarget.remove()}
 }
 }
 private fun <T> uploadReadLocked(action:()->T):T=locked {if(simpleEnabled)action() else session.withUploadReceiptScope {store.withReadScope(scratch.resolve("read-cache"),action)}}
 private fun <T> restoreReadLocked(action:()->T):T=locked {store.withReadScope(scratch.resolve("read-cache"),action)}
 private fun <T> locked(mutation:Boolean=true,action:()->T):T {
  DiagnosticTiming.start(DiagnosticPhase.OPERATION_LOCK_WAIT).use {timing->
   return synchronized(this) {
    FileChannel.open(SafePaths.checked(base.resolve("android-facade.lock")),StandardOpenOption.CREATE,StandardOpenOption.WRITE).use {channel->
     val lock=try {channel.tryLock()}catch(_:OverlappingFileLockException){null}
     checkNotNull(lock){CoreMessages.ANDROID_ANOTHER_ANDROID_SYNC_OPERATION_IS_RUNNING_WAIT_FOR_IT_TO_FINISH.text()}
     lock.use {timing.close();try {action()} finally {if(mutation)mutationEpoch.incrementAndGet()}}
    }
   }
  }
 }
 companion object {
  private val sharedControlledSessions=AndroidControlledSessions()
  private val sharedMutationEpochs=ConcurrentHashMap<Path,AtomicLong>()
  private val sharedHomeContinuity=ConcurrentHashMap<Path,HomeContinuity>()
  @JvmStatic fun cancelControlledSession(root:Path,token:String){sharedControlledSessions.cancel(SafePaths.checked(root),token)}
  @JvmStatic fun hasUnresolvedRestore(root:Path):Boolean {val base=SafePaths.checked(root);val dir=SafePaths.checked(base.resolve("restore-journals").resolve(AndroidGameIdentity.LOCAL_STATE_GENERATION));if(!Files.exists(dir,LinkOption.NOFOLLOW_LINKS))return false;require(Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS)){CoreMessages.ANDROID_RESTORE_RECOVERY_STORAGE_IS_DAMAGED.text()};return Files.list(dir).use {it.anyMatch {p->p.fileName.toString().endsWith(".json")}}}
  private fun <T> immutable(values:List<T>):List<T> = Collections.unmodifiableList(ArrayList(values))
  private fun <K,V> immutableMap(values:Map<K,V>):Map<K,V> = Collections.unmodifiableMap(LinkedHashMap(values))
  private fun deleteTree(root:Path){if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return;Files.walk(root).use {it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)}}
  private fun keepsLocalProgress(c:OnboardingComparison)=c.kind==OnboardingKind.DIFFERENT && c.baselineLineage==OnboardingLineage.SAME_TIP && c.baselineId!=null && c.observedTips==setOf(c.baselineId) && !c.retryablePending
  private fun description(c:OnboardingComparison)=when(c.kind) {OnboardingKind.BOTH_EMPTY->CoreMessages.ANDROID_NO_LOCAL_OR_CLOUD_SAVES_WERE_FOUND.text();OnboardingKind.LOCAL_ONLY->CoreMessages.ANDROID_ONLY_THIS_DEVICE_HAS_SAVES_UPLOAD_PRESERVES_THEM_IN_THE_CLOUD.text();OnboardingKind.REMOTE_ONLY->CoreMessages.ANDROID_CLOUD_SAVES_ARE_AVAILABLE_THE_LOCAL_GAME_SAVE_DIRECTORY_IS_EMPTY.text();OnboardingKind.IDENTICAL->CoreMessages.ANDROID_LOCAL_AND_CLOUD_SAVE_BYTES_MATCH.text();OnboardingKind.DIFFERENT->if(c.baselineLineage==OnboardingLineage.SAME_TIP)CoreMessages.ANDROID_LOCAL_PROGRESS_IS_AHEAD_OF_THE_SHARED_BASELINE_AUTOMATIC_UPLOAD_CAN_CONTINUE.text() else CoreMessages.ANDROID_LOCAL_AND_CLOUD_SAVES_DIFFER_REVIEW_VERSIONS_BEFORE_RESTORING_OR_MERGING.text();OnboardingKind.BRANCHED->CoreMessages.ANDROID_MULTIPLE_CLOUD_BRANCHES_EXIST_EACH_IS_PRESERVED_CHOOSE_EXPLICITLY_OR_PREVIEW_A_SAFE_MERGE.text();OnboardingKind.BLOCKED->c.blocker ?: "Comparison is blocked."}
  private fun version(commit:AppendCommit,tip:Boolean,reason:String)=version(commit.snapshot,commit.id,tip,reason)
  private fun version(m:Manifest,commitId:String?,tip:Boolean,reason:String)=Version(m.id,commitId,m.directoryMetadata?.displayName?.takeUnless(String::isBlank) ?: "Snapshot ${m.id.take(8)}",m.saveVersions?.summary() ?: m.version,m.files.size,m.metadata?.capturedAt,m.metadata?.device?.displayName,tip,reason)
 }
}

class AndroidTargetChangedException(message:String):IllegalStateException(message),ReasonedSyncFailure {override val syncBlockReason get()=SyncBlockReason.TARGET_CHANGED}
private class AndroidSaveChangedException(message:String):IllegalArgumentException(message),ReasonedSyncFailure {override val syncBlockReason get()=SyncBlockReason.SAVE_NOT_STABLE}
