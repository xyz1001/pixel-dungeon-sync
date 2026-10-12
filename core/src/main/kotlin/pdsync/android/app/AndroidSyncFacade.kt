package pdsync.android.app

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
 val identity: AndroidGameIdentity, private val suppliedStore: AppendStore?, private val quietMillis: Long
) {
 constructor(root:Path, connection:AndroidConnection, bridge:AndroidGameBridge, identity:AndroidGameIdentity):
  this(root,connection,bridge,identity,null,0)
 private val validatedConnection=AppConfig(endpoint=connection.endpoint,profile=connection.profile,username=connection.username).validated()
 private val adapter=AndroidGameAdapter(identity)
 private val currentScope=hash(listOf(validatedConnection.endpoint,validatedConnection.profile,connection.username,identity.packageName,identity.originalSourceIdentity,identity.namespace).joinToString("\n").encodeToByteArray())
 private val base=SafePaths.checked(workspaceRoot).also { Files.createDirectories(it) }
 private val legacyScope=hash(listOf(validatedConnection.endpoint,validatedConnection.profile,connection.username).joinToString("\n").encodeToByteArray())
 private val legacyJournalPath=base.resolve("android-restore.json")
 private val legacyWorkspacePath=base.resolve("connections").resolve(legacyScope)
 private val legacyBindingPath=base.resolve("legacy-workspaces").resolve("$currentScope.json")
 private val legacyRecoveryActive=isLegacyRecoveryCandidate()
 private val scope=if(hasVerifiedLegacyBinding() || legacyRecoveryActive)legacyScope else currentScope
 private val workspace=SafePaths.checked(base.resolve("connections").resolve(scope)).also { Files.createDirectories(it) }
 private val mirror=SafePaths.checked(workspace.resolve("mirror")).also { Files.createDirectories(it) }
 private val state=workspace.resolve("state")
 private val scratch=SafePaths.checked(workspace.resolve("transfers")).also { Files.createDirectories(it) }
 private val journals=SafePaths.checked(base.resolve("restore-journals")).also { Files.createDirectories(it) }
 private val journalPath=if(legacyRecoveryActive)legacyJournalPath else journals.resolve("${identity.originalSourceIdentity}.json")
 private val owner=Any()
 private val config=validatedConnection.copy(saveRoot=mirror.toString(),targetGame="android-package:${identity.packageName}")
 private var pinnedTarget:AndroidGameTarget?=null
 private val versions=AndroidProfileVersions(identity,adapter,::verifiedTarget)
 private val store:AppendStore by lazy {
  val delegate=suppliedStore ?: AppendOnlyWebDav(config.endpoint,adapter.id,config.profile,connection.username,connection.password)
  object:AppendStore by delegate {
   override fun writeObject(id:String,bytes:ByteArray) { verifiedTarget();delegate.writeObject(id,bytes) }
   override fun writeCommit(id:String,bytes:ByteArray) { verifiedTarget();delegate.writeCommit(id,bytes) }
  }
 }
 private val session by lazy { AppendProfileSession(config,state,adapter,versions,quietMillis) { "Android" } }
 private val mirrorStopped:()->Boolean={verifiedTarget();true}

 class ConnectionStatus(val isSuccess:Boolean,val message:String,val hasRemote:Boolean)
 class Version(val snapshotId:String,val commitId:String?,val displayName:String,val versionSummary:String,val fileCount:Int,val capturedAt:String?,val deviceLabel:String?,val isTip:Boolean,val reason:String) {
  override fun toString()="$displayName · $fileCount files · $versionSummary"
 }
 class Inspection internal constructor(internal val owner:Any,internal val comparison:OnboardingComparison?,val kind:String,val message:String,val localFileCount:Int,remoteVersions:List<Version>,val isRetryablePending:Boolean,val canAdoptRemote:Boolean,val needsRestoreRecovery:Boolean,internal val blockReason:SyncBlockReason?=comparison?.blockReason,internal val history:SyncHistory?=null) {
  val remoteVersions=immutable(remoteVersions)
  val canAutoUpload get()=!needsRestoreRecovery && comparison?.let(::automaticEligible)==true
  val canCompleteWithoutTransfer get()=!needsRestoreRecovery && comparison?.let { it.kind==OnboardingKind.BOTH_EMPTY || it.kind==OnboardingKind.IDENTICAL && it.baselineLineage==OnboardingLineage.SAME_TIP }==true
  val canKeepLocalProgress get()=!needsRestoreRecovery && comparison?.let { it.kind==OnboardingKind.DIFFERENT && it.baselineLineage==OnboardingLineage.SAME_TIP && it.baselineId!=null && it.observedTips==setOf(it.baselineId) && !it.retryablePending }==true
 }
 class OperationResult(val message:String,val isConflict:Boolean,val isPending:Boolean,val snapshotId:String?)
 class AutoUploadResult(val status:String,val message:String,val snapshotId:String?,val needsUserChoice:Boolean,reason:SyncBlockReason?=null) {
  val reasonCode=reason?.name
 }
 class MergePreview(val isAllowed:Boolean,val message:String,val slotCount:Int,decisions:List<String>) { val decisions=immutable(decisions) }
 class LocalPreview(val fileCount:Int,val summary:SaveSummary?,val message:String)
 @Serializable private data class RestoreJournal(val schema:Int=2,val profileScope:String,val gameSourceIdentity:String,val gamePackage:String,val installedApkSha256:String,val sourceApkSha256:String,val expectedBefore:Map<String,String>,val expectedAfter:Map<String,String>,val action:String,val ready:Boolean=false)
 @Serializable private data class LegacyRestoreJournal(val schema:Int=1,val profileScope:String,val expectedBefore:Map<String,String>,val expectedAfter:Map<String,String>,val action:String,val ready:Boolean=false)
 @Serializable private data class LegacyWorkspaceBinding(val schema:Int=1,val gameSourceIdentity:String,val gamePackage:String,val namespace:String,val currentScope:String,val legacyScope:String)

 fun hasUnresolvedRestore()=locked { anyUnresolvedRestore() }
 fun testConnection()=connectionCheck(false)
 fun connect()=connectionCheck(true)
 private fun connectionCheck(create:Boolean)=locked {
  if(suppliedStore!=null) {
   require(!create){"Namespace creation requires the WebDAV transport."}
   val graph=AppendRepository(state.resolve("connection-probe"),adapter,config.profile).discover(store).also {it.requireSettled()}
   ConnectionStatus(true,"Connection verified.",graph.commits.isNotEmpty())
  } else {
   val setup=ConnectionSetup(config,adapter.id);setup.update(config,connection.password)
   when(val result=if(create)setup.connect() else setup.testConnection()) {
    is ConnectionTestResult.Success -> ConnectionStatus(true,if(create)"WebDAV connected; the sync folders are ready." else "WebDAV connection verified.",result.hasRemote)
    is ConnectionTestResult.Failure -> ConnectionStatus(false,result.message,false)
   }
  }
 }
 fun inspect()=locked { inspectLocked() }
 private fun inspectLocked():Inspection {
  if(anyUnresolvedRestore()) return Inspection(owner,null,"BLOCKED","A game restore needs verification or recovery. Upload is blocked until it is resolved.",currentFiles().size,emptyList(),false,false,true,SyncBlockReason.RESTORE_RECOVERY_REQUIRED)
  freshExport { replaceMirror(it) }
  val comparison=session.compareOnboarding(store,mirrorStopped)
  val remote=comparison.remoteEntries.map { version(it.commit,true,it.entry.reason) }
  return try {
   comparison.remoteEntries.forEach { versions.detect(session.loadSnapshot(it.commit.snapshot.id)) }
   // Capture the same verified observation for presentation. Rendering never re-exports files.
   val history=if(comparison.kind==OnboardingKind.BLOCKED)null else session.syncHistory()
   Inspection(owner,comparison,comparison.kind.name,comparison.blocker ?: description(comparison),currentFiles().size,remote,comparison.retryablePending,comparison.canAdoptRemote,false,history=history)
  } catch(e:UnsupportedAndroidSaveFormatException) {
   Inspection(owner,null,"BLOCKED",e.message ?: "Unsupported cloud save format.",currentFiles().size,remote,false,false,false,SyncBlockReason.UNSUPPORTED_SAVE_FORMAT)
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
   if(comparison.kind in setOf(OnboardingKind.IDENTICAL,OnboardingKind.BOTH_EMPTY)) return@locked AutoUploadResult("UNCHANGED","No changed local snapshot needs uploading.",null,false)
   if(!automaticEligible(comparison)) {
    val choice=comparison.observedTips.size>1 || comparison.kind==OnboardingKind.DIFFERENT || comparison.retryablePending
    val reason=comparison.blockReason ?: when {choice->SyncBlockReason.HISTORY_CONFLICT;comparison.kind==OnboardingKind.REMOTE_ONLY->SyncBlockReason.REMOTE_ONLY;comparison.baselineLineage==OnboardingLineage.BLOCKED->SyncBlockReason.BASELINE_UNVERIFIED;else->SyncBlockReason.COMPARISON_FAILED}
    return@locked autoBlocked(if(choice)"SKIPPED_CONFLICT" else "SKIPPED_UNCERTAIN",reason,choice || comparison.kind==OnboardingKind.REMOTE_ONLY)
   }
   checkActualUnchanged(comparison.localFiles)
   when(val result=session.onboardingUpload(comparison,store,mirrorStopped)) {
    is AppendPushResult.Success -> AutoUploadResult("UPLOADED",if(result.remoteAhead)"Snapshot uploaded, but the cloud advanced; review before restoring." else "Background snapshot uploaded and verified.",result.commit.snapshot.id,result.remoteAhead)
    is AppendPushResult.Diverged -> AutoUploadResult("SKIPPED_CONFLICT","A remote branch arrived during upload. Both immutable branches are preserved; choose explicitly.",result.conflict.branches.find {it.commit.id==result.conflict.publishedLocalCommit}?.snapshot?.manifest?.id,true,SyncBlockReason.HISTORY_CONFLICT)
   }
  } catch(e:Exception) {
   var reason=SyncBlockReason.fromFailure(e)
   if(e is UnsupportedAndroidSaveFormatException || e is AndroidTargetChangedException) return@locked autoBlocked("SKIPPED_UNCERTAIN",reason,true)
   if(e is InterruptedException)Thread.currentThread().interrupt()
   else if(!anyUnresolvedRestore()) {
    val observation=runCatching {session.compareOnboarding(store,mirrorStopped)}.getOrNull()
    if(observation!=null && !automaticEligible(observation) && (observation.observedTips.size>1 || observation.kind==OnboardingKind.DIFFERENT || observation.retryablePending)) return@locked autoBlocked("SKIPPED_CONFLICT",observation.blockReason ?: SyncBlockReason.HISTORY_CONFLICT,true)
   }
   if(reason==SyncBlockReason.COMPARISON_FAILED)reason=SyncBlockReason.UPLOAD_NOT_CONFIRMED
   autoBlocked("RETRY_LATER",reason,false)
  }
 }
 private fun autoBlocked(status:String,reason:SyncBlockReason,needsChoice:Boolean)=AutoUploadResult(status,reason.safeMessage,null,needsChoice,reason)
 fun upload(inspection:Inspection)=locked { val comparison=checked(inspection,true);checkActualUnchanged(comparison.localFiles);pushResult(session.onboardingUpload(comparison,store,mirrorStopped)) }
 fun listVersions():List<Version> = locked {
  requireNoRecovery();verifiedTarget();session.readRemote(store)
  val history=session.syncHistory();val cloud=history.cloud.map {version(it,it.id in history.graph.tips,"Verified cloud history")};val ids=cloud.map {it.snapshotId}.toSet()
  immutable(cloud+session.history().filter {it.manifest.id !in ids}.map {version(it.manifest,null,false,it.reason)})
 }
 fun restoreSelected(inspection:Inspection,snapshotId:String,allowUniqueReplacement:Boolean)=locked {
  val comparison=checked(inspection);checkActualUnchanged(comparison.localFiles)
  val history=session.syncHistory();val commit=history.graph.commits.values.singleOrNull {it.snapshot.id==snapshotId}
  val snapshot=if(commit!=null)AppendRepository(session.directory.resolve("append-v2"),adapter,config.profile).download(store,commit) else session.loadSnapshot(snapshotId)
  versions.checkRestore(snapshot,config.targetGame)
  require(allowUniqueReplacement || comparison.localFiles.isEmpty() || history.currentProtected){"Current game progress has no verified cloud copy. Explicit replacement confirmation is required."}
  var result:OnboardingRestoreResult?=null
  prepareGameRestore(comparison.localFiles,snapshot.manifest.files,"restore") {
   when {commit!=null && commit.id in comparison.observedTips -> result=session.onboardingRestore(comparison,snapshotId,store,mirrorStopped)
    commit!=null -> session.restoreCloudHistory(snapshotId,store,allowUniqueReplacement,comparison.localFiles,mirrorStopped)
    else -> session.restoreHistory(snapshotId,mirrorStopped)}
  }
  val published=result as? OnboardingRestoreResult.Published
  if(published?.result is AppendPushResult.Diverged)pushResult(published.result)
  else OperationResult(if(session.hasPending())"Selected version restored and verified in the game. A restore revision is pending; Upload publishes it." else "Selected version restored and verified in the game.",false,session.hasPending(),snapshotId)
 }
 fun resolveConflict(inspection:Inspection,snapshotId:String)=locked {
  val comparison=checked(inspection);checkActualUnchanged(comparison.localFiles);val conflict=currentConflict(comparison)
  val branch=conflict.branches.singleOrNull {it.snapshot.manifest.id==snapshotId} ?: error("Select a snapshot from the currently observed conflict branches.")
  var result:AppendPushResult?=null
  prepareGameRestore(comparison.localFiles,branch.snapshot.manifest.files,"resolution") {result=session.resolve(conflict,snapshotId,store,mirrorStopped)}
  pushResult(requireNotNull(result),"Branch choice applied to the game and published.")
 }
 fun previewMerge(inspection:Inspection)=locked {
  val comparison=checked(inspection);checkActualUnchanged(comparison.localFiles)
  try {versions.requireMerge();val result=session.previewMerge(mergeObservation(comparison),store,mirrorStopped);MergePreview(true,"Safe merge is available. Review the decisions before applying.",result.slots,result.decisions)}
  catch(e:RunMergeException){MergePreview(false,e.message ?: "This conflict cannot be safely merged.",0,emptyList())}
 }
 fun mergeConflict(inspection:Inspection)=locked {
  val comparison=checked(inspection);checkActualUnchanged(comparison.localFiles);versions.requireMerge();val conflict=mergeObservation(comparison)
  val preview=session.previewMerge(conflict,store,mirrorStopped);var result:AppendPushResult?=null
  prepareGameRestore(comparison.localFiles,preview.snapshot.manifest.files,"merge") {result=if(comparison.observedTips.size>=2)session.merge(conflict,store,mirrorStopped) else session.mergeCurrent(store,mirrorStopped)}
  pushResult(requireNotNull(result),"Merged game saves applied, verified and published.")
 }
 fun adoptIdentical(inspection:Inspection)=locked {val c=checked(inspection);checkActualUnchanged(c.localFiles);OperationResult("Identical local and cloud saves linked. No game files were replaced.",false,false,session.adoptOnboardingRemote(c,store,mirrorStopped).snapshot.id)}
 fun confirmRunIdentity(inspection:Inspection,slot:String,sameRun:Boolean)=locked {val c=checked(inspection);checkActualUnchanged(c.localFiles);session.confirmRunIdentity(slot,c.localFiles,sameRun,mirrorStopped);OperationResult("Run identity recorded. Compare again before merging.",false,session.hasPending(),null)}
 fun recoverRestore()=locked {val journal=readJournal();require(journal.ready){"Restore preparation was interrupted. Originals and backups are retained. Explicitly discard the interrupted operation and compare again."};require(currentFiles()==journal.expectedAfter){"The prepared restore changed. Recovery is blocked; backups are retained."};applyPrepared(journal);OperationResult("Game restore verified. Any frozen upload remains available for explicit retry.",false,session.hasPending(),null)}
 fun discardInterruptedRestore()=locked {readJournal(false);freshExport {exported->val retained=Files.createDirectories(scratch.resolve("abandoned-${UUID.randomUUID()}"));copySaveFiles(mirror,retained);session.archivePending();replaceMirror(exported);clearCompletedJournal()};OperationResult("Interrupted operation archived. Current game files were left unchanged; compare again.",false,false,null)}
 fun archivePending()=locked {requireNoRecovery();session.archivePending()}
 fun previewLocal()=locked {requireNoRecovery();freshExport {exported->val count=capture(exported).manifest.files.size;LocalPreview(count,runCatching {SaveSummary.read(exported)}.getOrNull(),if(count==0)"The selected game's save directory is empty." else "Verified $count save files for ${identity.displayName}.")}}
 /** Standalone refresh explicitly creates a new observation. Use overload for an existing UI. */
 fun historyPresentation():SyncHistory=locked {val inspection=inspectLocked();requireNotNull(inspection.history){inspection.message}}
 fun historyPresentation(inspection:Inspection):SyncHistory=locked {checked(inspection);requireNotNull(inspection.history){inspection.message}}
 fun completeOnboarding(inspection:Inspection)=locked {val c=checked(inspection);checkActualUnchanged(c.localFiles);if(inspection.canKeepLocalProgress)session.confirmLocalProgressReady(c,store,mirrorStopped) else session.confirmOnboardingReady(c,store,mirrorStopped)}
 fun renameCurrent(inspection:Inspection,name:String)=locked {checkActualUnchanged(checked(inspection).localFiles);session.renameCurrent(name,mirrorStopped)}
 fun renameHistory(snapshotId:String,name:String)=locked {requireNoRecovery();verifiedTarget();session.renameHistory(snapshotId,name)}

 private fun verifiedTarget():AndroidGameTarget {
  val target=bridge.targetGame()
  if(target.identity!=identity)throw AndroidTargetChangedException("Selected game identity or cloud binding changed. Reopen that game and compare again.")
  if(pinnedTarget!=null && pinnedTarget!=target)throw AndroidTargetChangedException("The selected installed APK changed during this sync session. Reopen the game selection and compare again.")
  if(pinnedTarget==null)pinnedTarget=target
  return target
 }
 private fun checked(inspection:Inspection,allowPending:Boolean=false):OnboardingComparison {
  requireNoRecovery();require(inspection.owner===owner){"This comparison belongs to another connection. Compare again."}
  val c=requireNotNull(inspection.comparison){"Complete a safe comparison first."}
  require(c.kind!=OnboardingKind.BLOCKED || allowPending && c.retryablePending){inspection.message}
  require(currentFiles()==c.localFiles){"The local mirror changed. Compare again."};return c
 }
 private fun currentConflict(c:OnboardingComparison):AppendConflict {
  val conflict=session.inspectConflict(store,mirrorStopped) ?: error("There is no current cloud conflict. Compare again.")
  require(conflict.observedTips==c.observedTips && conflict.observedLocalFiles==c.localFiles){"Cloud branches or current game files changed. Compare again before selecting a branch."};return conflict
 }
 private fun mergeObservation(c:OnboardingComparison)=if(c.observedTips.size>=2)currentConflict(c) else AppendConflict(c.observedTips,emptyList(),c.localFiles)
 private fun pushResult(result:AppendPushResult,success:String="Snapshot uploaded and verified.")=when(result) {
  is AppendPushResult.Diverged -> OperationResult("Both branches are preserved. Compare again and explicitly choose a branch or preview a safe merge.",true,session.hasPending(),null)
  is AppendPushResult.Success -> OperationResult(when {result.remoteAhead->"Snapshot is preserved, but another cloud branch advanced. Compare again.";result.localDirty->"Snapshot uploaded; newer local changes remain. Compare again.";else->success},false,session.hasPending(),result.commit.snapshot.id)
 }
 private fun prepareGameRestore(before:Map<String,String>,after:Map<String,String>,action:String,stage:()->Unit) {
  val target=verifiedTarget();val journal=RestoreJournal(profileScope=scope,gameSourceIdentity=identity.originalSourceIdentity,gamePackage=identity.packageName,installedApkSha256=target.apkSha256,sourceApkSha256=target.sourceApkSha256,expectedBefore=before,expectedAfter=after,action=action)
  writeJournal(journal)
  try {
   stage()
   when(currentFiles()) {
    after -> {if(before==after){clearCompletedJournal();return};val ready=journal.copy(ready=true);writeJournal(ready);applyPrepared(ready)}
    before -> clearCompletedJournal()
    else -> error("Unexpected staged files. Recovery is required; game files were not changed.")
   }
  }catch(e:Exception){if(currentFiles()==after && !Files.exists(session.directory.resolve("append-v2/apply-required.txt")))writeJournal(journal.copy(ready=true)) else if(currentFiles()==before)Files.deleteIfExists(journalPath);throw e}
 }
 private fun applyPrepared(journal:RestoreJournal) {
  val staged=versions.annotate(Snapshots.capture(mirror,adapter,config.profile,"Android staged restore",null))
  require(staged.manifest.files==journal.expectedAfter);versions.checkRestore(staged,config.targetGame)
  val actual=freshExport {capture(it).manifest.files}
  if(actual!=journal.expectedAfter) {
   require(actual==journal.expectedBefore){"Game progress changed after confirmation. Recovery will not overwrite it. Backups are retained."}
   val temp=Files.createTempDirectory(scratch,"restore-")
   try {copySaveFiles(mirror,temp);verifiedTarget();bridge.restoreFrom(temp,immutableMap(journal.expectedBefore));verifiedTarget()}finally{deleteTree(temp)}
  }
  require(freshExport {capture(it).manifest.files}==journal.expectedAfter){"The helper restore could not be verified. Upload remains blocked; recover or inspect retained backups."}
  clearCompletedJournal()
 }
 private fun readJournal(requireOriginalBuild:Boolean=true):RestoreJournal {
  require(Files.exists(journalPath)){"Recovery belongs to another game or an older installation. Reopen that original selection; the recovery gate cannot be cleared here."}
  val target=verifiedTarget();val text=SafePaths.boundedRead(journalPath,2*1024*1024).decodeToString(throwOnInvalidSequence=true);requireUniqueJsonKeys(text)
  val journal=if(legacyRecoveryActive) {val legacy=decodeLegacyJournal(text);require(target.legacyRecoveryReceiptVerified){"Legacy recovery requires the original verified receipt."};require(legacy.profileScope==legacyScope && isKnownLegacyIdentity());RestoreJournal(profileScope=scope,gameSourceIdentity=identity.originalSourceIdentity,gamePackage=identity.packageName,installedApkSha256=target.apkSha256,sourceApkSha256=target.sourceApkSha256,expectedBefore=legacy.expectedBefore,expectedAfter=legacy.expectedAfter,action=legacy.action,ready=legacy.ready)} else json.decodeFromString<RestoreJournal>(text)
  require(journal.schema==2 && journal.profileScope==scope && journal.gameSourceIdentity==identity.originalSourceIdentity && journal.gamePackage==identity.packageName){"The interrupted restore belongs to another game or WebDAV connection. Reopen that selection first."}
  require(!requireOriginalBuild || target.apkSha256==journal.installedApkSha256 && target.sourceApkSha256==journal.sourceApkSha256){"The installed game build changed during restore recovery. Reopen the original build; the recovery journal and backups are retained."}
  contentSha256(journal.expectedBefore);contentSha256(journal.expectedAfter);require(journal.expectedAfter.isNotEmpty());return journal
 }
 private fun writeJournal(journal:RestoreJournal) {require(!legacyRecoveryActive){"Finish the original legacy recovery before starting another restore."};val bytes=json.encodeToString(journal).encodeToByteArray();require(bytes.size<=2*1024*1024){"Restore recovery metadata exceeds the supported size; no game files were changed."};SafePaths.atomicWrite(journalPath,bytes)}
 private fun isKnownLegacyIdentity()=identity.originalSourceIdentity=="32cd68da7bd2cdb467330cf33ca7c0e9b18eaae94ecf0b4b944313def1732f89" && identity.packageName=="com.shatteredpixel.shatteredpixeldungeon" && identity.namespace=="shattered"
 private fun decodeLegacyJournal(text:String):LegacyRestoreJournal {requireUniqueJsonKeys(text);return json.decodeFromString<LegacyRestoreJournal>(text).also {require(it.schema==1 && Regex("[0-9a-f]{64}").matches(it.profileScope));contentSha256(it.expectedBefore);contentSha256(it.expectedAfter);require(it.expectedAfter.isNotEmpty())}}
 private fun isLegacyRecoveryCandidate():Boolean {if(!isKnownLegacyIdentity() || !Files.exists(legacyJournalPath,LinkOption.NOFOLLOW_LINKS))return false;if(decodeLegacyJournal(SafePaths.boundedRead(legacyJournalPath,2*1024*1024).decodeToString(throwOnInvalidSequence=true)).profileScope!=legacyScope)return false;require(Files.isDirectory(SafePaths.checked(legacyWorkspacePath),LinkOption.NOFOLLOW_LINKS)){"Original legacy recovery workspace is missing; retained data was not replaced."};return true}
 private fun expectedLegacyBinding()=LegacyWorkspaceBinding(gameSourceIdentity=identity.originalSourceIdentity,gamePackage=identity.packageName,namespace=identity.namespace,currentScope=currentScope,legacyScope=legacyScope)
 private fun hasVerifiedLegacyBinding():Boolean {if(!Files.exists(SafePaths.checked(legacyBindingPath),LinkOption.NOFOLLOW_LINKS))return false;require(isKnownLegacyIdentity());val text=SafePaths.boundedRead(legacyBindingPath,2*1024*1024).decodeToString(throwOnInvalidSequence=true);requireUniqueJsonKeys(text);require(json.decodeFromString<LegacyWorkspaceBinding>(text)==expectedLegacyBinding());require(Files.isDirectory(SafePaths.checked(legacyWorkspacePath),LinkOption.NOFOLLOW_LINKS));return true}
 private fun clearCompletedJournal(){if(legacyRecoveryActive){require(pinnedTarget?.legacyRecoveryReceiptVerified==true);SafePaths.atomicWrite(legacyBindingPath,json.encodeToString(expectedLegacyBinding()).encodeToByteArray())};Files.delete(journalPath)}
 private fun requireNoRecovery(){require(!anyUnresolvedRestore()){ "A game restore needs recovery before another sync action. Reopen the original game and connection; changing games cannot dismiss recovery." }}
 private fun anyUnresolvedRestore()=hasUnresolvedRestore(base)
 private fun checkActualUnchanged(expected:Map<String,String>)=freshExport {if(capture(it).manifest.files!=expected)throw AndroidSaveChangedException("Actual game progress changed since comparison. Compare again; no game files were replaced.")}
 private fun capture(root:Path)=Snapshots.capture(root,adapter,config.profile,"Android save export",null)
 private fun currentFiles()=capture(mirror).manifest.files
 private fun <T> freshExport(block:(Path)->T):T {val temp=Files.createTempDirectory(scratch,"export-");try {verifiedTarget();bridge.exportTo(temp);verifiedTarget();validateExport(temp);val snapshot=capture(temp);Snapshots.verify(snapshot,adapter);versions.detect(snapshot);return block(temp)}finally{deleteTree(temp)}}
 private fun validateExport(root:Path) {var count=0;Files.walk(root).use {paths->paths.forEach {p->require(++count<=16384){"Bridge export has too many entries."};require(!Files.isSymbolicLink(p)){"Bridge export contains a symbolic link."};if(p!=root){val name=root.relativize(p).toString().replace('\\','/');require(adapter.includes(name)&&!isDirectoryMetadata(name)){"Bridge exported an unrecognized save entry."};require(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))}}}}
 private fun replaceMirror(exported:Path) {
  // A fresh verified helper export with identical complete bytes is a filesystem no-op.
  // Do not replace directory identities/timestamps or the directory metadata sidecar.
  if(currentFiles()==capture(exported).manifest.files)return
  Files.walk(mirror).use {paths->paths.sorted(Comparator.reverseOrder()).forEach {p->if(p!=mirror){val name=mirror.relativize(p).toString().replace('\\','/');if(adapter.includes(name)&&!isDirectoryMetadata(name))Files.delete(p)}}}
  copySaveFiles(exported,mirror)
 }
 private fun copySaveFiles(source:Path,destination:Path) {val snapshot=capture(source);Snapshots.verify(snapshot,adapter).forEach {(name,bytes)->val path=SafePaths.checked(destination.resolve(name));Files.createDirectories(path.parent);Files.write(path,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);Files.setLastModifiedTime(path,Files.getLastModifiedTime(source.resolve(name),LinkOption.NOFOLLOW_LINKS))};require(capture(destination).manifest.files==snapshot.manifest.files){"Staging copy failed content verification."}}
 private fun <T> locked(action:()->T):T=synchronized(this) {FileChannel.open(SafePaths.checked(base.resolve("android-facade.lock")),StandardOpenOption.CREATE,StandardOpenOption.WRITE).use {channel->val lock=try {channel.tryLock()}catch(_:OverlappingFileLockException){null};checkNotNull(lock){"Another Android sync operation is running. Wait for it to finish."};lock.use {action()}}}
 companion object {
  @JvmStatic fun hasUnresolvedRestore(root:Path):Boolean {val base=SafePaths.checked(root);if(Files.exists(base.resolve("android-restore.json"),LinkOption.NOFOLLOW_LINKS))return true;val dir=SafePaths.checked(base.resolve("restore-journals"));if(!Files.exists(dir,LinkOption.NOFOLLOW_LINKS))return false;require(Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS)){"Restore recovery storage is damaged."};return Files.list(dir).use {it.anyMatch {p->p.fileName.toString().endsWith(".json")}}}
  private fun automaticEligible(c:OnboardingComparison):Boolean {
   if(c.observedTips.size>1)return false
   if(c.retryablePending) {val pending=c.pendingCommit ?: return false;return c.observedTips==pending.parents.toSet() || c.observedTips==setOf(pending.id)}
   if(c.kind==OnboardingKind.BLOCKED || c.localFiles.isEmpty())return false
   return c.kind==OnboardingKind.LOCAL_ONLY || c.kind==OnboardingKind.DIFFERENT && c.baselineLineage==OnboardingLineage.SAME_TIP
  }
  private fun <T> immutable(values:List<T>):List<T> = Collections.unmodifiableList(ArrayList(values))
  private fun <K,V> immutableMap(values:Map<K,V>):Map<K,V> = Collections.unmodifiableMap(LinkedHashMap(values))
  private fun deleteTree(root:Path){if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return;Files.walk(root).use {it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)}}
  private fun description(c:OnboardingComparison)=when(c.kind) {OnboardingKind.BOTH_EMPTY->"No local or cloud saves were found.";OnboardingKind.LOCAL_ONLY->"Only this device has saves. Upload preserves them in the cloud.";OnboardingKind.REMOTE_ONLY->"Cloud saves are available; the local game save directory is empty.";OnboardingKind.IDENTICAL->"Local and cloud save bytes match.";OnboardingKind.DIFFERENT->if(c.baselineLineage==OnboardingLineage.SAME_TIP)"Local progress is ahead of the shared baseline. Automatic upload can continue." else "Local and cloud saves differ. Review versions before restoring or merging.";OnboardingKind.BRANCHED->"Multiple cloud branches exist. Each is preserved; choose explicitly or preview a safe merge.";OnboardingKind.BLOCKED->c.blocker ?: "Comparison is blocked."}
  private fun version(commit:AppendCommit,tip:Boolean,reason:String)=version(commit.snapshot,commit.id,tip,reason)
  private fun version(m:Manifest,commitId:String?,tip:Boolean,reason:String)=Version(m.id,commitId,m.directoryMetadata?.displayName?.takeUnless(String::isBlank) ?: "Snapshot ${m.id.take(8)}",m.saveVersions?.summary() ?: m.version,m.files.size,m.metadata?.capturedAt,m.metadata?.device?.displayName,tip,reason)
 }
}

class AndroidTargetChangedException(message:String):IllegalStateException(message),ReasonedSyncFailure {override val syncBlockReason get()=SyncBlockReason.TARGET_CHANGED}
private class AndroidSaveChangedException(message:String):IllegalArgumentException(message),ReasonedSyncFailure {override val syncBlockReason get()=SyncBlockReason.SAVE_NOT_STABLE}
