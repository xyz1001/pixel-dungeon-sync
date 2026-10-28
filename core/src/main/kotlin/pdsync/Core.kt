package pdsync

import pdsync.i18n.CoreValidationMessages

import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import java.nio.file.*
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.*
import kotlin.io.path.*

@Serializable data class Manifest(val schema: Int = 1, val game: String, val profile: String, val version: String, val id: String, val parent: String?, val sha256: String, val files: Map<String,String>, val saveVersions: SaveVersions? = null, val metadata: SnapshotMetadata? = null, val directoryMetadata: DirectorySnapshotMetadata? = null, val gameSource: GameSaveSource? = null, val gamePackage: String? = null)
val json = Json { prettyPrint = true; ignoreUnknownKeys = false }
fun hash(bytes: ByteArray):String {
 val digest=MessageDigest.getInstance("SHA-256").digest(bytes)
 val digits="0123456789abcdef";val encoded=CharArray(digest.size*2)
 for(i in digest.indices) {val n=digest[i].toInt() and 255;encoded[i*2]=digits[n ushr 4];encoded[i*2+1]=digits[n and 15]}
 return String(encoded)
}
interface GameAdapter {
 val id: String
 /** The actual game package is shared by desktop and Android; provenance does not name a family. */
 val packageName: String? get() = null
 fun acceptsManifest(manifest: Manifest): Boolean {
  if (manifest.game != id) return false
  manifest.gamePackage?.let(::requireGamePackage)
  manifest.gameSource?.validated()
  val expected = packageName ?: return true
  requireGamePackage(expected)
  // Exact-namespace local captures may await package annotation. Package evidence, when
  // present, must agree; matching packages never authorize another namespace.
  return recordedGamePackage(manifest)?.let { it == expected } ?: true
 }
 /** New connections use a stable per-game namespace; existing profiles are never rewritten. */
 val defaultRemoteProfile: String get() = "default"
 fun includes(name: String): Boolean
 fun compatible(source: String, target: String): Boolean
 fun canonical(path: Path): Path = path.toRealPath()
 fun launchCommand(executable: Path): List<String>
}
object Shattered : GameAdapter {
 override val id = "shattered"
 override val packageName = GameCloudIdentity.SHATTERED_PACKAGE
 override fun includes(name: String) = name in setOf("rankings.dat", "badges.dat", "journal.dat") || Regex("game[0-9]+(/.*)?").matches(name)
 override fun compatible(source: String, target: String) = source == target
 override fun launchCommand(executable: Path) = listOf(executable.toString())
}
fun deduplicate(paths: List<Path>, adapter: GameAdapter) = paths.distinctBy { adapter.canonical(it) }
data class Snapshot(val manifest: Manifest, val zip: ByteArray)
/** A single operation owns these verified bytes; no whole-history decoded cache is retained. */
class VerifiedSnapshot private constructor(
 val snapshot: Snapshot,
 internal val entries: Map<String, ByteArray>,
 private val source:VerifiedSnapshot?=null, private val knownVersions:SaveVersions?=null
) {
 val byteCount: Long = entries.values.sumOf { it.size.toLong() }
 internal val saveVersions: SaveVersions by lazy { source?.saveVersions ?: knownVersions ?: ShatteredVersions.detect(entries) }
 internal fun withManifest(manifest:Manifest):VerifiedSnapshot {
  require(manifest.files==snapshot.manifest.files && manifest.sha256==snapshot.manifest.sha256)
  return VerifiedSnapshot(snapshot.copy(manifest=manifest),entries,this)
 }
 companion object {
  /** Upload assumes intact save bytes; retain known metadata, parse only when needed. */
  internal fun knownArchive(snapshot:Snapshot,adapter:GameAdapter):VerifiedSnapshot =
   if(snapshot.manifest.saveVersions!=null)VerifiedSnapshot(snapshot,emptyMap(),knownVersions=snapshot.manifest.saveVersions)
   else VerifiedSnapshot(snapshot,Snapshots.readArchive(snapshot,adapter,false))
  internal fun captured(snapshot:Snapshot,entries:Map<String,ByteArray>)=VerifiedSnapshot(snapshot,entries)
  internal fun read(snapshot: Snapshot, adapter: GameAdapter): VerifiedSnapshot =
   VerifiedSnapshot(snapshot, Snapshots.verify(snapshot, adapter))
 }
}
/** One bounded filesystem read. Its fingerprint describes the same bytes used for the snapshot. */
internal class LocalSaveRead(
 val entries: Map<String, ByteArray>,
 val fingerprint: SaveFingerprint
)
const val MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024
const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
const val MAX_SNAPSHOT_FILES = 4096
internal fun safePath(name: String): Boolean = name.length <= 1024 && name.split('/').all { part ->
 part.matches(Regex("[A-Za-z0-9_.-]+")) && part !in setOf(".", "..") && !part.endsWith('.') &&
 !Regex("""(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\..*)?""").matches(part)
}
object Snapshots {
 // Android 8 libcore rejects closing ZipOutputStream without entries. A zero-entry ZIP is
 // exactly this 22-byte end-of-central-directory record, with all counts/offsets zero.
 private fun archive(files: Map<String,ByteArray>): ByteArray {
  if (files.isEmpty()) return ByteArray(22).also { it[0]=0x50; it[1]=0x4b; it[2]=0x05; it[3]=0x06 }
  val output=java.io.ByteArrayOutputStream()
  ZipOutputStream(output).use { zip -> files.forEach { (name,bytes) ->
   zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()
  } }
  return output.toByteArray()
 }

