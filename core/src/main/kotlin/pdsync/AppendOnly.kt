package pdsync

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import kotlin.io.path.*

/** A transport page is only an observation, never a globally authoritative head. */
data class CommitPage(val ids: List<String>, val next: String? = null)
interface AppendStore {
 fun listCommits(cursor: String? = null): CommitPage
 fun readCommit(id: String): ByteArray?
 fun readObject(id: String): ByteArray?
 fun writeCommit(id: String, bytes: ByteArray)
 fun writeObject(id: String, bytes: ByteArray)
 fun legacyPresent(): Boolean
}

fun validCommitId(id: String) = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(id)
const val MAX_COMMITS = 10000
const val MAX_COMMIT_PARENTS = 64
const val MAX_REMOTE_SCAN_BYTES = 256L * 1024 * 1024

@Serializable data class AppendCommit(
 val schema: Int = 2,
 val id: String,
 val parents: List<String>,
 val snapshot: Manifest,
 val objectBytes: Int,
 val kind: String = "snapshot",
 val operation: CommitOperationMetadata? = null
)

/** Frozen exact publication bytes. Retry never recaptures a ZIP or rewrites ancestry. */
data class UploadPlan(val commit: AppendCommit, val manifestBytes: ByteArray, val zip: ByteArray)
class RemoteIntegrityException(message: String): IllegalStateException(message)
class UnsettledRemoteException(message: String): IllegalStateException(message)
class LegacyProtocolException: IllegalStateException("发现旧版 head.json。未自动迁移或写入；请停用旧客户端并明确导入，或使用新的远端档案。")

data class CommitGraph(val commits: Map<String, AppendCommit>, val pending: Map<String, String>) {
 val tips: Set<String> get() = commits.keys - commits.values.flatMap { it.parents }.toSet()
 fun requireSettled() { if (pending.isNotEmpty()) throw UnsettledRemoteException("云端有 ${pending.size} 个缺失、未完整上传或无法验证的提交；本地未被覆盖，请稍后重试") }
 fun descendsFrom(child: String, ancestor: String): Boolean {
  val remaining = ArrayDeque<String>(); val seen = mutableSetOf<String>(); remaining.add(child)
  while (remaining.isNotEmpty()) { val id = remaining.removeFirst(); if (id == ancestor) return true; if (seen.add(id)) commits[id]?.parents?.forEach { remaining.add(it) } }
  return false
 }
}

/** Caller serializes all access using the profile's OS FileChannel lock. */
class AppendRepository(private val directory: Path, private val adapter: GameAdapter, private val profile: String, private val clock: Clock = Clock.systemUTC()) {
 private val knownRoot = directory.resolve("known")
 private val observedRoot = directory.resolve("observed")
 private val observationRoot = directory.resolve("observations")
 private val pendingRoot = directory.resolve("uploads")
 private val marker = directory.resolve("active-plan.txt")
 private val baselinePath = directory.resolve("baseline.txt")
 private val baselineMarker = directory.resolve("baseline-established.txt")

