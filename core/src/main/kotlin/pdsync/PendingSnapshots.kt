package pdsync
import java.nio.file.Path
import java.nio.file.Files
import kotlinx.serialization.decodeFromString
import kotlin.io.path.*

import pdsync.i18n.CoreMessages
/** Stores snapshot data and an endpoint fingerprint, never credentials. */
class PendingSnapshots(private val root: Path, private val adapter: GameAdapter = Shattered) {
 private fun checked(path: Path): Path {
  var cursor: Path? = path.toAbsolutePath().normalize()
  while(cursor != null) { require(!Files.isSymbolicLink(cursor)) { CoreMessages.VALIDATION_DEMO_STATE_MUST_NOT_USE_SYMBOLIC_LINKS.text() }; cursor=cursor.parent }
  return path
 }
 private val marker get() = checked(root.resolve("active.txt"))
 fun load(endpoint: String): Snapshot? {
  checked(root)
  if(!marker.exists()) return null
  val lines = Files.newInputStream(marker).use { it.readUpTo(513) }.also { require(it.size <= 512) }.decodeToString().lines()
  require(lines.size == 2 && lines[0] == hash(endpoint.encodeToByteArray())) { CoreMessages.VALIDATION_PENDING_SNAPSHOT_BELONGS_TO_ANOTHER_ENDPOINT.text() }
  val id = lines[1]; require(id.matches(Regex("[0-9a-fA-F-]{36}")))
  val manifestBytes = Files.newInputStream(checked(root.resolve("$id.json"))).use { it.readUpTo(MAX_MANIFEST_BYTES + 1) }; require(manifestBytes.size <= MAX_MANIFEST_BYTES)
  val manifest = json.decodeFromString<Manifest>(manifestBytes.decodeToString()); require(manifest.id == id)
  val bytes = Files.newInputStream(checked(root.resolve("$id.zip"))).use { it.readUpTo(MAX_SNAPSHOT_BYTES + 1) }; require(bytes.size <= MAX_SNAPSHOT_BYTES)
  return Snapshot(manifest,bytes).also { Snapshots.verify(it,adapter) }
 }
 fun save(endpoint: String, snapshot: Snapshot) {
  checked(root).createDirectories(); require(load(endpoint) == null) { CoreMessages.VALIDATION_RETRY_THE_PENDING_SNAPSHOT_FIRST.text() }
  val id=snapshot.manifest.id; require(id.matches(Regex("[0-9a-fA-F-]{36}")))
  Snapshots.verify(snapshot,adapter)
  checked(root.resolve("$id.zip")).writeBytes(snapshot.zip)
  checked(root.resolve("$id.json")).writeText(snapshot.manifest.toJson())
  SafePaths.atomicWrite(marker, (hash(endpoint.encodeToByteArray()) + "\n" + id).encodeToByteArray())
 }
 fun replace(endpoint: String, snapshot: Snapshot) {
  require(load(endpoint) != null) { CoreMessages.VALIDATION_NO_PENDING_SNAPSHOT_TO_REPLACE.text() }
  val id = snapshot.manifest.id; require(id.matches(Regex("[0-9a-fA-F-]{36}")))
  Snapshots.verify(snapshot, adapter)
  SafePaths.atomicWrite(checked(root.resolve("$id.zip")), snapshot.zip)
  SafePaths.atomicWrite(checked(root.resolve("$id.json")), snapshot.manifest.toJson().encodeToByteArray())
  SafePaths.atomicWrite(marker, (hash(endpoint.encodeToByteArray()) + "\n" + id).encodeToByteArray())
 }
 fun clear() { marker.deleteIfExists() }
}