 /** Assemble a planned complete file set; run the same archive and metadata validation as downloads. */
 fun fromFiles(files: Map<String,ByteArray>,adapter: GameAdapter,profile: String,version: String,
  directoryMetadata: DirectorySnapshotMetadata? = null, metadata: SnapshotMetadata? = null): Snapshot {
  require(files.size<=MAX_SNAPSHOT_FILES && files.values.sumOf { it.size.toLong() }<=MAX_SNAPSHOT_BYTES)
  files.keys.forEach { name -> require(safePath(name)&&adapter.includes(name)&&!isDirectoryMetadata(name)) }
  val archive=archive(files.toSortedMap())
  val snapshot=Snapshot(Manifest(game=adapter.id,profile=profile,version=version,id=UUID.randomUUID().toString(),parent=null,
   sha256=hash(archive),files=files.mapValues { hash(it.value) },metadata=metadata,directoryMetadata=directoryMetadata,
   saveVersions=if(adapter.id==Shattered.id)ShatteredVersions.detect(files) else null),archive)
  verify(snapshot,adapter)
  return snapshot
 }
 fun capture(root: Path, adapter: GameAdapter, profile: String, version: String, parent: String?, captureContext: SnapshotCaptureContext? = null): Snapshot {
  return capture(readLocal(root, adapter), adapter, profile, version, parent, captureContext)
 }

 internal fun capture(read: LocalSaveRead, adapter: GameAdapter, profile: String, version: String, parent: String?, captureContext: SnapshotCaptureContext? = null): Snapshot {
  require(profile.matches(Regex("[a-zA-Z0-9_-]+")))
  captureContext?.let { it.device.validated(); requireUtcTimestamp(it.capturedAt) }
  val zip = archive(read.entries); require(zip.size <= MAX_SNAPSHOT_BYTES)
  val modifiedAt = read.fingerprint.metadata.filterKeys { it in read.entries }.mapValues { it.value.modified.toInstant().toString() }
  return Snapshot(Manifest(game=adapter.id, profile=profile, version=version, id=UUID.randomUUID().toString(), parent=parent, sha256=hash(zip), files=read.fingerprint.files,
   metadata=captureContext?.let { SnapshotMetadata(device=it.device, capturedAt=it.capturedAt, fileModifiedAt=modifiedAt).validated(read.entries.keys) }), zip)
 }

 internal fun readLocal(root: Path, adapter: GameAdapter): LocalSaveRead {
  val metadata = sortedMapOf<String, SaveFileMetadata>()
  val hashes = sortedMapOf<String, String>()
  val entries = readCaptureFiles(root, adapter, metadata) { name, _, bytes ->
   hashes[name] = hash(bytes)
   bytes
  }
  return LocalSaveRead(entries, SaveFingerprint(hashes, metadata))
 }

 internal fun fingerprint(root: Path, adapter: GameAdapter): SaveFingerprint {
  val metadata = sortedMapOf<String, SaveFileMetadata>()
  val hashes = readCaptureFiles(root, adapter, metadata) { _, _, bytes -> hash(bytes) }
  return SaveFingerprint(hashes, metadata)
 }

 /** The same bounded allowed-file read as capture, without allocating or compressing a ZIP. */
 fun fileHashes(root: Path, adapter: GameAdapter): Map<String, String> =
  readCaptureFiles(root, adapter) { _, _, bytes -> hash(bytes) }