 private fun checkCommit(commit: AppendCommit, expected: String = commit.id) {
  require(commit.schema == 2 && validCommitId(commit.id) && commit.id == expected) { "Invalid commit identity" }
  require(commit.parents.size <= MAX_COMMIT_PARENTS && commit.parents.distinct().size == commit.parents.size && commit.parents.all { validCommitId(it) && it != commit.id }) { "Invalid commit parents" }
  commit.operation?.validated()
  require(commit.kind in setOf("snapshot", "resolution", "restore", "legacy-import"))
  require(commit.kind == "resolution" || commit.parents.size <= 1) { "Ordinary commits cannot absorb branches" }
  require(commit.kind != "resolution" || commit.parents.size >= 2) { "Resolution must name multiple parents" }
  require(commit.kind != "legacy-import" || commit.parents.isEmpty())
  val snapshot = commit.snapshot
  require(snapshot.schema == 1 && snapshot.game == adapter.id && snapshot.profile == profile && snapshot.parent == null)
  snapshot.metadata?.validated(snapshot.files.keys)
  snapshot.directoryMetadata?.validated(snapshot.files)
  require(validCommitId(snapshot.id) && snapshot.id != commit.id && snapshot.sha256.matches(Regex("[0-9a-f]{64}")))
  require(snapshot.files.isNotEmpty() && snapshot.files.size <= MAX_SNAPSHOT_FILES && snapshot.files.values.all { it.matches(Regex("[0-9a-f]{64}")) })
  require(commit.objectBytes in 1..MAX_SNAPSHOT_BYTES)
 }
 private fun decode(bytes: ByteArray, id: String): AppendCommit {
  require(bytes.size <= MAX_MANIFEST_BYTES)
  val text = bytes.decodeToString(throwOnInvalidSequence = true)
  requireUniqueJsonKeys(text)
  return json.decodeFromString<AppendCommit>(text).also { checkCommit(it, id) }
 }
 private fun idsIn(root: Path): Set<String> {
  if (!SafePaths.checked(root).exists()) return emptySet()
  return java.nio.file.Files.list(root).use { files -> files.toList().also { require(it.size <= MAX_COMMITS) }.map { path ->
   require(path.fileName.toString().endsWith(".json")); path.fileName.toString().removeSuffix(".json").also { require(validCommitId(it)) }
  }.toSet() }
 }
 private fun observe(id: String) {
  require(validCommitId(id))
  val path = observedRoot.resolve("$id.json")
  if (!SafePaths.checked(path).exists()) SafePaths.atomicWrite(path, byteArrayOf())
 }
 /** First successful verification observed by this installation, never a server receipt time. */
 fun observedAt(id: String): String? {
  require(validCommitId(id))
  val path = observationRoot.resolve("$id.txt")
  if (!SafePaths.checked(path).exists()) return null
  return SafePaths.boundedRead(path, 64).decodeToString(throwOnInvalidSequence = true).also(::requireUtcTimestamp)
 }
 private fun observeVerified(id: String) {
  if (observedAt(id) == null) SafePaths.atomicWrite(observationRoot.resolve("$id.txt"), clock.instant().toString().encodeToByteArray())
 }
 private fun known(id: String): ByteArray? = knownRoot.resolve("$id.json").let { if (SafePaths.checked(it).exists()) SafePaths.boundedRead(it, MAX_MANIFEST_BYTES) else null }
 private fun remember(id: String, bytes: ByteArray) {
  val old = known(id)
  if (old != null && !old.contentEquals(bytes)) throw RemoteIntegrityException("已知提交 $id 的内容被改写；停止同步")
  if (old == null) SafePaths.atomicWrite(knownRoot.resolve("$id.json"), bytes)
 }
 fun baseline(): String? {
  if (!SafePaths.checked(baselinePath).exists()) {
   check(!SafePaths.checked(baselineMarker).exists()) { "同步基线丢失；未将已有历史当成新档案" }; return null
  }
  return SafePaths.boundedRead(baselinePath, 128).decodeToString().also { require(validCommitId(it)) }
 }
 /** Only a previously established, validated local baseline; never infer one from the remote tips. */
 fun baselineFiles(): Map<String, String>? {
  val id = baseline() ?: return null
  val bytes = checkNotNull(known(id)) { "同步基线元数据丢失；未视为新档案" }
  return decode(bytes, id).snapshot.files
 }
 fun setBaseline(id: String, graph: CommitGraph) {
  graph.requireSettled(); val verified = requireNotNull(graph.commits[id]) { "Cannot baseline an incomplete commit" }
  val bytes = requireNotNull(known(id)); require(decode(bytes, id) == verified)
  SafePaths.atomicWrite(baselineMarker, "established".encodeToByteArray())
  SafePaths.atomicWrite(baselinePath, id.encodeToByteArray())
 }

