package pdsync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.channels.FileChannel
import java.nio.file.*
import java.util.UUID
import kotlin.io.path.*

const val DIRECTORY_METADATA_FILE = ".pdsync-meta.json"
private const val DIRECTORY_TEMP_PREFIX = ".pdsync-meta.tmp-"
const val CONTENT_HASH_ALGORITHM = "PDSYNC-CONTENT-V1"
private val digestPattern = Regex("[0-9a-f]{64}")
private val slotPattern = Regex("game[1-9][0-9]{0,3}")

/** Reserved only at the save root. Never broaden this to arbitrary user files. */
fun isDirectoryMetadata(name: String): Boolean = name == DIRECTORY_METADATA_FILE ||
 (name.startsWith(DIRECTORY_TEMP_PREFIX) && validCommitId(name.removePrefix(DIRECTORY_TEMP_PREFIX)))

/** Canonical, length-prefixed content-set digest, independent of ZIP bytes and metadata. */
fun contentSha256(files: Map<String, String>): String {
 require(files.size <= MAX_SNAPSHOT_FILES)
 val names = hashSetOf<String>()
 files.forEach { (path, digest) ->
  require(safePath(path) && !isDirectoryMetadata(path) && names.add(path.lowercase(java.util.Locale.ROOT))) { "Invalid content path" }
  require(digestPattern.matches(digest)) { "Invalid file digest" }
 }
 val sorted = files.keys.sortedWith { a, b ->
  val left=a.encodeToByteArray(); val right=b.encodeToByteArray()
  var result=0
  for(i in 0 until minOf(left.size,right.size)) { result=(left[i].toInt() and 255)-(right[i].toInt() and 255); if(result!=0) break }
  if(result==0) left.size-right.size else result
 }
 val bytes=ByteArrayOutputStream()
 DataOutputStream(bytes).use { out ->
  out.write(CONTENT_HASH_ALGORITHM.encodeToByteArray()); out.writeInt(sorted.size)
  sorted.forEach { name -> val path=name.encodeToByteArray(); out.writeInt(path.size); out.write(path)
   out.write(files.getValue(name).chunked(2).map { it.toInt(16).toByte() }.toByteArray()) }
 }
 return hash(bytes.toByteArray())
}

/** Hashes each complete run relative to its own directory, so moving slots keeps content identity. */
fun runContentHashes(files: Map<String,String>): Map<String,String> = files.keys.map { it.substringBefore('/') }
 .filter { slotPattern.matches(it) }.distinct().associateWith { slot ->
  require("$slot/game.dat" in files) { "Incomplete run: $slot" }
  contentSha256(files.filterKeys { it.startsWith("$slot/") }.mapKeys { it.key.removePrefix("$slot/") })
 }

@Serializable data class RunIdentity(
 val runUUID: String?, val contentSha256: String,
 val parentRunUUIDs: List<String> = emptyList(),
 /** Hint for an explicit user decision only; never accepted as the current run identity. */
 val previousRunUUID: String? = null
) {
 fun validated(): RunIdentity = also {
  require(runUUID == null || validCommitId(runUUID)); require(digestPattern.matches(contentSha256))
  require(parentRunUUIDs.size<=2 && parentRunUUIDs.distinct()==parentRunUUIDs && parentRunUUIDs.all(::validCommitId))
  require(previousRunUUID == null || validCommitId(previousRunUUID))
 }
}