 private fun <T> readCaptureFiles(root: Path, adapter: GameAdapter, metadata: MutableMap<String, SaveFileMetadata>? = null,
  read: (String, Path, ByteArray) -> T): Map<String, T> {
  if (Thread.currentThread().isInterrupted) throw InterruptedException()
  SafePaths.checked(root)
  require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { CoreValidationMessages.SAVE_ROOT_MUST_BE_A_REAL_DIRECTORY_NOT_A_SYMBOLIC_LINK_OR_FILE.text() }
  val files = sortedMapOf<String, T>(); var total = 0L; val portableNames = mutableSetOf<String>()
  Files.walk(root).use { stream -> stream.forEach { p ->
   if (Thread.currentThread().isInterrupted) throw InterruptedException()
   val name = root.relativize(p).toString().replace('\\','/')
   if (adapter.includes(name) && !isDirectoryMetadata(name)) {
    val attributes = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    require(!attributes.isSymbolicLink) { CoreValidationMessages.SYMBOLIC_LINKS_ARE_FORBIDDEN.text() }
    if (attributes.isRegularFile) {
     require(safePath(name) && portableNames.add(name.lowercase(java.util.Locale.ROOT))) { CoreValidationMessages.UNSAFE_OR_ALIASED_SAVE_PATH.text() }
     require(files.size < MAX_SNAPSHOT_FILES)
     val remaining = MAX_SNAPSHOT_BYTES - total.toInt()
     val bytes = Files.newInputStream(p, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.readUpTo(remaining + 1) }
     total += bytes.size; require(total <= MAX_SNAPSHOT_BYTES)
     files[name] = read(name, p, bytes)
    } else {
     require(attributes.isDirectory) { CoreValidationMessages.SAVE_ENTRY_DISAPPEARED_OR_IS_NOT_A_REGULAR_FILE_DIRECTORY.text() }
    }
    metadata?.let {
     require(it.size < MAX_SNAPSHOT_FILES * 4) { pdsync.i18n.CoreMessages.VALIDATION_TOO_MANY_SAVE_ENTRIES.text() }
     it[name] = SaveFileMetadata(attributes.isDirectory, if (attributes.isDirectory) 0 else attributes.size(), attributes.lastModifiedTime(), attributes.fileKey()?.toString())
    }
   }
  } }
  files.keys.filter { it.startsWith("game") }.map { it.substringBefore('/') }.distinct().forEach { require("$it/game.dat" in files) { CoreValidationMessages.INCOMPLETE_RUN.text(it) } }
  return files
 }
 fun verify(snapshot: Snapshot, adapter: GameAdapter): Map<String, ByteArray> = DiagnosticTiming.measure(DiagnosticPhase.SNAPSHOT_VERIFY) {readArchive(snapshot,adapter,true)}
 internal fun readArchive(snapshot:Snapshot,adapter:GameAdapter,verifyContent:Boolean):Map<String,ByteArray> {
  require(snapshot.zip.size <= MAX_SNAPSHOT_BYTES && snapshot.manifest.files.size <= MAX_SNAPSHOT_FILES)
  require(snapshot.manifest.schema == 1 && adapter.acceptsManifest(snapshot.manifest))
  snapshot.manifest.gameSource?.validated()
  snapshot.manifest.gamePackage?.let(::requireGamePackage)
  snapshot.manifest.metadata?.validated(snapshot.manifest.files.keys)
  snapshot.manifest.directoryMetadata?.validated(snapshot.manifest.files)
  if(verifyContent)require(hash(snapshot.zip) == snapshot.manifest.sha256) { CoreValidationMessages.ARCHIVE_CHECKSUM_MISMATCH.text() }
  val files = mutableMapOf<String, ByteArray>(); var total = 0L; val portableNames = mutableSetOf<String>()
  ZipInputStream(snapshot.zip.inputStream()).use { zip -> while(true) {
   val entry = zip.nextEntry ?: break; val name = entry.name
   require(!entry.isDirectory && !name.startsWith('/') && !name.contains('\\') && name.split('/').none { it == ".." || it.isEmpty() } && !name.contains(':') && adapter.includes(name) && !isDirectoryMetadata(name)) { CoreValidationMessages.UNSAFE_ARCHIVE_PATH.text() }
   require(safePath(name) && portableNames.add(name.lowercase(java.util.Locale.ROOT))) { CoreValidationMessages.UNSAFE_OR_ALIASED_ARCHIVE_PATH.text() }
   require(name !in files && files.size < MAX_SNAPSHOT_FILES)
   val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
   while(true) { val n = zip.read(buffer); if(n < 0) break; total += n; require(total <= MAX_SNAPSHOT_BYTES); out.write(buffer,0,n) }
   files[name] = out.toByteArray()
  } }
  if(verifyContent)require(files.mapValues { hash(it.value) } == snapshot.manifest.files) { CoreValidationMessages.FILE_CHECKSUM_MISMATCH.text() }
  files.keys.filter { it.startsWith("game") }.map { it.substringBefore('/') }.distinct().forEach { require("$it/game.dat" in files) }
  return files
 }
 /** Replaces the selected save set without retaining overwritten bytes or rolling them back. */
 fun restore(snapshot: Snapshot, root: Path, backupRoot: Path, adapter: GameAdapter, version: String, guard: () -> Boolean, beforeWrite: () -> Unit = {}, afterFileWrite: (String) -> Unit = {}, onBackupReady: (Snapshot, Path) -> Unit = { _, _ -> },
  validateCompatibility: (Snapshot, String) -> Unit = { candidate, target -> require(adapter.compatible(candidate.manifest.version, target)) },
  captureBackup: (Path) -> Snapshot = { capture(it, adapter, snapshot.manifest.profile, version, null) },
  beforeReplacement: () -> Unit = {}, afterRestore: () -> Unit = {}) {
  // Legacy arguments remain source-compatible; no backup path, capture or callback is used.
  restoreImpl(snapshot,root,adapter,version,guard,beforeWrite,afterFileWrite,validateCompatibility,beforeReplacement,afterRestore,null)
 }
 internal fun restoreVerified(verified: VerifiedSnapshot, root: Path, adapter: GameAdapter, version: String, guard: () -> Boolean,
  validateCompatibility: (Snapshot, String) -> Unit, beforeReplacement: () -> Unit, afterRestore: () -> Unit) =
  restoreImpl(verified.snapshot,root,adapter,version,guard,{},{},validateCompatibility,beforeReplacement,afterRestore,verified)