 /** Missing later listings never erase remembered IDs. Parents are fetched even when omitted. */
 fun discover(store: AppendStore): CommitGraph {
  if (store.legacyPresent()) throw LegacyProtocolException()
  val ids = (idsIn(knownRoot) + idsIn(observedRoot)).toMutableSet(); baseline()?.let { ids.add(it) }; require(ids.size <= MAX_COMMITS)
  val cursors = mutableSetOf<String>(); var cursor: String? = null; var pages = 0; var listed = 0L
  do {
   require(++pages <= MAX_COMMITS) { "Too many listing pages; no partial discovery accepted" }
   val page = store.listCommits(cursor)
   listed += page.ids.size; require(listed <= MAX_COMMITS) { "Listing entry work budget exceeded; no partial discovery accepted" }
   require(page.ids.all(::validCommitId)) { "Invalid commit filename" }
   ids.addAll(page.ids); require(ids.size <= MAX_COMMITS) { "Too many commits; no partial discovery accepted" }
   page.ids.forEach(::observe)
   cursor = page.next
   if (cursor != null) require(cursors.add(cursor)) { "Repeated listing continuation; no partial discovery accepted" }
  } while (cursor != null)
  val pending = mutableMapOf<String, String>(); val candidates = linkedMapOf<String, AppendCommit>()
  val queue = ArrayDeque(ids); val seen = mutableSetOf<String>(); var scanBytes = 0L
  while (queue.isNotEmpty()) {
   val id = queue.removeFirst(); if (!seen.add(id)) continue
   require(seen.size <= MAX_COMMITS) { "History too large; no partial discovery accepted" }
   observe(id)
   val bytes = store.readCommit(id)
   if (bytes == null) { pending[id] = "missing manifest"; continue }
   scanBytes += bytes.size; require(scanBytes <= MAX_REMOTE_SCAN_BYTES) { "Remote scan byte budget exceeded; no partial discovery accepted" }
   val old = known(id)
   if (old != null && !old.contentEquals(bytes)) throw RemoteIntegrityException("已知提交 $id 的内容被改写；停止同步")
   val commit = try { decode(bytes, id) } catch (_: Exception) { pending[id] = "invalid manifest"; continue }
   remember(id, bytes)
   commit.parents.forEach { queue.add(it) }
   scanBytes += commit.objectBytes; require(scanBytes <= MAX_REMOTE_SCAN_BYTES) { "Remote scan byte budget exceeded; no partial discovery accepted" }
   val zip = store.readObject(commit.snapshot.id)
   if (zip == null) { pending[id] = "missing object"; continue }
   scanBytes += maxOf(0, zip.size - commit.objectBytes)
   require(scanBytes <= MAX_REMOTE_SCAN_BYTES) { "Remote scan byte budget exceeded; no partial discovery accepted" }
   try { require(zip.size == commit.objectBytes); Snapshots.verify(Snapshot(commit.snapshot, zip), adapter) }
   catch (_: Exception) { pending[id] = "invalid object"; continue }
   observeVerified(id)
   candidates[id] = commit
  }
  // Iterative closure avoids recursive-stack attacks. Cycles and incomplete ancestors stay pending.
  val complete = linkedMapOf<String, AppendCommit>()
  do {
   val additions = candidates.filter { (id, commit) -> id !in complete && commit.parents.all { it in complete } }
   complete.putAll(additions)
  } while (additions.isNotEmpty())
  candidates.keys.filter { it !in complete }.forEach { pending[it] = "incomplete ancestry or cycle" }
  return CommitGraph(complete, pending)
 }

 fun download(store: AppendStore, commit: AppendCommit): Snapshot {
  checkCommit(commit)
  val bytes = store.readCommit(commit.id) ?: throw UnsettledRemoteException("提交暂不可见，请重试")
  require(decode(bytes, commit.id) == commit); remember(commit.id, bytes)
  val zip = store.readObject(commit.snapshot.id) ?: throw UnsettledRemoteException("快照暂不可见，请重试")
  require(zip.size == commit.objectBytes)
  return Snapshot(commit.snapshot, zip).also { Snapshots.verify(it, adapter); observeVerified(commit.id) }
 }

