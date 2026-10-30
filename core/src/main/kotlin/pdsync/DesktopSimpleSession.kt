package pdsync

import java.nio.file.Path

/** Desktop file adapter for the same three-field protocol used by Android.
 * The legacy result types below are UI adapters only; no legacy repository or wire format is used.
 */
class DesktopSimpleSession(config:AppConfig,stateRoot:Path,
 val adapter:GameAdapter=Shattered,private val versions:ProfileVersions=ShatteredProfileVersions,private val suppliedStore:SimpleSaveStore?=null) {
 private val local=ProfileSession(config,desktopAccountStateRoot(stateRoot,config),adapter,versions,0)
 val config get()=local.config
 val root get()=local.root
 private val baselinePath=local.directory.resolve("simple/baseline-hash")
 private var remote:SimpleSaveStore?=null
 private var nodes=emptyList<SaveNode>()
 private var latest:String?=null
 private var currentName:String?=null
 private var acceptedFiles:Map<String,String>?=null
 private fun protocol(store:SimpleSaveStore)=SimpleSaveSync(store,baselinePath)
 private fun active()=requireNotNull(remote){"Read the current cloud profile first"}
 private fun bind(store:AppendOnlyWebDav):SimpleSaveStore=(suppliedStore ?: store.simpleStore()).also {remote=it}
 fun hasPending()=false
 fun retryablePendingSourceAvailable()=false
 fun history()=emptyList<HistoryEntry>() // No local ZIP archive or synthetic timestamps.
 fun observedAt(id:String):String?=null
 fun deviceIdentity()=local.deviceIdentity()
 fun targetInfo()=local.targetInfo()
 fun currentVersions()=local.currentVersions()
 fun observedFiles():Map<String,String> = local.locked {Snapshots.readLocal(root,adapter).entries.mapValues {hash(it.value)}}
 fun baselineFiles():Map<String,String>? {
  val files=observedFiles()
  val id=if(java.nio.file.Files.exists(baselinePath))java.nio.file.Files.readString(baselinePath) else null
  return if(files.isNotEmpty() && contentSha256(files)==id)files else acceptedFiles
 }
 private fun baseline():String?=if(java.nio.file.Files.exists(baselinePath))java.nio.file.Files.readString(baselinePath) else null
 fun syncHistory(previous:SyncHistory?=null):SyncHistory {
  val read=Snapshots.readLocal(root,adapter);val files=read.entries.mapValues {hash(it.value)}
  return SyncHistory(CommitGraph(emptyMap(),emptyMap()),baseline(),files,currentName.orEmpty(),
   if(files.isEmpty())null else SaveSummary.readVerified(root,read.entries),nodes.associate {it.hashId to it.name},
   simple=SimpleHistory(nodes,latest,files.takeIf {it.isNotEmpty()}?.let(::contentSha256)))
 }
 fun readHomeHistory(store:AppendOnlyWebDav,fullHistory:Boolean=false):SyncHistory {
  val cloud=bind(store);val previousLatest=latest;latest=cloud.latest()
  nodes=if(fullHistory)SimpleSaveProtocol.history(cloud,latest) else latest?.let {id->
   val current=requireNotNull(cloud.node(id))
   if(previousLatest==id && nodes.firstOrNull()?.hashId==id)listOf(current)+nodes.drop(1) else listOf(current)
  } ?: emptyList()
  return syncHistory()
 }
 private fun manifest(node:SaveNode,files:Map<String,String> = emptyMap(),zip:ByteArray=byteArrayOf()) =
  Manifest(game=adapter.id,profile=config.profile,version="",id=node.hashId,parent=node.parentId,sha256=hash(zip),files=files)
 private fun commit(node:SaveNode,files:Map<String,String> = emptyMap()) =
  AppendCommit(id=node.hashId,parents=listOfNotNull(node.parentId),snapshot=manifest(node,files),objectBytes=0)
 private fun conflict(files:Map<String,String>,tip:String):AppendConflict {
  val node=nodes.find {it.hashId==tip} ?: requireNotNull(active().node(tip))
  val remoteSnapshot=Snapshot(manifest(node),byteArrayOf())
  return AppendConflict(setOf(tip),listOf(AppendBranch(commit(node),remoteSnapshot)),files,overwriteTargetSnapshotId=tip)
 }
 private fun upload(store:AppendOnlyWebDav,stopped:()->Boolean,confirmed:String?=null):AppendPushResult = local.locked {
  val cloud=bind(store);var files=emptyMap<String,String>()
  val result=protocol(cloud).upload({}, {
   local.guard(stopped)
   val read=Snapshots.readLocal(root,adapter)
   val snapshot=Snapshots.capture(read,adapter,config.profile,"",null)
   versions.annotate(VerifiedSnapshot.captured(snapshot,read.entries))
   files=snapshot.manifest.files
   require(files.isNotEmpty())
   SaveUpload(files,snapshot.zip,currentName ?: nodes.find {it.hashId==baseline()}?.name ?: "Save ${contentSha256(files).take(6)}")
  },confirmed,{if(Thread.currentThread().isInterrupted)throw InterruptedException()})
  when(result) {
   is SaveUploadResult.Conflict -> {latest=result.latest;AppendPushResult.Diverged(conflict(files,result.latest))}
   is SaveUploadResult.Complete -> {
    latest=result.node.hashId;acceptedFiles=files
    val known=nodes.associateBy {it.hashId};val chain=mutableListOf(result.node);val seen=mutableSetOf(result.node.hashId)
    var next=result.node.parentId
    while(next!=null && seen.add(next)) {val ancestor=known[next] ?: break;chain+=ancestor;next=ancestor.parentId}
    nodes=chain
    AppendPushResult.Success(commit(result.node,files),false,reused=!result.uploaded)
   }
  }
 }
 fun pushFromHome(history:SyncHistory?,store:AppendOnlyWebDav,stopped:()->Boolean):HomePushResult = HomePushResult.Published(upload(store,stopped))
 fun automaticPush(store:AppendOnlyWebDav,stopped:()->Boolean):AutomaticPushResult = AutomaticPushResult.Published(upload(store,stopped))
 fun overwriteCloudConflict(conflict:AppendConflict,selected:String,store:AppendOnlyWebDav,stopped:()->Boolean):AppendPushResult {
  require(selected==conflict.overwriteTargetSnapshotId)
  return upload(store,stopped,selected)
 }
 fun inspectConflict(store:AppendOnlyWebDav,stopped:()->Boolean):AppendConflict? {
  val view=readHomeHistory(store);local.guard(stopped)
  return if(SimpleSaveProtocol.kind(view.currentFiles,view.baseline,latest)=="BRANCHED")conflict(view.currentFiles,requireNotNull(latest)) else null
 }
 private fun restore(id:String,store:SimpleSaveStore,stopped:()->Boolean,expectedBefore:Map<String,String>?=null):RestoreResult = local.locked {
  require(nodes.any {it.hashId==id}){"Select a current history entry"}
  local.guard(stopped)
  val node=requireNotNull(store.node(id));val zip=store.objectBytes(id)
  val entries=SimpleSaveProtocol.restoreEntries(zip,adapter);val files=entries.mapValues {hash(it.value)}
  val raw=Snapshot(manifest(node,files,zip),zip)
  val verified=VerifiedSnapshot.captured(raw,entries)
  val annotated=versions.annotate(verified)
  val result=local.restore(verified.withManifest(annotated.manifest),stopped,expectedBefore)
  protocol(store).acceptBaseline(id);acceptedFiles=files
  result
 }
 fun restoreRemote(id:String,store:AppendOnlyWebDav,stopped:()->Boolean)=restore(id,bind(store),stopped)
 fun restoreCloudHistory(id:String,store:AppendOnlyWebDav,allowUniqueReplacement:Boolean,expectedBefore:Map<String,String>?,stopped:()->Boolean)=restore(id,bind(store),stopped,expectedBefore)
 fun restoreHistory(id:String,stopped:()->Boolean)=restore(id,active(),stopped)
 fun checkRestore(id:String) {require(nodes.any {it.hashId==id});requireNotNull(targetInfo())}
 fun inspectDirectory(stopped:()->Boolean):DirectorySnapshotMetadata = local.locked {
  local.guard(stopped);val files=Snapshots.readLocal(root,adapter).entries.mapValues {hash(it.value)}
  DirectorySnapshotMetadata(displayName=currentName.orEmpty(),contentSha256=contentSha256(files))
 }
 fun renameCurrent(name:String,stopped:()->Boolean):DirectorySnapshotMetadata {
  local.guard(stopped)
  currentName=sanitizeSnapshotName(name)
  val metadata=inspectDirectory(stopped)
  nodes.find {it.hashId==metadata.contentSha256}?.let {old->
   val updated=old.copy(name=requireNotNull(currentName));active().rename(updated)
   nodes=nodes.map {if(it.hashId==old.hashId)updated else it}
  }
  return metadata
 }
 fun renameHistory(id:String,name:String,observed:SyncHistory) {
  require(observed.simple?.nodes?.any {it.hashId==id}==true)
  val node=requireNotNull(nodes.find {it.hashId==id}).copy(name=sanitizeSnapshotName(name))
  active().rename(node);nodes=nodes.map {if(it.hashId==id)node else it}
 }
 fun confirmRunIdentity(slot:String,files:Map<String,String>,sameRun:Boolean,stopped:()->Boolean):DirectorySnapshotMetadata = error("Run identity editing is not part of the current protocol")
 fun createSnapshot(stopped:()->Boolean):Nothing = error("Local archives are not part of the current protocol")
 fun archivePending():Nothing = error("There is no pending upload in the current protocol")
}
