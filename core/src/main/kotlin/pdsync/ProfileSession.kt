package pdsync

import pdsync.i18n.CoreSessionMessages

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
 private val buffered=ThreadLocal<Boolean>()
 internal fun forceWrites()=buffered.get()!=true
 internal fun <T> bufferedUploadWrites(action:()->T):T {
  val old=buffered.get();buffered.set(true)
  try{return action()}finally{if(old==null)buffered.remove() else buffered.set(old)}
 }

 fun checked(path: Path): Path {
  val absolute = path.toAbsolutePath().normalize()
  var cursor: Path? = absolute
  while (cursor != null) {
   require(!Files.isSymbolicLink(cursor)) { CoreSessionMessages.PATHS_CANNOT_CONTAIN_SYMBOLIC_LINKS.text(cursor) }
   cursor = cursor.parent
  }
  return absolute
 }
 fun directory(path: Path): Path {
  val checked = checked(path)
  require(Files.isDirectory(checked, LinkOption.NOFOLLOW_LINKS)) { CoreSessionMessages.SELECT_AN_EXISTING_SAVES_FOLDER.text() }
  return checked.toRealPath()
 }
 fun disjoint(a: Path, b: Path) {
  fun physical(path:Path):Path {
   val absolute=checked(path)
   var existing=absolute
   while(true) {
    try {
     Files.readAttributes(existing,java.nio.file.attribute.BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
     break
    } catch(_:NoSuchFileException) {
     existing=existing.parent ?: throw IllegalArgumentException("Path has no existing ancestor")
    }
   }
   return existing.toRealPath().resolve(existing.relativize(absolute)).normalize()
  }
  val first=physical(a);val second=physical(b)
  require(!first.startsWith(second) && !second.startsWith(first)) { CoreSessionMessages.THE_SAVES_FOLDER_AND_SYNC_DATA_FOLDER_CANNOT_CONTAIN_ONE.text() }
 }
 fun atomicWrite(path: Path, bytes: ByteArray) {
  atomicWrite(path,bytes) { from,to -> Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);Unit }
 }
 /** No silent non-atomic fallback for configuration, baseline, or durable operation markers. */
 internal fun atomicWrite(path:Path,bytes:ByteArray,replace:(Path,Path)->Unit) {
  val target = checked(path)
  checked(target.parent).createDirectories()
  val temp = Files.createTempFile(target.parent, ".write-", ".tmp")
  try {
   FileChannel.open(temp,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS).use { channel ->
    val buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);if(forceWrites())channel.force(true)
   }
   replace(temp,checked(target))
  } finally { Files.deleteIfExists(temp) }
 }
 fun boundedRead(path: Path, limit: Int): ByteArray = Files.newInputStream(checked(path)).use {
  it.readUpTo(limit + 1).also { data -> require(data.size <= limit) { CoreSessionMessages.THE_LOCAL_FILE_IS_TOO_LARGE.text() } }
 }
}

/** Reject ambiguous raw paths before HttpUrl can normalize away the configured root boundary. */
private fun validateRawEndpoint(endpoint: String) {
 require(endpoint.length <= 8192 && endpoint.none { it.isWhitespace() || it.isISOControl() || it == '\\' }) { CoreSessionMessages.INVALID_SERVICE_ADDRESS_FORMAT.text() }
 val uri = try { java.net.URI(endpoint) } catch (_: Exception) { throw IllegalArgumentException(CoreSessionMessages.INVALID_SERVICE_ADDRESS_FORMAT.text()) }
 require(uri.isAbsolute && !uri.isOpaque && uri.rawAuthority != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { CoreSessionMessages.THE_SERVICE_ADDRESS_CANNOT_CONTAIN_AN_ACCOUNT_QUERY_PARAMETERS_OR.text() }
 val rawPath = uri.rawPath ?: ""
 require("//" !in rawPath) { CoreSessionMessages.THE_SERVICE_ADDRESS_CONTAINS_AN_AMBIGUOUS_PATH.text() }
 rawPath.split('/').forEach { raw ->
  val decoded = try { java.net.URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8.name()) } catch (_: Exception) { throw IllegalArgumentException(CoreSessionMessages.INVALID_SERVICE_ADDRESS_ENCODING.text()) }
  require(decoded != "." && decoded != ".." && decoded.none { it == '/' || it == '\\' || it == '%' || it.isISOControl() }) { CoreSessionMessages.THE_SERVICE_ADDRESS_CANNOT_CONTAIN_PATH_TRAVERSAL_OR_ENCODED_SEPARATORS.text() }
 }
}