 private fun restoreImpl(snapshot: Snapshot, root: Path, adapter: GameAdapter, version: String, guard: () -> Boolean,
  beforeWrite: () -> Unit, afterFileWrite: (String) -> Unit,
  validateCompatibility: (Snapshot, String) -> Unit,
  beforeReplacement: () -> Unit, afterRestore: () -> Unit, verified: VerifiedSnapshot?) {
  guard()
  if (Thread.currentThread().isInterrupted) throw InterruptedException()
  SafePaths.directory(root)
  validateCompatibility(snapshot, version)
  val files = verified?.entries ?: verify(snapshot,adapter)
  Files.walk(root).use { entries -> require(entries.noneMatch { Files.isSymbolicLink(it) }) { CoreValidationMessages.RESTORE_TARGET_CONTAINS_SYMLINKS.text() } }
  val stability = SaveStabilityGuard(root,adapter,guard)
  val before = stability.fingerprint()
  guard()
  if (Thread.currentThread().isInterrupted) throw InterruptedException()
  beforeReplacement()
  stability.requireUnchanged(before)
  // Failures after this boundary leave the actual partial result; old bytes are never restored.
  Files.walk(root).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach { p ->
   val name=root.relativize(p).toString().replace('\\','/')
   if(p != root && adapter.includes(name) && !isDirectoryMetadata(name)) { require(!Files.isSymbolicLink(p)); Files.delete(p) }
  } }
  beforeWrite()
  files.forEach { (name,bytes) -> val p = root.resolve(name); SafePaths.checked(p); p.parent.createDirectories(); p.writeBytes(bytes); afterFileWrite(name) }
  require(fileHashes(root, adapter) == snapshot.manifest.files) { CoreValidationMessages.RESTORED_FILES_FAILED_VERIFICATION.text() }
  afterRestore()
 }

}
data class RemoteHead(val manifest: Manifest, val etag: String?)
interface Store { fun head(): RemoteHead?; fun upload(snapshot: Snapshot); fun download(manifest: Manifest): Snapshot; fun publish(manifest: Manifest, expected: RemoteHead?) }
class Conflict(val local: Snapshot, val remote: RemoteHead): Exception(CoreValidationMessages.OFFLINE_BRANCHES_PRESERVED_CHOOSE_EXPLICITLY.text())
class Sync(private val store: Store) {
 fun push(local: Snapshot): Manifest {
  val remote = store.head()
  if (remote?.manifest == local.manifest) { require(store.download(local.manifest).zip.contentEquals(local.zip)); return local.manifest }
  if(remote?.manifest?.id != local.manifest.parent) throw Conflict(local, remote ?: throw IllegalStateException(CoreValidationMessages.REMOTE_HISTORY_DISAPPEARED.text()))
  store.upload(local)
  require(hash(store.download(local.manifest).zip) == local.manifest.sha256)
  store.publish(local.manifest,remote)
  return local.manifest
 }
}

fun Manifest.toJson(): String = json.encodeToString(this)
