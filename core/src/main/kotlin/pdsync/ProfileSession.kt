package pdsync

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.nio.channels.FileChannel
import java.nio.file.*
import java.time.Instant
import java.util.UUID
import kotlin.io.path.*

/** Conservative filesystem checks. These do not prevent a hostile concurrent path replacement. */
object SafePaths {
 fun checked(path: Path): Path {
  val absolute = path.toAbsolutePath().normalize()
  var cursor: Path? = absolute
  while (cursor != null) {
   require(!Files.isSymbolicLink(cursor)) { "路径不能含符号链接：$cursor" }
   cursor = cursor.parent
  }
  return absolute
 }
 fun directory(path: Path): Path {
  val checked = checked(path)
  require(Files.isDirectory(checked, LinkOption.NOFOLLOW_LINKS)) { "请选择已存在的存档目录" }
  return checked.toRealPath()
 }
 fun disjoint(a: Path, b: Path) {
  require(!a.startsWith(b) && !b.startsWith(a)) { "存档目录与同步器数据目录不能互相包含" }
 }
 fun atomicWrite(path: Path, bytes: ByteArray) {
  val target = checked(path)
  checked(target.parent).createDirectories()
  val temp = Files.createTempFile(target.parent, ".write-", ".tmp")
  try {
   Files.write(temp, bytes)
   try { Files.move(temp, checked(target), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
   catch (_: AtomicMoveNotSupportedException) { Files.move(temp, checked(target), StandardCopyOption.REPLACE_EXISTING) }
  } finally { Files.deleteIfExists(temp) }
 }
 fun boundedRead(path: Path, limit: Int): ByteArray = Files.newInputStream(checked(path)).use {
  it.readNBytes(limit + 1).also { data -> require(data.size <= limit) { "本地文件过大" } }
 }
}

/** Reject ambiguous raw paths before HttpUrl can normalize away the configured root boundary. */
private fun validateRawEndpoint(endpoint: String) {
 require(endpoint.length <= 8192 && endpoint.none { it.isWhitespace() || it.isISOControl() || it == '\\' }) { "服务地址格式无效" }
 val uri = try { java.net.URI(endpoint) } catch (_: Exception) { throw IllegalArgumentException("服务地址格式无效") }
 require(uri.isAbsolute && !uri.isOpaque && uri.rawAuthority != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "服务地址不能含账号、查询参数或片段" }
 val rawPath = uri.rawPath ?: ""
 require("//" !in rawPath) { "服务地址包含不明确的路径" }
 rawPath.split('/').forEach { raw ->
  val decoded = try { java.net.URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8) } catch (_: Exception) { throw IllegalArgumentException("服务地址编码无效") }
  require(decoded != "." && decoded != ".." && decoded.none { it == '/' || it == '\\' || it == '%' || it.isISOControl() }) { "服务地址不能包含路径跳转或编码分隔符" }
 }
}

@Serializable data class AppConfig(
 val saveRoot: String = "", val endpoint: String = "http://127.0.0.1:8765",
 val profile: String = "demo", val version: String? = null, val targetGame: String = "",
 val username: String = "", val rememberPassword: Boolean = false, val autoUploadPaused: Boolean = false
) {
 fun validated(): AppConfig {
  require(profile.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "档案名只允许 1–64 位字母、数字、下划线或连字符" }
  validateRawEndpoint(endpoint)
  val url = endpoint.toHttpUrl()
  require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "服务地址不能含账号、密码、查询参数或片段" }
  require(url.scheme == "https" || url.scheme == "http" && url.host in setOf("127.0.0.1", "localhost", "::1")) { "服务必须使用 HTTPS；仅本机演示允许 HTTP" }
  return copy(endpoint = url.toString().trimEnd('/'), version = null)
 }
}

