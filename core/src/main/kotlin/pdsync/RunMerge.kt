package pdsync

import java.util.UUID

class RunMergeException(message: String): IllegalStateException("合并失败：$message。未修改存档。")
data class RunMergeResult(val snapshot: Snapshot, val slots: Int, val addedSlots: Int, val decisions: List<String>)

/** Plans and copies complete verified run units in memory; never touches a save directory/network. */
object RunMergePlanner {
 private data class Run(val slot: String,val identity: RunIdentity,val snapshot: Snapshot,val files: Map<String,ByteArray>)
 private data class Input(val snapshot: Snapshot,val runs: Map<String,Run>,val globals: Map<String,ByteArray>)
 private fun fail(message: String): Nothing = throw RunMergeException(message)
 private fun input(snapshot: Snapshot): Input {
  val files=Snapshots.verify(snapshot,Shattered)
  val versions=ShatteredVersions.detect(files)
  if(versions.runs.values.any { it!=896 })fail("当前仅验证 Shattered 3.3.8 写入格式")
  val metadata=snapshot.manifest.directoryMetadata ?: fail("快照缺少单局身份，需要先建立共同身份基线")
  metadata.validated(snapshot.manifest.files)
  if((versions.runs.keys+versions.inactiveSlots).any { !Regex("game[1-6]").matches(it) })fail("存在超出已验证六槽范围的存档")
  val runs=versions.runs.keys.associate { slot ->
   val identity=metadata.runs.getValue(slot)
   val id=identity.runUUID ?: fail("$slot 的单局身份尚未确认，不能按槽位或种子猜测")
   id to Run(slot,identity,snapshot,files.filterKeys { it.startsWith("$slot/") })
  }
  val globals=files.filterKeys { !it.startsWith("game") }
  return Input(snapshot,runs,globals)
 }

 fun plan(base: Snapshot, branches: List<Snapshot>, target: TargetGame, context: SnapshotCaptureContext): RunMergeResult {
  if(target.displayVersion!="3.3.8"||target.versionCode!=896||!target.trusted)fail("所选游戏版本尚未验证槽位合并能力")
  require(branches.isNotEmpty() && branches.size<=MAX_COMMIT_PARENTS+1)
  val all=listOf(base)+branches
  if(all.any { it.manifest.game!=base.manifest.game||it.manifest.profile!=base.manifest.profile })fail("档案不一致")
  val before=input(base); val inputs=branches.map(::input)
  val globalHash: (Map<String,ByteArray>)->String = { contentSha256(it.mapValues { e->hash(e.value) }) }
  val originalGlobal=globalHash(before.globals)
  val changedGlobals=inputs.map { it.globals }.filter { globalHash(it)!=originalGlobal }.distinctBy(globalHash)
  if(changedGlobals.size>1)fail("排行榜、成就或其他全局记录同时出现不同修改，尚无安全的语义合并规则")
  val globalFiles=changedGlobals.singleOrNull() ?: before.globals
  val outputRuns=mutableListOf<Run>();val decisions=mutableListOf<String>()
  val ids=(before.runs.keys+inputs.flatMap { it.runs.keys }).sorted()
  for(id in ids){
   val old=before.runs[id]; val variants=inputs.map { it.runs[id] }
    .filter { it?.identity?.contentSha256!=old?.identity?.contentSha256 }.distinctBy { it?.identity?.contentSha256 }
   when {
    variants.isEmpty() -> (inputs.first().runs[id] ?: old)?.let { outputRuns+=it }
    variants.size==1 -> variants.single()?.let { outputRuns+=it }
    variants.any { it==null } -> fail("单局 ${id.take(8)} 存在删除与继续推进的冲突")
    else -> variants.filterNotNull().forEach { run ->
     outputRuns+=run.copy(identity=run.identity.copy(runUUID=UUID.randomUUID().toString(),parentRunUUIDs=listOf(id),previousRunUUID=null))
    }
   }
   decisions+="${id.take(8)}："+when { variants.size>1->"保留 ${variants.size} 个完整分支";variants.singleOrNull()==null&&variants.isNotEmpty()->"采用删除";variants.isEmpty()->"保持原内容";else->"采用唯一改动" }
  }
  if(outputRuns.size>6)fail("需要 ${outputRuns.size} 个游戏槽位，容量只有 6 个")
  val occupied=mutableSetOf<Int>();val merged=globalFiles.toMutableMap();val identities=linkedMapOf<String,RunIdentity>()
  val times=mutableMapOf<String,String>()
  val globalSource=(listOf(before)+inputs).first { globalHash(it.globals)==globalHash(globalFiles) }.snapshot
  globalFiles.keys.forEach { path -> globalSource.manifest.metadata?.fileModifiedAt?.get(path)?.let { times[path]=it } }
  outputRuns.forEach { run ->
   val preferred=run.slot.removePrefix("game").toInt()
   val slot=if(preferred !in occupied)preferred else (1..6).first { it !in occupied };occupied+=slot
   val name="game$slot"
   run.files.forEach { (path,bytes) ->
    val destination="$name/${path.removePrefix(run.slot+"/")}"
    require(destination !in merged); merged[destination]=bytes
    run.snapshot.manifest.metadata?.fileModifiedAt?.get(path)?.let { times[destination]=it }
   }
   identities[name]=run.identity
  }
  if(merged.isEmpty())fail("结果没有可验证的存档内容")
  val files=merged.mapValues { hash(it.value) }
  val metadata=DirectorySnapshotMetadata(displayName=branches.first().manifest.directoryMetadata?.displayName.orEmpty(),contentSha256=contentSha256(files),runs=identities).validated(files)
  val provenance=if(times.keys==files.keys)SnapshotMetadata(device=context.device,capturedAt=context.capturedAt,fileModifiedAt=times).validated(files.keys) else null
  val snapshot=Snapshots.fromFiles(merged,Shattered,base.manifest.profile,"Shattered 3.3.8",metadata,provenance)
  return RunMergeResult(snapshot,outputRuns.size,maxOf(0,outputRuns.size-inputs.first().runs.size),decisions)
 }
}
