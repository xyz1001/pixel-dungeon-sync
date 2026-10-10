package pdsync

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

/**
 * Desktop-only v2 facade. ProfileSession supplies filesystem safeguards and the same root OS lock,
 * but none of its Store/CAS methods are called. Old history remains readable; old retry work is
 * preserved until explicitly archived, never interpreted as an append-only baseline.
 */
class AppendProfileSession(
 config: AppConfig, stateRoot: Path, adapter: GameAdapter = Shattered,
 versions: ProfileVersions = ShatteredProfileVersions, private val captureQuietMillis: Long = 2000
) {
 private val local = ProfileSession(config, stateRoot, adapter, versions, captureQuietMillis)
 val config get() = local.config
 val root get() = local.root
 val adapter get() = local.adapter
 val directory get() = local.directory
 private val repository = AppendRepository(directory.resolve("append-v2"), adapter, config.profile)
 private val applyMarker = directory.resolve("append-v2/apply-required.txt")
 private val comparisonOwner = Any()

 fun history() = local.history()
 private val aliasesPath get()=directory.resolve("display-names.json")
 private fun aliases(): Map<String,String> {
  if(!SafePaths.checked(aliasesPath).exists())return emptyMap()
  val text=SafePaths.boundedRead(aliasesPath,MAX_MANIFEST_BYTES).decodeToString();requireUniqueJsonKeys(text)
  return json.decodeFromString<Map<String,String>>(text).also { values -> require(values.size<=MAX_COMMITS);values.forEach { (id,name)->require(validCommitId(id)&&name==sanitizeSnapshotName(name)) } }
 }
 fun renameHistory(snapshotId:String,name:String) = local.locked {
  require(repository.cachedGraph().commits.values.any { it.snapshot.id==snapshotId }) { "历史记录不存在" }
  SafePaths.atomicWrite(aliasesPath,json.encodeToString(aliases()+(snapshotId to sanitizeSnapshotName(name))).encodeToByteArray())
 }
 fun syncHistory(): SyncHistory = local.locked {
  val graph=repository.cachedGraph();val files=currentFiles()
  val baseline=repository.baseline()
  val transfer=directory.resolve("baseline-transfer.txt")
  val direction=if(SafePaths.checked(transfer).exists())SafePaths.boundedRead(transfer,128).decodeToString().split(' ').takeIf { it.size==2&&it[0]==baseline&&it[1] in setOf("upload","restore") }?.get(1) else null
  SyncHistory(graph,baseline,files,(local.directoryMetadata.read() as? SidecarRead.Valid)?.value?.displayName.orEmpty(),runCatching { SaveSummary.read(root) }.getOrNull(),aliases(),direction)
 }
 /** User-selected historical cloud content is downloaded only on an explicit restore action. */
 fun restoreCloudHistory(snapshotId:String,store:AppendStore,allowUniqueReplacement:Boolean,expectedBefore:Map<String,String>?=null,stopped:()->Boolean):RestoreResult = local.locked {
  local.guard(stopped);noLegacyPending();appliedOnly();require(repository.pending()==null){"请先处理待上传操作"}
  val graph=discover(store);val chosen=graph.commits.values.single { it.snapshot.id==snapshotId }
  val before=currentFiles()
  require(expectedBefore==null || before==expectedBefore) { "确认后本机存档已变化，请重新比较" }
  require(allowUniqueReplacement || before.isEmpty() || graph.commits.values.any { it.snapshot.files==before }) { "本机有独有进度，请明确选择覆盖或合并" }
  val snapshot=repository.download(store,chosen);local.remember(snapshot,"选择云端历史",repository.observedAt(chosen.id));local.checkRestore(snapshotId)
  var restored:RestoreResult?=null
  prepareAndApply(snapshot,listOfNotNull(repository.baseline()),"restore","恢复云端历史 · 待上传") { plan ->
   restored=local.restore(this.snapshot(plan),stopped,before)
  }
  requireNotNull(restored)
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
  require(captured.manifest.files==expectedFiles) { "确认期间内容发生变化，请重新检查" }
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
  local.remember(local.capture(stopped, null), "本地快照")
 }
 fun hasPending(): Boolean = local.locked { repository.pending() != null || local.legacyPending() != null || needsApply() }
 fun baseline(): String? = local.locked { repository.baseline() }
 private fun needsApply() = SafePaths.checked(applyMarker).exists()
 private fun appliedOnly() {
  require(!needsApply()) { "上次恢复未确认完成，原快照和备份仍保留。请先明确归档该操作，再重新选择恢复；不会直接上传未完成的恢复。" }
 }
 private fun noLegacyPending() {
  require(local.legacyPending() == null) { "旧版待上传快照仍保留。请先明确归档，再使用新的远端档案；不会将旧版重试直接上传为新版。" }
 }
 private fun discover(store: AppendStore): CommitGraph = repository.discover(store).also {
  it.requireSettled()
  repository.baseline()?.let { baseline -> require(baseline in it.commits) { "同步基线缺失或无法验证；未创建新的根提交" } }
 }
 private fun currentFiles(): Map<String, String> = Snapshots.capture(root, adapter, config.profile, "自动识别", null).manifest.files
 private fun snapshot(plan: UploadPlan) = Snapshot(plan.commit.snapshot, plan.zip)
 private fun preserve(plan: UploadPlan, reason: String) = local.remember(snapshot(plan), reason)
 private fun preserveConflict(graph: CommitGraph, store: AppendStore, stopped: () -> Boolean): AppendConflict {
  local.guard(stopped)
  val branches = graph.tips.sorted().map { id ->
   val commit = graph.commits.getValue(id)
   val snapshot = repository.download(store, commit)
   local.remember(snapshot, "冲突 · 已观察分支 ${id.take(8)}", repository.observedAt(id))
   AppendBranch(commit, snapshot)
  }
  // Record the real current files, not an older frozen upload. A subsequent choice must recheck.
  val current = currentFiles()
  local.guard(stopped)
  require(currentFiles() == current) { "读取冲突期间存档发生变化，请退出游戏后重试" }
  return AppendConflict(graph.tips, branches, current, repository.baseline())
 }
 private fun publish(plan: UploadPlan, store: AppendStore, stopped: () -> Boolean): AppendPushResult {
  preserve(plan, "追加同步 · ${plan.commit.kind}")
  repository.publish(store, plan)
  val graph = discover(store)
  require(plan.commit.id in graph.commits) { "刚发布的提交尚未完整可见；原操作保留，请重试" }
  local.remember(snapshot(plan), "追加同步 · ${plan.commit.kind}", repository.observedAt(plan.commit.id))
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

 /** Read every observed tip. A listing is an observation, never a promise of a global latest save. */
 fun readRemote(store: AppendStore): List<AppendRemoteEntry> = local.locked {
  val graph = discover(store)
  graph.tips.sorted().map { id ->
   val commit = graph.commits.getValue(id)
   AppendRemoteEntry(commit, local.remember(repository.download(store, commit), "云端已观察分支 ${id.take(8)}", repository.observedAt(id)))
  }
 }

 /** Ordinary fast-forward is allowed only for a clean baseline or a genuinely empty new target. */
 fun restoreRemote(snapshotId: String, store: AppendStore, stopped: () -> Boolean): RestoreResult = local.locked {
  restoreRemoteLocked(snapshotId, store, stopped)
 }
 private fun restoreRemoteLocked(snapshotId: String, store: AppendStore, stopped: () -> Boolean): RestoreResult {
  local.guard(stopped); noLegacyPending(); appliedOnly()
  require(repository.pending() == null) { "请先重试或明确归档待上传操作" }
  val graph = discover(store)
  require(graph.tips.size == 1) { "云端存在多个分支，不能作为普通恢复。请明确选择并发布冲突解决。" }
  val commit = graph.commits.getValue(graph.tips.single())
  require(commit.snapshot.id == snapshotId) { "已观察分支发生变化；没有恢复，请重新读取云端" }
  val before = currentFiles()
  val baseline = repository.baseline()
  if (baseline == null) require(before.isEmpty()) { "本地已有未同步进度，不能直接覆盖。请先同步保留本地分支，再明确选择冲突快照。" }
  else {
   require(before == graph.commits.getValue(baseline).snapshot.files) { "本地进度已变化，不能直接覆盖。请先同步保留本地分支。" }
   require(graph.descendsFrom(commit.id, baseline)) { "所选分支不是当前基线的后续，需明确解决冲突" }
  }
  val chosen = repository.download(store, commit)
  local.remember(chosen, "已校验云端恢复快照", repository.observedAt(commit.id))
  local.checkRestore(chosen.manifest.id)
  require(currentFiles() == before) { "验证期间本地存档发生变化，未恢复" }
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
   plan?.let { preserve(it, "未完成的本地恢复 · 已归档") }
   repository.clearPending()
   throw failure
  }
 }

 /** A history restore is new work parented at the actual baseline, not a rewind to the old ID. */
 fun restoreHistory(id: String, stopped: () -> Boolean): RestoreResult = local.locked {
  local.guard(stopped); noLegacyPending(); appliedOnly()
  require(repository.pending() == null) { "请先重试或明确归档待上传操作" }
  local.checkRestore(id)
  var restored: RestoreResult? = null
  prepareAndApply(local.loadSnapshot(id), listOfNotNull(repository.baseline()), "restore", "历史恢复 · 待追加发布") { plan ->
   restored = local.restore(snapshot(plan), stopped)
  }
  requireNotNull(restored)
 }

 /** Explicit whole-snapshot choice; every observed tip is retained as a resolution parent. */
 fun resolve(conflict: AppendConflict, chosenSnapshotId: String, store: AppendStore, stopped: () -> Boolean): AppendPushResult = local.locked {
  resolveLocked(conflict, chosenSnapshotId, store, stopped)
 }
 /** Preview and apply both repeat validation. Preview never changes game bytes or publishes data. */
 fun previewMerge(conflict: AppendConflict, store: AppendStore, stopped: () -> Boolean): RunMergeResult = local.locked {
  buildMerge(conflict,store,stopped)
 }
 /** Preflight before publishing a unique local branch; then retain both sources in the merge DAG. */
 fun mergeCurrent(store:AppendStore,stopped:()->Boolean):AppendPushResult = local.locked {
  val graph=discover(store);require(graph.tips.isNotEmpty()) { "没有可合并的云端分支" }
  val observed=AppendConflict(graph.tips,emptyList(),currentFiles())
  val preview=buildMerge(observed,store,stopped)
  local.checkRestore(preview.snapshot)
  val captured=local.capture(stopped,null)
  require(captured.manifest.files==observed.observedLocalFiles) { "预检后本机内容变化，请重新比较" }
  val localPlan=repository.prepare(captured,listOfNotNull(repository.baseline()),operation=local.operationMetadata())
  when(val uploaded=publish(localPlan,store,stopped)) {
   is AppendPushResult.Success -> uploaded
   is AppendPushResult.Diverged -> mergeLocked(uploaded.conflict,store,stopped)
  }
 }
 private fun buildMerge(conflict: AppendConflict, store: AppendStore, stopped: () -> Boolean): RunMergeResult {
  local.guard(stopped);noLegacyPending();appliedOnly()
  require(repository.pending()==null) { "请先处理待上传操作" }
  val graph=discover(store)
  require(graph.tips==conflict.observedTips && graph.tips.isNotEmpty()) { "冲突分支已变化，请重新检查" }
  require(currentFiles()==conflict.observedLocalFiles) { "本机内容已变化，请重新检查" }
  val baseline=repository.baseline() ?: throw RunMergeException("缺少共同基线，不能推断单局身份")
  val children=graph.tips+baseline
  val common=graph.commits.keys.filter { ancestor -> children.all { graph.descendsFrom(it,ancestor) } }
  val nearest=common.filter { candidate -> common.none { it!=candidate&&graph.descendsFrom(it,candidate) } }
  if(nearest.size!=1)throw RunMergeException("没有唯一可验证的共同祖先")
  val base=repository.download(store,graph.commits.getValue(nearest.single()))
  val current=local.capture(stopped,null)
  require(current.manifest.files==conflict.observedLocalFiles) { "采集期间内容发生变化，未合并" }
  val branches=graph.tips.sorted().map { id ->
   val commit=graph.commits.getValue(id)
   val downloaded=repository.download(store,commit)
   // Current local work continues this baseline tip; it is not a parallel branch of that tip.
   if(id==baseline)current else downloaded
  }.toMutableList()
  if(branches.none { it===current })branches.add(0,current) else { branches.remove(current);branches.add(0,current) }
  val operation=local.operationMetadata()
  return RunMergePlanner.plan(base,branches,local.targetInfo() ?: throw RunMergeException("尚未选择已验证游戏"),SnapshotCaptureContext(operation.device,operation.preparedAt))
 }
 fun merge(conflict: AppendConflict, store: AppendStore, stopped: () -> Boolean): AppendPushResult = local.locked {
  mergeLocked(conflict,store,stopped)
 }
 private fun mergeLocked(conflict:AppendConflict,store:AppendStore,stopped:()->Boolean):AppendPushResult {
  require(conflict.observedTips.size>=2) { "冲突分支已变化，请重新检查" }
  val result=buildMerge(conflict,store,stopped)
  // Capacity/identity/global/version checks have all completed before an apply marker or writes.
  local.remember(result.snapshot,"逐局合并 · 已完成预检")
  local.checkRestore(result.snapshot.manifest.id)
  val latest=discover(store)
  if(latest.tips!=conflict.observedTips)return AppendPushResult.Diverged(preserveConflict(latest,store,stopped))
  require(currentFiles()==conflict.observedLocalFiles) { "预检后本机内容发生变化，未合并" }
  val plan=prepareAndApply(result.snapshot,latest.tips.sorted(),"resolution","逐局合并 · ${result.slots} 个槽位") { plan ->
   require(currentFiles()==conflict.observedLocalFiles) { "合并前本机内容发生变化，未覆盖" }
   local.restore(snapshot(plan),stopped,conflict.observedLocalFiles)
  }
  // A network failure after verified local apply leaves this exact plan pending for retry.
  // It is not advertised as an atomic transaction across the local disk and WebDAV.
  return publish(plan,store,stopped)
 }
 private fun resolveLocked(conflict: AppendConflict, chosenSnapshotId: String, store: AppendStore, stopped: () -> Boolean): AppendPushResult {
  local.guard(stopped); noLegacyPending(); appliedOnly()
  require(repository.pending() == null) { "请先重试或明确归档待上传操作" }
  val graph = discover(store)
  if (graph.tips != conflict.observedTips) return AppendPushResult.Diverged(preserveConflict(graph, store, stopped))
  require(graph.tips.size >= 2) { "冲突分支已变化，请重新读取云端" }
  require(currentFiles() == conflict.observedLocalFiles) { "选择期间本地进度已变化；未覆盖。请重新同步保留当前分支。" }
  val branch = conflict.branches.single { it.snapshot.manifest.id == chosenSnapshotId }
  require(branch.commit.id in graph.tips && graph.commits[branch.commit.id] == branch.commit) { "选择不属于已观察分支" }
  // Re-download exact immutable bytes and preserve all branches before preparing a resolution.
  conflict.branches.forEach { local.remember(repository.download(store, it.commit), "冲突 · 原始分支", repository.observedAt(it.commit.id)) }
  val chosen = repository.download(store, branch.commit)
  if (currentFiles() != chosen.manifest.files) local.checkRestore(chosen.manifest.id)
  val plan = prepareAndApply(chosen, graph.tips.sorted(), "resolution", "冲突解决 · 明确选择 ${branch.commit.id.take(8)}") { plan ->
   require(currentFiles() == conflict.observedLocalFiles) { "验证期间本地进度已变化，未覆盖" }
   if (currentFiles() != chosen.manifest.files) local.restore(snapshot(plan), stopped,conflict.observedLocalFiles)
  }
  // Apply precedes publication. Failed network work can only retry this exact chosen snapshot.
  // Changes after this point remain dirty relative to the published baseline.
  return publish(plan, store, stopped)
 }

 /** Read-only save comparison. Remote verification/history caches may be populated; saves and
  * baseline never change, and no remote object/commit is published by this operation. */
 fun compareOnboarding(store: AppendStore, stopped: () -> Boolean): OnboardingComparison = try {
  local.locked {
   local.guard(stopped); local.validateRoot()
   val stability = SaveStabilityGuard(root, adapter, stopped, captureQuietMillis)
   val fingerprint = stability.awaitStable()
   val baseline = repository.baseline()
   if (needsApply()) return@locked blockedComparison("上次恢复尚未确认完成，请先明确归档并检查保留的快照与备份。", fingerprint, baseline)
   if (local.legacyPending() != null) return@locked blockedComparison("旧版待上传快照仍保留，请先明确归档；不会作为新版操作上传。", fingerprint, baseline)
   val pending = repository.pending()
   // A failed/missing page or object must not become an apparently empty cloud.
   val graph = discover(store)
   val entries = graph.tips.sorted().map { id ->
    val commit = graph.commits.getValue(id)
    AppendRemoteEntry(commit, local.remember(repository.download(store, commit), "首次比较 · 已校验云端分支 ${id.take(8)}", repository.observedAt(id)))
   }
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
   OnboardingComparison(kind, fingerprint.files, baseline, lineage, entries, graph.tips,
    blocker = if (pending == null) null else "已有冻结的待上传操作；重试会使用原快照，不会重新采集当前存档。",
    canAdoptRemote = kind == OnboardingKind.IDENTICAL && lineage in setOf(OnboardingLineage.NONE, OnboardingLineage.SAME_TIP, OnboardingLineage.ANCESTOR_OF_TIP),
    retryablePending = pending != null, pendingCommitId = pending?.commit?.id,
    owner = comparisonOwner, fingerprint = fingerprint, graph = graph, pendingCommit = pending?.commit)
  }
 } catch (failure: Exception) {
  if (failure is InterruptedException) Thread.currentThread().interrupt()
  val message = when (failure) {
   is SaveNotStableException -> "游戏正在运行、状态未知或存档仍在变化；比较已停止，请退出游戏并等待存档稳定后重试。"
   is LegacyProtocolException -> "发现旧版云端协议；已停止比较，不会当作空云端或自动迁移。"
   is UnsettledRemoteException -> "云端存在缺失、未完整上传或无法验证的提交；比较已停止，请稍后重试。"
   is RemoteIntegrityException -> "云端不可变历史校验失败；比较已停止，双方存档均未覆盖。"
   else -> "无法完成本地与完整云端历史的安全比较；请检查连接、目录和同步记录后重试。"
  }
  blockedComparison(message)
 }

 private fun blockedComparison(message: String, fingerprint: SaveFingerprint? = null, baseline: String? = null) =
  OnboardingComparison(OnboardingKind.BLOCKED, fingerprint?.files.orEmpty(), baseline, OnboardingLineage.BLOCKED,
   emptyList(), emptySet(), blocker = message, owner = comparisonOwner)

 /** Never trust UI-visible collections or an old comparison without repeating every safety gate. */
 private fun revalidateComparison(comparison: OnboardingComparison, store: AppendStore, stopped: () -> Boolean,
  allowPending: Boolean = false): CommitGraph {
  require(comparison.owner === comparisonOwner) { "比较不属于当前档案，请重新比较" }
  require(comparison.kind != OnboardingKind.BLOCKED || (allowPending && comparison.retryablePending)) { "请先完成安全比较或处理被阻止的操作" }
  local.guard(stopped); noLegacyPending(); appliedOnly(); local.validateRoot()
  val expected = requireNotNull(comparison.fingerprint) { "比较未完成，请重试" }
  val stability = SaveStabilityGuard(root, adapter, stopped, captureQuietMillis)
  require(stability.awaitStable() == expected) { "比较后本地存档发生变化；未使用旧选择，请重新比较" }
  require(repository.baseline() == comparison.baselineId) { "比较后同步基线已变化，请重新比较" }
  val pending = repository.pending()
  require(pending?.commit == comparison.pendingCommit) { "待上传操作已变化，请重新比较" }
  require(allowPending || pending == null) { "请先重试或明确归档待上传操作" }
  val graph = discover(store)
  require(graph == comparison.graph) { "比较后云端分支已变化；未使用旧选择，请重新比较" }
  stability.requireUnchanged(expected)
  return graph
 }

 /** Finish a no-action first-run choice only while its exact empty/already-linked state remains
  * current. This does not create a baseline or enable automatic uploads itself. */
 fun confirmOnboardingReady(comparison: OnboardingComparison, store: AppendStore, stopped: () -> Boolean) = local.locked {
  revalidateComparison(comparison, store, stopped)
  require(comparison.kind == OnboardingKind.BOTH_EMPTY ||
   (comparison.kind == OnboardingKind.IDENTICAL && comparison.baselineLineage == OnboardingLineage.SAME_TIP)) {
   "当前比较仍需明确选择上传、恢复或云端关联"
  }
 }

 /** Explicit association of equal bytes with an exact verified tip, never hash-based ancestry. */
 fun adoptOnboardingRemote(comparison: OnboardingComparison, store: AppendStore, stopped: () -> Boolean): AppendCommit = local.locked {
  var graph = revalidateComparison(comparison, store, stopped)
  require(comparison.kind == OnboardingKind.IDENTICAL && comparison.canAdoptRemote) { "不能自动关联无共同历史的分支，请明确保留并选择分支" }
  require(graph.tips.size == 1)
  val commit = graph.commits.getValue(graph.tips.single())
  require(commit.snapshot.files == comparison.localFiles)
  comparison.baselineId?.let { require(graph.descendsFrom(commit.id, it)) { "云端分支不是当前基线的后续；不能直接关联" } }
  local.remember(repository.download(store, commit), "明确采用云端关联", repository.observedAt(commit.id))
  // No game bytes are written or loaded. Restore-only target/version checks would incorrectly
  // reject equal global-record-only saves; future restores still use the unchanged version gate.
  graph = revalidateComparison(comparison, store, stopped)
  local.directoryMetadata.adopt(commit.snapshot.directoryMetadata,commit.snapshot.files)
  repository.setBaseline(commit.id, graph)
  markDirectorySynced(commit)
  commit
 }

 /** Upload is explicit, including a retry of an already frozen operation. Never freeze empty saves. */
 fun onboardingUpload(comparison: OnboardingComparison, store: AppendStore, stopped: () -> Boolean): AppendPushResult = local.locked {
  revalidateComparison(comparison, store, stopped, allowPending = true)
  val plan = repository.pending() ?: run {
   require(comparison.localFiles.isNotEmpty()) { "本地没有存档，不能上传空快照" }
   val captured = local.capture(stopped, null)
   require(captured.manifest.files == comparison.localFiles) { "比较后本地存档发生变化，请重新比较" }
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
 fun onboardingRestore(comparison: OnboardingComparison, snapshotId: String, store: AppendStore, stopped: () -> Boolean): OnboardingRestoreResult = local.locked {
  var graph = revalidateComparison(comparison, store, stopped)
  val selected = graph.tips.map(graph.commits::getValue).singleOrNull { it.snapshot.id == snapshotId }
   ?: throw IllegalArgumentException("所选快照不属于已比较的云端分支，请重新比较")
  val chosen = repository.download(store, selected)
  local.remember(chosen, "首次恢复 · 已校验选择", repository.observedAt(selected.id))
  if (chosen.manifest.files != comparison.localFiles) local.checkRestore(snapshotId)
  graph = revalidateComparison(comparison, store, stopped)
  val before = comparison.localFiles
  val baselineFiles = comparison.baselineId?.let { graph.commits.getValue(it).snapshot.files }
  val preserveLocal = before.isNotEmpty() && (comparison.baselineId == null || before != baselineFiles)
  if (preserveLocal) {
   val captured = local.capture(stopped, null)
   require(captured.manifest.files == before) { "比较后本地存档发生变化，未恢复" }
   SaveStabilityGuard(root, adapter, stopped, captureQuietMillis).requireUnchanged(requireNotNull(comparison.fingerprint))
   val plan = repository.prepare(captured, listOfNotNull(comparison.baselineId), operation = local.operationMetadata())
   val expectedTips = (graph.tips - plan.commit.parents.toSet()) + plan.commit.id
   publish(plan, store, stopped)
   graph = discover(store)
   if (graph.tips != expectedTips) return@locked OnboardingRestoreResult.Published(AppendPushResult.Diverged(preserveConflict(graph, store, stopped)))
   SaveStabilityGuard(root, adapter, stopped, captureQuietMillis).requireUnchanged(requireNotNull(comparison.fingerprint))
  }
  if (graph.tips.size > 1) {
   val conflict = preserveConflict(graph, store, stopped)
   require(conflict.observedLocalFiles == before) { "选择期间本地存档发生变化，未恢复" }
   return@locked OnboardingRestoreResult.Published(resolveLocked(conflict, snapshotId, store, stopped))
  }
  if (!preserveLocal && (comparison.baselineId == null || before == baselineFiles)) {
   return@locked OnboardingRestoreResult.Restored(restoreRemoteLocked(snapshotId, store, stopped))
  }
  // Local publication may have advanced the selected former tip. Retain that progress and record
  // the explicit older-snapshot choice as a new restore commit, never rewind the baseline. An
  // empty dirty target also creates only a nonempty chosen restore, never an empty local upload.
  val expectedGraph = graph
  require(graph.commits[selected.id] == selected) { "所选历史已变化，未恢复" }
  val parent = repository.baseline() ?: graph.tips.single()
  val currentFingerprint = SaveStabilityGuard(root, adapter, stopped, captureQuietMillis).awaitStable()
  require(currentFingerprint.files == before) { "选择期间本地存档发生变化，未恢复" }
  require(discover(store) == expectedGraph) { "云端分支已变化，请重新比较" }
  val plan = prepareAndApply(chosen, listOf(parent), "restore", "首次恢复 · 保留本地后明确选择云端") { plan ->
   SaveStabilityGuard(root, adapter, stopped, captureQuietMillis).requireUnchanged(currentFingerprint)
   if (before != chosen.manifest.files) local.restore(snapshot(plan), stopped)
  }
  OnboardingRestoreResult.Published(publish(plan, store, stopped))
 }

 /** Explicit confirmation removes retry markers only, after every original is kept in history. */
 fun archivePending() = local.locked {
  repository.pending()?.let { preserve(it, "已归档追加上传操作") }
  local.archiveLegacyPending()
  repository.clearPending()
  SafePaths.checked(applyMarker).deleteIfExists()
 }
}