/** Only explicitly enumerated, non-secret fields are written. Legacy roots are never restored. */
class ConfigFile(private val path: Path) {
 @Serializable private data class Persisted(
  val endpoint: String, val profile: String, val targetGame: String = "",
  val username: String = "", val rememberPassword: Boolean = false, val autoUploadPaused: Boolean = false
 )
 fun exists(): Boolean = SafePaths.checked(path).exists()
 fun load(): AppConfig? = if (exists())
  json.decodeFromString<AppConfig>(SafePaths.boundedRead(path, 16 * 1024).decodeToString())
   .copy(saveRoot = "").validated() else null
 fun save(config: AppConfig) {
  val safe = config.validated()
  val bytes = json.encodeToString(Persisted(safe.endpoint, safe.profile, safe.targetGame, safe.username, safe.rememberPassword, safe.autoUploadPaused)).encodeToByteArray()
  require(bytes.size <= 16 * 1024) { "配置过大，未保存" }
  preserveLegacy()
  SafePaths.atomicWrite(path, bytes)
 }
 private fun preserveLegacy() {
  if (!exists()) return
  val bytes = SafePaths.boundedRead(path, 16 * 1024)
  val previous = runCatching { json.parseToJsonElement(bytes.decodeToString()) as? kotlinx.serialization.json.JsonObject }.getOrNull()
  if (previous != null && "saveRoot" !in previous && "version" !in previous) return
  val first = SafePaths.checked(path.resolveSibling(path.fileName.toString() + ".legacy"))
  val backup = if (!first.exists() || SafePaths.boundedRead(first, 16 * 1024).contentEquals(bytes)) first
   else SafePaths.checked(path.resolveSibling(path.fileName.toString() + ".legacy." + hash(bytes)))
  if (!backup.exists()) {
   // CREATE_NEW preserves another process's copy; any incomplete or mismatched backup blocks save.
   try { Files.write(backup, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE) }
   catch (_: FileAlreadyExistsException) { }
  }
  require(SafePaths.boundedRead(backup, 16 * 1024).contentEquals(bytes)) { "旧配置备份校验失败，未覆盖原配置" }
 }
}

/** savedAt is local history receipt; remoteObservedAt is this device's first verified cloud observation. */
@Serializable data class HistoryEntry(val manifest: Manifest, val savedAt: String, val reason: String, val remoteObservedAt: String? = null)
data class PreservedConflict(val local: Snapshot, val remote: Snapshot, val head: RemoteHead)
sealed interface PushResult {
 data class Success(val manifest: Manifest): PushResult
 data class Diverged(val conflict: PreservedConflict): PushResult
}
data class RestoreResult(val restored: Manifest, val backup: HistoryEntry, val backupPath: Path)

/** Version handling belongs to the selected game, not the WebDAV protocol. */
interface ProfileVersions {
 fun annotate(snapshot: Snapshot): Snapshot
 fun target(path: String): TargetGame?
 fun checkRestore(snapshot: Snapshot, targetPath: String)
}
object ShatteredProfileVersions : ProfileVersions {
 override fun annotate(snapshot: Snapshot): Snapshot {
  val files = Snapshots.verify(snapshot, Shattered)
  val detected = try { ShatteredVersions.detect(files) } catch (e: Exception) {
   SaveVersions(emptyMap(), emptyList(), "无法识别：${e.message}")
  }
  return snapshot.copy(manifest = snapshot.manifest.copy(version = detected.summary(), saveVersions = detected))
 }
 override fun target(path: String): TargetGame? = if (path.isBlank()) null else ShatteredVersions.target(Path.of(path))
 override fun checkRestore(snapshot: Snapshot, targetPath: String) {
  val actual = ShatteredVersions.detect(Snapshots.verify(snapshot, Shattered))
  snapshot.manifest.saveVersions?.let { require(it == actual) { "快照的版本元数据与存档内容不一致" } }
  val target = target(targetPath) ?: error("请先选择目标游戏 JAR；不能从存档推断已安装游戏版本")
  ShatteredVersions.requireCompatible(actual, target)
 }
}