 fun pending(): UploadPlan? {
  if (!SafePaths.checked(marker).exists()) return null
  val id = SafePaths.boundedRead(marker, 128).decodeToString(); require(validCommitId(id))
  val bytes = SafePaths.boundedRead(pendingRoot.resolve("$id.json"), MAX_MANIFEST_BYTES)
  val commit = decode(bytes, id)
  val zip = SafePaths.boundedRead(pendingRoot.resolve("$id.zip"), MAX_SNAPSHOT_BYTES)
  require(zip.size == commit.objectBytes); Snapshots.verify(Snapshot(commit.snapshot, zip), adapter)
  return UploadPlan(commit, bytes, zip)
 }
 fun prepare(snapshot: Snapshot, parents: List<String>, kind: String = "snapshot", operation: CommitOperationMetadata? = null): UploadPlan {
  require(pending() == null) { "Retry or explicitly archive the pending operation first" }
  Snapshots.verify(snapshot, adapter)
  // A new publication always uses new independent object and commit IDs, including restore/resolution.
  val frozen = snapshot.copy(manifest = snapshot.manifest.copy(id = UUID.randomUUID().toString(), parent = null))
  val commit = AppendCommit(id = UUID.randomUUID().toString(), parents = parents.sorted(), snapshot = frozen.manifest, objectBytes = frozen.zip.size, kind = kind, operation = operation)
  checkCommit(commit)
  val bytes = json.encodeToString(commit).encodeToByteArray(); require(bytes.size <= MAX_MANIFEST_BYTES)
  SafePaths.atomicWrite(pendingRoot.resolve("${commit.id}.zip"), frozen.zip)
  SafePaths.atomicWrite(pendingRoot.resolve("${commit.id}.json"), bytes)
  SafePaths.atomicWrite(marker, commit.id.encodeToByteArray())
  return requireNotNull(pending())
 }
 /** Only identical bytes may be replayed; corrupt/truncated existing keys require explicit recovery. */
 private fun putVerified(read: () -> ByteArray?, write: () -> Unit, expected: ByteArray) {
  val before = read()
  if (before != null) {
   if (!before.contentEquals(expected)) throw RemoteIntegrityException("不可变对象身份冲突；未覆盖已有内容")
   return
  }
  try { write() } catch (e: Exception) {
   val observed = try { read() } catch (_: Exception) { throw e }
   if (observed == null) throw e
   if (!observed.contentEquals(expected)) throw RemoteIntegrityException("上传结果不确定且已有对象内容不同；未重写")
   return
  }
  val after = read() ?: throw UnsettledRemoteException("上传后对象尚不可见；冻结的操作已保留，可重试")
  if (!after.contentEquals(expected)) throw RemoteIntegrityException("上传回读校验失败；未继续发布")
 }
 fun publish(store: AppendStore, plan: UploadPlan): AppendCommit {
  val frozen = requireNotNull(pending())
  require(frozen.commit == plan.commit && frozen.manifestBytes.contentEquals(plan.manifestBytes) && frozen.zip.contentEquals(plan.zip))
  val graph = discover(store); graph.requireSettled()
  require(plan.commit.parents.all { it in graph.commits }) { "父提交尚未完整验证，未发布" }
  putVerified({ store.readObject(plan.commit.snapshot.id) }, { store.writeObject(plan.commit.snapshot.id, plan.zip) }, plan.zip)
  putVerified({ store.readCommit(plan.commit.id) }, { store.writeCommit(plan.commit.id, plan.manifestBytes) }, plan.manifestBytes)
  remember(plan.commit.id, plan.manifestBytes)
  observeVerified(plan.commit.id)
  return plan.commit
 }
 /** Caller records its safe baseline first; clearing never deletes immutable data. */
 fun clearPending() { SafePaths.checked(marker).deleteIfExists() }
}

/** Serialization rejects unknown/type-invalid fields; this pass also rejects duplicate keys/deep JSON. */
internal fun requireUniqueJsonKeys(text: String) {
 val scopes = ArrayDeque<MutableSet<String>?>(); var position = 0
 while (position < text.length) {
  when (text[position]) {
   '{' -> { require(scopes.size < 64) { "Manifest nesting too deep" }; scopes.addLast(mutableSetOf()) }
   '[' -> { require(scopes.size < 64) { "Manifest nesting too deep" }; scopes.addLast(null) }
   '}', ']' -> { require(scopes.isNotEmpty()); scopes.removeLast() }
   '"' -> {
    val start = position++; var escaped = false
    while (position < text.length) {
     val character = text[position]
     if (!escaped && character == '"') break
     escaped = !escaped && character == '\\'; position++
    }
    require(position < text.length) { "Unterminated JSON string" }
    var next = position + 1; while (next < text.length && text[next].isWhitespace()) next++
    if (next < text.length && text[next] == ':') {
     val keys = scopes.lastOrNull() ?: throw IllegalArgumentException("JSON key outside object")
     require(keys.add(json.decodeFromString<String>(text.substring(start, position + 1)))) { "Duplicate JSON key" }
    }
   }
  }
  position++
 }
 require(scopes.isEmpty()) { "Unterminated JSON object" }
}
