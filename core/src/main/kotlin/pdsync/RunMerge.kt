package pdsync

import pdsync.i18n.CoreMessages
import java.util.UUID

class RunMergeException(message: String): IllegalStateException(CoreMessages.MERGE_FAILED_1_S_SAVES_WERE_NOT_MODIFIED.text(message))
data class RunMergeResult(val snapshot: Snapshot, val slots: Int, val addedSlots: Int, val decisions: List<String>)

/** Plans and copies complete verified run units in memory; never touches a save directory/network. */
object RunMergePlanner {
 private data class Run(val slot: String,val identity: RunIdentity,val snapshot: Snapshot,val files: Map<String,ByteArray>)
 private data class Input(val snapshot: Snapshot,val runs: Map<String,Run>,val globals: Map<String,ByteArray>)
 private fun fail(message: String): Nothing = throw RunMergeException(message)
 private fun input(snapshot: Snapshot, adapter: GameAdapter): Input {
  val files=Snapshots.verify(snapshot,adapter)
  val versions=ShatteredVersions.detect(files)
  if(versions.runs.values.any { it!=896 })fail(CoreMessages.ONLY_THE_SHATTERED_3_3_8_WRITE_FORMAT_IS_CURRENTLY_VERIFIED.text())
  val metadata=snapshot.manifest.directoryMetadata ?: fail(CoreMessages.SNAPSHOT_HAS_NO_RUN_IDENTITIES_ESTABLISH_A_SHARED_IDENTITY_BASELINE_FIRST.text())
  metadata.validated(snapshot.manifest.files)
  if((versions.runs.keys+versions.inactiveSlots).any { !Regex("game[1-6]").matches(it) })fail(CoreMessages.SAVES_EXCEED_THE_VERIFIED_SIX_SLOT_RANGE.text())
  val runs=versions.runs.keys.associate { slot ->
   val identity=metadata.runs.getValue(slot)
   val id=identity.runUUID ?: fail(CoreMessages.RUN_IDENTITY_FOR_1_S_IS_NOT_VERIFIED_IT_CANNOT_BE_GUESSED_FROM_A_SLOT_OR_SEED.text(slot))
   id to Run(slot,identity,snapshot,files.filterKeys { it.startsWith("$slot/") })
  }
  val globals=files.filterKeys { !it.startsWith("game") }
  return Input(snapshot,runs,globals)
 }

 fun plan(base: Snapshot, branches: List<Snapshot>, target: TargetGame, context: SnapshotCaptureContext, adapter: GameAdapter = Shattered): RunMergeResult {
  if(!target.trusted || (target.mergeFormat!="shattered-896" && (target.displayVersion!="3.3.8"||target.versionCode!=896)))fail(CoreMessages.SLOT_MERGING_HAS_NOT_BEEN_VERIFIED_FOR_THE_SELECTED_GAME_VERSION.text())
  require(branches.isNotEmpty() && branches.size<=MAX_COMMIT_PARENTS+1)
  val all=listOf(base)+branches
  if(all.any { !adapter.acceptsManifest(it.manifest)||it.manifest.profile!=base.manifest.profile })fail(CoreMessages.PROFILE_MISMATCH.text())
  val packages=all.map { recordedGamePackage(it.manifest) }
  val source=all.map { it.manifest.gameSource }.distinct().singleOrNull()
  val knownPackages=packages.filterNotNull().distinct()
  if(knownPackages.size>1 || packages.any { it==null })fail(CoreMessages.VALIDATION_GAME_PACKAGES_DIFFER_OR_PACKAGE_IDENTITY_IS_UNKNOWN.text())
  val packageName=knownPackages.single()
  val before=input(base,adapter); val inputs=branches.map { input(it,adapter) }
  val globals=GlobalRecordMerge.plan(before.globals,inputs.map { it.globals })
  val globalFiles=globals.files
  val outputRuns=mutableListOf<Run>();val decisions=globals.decisions.toMutableList()
  val ids=(before.runs.keys+inputs.flatMap { it.runs.keys }).sorted()
  for(id in ids){
   val old=before.runs[id]; val variants=inputs.map { it.runs[id] }
    .filter { it?.identity?.contentSha256!=old?.identity?.contentSha256 }.distinctBy { it?.identity?.contentSha256 }
   when {
    variants.isEmpty() -> (inputs.first().runs[id] ?: old)?.let { outputRuns+=it }
    variants.size==1 -> variants.single()?.let { outputRuns+=it }
    variants.any { it==null } -> fail(CoreMessages.RUN_1_S_HAS_A_CONFLICT_BETWEEN_DELETION_AND_FURTHER_PROGRESS.text(id.take(8)))
    else -> variants.filterNotNull().forEach { run ->
     outputRuns+=run.copy(identity=run.identity.copy(runUUID=UUID.randomUUID().toString(),parentRunUUIDs=listOf(id),previousRunUUID=null))
    }
   }
   decisions+="${id.take(8)}："+when { variants.size>1->CoreMessages.KEEP_1_S_COMPLETE_BRANCHES.text(variants.size);variants.singleOrNull()==null&&variants.isNotEmpty()->CoreMessages.USE_DELETION.text();variants.isEmpty()->CoreMessages.KEEP_ORIGINAL_CONTENTS.text();else->CoreMessages.USE_THE_ONLY_CHANGE.text() }
  }
  if(outputRuns.size>6)fail(CoreMessages.TEXT_1_S_GAME_SLOTS_ARE_NEEDED_BUT_CAPACITY_IS_ONLY_6.text(outputRuns.size))
  val occupied=mutableSetOf<Int>();val merged=globalFiles.toMutableMap();val identities=linkedMapOf<String,RunIdentity>()
  val times=mutableMapOf<String,String>()
  globalFiles.forEach { (path,bytes) ->
   val source=(listOf(before)+inputs).firstOrNull { it.globals[path]?.contentEquals(bytes)==true }
   // Synthesized global bundles are new content; never claim an unrelated source modification time.
   times[path]=source?.snapshot?.manifest?.metadata?.fileModifiedAt?.get(path) ?: context.capturedAt
  }
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
  if(merged.isEmpty())fail(CoreMessages.THE_RESULT_CONTAINS_NO_VERIFIABLE_SAVE_DATA.text())
  val files=merged.mapValues { hash(it.value) }
  val metadata=DirectorySnapshotMetadata(displayName=branches.first().manifest.directoryMetadata?.displayName.orEmpty(),contentSha256=contentSha256(files),runs=identities).validated(files)
  val provenance=if(times.keys==files.keys)SnapshotMetadata(device=context.device,capturedAt=context.capturedAt,fileModifiedAt=times).validated(files.keys) else null
  val snapshot=Snapshots.fromFiles(merged,adapter,base.manifest.profile,"Shattered 3.3.8",metadata,provenance)
  val annotated=snapshot.copy(manifest=snapshot.manifest.copy(saveVersions=ShatteredVersions.detect(merged),gameSource=source,gamePackage=packageName))
  return RunMergeResult(annotated,outputRuns.size,maxOf(0,outputRuns.size-inputs.first().runs.size),decisions)
 }
}