/** Each scope binds a physical save directory, adapter, remote endpoint, profile, and version. */
class ProfileSession(
 config: AppConfig, private val stateRoot: Path, val adapter: GameAdapter = Shattered,
 private val versions: ProfileVersions = ShatteredProfileVersions,
 private val captureQuietMillis: Long = 2_000
) {
 val config = config.validated()
 val root = SafePaths.directory(Path.of(this.config.saveRoot))
 private val state = SafePaths.checked(stateRoot)
 private val metadata = DeviceMetadata(state)
 private val scopeKey = hash(listOf("auto-version-v2", adapter.id, root.toString(), this.config.endpoint, this.config.profile).joinToString("\n").encodeToByteArray())
 val directory: Path = state.resolve("profiles").resolve(scopeKey)
 private val historyRoot = directory.resolve("history")
 private val backupRoot = directory.resolve("backups")
 private val headPath = directory.resolve("head.txt")
 private val queue = PendingSnapshots(directory.resolve("pending"), adapter)
 private val queueKey = scopeKey
 init {
  require(root.parent != null && root != Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()) { "不能选择磁盘根目录或用户主目录" }
  SafePaths.disjoint(root, state)
  validateRoot()
  locked { importLegacyScopes() }
 }
 fun validateRoot(): Int {
  require(SafePaths.directory(root) == root)
  require(Files.isReadable(root) && Files.isWritable(root)) { "目录需要读写权限" }
  Files.walk(root).use { paths -> paths.forEach { require(!Files.isSymbolicLink(it)) { "存档目录不能含符号链接" } } }
  return Snapshots.capture(root, adapter, config.profile, "自动识别", null).manifest.files.size.also {
   require(it > 0 || Files.list(root).use { entries -> entries.findAny().isEmpty }) { "未发现 gameN/game.dat 或 rankings / badges / journal；请选择档案根目录（或全空的恢复目标目录）" }
  }
 }
 internal fun <T> locked(action: () -> T): T {
  SafePaths.checked(state.resolve("locks")).createDirectories()
  val lock = SafePaths.checked(state.resolve("locks/${hash(root.toString().encodeToByteArray())}.lock"))
  FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
   val acquired = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
   check(acquired != null) { "另一项同步器操作正在使用此存档，请稍后重试" }
   acquired.use { return action() }
  }
 }
 /** One-time, non-destructive import of scopes whose old key proves this exact root/endpoint/profile. */
 private fun importLegacyScopes() {
  val profiles = SafePaths.checked(state.resolve("profiles"))
  if (!profiles.exists()) return
  data class Legacy(val key: String, val directory: Path, val entries: List<Path>, val pending: Snapshot?, val head: String?)
  val candidates = Files.list(profiles).use { paths ->
   val dirs = paths.toList(); require(dirs.size <= 4096) { "本地档案目录过多，停止自动导入" }
   dirs.mapNotNull { old ->
    val key = old.fileName.toString()
    if (key == scopeKey || !key.matches(Regex("[0-9a-f]{64}"))) return@mapNotNull null
    SafePaths.checked(old)
    if (!Files.isDirectory(old, LinkOption.NOFOLLOW_LINKS)) return@mapNotNull null
    if (SafePaths.checked(directory.resolve("imported-$key.txt")).exists()) return@mapNotNull null
    val oldHistory = SafePaths.checked(old.resolve("history"))
    if (!oldHistory.exists()) return@mapNotNull null
    val entries = Files.list(oldHistory).use { stream -> stream.filter { it.fileName.toString().endsWith(".entry.json") }.toList() }
    require(entries.size <= 10000) { "旧历史条目过多，停止自动导入" }
    // The old scope encoded one declared version; any valid entry can prove its binding.
    val bound = entries.take(32).any { entryPath ->
     val entry = runCatching { json.decodeFromString<HistoryEntry>(SafePaths.boundedRead(entryPath, MAX_MANIFEST_BYTES).decodeToString()) }.getOrNull()
     entry != null && entry.manifest.game == adapter.id && entry.manifest.profile == config.profile &&
      hash(listOf(adapter.id, root.toString(), config.endpoint, config.profile, entry.manifest.version).joinToString("\n").encodeToByteArray()) == key
    }
    if (!bound) return@mapNotNull null
    val oldHeadPath = SafePaths.checked(old.resolve("head.txt"))
    val oldHead = if (oldHeadPath.exists()) SafePaths.boundedRead(oldHeadPath, 128).decodeToString().trim().also {
     require(it.matches(Regex("[0-9a-fA-F-]{36}"))) { "旧同步基线损坏，停止导入" }
    } else null
    val pending = PendingSnapshots(old.resolve("pending"), adapter).load(key)
    Legacy(key, old, entries, pending, oldHead)
   }
  }
  if (candidates.isEmpty()) return
  val currentPending = queue.load(queueKey)
  val allPending = (candidates.mapNotNull { it.pending } + listOfNotNull(currentPending))
  require(allPending.map { it.manifest.id }.distinct().size <= 1) { "多个旧待上传快照存在歧义；保留全部原数据，未自动选择" }
  if (allPending.isNotEmpty()) require(allPending.all {
   it.manifest == allPending.first().manifest && it.zip.contentEquals(allPending.first().zip)
  }) { "旧待上传快照身份冲突，停止导入" }
  val allHeads = (candidates.mapNotNull { it.head } + listOfNotNull(head())).distinct()
  require(allHeads.size <= 1) { "多个旧同步基线不一致；保留全部原数据，未自动选择" }
  candidates.forEach { old ->
   old.entries.forEach { entryPath ->
    val entry = json.decodeFromString<HistoryEntry>(SafePaths.boundedRead(entryPath, MAX_MANIFEST_BYTES).decodeToString())
    checkedManifest(entry.manifest)
    require(entryPath.fileName.toString() == "${entry.manifest.id}.entry.json")
    val snapshot = Snapshot(entry.manifest, SafePaths.boundedRead(old.directory.resolve("history/${entry.manifest.id}.zip"), MAX_SNAPSHOT_BYTES))
    Snapshots.verify(snapshot, adapter)
    val destination = historyRoot.resolve("${entry.manifest.id}.entry.json")
    val existed = SafePaths.checked(destination).exists()
    remember(snapshot, entry.reason)
    if (!existed) SafePaths.atomicWrite(destination, json.encodeToString(entry).encodeToByteArray())
   }
  }
  if (currentPending == null && allPending.isNotEmpty()) queue.save(queueKey, allPending.first())
  if (head() == null && allHeads.isNotEmpty()) setHead(allHeads.single())
  // Originals, including raw backup files and original pending markers, are never deleted.
  candidates.forEach { SafePaths.atomicWrite(directory.resolve("imported-${it.key}.txt"), "copied-and-verified".encodeToByteArray()) }
 }

 fun deviceIdentity(): DeviceIdentity = metadata.currentDevice()
 internal fun operationMetadata(): CommitOperationMetadata = metadata.operation()
 internal fun guard(stopped: () -> Boolean) { if (!stopped()) throw SaveNotStableException("游戏正在运行或进程状态未知，请等待游戏退出") }
 private fun checkedManifest(manifest: Manifest) {
  require(manifest.id.matches(Regex("[0-9a-fA-F-]{36}")))
  require(manifest.game == adapter.id && manifest.profile == config.profile) { "快照游戏或档案不匹配" }
  manifest.metadata?.validated(manifest.files.keys)
 }
 internal fun capture(stopped: () -> Boolean, parent: String? = head()): Snapshot {
  guard(stopped); validateRoot()
  val snapshot = versions.annotate(SaveStabilityGuard(root, adapter, stopped, captureQuietMillis).capture(config.profile, "自动识别", parent, captureContextProvider = metadata::captureContext))
  require(snapshot.manifest.files.isNotEmpty()) { "空目录只能用于恢复，不能创建或上传空快照" }
  guard(stopped)
  if (Snapshots.capture(root, adapter, config.profile, "自动识别", parent).manifest.files != snapshot.manifest.files) throw SaveNotStableException("采集期间存档发生变化；已停止操作，等待文件稳定后重试")
  return snapshot
 }
 private fun head(): String? = if (SafePaths.checked(headPath).exists())
  SafePaths.boundedRead(headPath, 128).decodeToString().trim().also { require(it.matches(Regex("[0-9a-fA-F-]{36}"))) } else null
 private fun setHead(id: String) = SafePaths.atomicWrite(headPath, id.encodeToByteArray())
 internal fun remember(snapshot: Snapshot, reason: String, remoteObservedAt: String? = null): HistoryEntry {
  remoteObservedAt?.let(::requireUtcTimestamp)
  checkedManifest(snapshot.manifest); Snapshots.verify(snapshot, adapter)
  snapshot.manifest.saveVersions?.let { evidence ->
   require(versions.annotate(snapshot).manifest.saveVersions == evidence) { "快照的版本元数据与实际内容不一致" }
  }
  val id = snapshot.manifest.id
  val entryPath = historyRoot.resolve("$id.entry.json")
  if (SafePaths.checked(entryPath).exists()) {
   val old = loadSnapshot(id)
   require(old.manifest == snapshot.manifest && old.zip.contentEquals(snapshot.zip)) { "同名历史快照内容不一致" }
   val existing = json.decodeFromString<HistoryEntry>(SafePaths.boundedRead(entryPath, MAX_MANIFEST_BYTES).decodeToString())
   existing.remoteObservedAt?.let(::requireUtcTimestamp)
   if (existing.remoteObservedAt != null || remoteObservedAt == null) return existing
   return existing.copy(remoteObservedAt = remoteObservedAt).also { SafePaths.atomicWrite(entryPath, json.encodeToString(it).encodeToByteArray()) }
  }
  val entry = HistoryEntry(snapshot.manifest, Instant.now().toString(), reason, remoteObservedAt)
  SafePaths.atomicWrite(historyRoot.resolve("$id.zip"), snapshot.zip)
  SafePaths.atomicWrite(entryPath, json.encodeToString(entry).encodeToByteArray())
  return entry
 }
 fun history(): List<HistoryEntry> = locked {
  if (!SafePaths.checked(historyRoot).exists()) return@locked emptyList()
  Files.list(historyRoot).use { paths -> paths.filter { it.fileName.toString().endsWith(".entry.json") }.toList().map { path ->
   val entry = json.decodeFromString<HistoryEntry>(SafePaths.boundedRead(path, MAX_MANIFEST_BYTES).decodeToString())
   checkedManifest(entry.manifest)
   entry.remoteObservedAt?.let(::requireUtcTimestamp)
   require(path.fileName.toString() == "${entry.manifest.id}.entry.json")
   entry
  }.sortedByDescending { it.savedAt } }
 }
 fun loadSnapshot(id: String): Snapshot {
  require(id.matches(Regex("[0-9a-fA-F-]{36}")))
  val entry = json.decodeFromString<HistoryEntry>(SafePaths.boundedRead(historyRoot.resolve("$id.entry.json"), MAX_MANIFEST_BYTES).decodeToString())
  checkedManifest(entry.manifest); require(entry.manifest.id == id)
  return Snapshot(entry.manifest, SafePaths.boundedRead(historyRoot.resolve("$id.zip"), MAX_SNAPSHOT_BYTES)).also { Snapshots.verify(it, adapter) }
 }
 fun currentVersions(): SaveVersions? = locked {
  versions.annotate(Snapshots.capture(root, adapter, config.profile, "自动识别", null)).manifest.saveVersions
 }
 fun targetInfo(): TargetGame? = versions.target(config.targetGame)
 // Immutable history reads do not take the mutation lock. Restore repeats this check under the lock.
 fun checkRestore(id: String) = versions.checkRestore(loadSnapshot(id), config.targetGame)
 fun createSnapshot(stopped: () -> Boolean): HistoryEntry = locked { remember(capture(stopped), "本地快照") }
 internal fun legacyPending(): Snapshot? = queue.load(queueKey)
 internal fun archiveLegacyPending() {
  queue.load(queueKey)?.let { remember(it, "已归档旧版待上传快照") }; queue.clear()
 }
 fun hasPending(): Boolean = locked { queue.load(queueKey) != null }
 fun readRemote(store: Store): HistoryEntry? = locked {
  val remote = store.head() ?: return@locked null
  val snapshot = store.download(remote.manifest)
  checkedManifest(snapshot.manifest); Snapshots.verify(snapshot, adapter)
  remember(snapshot, "已读取远端快照")
 }
 fun restoreRemote(id: String, store: Store, stopped: () -> Boolean): RestoreResult = locked {
  guard(stopped)
  require(queue.load(queueKey) == null) { "请先处理或归档待上传快照" }
  val snapshot = loadSnapshot(id)
  require(store.head()?.manifest == snapshot.manifest) { "远端已发生变化；没有恢复，请重新读取远端" }
  val result = restore(snapshot, stopped)
  setHead(snapshot.manifest.id)
  result
 }
 private fun preserve(conflict: Conflict, store: Store): PushResult.Diverged {
  remember(conflict.local, "冲突 · 本地分支")
  val remote = store.download(conflict.remote.manifest)
  checkedManifest(remote.manifest); Snapshots.verify(remote, adapter)
  remember(remote, "冲突 · 远端分支")
  return PushResult.Diverged(PreservedConflict(conflict.local, remote, conflict.remote))
 }
 private fun pushPending(snapshot: Snapshot, store: Store): PushResult = try {
  Sync(store).push(snapshot)
  setHead(snapshot.manifest.id); queue.clear()
  PushResult.Success(snapshot.manifest)
 } catch (conflict: Conflict) { preserve(conflict, store) }
 fun push(store: Store, stopped: () -> Boolean): PushResult = locked {
  guard(stopped)
  val snapshot = queue.load(queueKey) ?: capture(stopped).also {
   remember(it, "同步采集快照"); queue.save(queueKey, it)
  }
  remember(snapshot, "同步采集快照")
  pushPending(snapshot, store)
 }
 private fun unchanged(conflict: PreservedConflict, store: Store): PushResult.Diverged? {
  val now = store.head()
  require(now != null) { "远端历史已消失；已停止，双方历史保留" }
  return if (now.manifest != conflict.head.manifest || now.etag != conflict.head.etag)
   preserve(Conflict(conflict.local, now), store) else null
 }
 fun chooseLocal(conflict: PreservedConflict, store: Store, stopped: () -> Boolean): PushResult = locked {
  guard(stopped)
  require(queue.load(queueKey)?.manifest?.id == conflict.local.manifest.id) { "待处理分支已变更，请重新同步" }
  unchanged(conflict, store)?.let { return@locked it }
  remember(conflict.local, "冲突 · 本地分支"); remember(conflict.remote, "冲突 · 远端分支")
  val chosen = conflict.local.copy(manifest = conflict.local.manifest.copy(id = UUID.randomUUID().toString(), parent = conflict.remote.manifest.id))
  remember(chosen, "冲突解决 · 采用本地")
  // Both originals are saved before replacing the retry marker.
  queue.replace(queueKey, chosen)
  pushPending(chosen, store)
 }
 internal fun restore(snapshot: Snapshot, stopped: () -> Boolean): RestoreResult {
  guard(stopped); checkedManifest(snapshot.manifest)
  var entry: HistoryEntry? = null
  val stability = SaveStabilityGuard(root, adapter, stopped, captureQuietMillis)
  var backupFingerprint: SaveFingerprint? = null
  val backupPath = Snapshots.restore(snapshot, root, backupRoot, adapter, "自动识别", stopped,
   onBackupReady = { backup, _ -> entry = remember(backup, "恢复前自动备份") },
   validateCompatibility = { candidate, _ -> versions.checkRestore(candidate, config.targetGame) },
   captureBackup = { current ->
    require(current == root)
    val captured = stability.captureStable(config.profile, "自动识别", null, captureContextProvider = metadata::captureContext)
    backupFingerprint = captured.fingerprint
    versions.annotate(captured.snapshot)
   }, beforeReplacement = { stability.requireUnchanged(requireNotNull(backupFingerprint)) })
  return RestoreResult(snapshot.manifest, requireNotNull(entry), backupPath)
 }
 fun restoreHistory(id: String, stopped: () -> Boolean): RestoreResult = locked {
  require(queue.load(queueKey) == null) { "还有待上传/冲突快照，请先重试或明确归档待上传快照" }
  restore(loadSnapshot(id), stopped)
 }
 fun chooseRemote(conflict: PreservedConflict, store: Store, stopped: () -> Boolean): Pair<RestoreResult?, PushResult.Diverged?> = locked {
  guard(stopped)
  require(queue.load(queueKey)?.manifest?.id == conflict.local.manifest.id) { "待处理分支已变更，请重新同步" }
  unchanged(conflict, store)?.let { return@locked null to it }
  remember(conflict.local, "冲突 · 本地分支"); remember(conflict.remote, "冲突 · 远端分支")
  val result = restore(conflict.remote, stopped)
  setHead(conflict.remote.manifest.id); queue.clear()
  result to null
 }
 /** Explicit UI confirmation is required. No remote changes; immutable history is retained. */
 fun archivePending() = locked {
  queue.load(queueKey)?.let { remember(it, "已归档待上传快照") }
  queue.clear()
 }
}