/** Portable snapshot extension. Local synchronization proof is deliberately not transmitted. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class DirectorySnapshotMetadata(
 @EncodeDefault val schema: Int = 1, @EncodeDefault val hashAlgorithm: String = CONTENT_HASH_ALGORITHM,
 val displayName: String = "", val contentSha256: String,
 val runs: Map<String,RunIdentity> = emptyMap()
) {
 fun validated(files: Map<String,String>): DirectorySnapshotMetadata = also {
  require(schema==1 && hashAlgorithm==CONTENT_HASH_ALGORITHM) { "Unsupported directory metadata" }
  require(displayName==sanitizeSnapshotName(displayName)) { "Invalid snapshot name" }
  require(contentSha256==pdsync.contentSha256(files)) { "Directory digest mismatch" }
  val digests=runContentHashes(files)
  require(runs.keys==digests.keys) { "Incomplete run identity map" }
  runs.forEach { (slot,run) -> run.validated(); require(run.contentSha256==digests.getValue(slot)) }
  val ids=runs.values.mapNotNull { it.runUUID }; require(ids.distinct()==ids) { "Duplicate run identity" }
 }
}

fun sanitizeSnapshotName(raw: String): String = if(raw.isBlank()) "" else
 sanitizeDeviceLabel(raw).codePoints().limit(40).toArray().let { String(it,0,it.size) }

@Serializable data class DirectorySyncBaseline(val scope: String, val commitId: String, val contentSha256: String) {
 fun validated() = also { require(digestPattern.matches(scope)); require(validCommitId(commitId)); require(digestPattern.matches(contentSha256)) }
}
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class DirectorySidecar(
 @EncodeDefault val schema: Int = 1, @EncodeDefault val hashAlgorithm: String = CONTENT_HASH_ALGORITHM,
 val displayName: String = "", val currentContentSha256: String,
 val lastSynced: DirectorySyncBaseline? = null,
 val runs: Map<String,RunIdentity> = emptyMap()
) {
 fun validated() = also {
  require(schema==1 && hashAlgorithm==CONTENT_HASH_ALGORITHM)
  require(displayName==sanitizeSnapshotName(displayName)); require(digestPattern.matches(currentContentSha256))
  lastSynced?.validated(); require(runs.size<=MAX_SNAPSHOT_FILES)
  runs.forEach { (slot,run) -> require(slotPattern.matches(slot)); run.validated() }
  val ids=runs.values.mapNotNull { it.runUUID }; require(ids.distinct()==ids)
 }
}
sealed interface SidecarRead {
 data object Missing: SidecarRead
 data class Valid(val value: DirectorySidecar): SidecarRead
 data class Damaged(val reason: String): SidecarRead
}

/** Caller holds the profile/root lock. Strict atomic replacement, no non-atomic fallback. */
class DirectoryMetadataStore(private val root: Path,
 private val replace: (Path,Path)->Unit = { from,to -> Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); Unit }
) {
 private val path get() = SafePaths.checked(root.resolve(DIRECTORY_METADATA_FILE))
 fun read(): SidecarRead = try {
  if(!path.exists()) SidecarRead.Missing else {
   val text=SafePaths.boundedRead(path,MAX_MANIFEST_BYTES).decodeToString(throwOnInvalidSequence=true)
   requireUniqueJsonKeys(text)
   SidecarRead.Valid(json.decodeFromString<DirectorySidecar>(text).validated())
  }
 } catch(e: Exception) { SidecarRead.Damaged(e.message ?: "Invalid directory metadata") }

 fun write(value: DirectorySidecar) {
  value.validated(); SafePaths.directory(root)
  val target=path; val temp=SafePaths.checked(root.resolve(DIRECTORY_TEMP_PREFIX+UUID.randomUUID()))
  val bytes=json.encodeToString(value).encodeToByteArray(); require(bytes.size<=MAX_MANIFEST_BYTES)
  try {
   FileChannel.open(temp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS).use { channel ->
    val buffer=java.nio.ByteBuffer.wrap(bytes); while(buffer.hasRemaining())channel.write(buffer); channel.force(true)
   }
   replace(temp,target)
  } finally { Files.deleteIfExists(temp) }
 }

 /** Any changed bytes may be a reused slot. Keep a hint, but invalidate unproven continuity. */
 fun observe(files: Map<String,String>, initialImport: Boolean = true): DirectorySnapshotMetadata {
  val read=read(); val old=(read as? SidecarRead.Valid)?.value
  val runs=runContentHashes(files).mapValues { (slot,digest) ->
   val prior=old?.runs?.get(slot)
   when {
    prior?.contentSha256==digest -> prior
    prior!=null -> RunIdentity(null,digest,previousRunUUID=prior.runUUID ?: prior.previousRunUUID)
    read is SidecarRead.Missing && initialImport -> RunIdentity(UUID.randomUUID().toString(),digest)
    else -> RunIdentity(null,digest)
   }
  }
  val value=DirectorySidecar(displayName=old?.displayName ?: "",currentContentSha256=contentSha256(files),lastSynced=old?.lastSynced,runs=runs)
  // Damaged metadata remains available for diagnosis; never silently replace lost identities.
  if(read !is SidecarRead.Damaged)write(value)
  return DirectorySnapshotMetadata(displayName=value.displayName,contentSha256=value.currentContentSha256,runs=runs).validated(files)
 }

 fun rename(name: String, files: Map<String,String>): DirectorySnapshotMetadata {
  require(read() !is SidecarRead.Damaged) { "目录元数据损坏，未覆盖原文件" }
  observe(files)
  val old=(read() as SidecarRead.Valid).value
  write(old.copy(displayName=sanitizeSnapshotName(name)))
  return observe(files)
 }

 /** Explicit same-run/new-run choice is bound to the freshly observed complete file set. */
 fun confirmRun(slot: String, expectedFiles: Map<String,String>, sameRun: Boolean): DirectorySnapshotMetadata {
  require(read() !is SidecarRead.Damaged) { "目录元数据损坏，无法确认单局身份" }
  observe(expectedFiles)
  val old=(read() as SidecarRead.Valid).value; val run=old.runs.getValue(slot)
  val id=if(sameRun)requireNotNull(run.previousRunUUID) { "缺少可确认的来源身份" } else UUID.randomUUID().toString()
  write(old.copy(runs=old.runs+(slot to RunIdentity(id,run.contentSha256))))
  return observe(expectedFiles)
 }

 fun adopt(metadata: DirectorySnapshotMetadata?, files: Map<String,String>) {
  metadata?.validated(files)
  write(DirectorySidecar(displayName=metadata?.displayName ?: "",currentContentSha256=contentSha256(files),runs=metadata?.runs ?: runContentHashes(files).mapValues { RunIdentity(null,it.value) }))
 }

 /** Only the repository's verified baseline is authoritative; a copied sidecar never proves sync. */
 fun markSynced(files: Map<String,String>, verified: DirectorySyncBaseline) {
  verified.validated(); require(contentSha256(files)==verified.contentSha256)
  val old=(read() as? SidecarRead.Valid)?.value ?: return
  write(old.copy(lastSynced=verified))
 }
 fun isSynced(actualFiles: Map<String,String>, verified: DirectorySyncBaseline?): Boolean {
  val stored=(read() as? SidecarRead.Valid)?.value ?: return false
  return verified!=null && stored.lastSynced==verified.validated() && contentSha256(actualFiles)==verified.contentSha256
 }
}
