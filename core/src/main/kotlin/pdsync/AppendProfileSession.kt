package pdsync

import java.nio.file.Path
import kotlin.io.path.*

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
 versions: ProfileVersions = ShatteredProfileVersions, captureQuietMillis: Long = 2000
) {
 private val local = ProfileSession(config, stateRoot, adapter, versions, captureQuietMillis)
 val config get() = local.config
 val root get() = local.root
 val adapter get() = local.adapter
 val directory get() = local.directory
 private val repository = AppendRepository(directory.resolve("append-v2"), adapter, config.profile)
 private val applyMarker = directory.resolve("append-v2/apply-required.txt")

 fun history() = local.history()
 fun deviceIdentity() = local.deviceIdentity()
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
   repository.prepare(local.capture(stopped, null), listOfNotNull(baseline), operation = local.operationMetadata())
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
  val restored = local.restore(chosen, stopped)
  repository.setBaseline(commit.id, graph)
  SafePaths.checked(applyMarker).deleteExisting()
  restored
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
  local.guard(stopped); noLegacyPending(); appliedOnly()
  require(repository.pending() == null) { "请先重试或明确归档待上传操作" }
  val graph = discover(store)
  if (graph.tips != conflict.observedTips) return@locked AppendPushResult.Diverged(preserveConflict(graph, store, stopped))
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
   if (currentFiles() != chosen.manifest.files) local.restore(snapshot(plan), stopped)
  }
  // Apply precedes publication. Failed network work can only retry this exact chosen snapshot.
  // Changes after this point remain dirty relative to the published baseline.
  publish(plan, store, stopped)
 }

 /** Explicit confirmation removes retry markers only, after every original is kept in history. */
 fun archivePending() = local.locked {
  repository.pending()?.let { preserve(it, "已归档追加上传操作") }
  local.archiveLegacyPending()
  repository.clearPending()
  SafePaths.checked(applyMarker).deleteIfExists()
 }
}