@Serializable enum class OnboardingProgress { LEGACY, PENDING, COMPLETE, DEFERRED }

@Serializable data class AppConfig(
 val saveRoot: String = "", val endpoint: String = "http://127.0.0.1:8765",
 val profile: String = "demo", val version: String? = null, val targetGame: String = "",
 val username: String = "", val rememberPassword: Boolean = false, val autoUploadPaused: Boolean = false,
 val onboardingProgress: OnboardingProgress = OnboardingProgress.LEGACY,
 val password: String? = null,
 /** Explicit selection in the new path step; old saveRoot remains ignored by desktop migration. */
 val selectedSaveRoot: String? = null,
 /** Explicit service selection; old desktop configurations remain custom regardless of URL. */
 val connectionProvider: String = "custom"
) {
 override fun toString() = "AppConfig(profile=$profile, targetGame=$targetGame, onboardingProgress=$onboardingProgress, password=<redacted>)"
 fun validated(): AppConfig {
  require(profile.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { CoreSessionMessages.PROFILE_NAMES_MUST_CONTAIN_1_64_LETTERS_DIGITS_UNDERSCORES_OR.text() }
  validateRawEndpoint(endpoint)
  val url = endpoint.toHttpUrl()
  require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { CoreSessionMessages.THE_SERVICE_ADDRESS_CANNOT_CONTAIN_CREDENTIALS_QUERY_PARAMETERS_OR_A.text() }
  require(url.scheme == "https" || url.scheme == "http" && url.host in setOf("127.0.0.1", "localhost", "::1")) { CoreSessionMessages.THE_SERVICE_MUST_USE_HTTPS_HTTP_IS_ALLOWED_ONLY_FOR.text() }
  return copy(endpoint = url.toString().trimEnd('/'), version = null)
 }
}

/** Explicit local-file persistence. Password is plaintext by product choice; never log this file. */
class ConfigFile(private val path: Path) {
 @Serializable private data class Persisted(
  val endpoint: String, val profile: String, val targetGame: String = "",
  val username: String = "", val rememberPassword: Boolean = false, val autoUploadPaused: Boolean = false,
  val onboardingProgress: OnboardingProgress = OnboardingProgress.LEGACY, val password: String? = null,
  val selectedSaveRoot: String? = null, val connectionProvider: String = "custom"
 )
 fun exists(): Boolean = SafePaths.checked(path).exists()
 fun load(): AppConfig? = try { if (exists())
  json.decodeFromString<AppConfig>(SafePaths.boundedRead(path, 16 * 1024).decodeToString())
   .copy(saveRoot = "").validated() else null
 } catch (_: Exception) { error(CoreSessionMessages.THE_CONFIGURATION_IS_UNREADABLE_OR_DAMAGED_THE_ORIGINAL_FILE_IS.text()) }
 fun save(config: AppConfig) {
  val safe = config.validated()
  val bytes = json.encodeToString(Persisted(safe.endpoint, safe.profile, safe.targetGame, safe.username, safe.rememberPassword, safe.autoUploadPaused, safe.onboardingProgress, safe.password, safe.selectedSaveRoot, safe.connectionProvider)).encodeToByteArray()
  require(bytes.size <= 16 * 1024) { CoreSessionMessages.THE_CONFIGURATION_IS_TOO_LARGE_AND_WAS_NOT_SAVED.text() }
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
  require(SafePaths.boundedRead(backup, 16 * 1024).contentEquals(bytes)) { CoreSessionMessages.THE_OLD_CONFIGURATION_BACKUP_FAILED_VERIFICATION_THE_ORIGINAL_CONFIGURATION_WAS.text() }
 }
}

/** savedAt is local history receipt; remoteObservedAt is this device's first verified cloud observation. */
@Serializable data class HistoryEntry(val manifest: Manifest, val savedAt: String, val reason: String, val remoteObservedAt: String? = null)
data class PreservedConflict(val local: Snapshot, val remote: Snapshot, val head: RemoteHead)
sealed interface PushResult {
 data class Success(val manifest: Manifest): PushResult
 data class Diverged(val conflict: PreservedConflict): PushResult
}
data class RestoreResult(val restored: Manifest)

/** Version handling belongs to the selected game, not the WebDAV protocol. */
interface ProfileVersions {
 fun annotate(snapshot: Snapshot): Snapshot
 fun annotate(verified: VerifiedSnapshot): Snapshot = annotate(verified.snapshot)
 fun target(path: String): TargetGame?
 fun checkRestore(snapshot: Snapshot, targetPath: String)
 fun checkRestore(verified: VerifiedSnapshot, targetPath: String) = checkRestore(verified.snapshot, targetPath)
}
object ShatteredProfileVersions : ProfileVersions {
 override fun annotate(verified:VerifiedSnapshot):Snapshot {
  val snapshot=verified.snapshot
  require(recordedGamePackage(snapshot.manifest)==GameCloudIdentity.SHATTERED_PACKAGE) { CoreSessionMessages.THE_SNAPSHOT_HAS_NO_VERIFIABLE_GAME_PACKAGE_IDENTITY.text() }
  val detected=try {verified.saveVersions} catch(e:Exception) {SaveVersions(emptyMap(),emptyList(),CoreSessionMessages.UNRECOGNIZED.text(e.message))}
  return snapshot.copy(manifest=snapshot.manifest.copy(version=detected.summary(),saveVersions=detected,gamePackage=GameCloudIdentity.SHATTERED_PACKAGE))
 }
 override fun annotate(snapshot: Snapshot): Snapshot {
  val files = Snapshots.verify(snapshot, Shattered)
  require(recordedGamePackage(snapshot.manifest) == GameCloudIdentity.SHATTERED_PACKAGE) { CoreSessionMessages.THE_SNAPSHOT_HAS_NO_VERIFIABLE_GAME_PACKAGE_IDENTITY.text() }
  val detected = try { ShatteredVersions.detect(files) } catch (e: Exception) {
   SaveVersions(emptyMap(), emptyList(), CoreSessionMessages.UNRECOGNIZED.text(e.message))
  }
  return snapshot.copy(manifest = snapshot.manifest.copy(version = detected.summary(), saveVersions = detected,
   gamePackage = GameCloudIdentity.SHATTERED_PACKAGE))
 }
 override fun target(path: String): TargetGame? = if (path.isBlank()) null else ShatteredVersions.target(java.nio.file.Paths.get(path))
 override fun checkRestore(snapshot: Snapshot, targetPath: String) {
  val actual = ShatteredVersions.detect(Snapshots.verify(snapshot, Shattered))
  checkRestore(snapshot,targetPath,actual)
 }
 override fun checkRestore(verified:VerifiedSnapshot,targetPath:String)=checkRestore(verified.snapshot,targetPath,verified.saveVersions)
 private fun checkRestore(snapshot:Snapshot,targetPath:String,actual:SaveVersions) {
  snapshot.manifest.saveVersions?.let { require(it == actual) { CoreSessionMessages.SNAPSHOT_VERSION_METADATA_DOES_NOT_MATCH_THE_SAVE_CONTENTS.text() } }
  require(recordedGamePackage(snapshot.manifest) == GameCloudIdentity.SHATTERED_PACKAGE) { CoreSessionMessages.THE_SNAPSHOT_BELONGS_TO_A_DIFFERENT_GAME_PACKAGE_AND_CANNOT.text() }
  val target = target(targetPath) ?: error(CoreSessionMessages.SELECT_THE_TARGET_GAME_JAR_FIRST_THE_INSTALLED_GAME_VERSION.text())
  ShatteredVersions.requireCompatible(actual, target)
 }
}

/** Current scopes bind a physical save directory, adapter, remote endpoint, and profile. */
class ProfileSession(
 config: AppConfig, private val stateRoot: Path, val adapter: GameAdapter = Shattered,
 private val versions: ProfileVersions = ShatteredProfileVersions,
 deviceLabel: (() -> String?)? = null
) {
 val config = config.validated()
 val root = SafePaths.directory(java.nio.file.Paths.get(this.config.saveRoot))
 private val state = SafePaths.checked(stateRoot)
 private val metadata = if (deviceLabel == null) DeviceMetadata(state) else DeviceMetadata(state, hostname = deviceLabel)
 internal val directoryMetadata = DirectoryMetadataStore(root)
 private val scopeKey = hash(listOf("package-current-v1", adapter.id, root.toString(), this.config.endpoint, this.config.profile).joinToString("\n").encodeToByteArray())
 val directory: Path = state.resolve("profiles").resolve(scopeKey)
 private val historyRoot = directory.resolve("history")
 private val headPath = directory.resolve("head.txt")
 private val queue = PendingSnapshots(directory.resolve("pending"), adapter)
 private val queueKey = scopeKey
 init {
  val userHome = System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { java.nio.file.Paths.get(it).toAbsolutePath().normalize() }
  require(root.parent != null && root != userHome) { CoreSessionMessages.A_DISK_ROOT_OR_YOUR_HOME_FOLDER_CANNOT_BE_SELECTED.text() }
  SafePaths.disjoint(root, state)
  locked { validateRoot() }
 }
 fun validateRoot(): Int {
  validateRootStructure()
  return Snapshots.fileHashes(root, adapter).size.also {
   require(it > 0 || Files.list(root).use { entries -> entries.allMatch { p -> isDirectoryMetadata(p.fileName.toString()) } }) { CoreSessionMessages.NO_GAMEN_GAME_DAT_RANKINGS_BADGES_OR_JOURNAL_FILES_WERE.text() }
  }
 }
 private fun validateRootStructure() {
  require(SafePaths.directory(root) == root)
  require(Files.isReadable(root) && Files.isWritable(root)) { CoreSessionMessages.THE_FOLDER_REQUIRES_READ_AND_WRITE_ACCESS.text() }
  Files.walk(root).use { paths -> paths.forEach { require(!Files.isSymbolicLink(it)) { CoreSessionMessages.THE_SAVES_FOLDER_CANNOT_CONTAIN_SYMBOLIC_LINKS.text() } } }
 }
 internal fun <T> locked(action: () -> T): T {
  SafePaths.checked(state.resolve("locks")).createDirectories()
  val lock = SafePaths.checked(state.resolve("locks/${hash(root.toString().encodeToByteArray())}.lock"))
  FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
   val acquired = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
   check(acquired != null) { CoreSessionMessages.ANOTHER_SYNC_OPERATION_IS_USING_THESE_SAVES_TRY_AGAIN_LATER.text() }
   acquired.use { return action() }
  }
 }
 fun deviceIdentity(): DeviceIdentity = metadata.currentDevice()
 internal fun operationMetadata(): CommitOperationMetadata = metadata.operation()
 internal fun guard(stopped: () -> Boolean) {
  // Keep identity/authorization failures from existing platform callbacks, without a running gate.
  stopped()
  if (Thread.currentThread().isInterrupted) throw InterruptedException()
 }
 private fun checkedManifest(manifest: Manifest) {
  require(validRecordId(manifest.id))
  if (validContentHashId(manifest.id)) require(manifest.id == contentSha256(manifest.files))
  require(adapter.acceptsManifest(manifest) && manifest.profile == config.profile) { CoreSessionMessages.THE_SNAPSHOT_GAME_OR_PROFILE_DOES_NOT_MATCH.text() }
  manifest.metadata?.validated(manifest.files.keys)
  manifest.directoryMetadata?.validated(manifest.files)
 }
 internal fun captureVerified(stopped:()->Boolean):VerifiedSnapshot {
  validateRootStructure()
  stopped();if(Thread.currentThread().isInterrupted)throw InterruptedException()
  val context=metadata.captureContext();val read=Snapshots.readLocal(root,adapter)
  val raw=Snapshots.capture(read,adapter,config.profile,"自动识别",null,context)
  val verified=VerifiedSnapshot.captured(raw,read.entries)
  require(raw.manifest.files.isNotEmpty()) { CoreSessionMessages.AN_EMPTY_FOLDER_CAN_ONLY_BE_USED_FOR_RESTORING_EMPTY.text() }
  val annotated=versions.annotate(verified)
  return verified.withManifest(annotated.manifest.copy(directoryMetadata=directoryMetadata.observe(raw.manifest.files,initialImport=!historyRoot.exists(),persist=true)))
 }
 internal fun capture(stopped: () -> Boolean, parent: String? = head(), persistDirectoryMetadata: Boolean = true): Snapshot {
  validateRootStructure()
  val snapshot = versions.annotate(SaveStabilityGuard(root, adapter, stopped).capture(config.profile, "自动识别", parent, captureContextProvider = metadata::captureContext))
  require(snapshot.manifest.files.isNotEmpty()) { CoreSessionMessages.AN_EMPTY_FOLDER_CAN_ONLY_BE_USED_FOR_RESTORING_EMPTY.text() }
  return snapshot.copy(manifest=snapshot.manifest.copy(directoryMetadata=directoryMetadata.observe(snapshot.manifest.files,initialImport=!historyRoot.exists(),persist=persistDirectoryMetadata)))
 }
 private fun head(): String? = if (SafePaths.checked(headPath).exists())
  SafePaths.boundedRead(headPath, 128).decodeToString().trim().also { require(it.matches(Regex("[0-9a-fA-F-]{36}"))) } else null
 private fun setHead(id: String) = SafePaths.atomicWrite(headPath, id.encodeToByteArray())
 internal fun remember(snapshot: Snapshot, reason: String, remoteObservedAt: String? = null): HistoryEntry =
  remember(snapshot,reason,remoteObservedAt,null)
 internal fun remember(verified: VerifiedSnapshot, reason: String, remoteObservedAt: String? = null): HistoryEntry =
  remember(verified.snapshot,reason,remoteObservedAt,verified)
 private fun remember(snapshot: Snapshot, reason: String, remoteObservedAt: String?, verified: VerifiedSnapshot?): HistoryEntry {
  remoteObservedAt?.let(::requireUtcTimestamp)
  checkedManifest(snapshot.manifest)
  if (verified == null) Snapshots.verify(snapshot, adapter)
  snapshot.manifest.saveVersions?.let { evidence ->
   require((if(verified==null)versions.annotate(snapshot) else versions.annotate(verified)).manifest.saveVersions == evidence) { CoreSessionMessages.SNAPSHOT_VERSION_METADATA_DOES_NOT_MATCH_THE_ACTUAL_CONTENTS.text() }
  }
  val id = snapshot.manifest.id
  val entryPath = historyRoot.resolve("$id.entry.json")
  if (SafePaths.checked(entryPath).exists()) {
   val old = if(verified==null)loadSnapshot(id) else {
    val entry=json.decodeFromString<HistoryEntry>(SafePaths.boundedRead(entryPath,MAX_MANIFEST_BYTES).decodeToString())
    checkedManifest(entry.manifest)
    Snapshot(entry.manifest,SafePaths.boundedRead(historyRoot.resolve("$id.zip"),MAX_SNAPSHOT_BYTES))
   }
   if (validContentHashId(id)) {
    // A content address may have several valid ZIP encodings or capture metadata. Keep the
    // first local archive, but never turn an unverified existing file into a trusted winner.
    if(verified==null || old.manifest!=snapshot.manifest || !old.zip.contentEquals(snapshot.zip))Snapshots.verify(old, adapter)
    require(old.manifest.id == id && old.manifest.files == snapshot.manifest.files) {
     CoreSessionMessages.A_HISTORY_SNAPSHOT_WITH_THE_SAME_NAME_HAS_DIFFERENT_CONTENTS.text()
    }
   } else require(old.manifest == snapshot.manifest && old.zip.contentEquals(snapshot.zip)) { CoreSessionMessages.A_HISTORY_SNAPSHOT_WITH_THE_SAME_NAME_HAS_DIFFERENT_CONTENTS.text() }
   val existing = json.decodeFromString<HistoryEntry>(SafePaths.boundedRead(entryPath, MAX_MANIFEST_BYTES).decodeToString())
   existing.remoteObservedAt?.let(::requireUtcTimestamp)
   if (existing.remoteObservedAt != null || remoteObservedAt == null) return existing
   return existing.copy(remoteObservedAt = remoteObservedAt).also { SafePaths.atomicWrite(entryPath, json.encodeToString(it).encodeToByteArray()) }
  }
  // An interrupted or foreign content-addressed archive without its manifest is not safe to replace.
  if (validContentHashId(id)) require(!SafePaths.checked(historyRoot.resolve("$id.zip")).exists()) {
   CoreSessionMessages.A_HISTORY_SNAPSHOT_WITH_THE_SAME_NAME_HAS_DIFFERENT_CONTENTS.text()
  }
  val entry = HistoryEntry(snapshot.manifest, Instant.now().toString(), reason, remoteObservedAt)
  SafePaths.atomicWrite(historyRoot.resolve("$id.zip"), snapshot.zip)
  SafePaths.atomicWrite(entryPath, json.encodeToString(entry).encodeToByteArray())
  return entry
 }
 fun history(): List<HistoryEntry> = locked {
  if (!SafePaths.checked(historyRoot).exists()) return@locked emptyList()
  Files.list(historyRoot).use { paths -> paths.filter { it.fileName.toString().endsWith(".entry.json") }.iterator().asSequence().toList().map { path ->
   val entry = json.decodeFromString<HistoryEntry>(SafePaths.boundedRead(path, MAX_MANIFEST_BYTES).decodeToString())
   checkedManifest(entry.manifest)
   entry.remoteObservedAt?.let(::requireUtcTimestamp)
   require(path.fileName.toString() == "${entry.manifest.id}.entry.json")
   entry
  }.sortedByDescending { it.savedAt } }
 }
 /** Remove only an explicitly overwritten, unreferenced snapshot cache; never game files. */
 internal fun forgetOverwrittenSnapshot(id:String) {
  require(validRecordId(id))
  SafePaths.checked(historyRoot.resolve("$id.entry.json")).deleteIfExists()
  SafePaths.checked(historyRoot.resolve("$id.zip")).deleteIfExists()
 }
 fun loadSnapshot(id: String): Snapshot = loadVerifiedSnapshot(id).snapshot
 internal fun loadKnownUploadArchive(id:String):VerifiedSnapshot {
  require(validRecordId(id))
  val entry=json.decodeFromString<HistoryEntry>(SafePaths.boundedRead(historyRoot.resolve("$id.entry.json"),MAX_MANIFEST_BYTES).decodeToString())
  checkedManifest(entry.manifest);require(entry.manifest.id==id)
  return VerifiedSnapshot.knownArchive(Snapshot(entry.manifest,SafePaths.boundedRead(historyRoot.resolve("$id.zip"),MAX_SNAPSHOT_BYTES)),adapter)
 }
 internal fun loadVerifiedSnapshot(id:String):VerifiedSnapshot {
  require(validRecordId(id))
  val entry = json.decodeFromString<HistoryEntry>(SafePaths.boundedRead(historyRoot.resolve("$id.entry.json"), MAX_MANIFEST_BYTES).decodeToString())
  checkedManifest(entry.manifest); require(entry.manifest.id == id)
  return VerifiedSnapshot.read(Snapshot(entry.manifest, SafePaths.boundedRead(historyRoot.resolve("$id.zip"), MAX_SNAPSHOT_BYTES)),adapter)
 }
 fun currentVersions(): SaveVersions? = locked {
  versions.annotate(Snapshots.capture(root, adapter, config.profile, "自动识别", null)).manifest.saveVersions
 }
 fun targetInfo(): TargetGame? = versions.target(config.targetGame)
 // Immutable history reads do not take the mutation lock. Restore repeats this check under the lock.
 fun checkRestore(id: String) = versions.checkRestore(loadSnapshot(id), config.targetGame)
 internal fun checkRestore(snapshot: Snapshot) = versions.checkRestore(snapshot,config.targetGame)
 internal fun checkRestore(verified: VerifiedSnapshot) = versions.checkRestore(verified,config.targetGame)
 fun createSnapshot(stopped: () -> Boolean): HistoryEntry = locked { remember(capture(stopped), CoreSessionMessages.LOCAL_SNAPSHOT.text()) }
 internal fun legacyPending(): Snapshot? = queue.load(queueKey)
 internal fun archiveLegacyPending() {
  queue.load(queueKey)?.let { remember(it, CoreSessionMessages.LEGACY_PENDING_SNAPSHOT_ARCHIVED.text()) }; queue.clear()
 }
 fun hasPending(): Boolean = locked { queue.load(queueKey) != null }
 fun readRemote(store: Store): HistoryEntry? = locked {
  val remote = store.head() ?: return@locked null
  val snapshot = store.download(remote.manifest)
  checkedManifest(snapshot.manifest); Snapshots.verify(snapshot, adapter)
  remember(snapshot, CoreSessionMessages.REMOTE_SNAPSHOT_LOADED.text())
 }
 fun restoreRemote(id: String, store: Store, stopped: () -> Boolean): RestoreResult = locked {
  guard(stopped)
  require(queue.load(queueKey) == null) { CoreSessionMessages.RESOLVE_OR_ARCHIVE_THE_PENDING_SNAPSHOT_FIRST.text() }
  val snapshot = loadSnapshot(id)
  require(store.head()?.manifest == snapshot.manifest) { CoreSessionMessages.REMOTE_DATA_CHANGED_NOTHING_WAS_RESTORED_RELOAD_THE_REMOTE_DATA.text() }
  val result = restore(snapshot, stopped)
  setHead(snapshot.manifest.id)
  result
 }
 private fun preserve(conflict: Conflict, store: Store): PushResult.Diverged {
  remember(conflict.local, CoreSessionMessages.CONFLICT_LOCAL_BRANCH.text())
  val remote = store.download(conflict.remote.manifest)
  checkedManifest(remote.manifest); Snapshots.verify(remote, adapter)
  remember(remote, CoreSessionMessages.CONFLICT_REMOTE_BRANCH.text())
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
   remember(it, CoreSessionMessages.SYNC_CAPTURE.text()); queue.save(queueKey, it)
  }
  remember(snapshot, CoreSessionMessages.SYNC_CAPTURE.text())
  pushPending(snapshot, store)
 }
 private fun unchanged(conflict: PreservedConflict, store: Store): PushResult.Diverged? {
  val now = store.head()
  require(now != null) { CoreSessionMessages.REMOTE_HISTORY_DISAPPEARED_THE_OPERATION_STOPPED_AND_BOTH_HISTORIES_ARE.text() }
  return if (now.manifest != conflict.head.manifest || now.etag != conflict.head.etag)
   preserve(Conflict(conflict.local, now), store) else null
 }
 fun chooseLocal(conflict: PreservedConflict, store: Store, stopped: () -> Boolean): PushResult = locked {
  guard(stopped)
  require(queue.load(queueKey)?.manifest?.id == conflict.local.manifest.id) { CoreSessionMessages.THE_PENDING_BRANCH_CHANGED_SYNC_AGAIN.text() }
  unchanged(conflict, store)?.let { return@locked it }
  remember(conflict.local, CoreSessionMessages.CONFLICT_LOCAL_BRANCH.text()); remember(conflict.remote, CoreSessionMessages.CONFLICT_REMOTE_BRANCH.text())
  val chosen = conflict.local.copy(manifest = conflict.local.manifest.copy(id = UUID.randomUUID().toString(), parent = conflict.remote.manifest.id))
  remember(chosen, CoreSessionMessages.CONFLICT_RESOLUTION_SELECTED_LOCAL_SAVES.text())
  // Both originals are saved before replacing the retry marker.
  queue.replace(queueKey, chosen)
  pushPending(chosen, store)
 }
 internal fun restore(snapshot: Snapshot, stopped: () -> Boolean, expectedFiles: Map<String,String>? = null): RestoreResult =
  restore(snapshot,stopped,expectedFiles,null)
 internal fun restore(verified: VerifiedSnapshot, stopped: () -> Boolean, expectedFiles: Map<String,String>? = null): RestoreResult =
  restore(verified.snapshot,stopped,expectedFiles,verified)
 private fun restore(snapshot: Snapshot, stopped: () -> Boolean, expectedFiles: Map<String,String>?, verified: VerifiedSnapshot?): RestoreResult {
  guard(stopped); checkedManifest(snapshot.manifest)
  val stability = SaveStabilityGuard(root, adapter, stopped)
  val before = stability.fingerprint()
  require(expectedFiles==null || before.files==expectedFiles) { CoreSessionMessages.LOCAL_CONTENT_CHANGED_BEFORE_RESTORING_SELECT_AGAIN.text() }
  val compatibility: (Snapshot,String)->Unit = { candidate, _ ->
   if(verified==null)versions.checkRestore(candidate,config.targetGame) else versions.checkRestore(verified,config.targetGame)
  }
  val beforeReplacement: ()->Unit = { stability.requireUnchanged(before) }
  val afterRestore: ()->Unit = { directoryMetadata.adopt(snapshot.manifest.directoryMetadata,snapshot.manifest.files) }
  if(verified==null)
   Snapshots.restore(snapshot,root,adapter,"自动识别",stopped,
    validateCompatibility=compatibility,beforeReplacement=beforeReplacement,afterRestore=afterRestore)
  else Snapshots.restoreVerified(verified,root,adapter,"自动识别",stopped,compatibility,beforeReplacement,afterRestore)
  return RestoreResult(snapshot.manifest)
 }
 fun restoreHistory(id: String, stopped: () -> Boolean): RestoreResult = locked {
  require(queue.load(queueKey) == null) { CoreSessionMessages.AN_UPLOAD_OR_CONFLICT_SNAPSHOT_IS_PENDING_RETRY_OR_EXPLICITLY.text() }
  restore(loadSnapshot(id), stopped)
 }
 fun chooseRemote(conflict: PreservedConflict, store: Store, stopped: () -> Boolean): Pair<RestoreResult?, PushResult.Diverged?> = locked {
  guard(stopped)
  require(queue.load(queueKey)?.manifest?.id == conflict.local.manifest.id) { CoreSessionMessages.THE_PENDING_BRANCH_CHANGED_SYNC_AGAIN.text() }
  unchanged(conflict, store)?.let { return@locked null to it }
  remember(conflict.local, CoreSessionMessages.CONFLICT_LOCAL_BRANCH.text()); remember(conflict.remote, CoreSessionMessages.CONFLICT_REMOTE_BRANCH.text())
  val result = restore(conflict.remote, stopped)
  setHead(conflict.remote.manifest.id); queue.clear()
  result to null
 }
 /** Explicit UI confirmation is required. No remote changes; immutable history is retained. */
 fun archivePending() = locked {
  queue.load(queueKey)?.let { remember(it, CoreSessionMessages.PENDING_SNAPSHOT_ARCHIVED.text()) }
  queue.clear()
 }
}
