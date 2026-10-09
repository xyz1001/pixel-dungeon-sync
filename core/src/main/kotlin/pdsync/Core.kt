package pdsync

import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import java.nio.file.*
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.*
import kotlin.io.path.*

@Serializable data class Manifest(val schema: Int = 1, val game: String, val profile: String, val version: String, val id: String, val parent: String?, val sha256: String, val files: Map<String,String>, val saveVersions: SaveVersions? = null)
val json = Json { prettyPrint = true; ignoreUnknownKeys = false }
fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
interface GameAdapter {
 val id: String
 fun includes(name: String): Boolean
 fun compatible(source: String, target: String): Boolean
 fun canonical(path: Path): Path = path.toRealPath()
 fun launchCommand(executable: Path): List<String>
}
object Shattered : GameAdapter {
 override val id = "shattered"
 override fun includes(name: String) = name in setOf("rankings.dat", "badges.dat", "journal.dat") || Regex("game[0-9]+(/.*)?").matches(name)
 override fun compatible(source: String, target: String) = source == target
 override fun launchCommand(executable: Path) = listOf(executable.toString())
}
fun deduplicate(paths: List<Path>, adapter: GameAdapter) = paths.distinctBy { adapter.canonical(it) }
data class Snapshot(val manifest: Manifest, val zip: ByteArray)
const val MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024
const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
const val MAX_SNAPSHOT_FILES = 4096
private fun safePath(name: String): Boolean = name.length <= 1024 && name.split('/').all { part ->
 part.matches(Regex("[A-Za-z0-9_.-]+")) && part !in setOf(".", "..") && !part.endsWith('.') &&
 !Regex("""(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\..*)?""").matches(part)
}
object Snapshots {
 fun capture(root: Path, adapter: GameAdapter, profile: String, version: String, parent: String?): Snapshot {
  require(profile.matches(Regex("[a-zA-Z0-9_-]+")))
  SafePaths.checked(root)
  require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { "Save root must be a real directory, not a symbolic link or file" }
  val files = sortedMapOf<String, ByteArray>(); var total = 0L; val portableNames = mutableSetOf<String>()
  Files.walk(root).use { stream -> stream.forEach { p ->
   val name = root.relativize(p).toString().replace('\\','/')
   if (adapter.includes(name)) {
    require(!Files.isSymbolicLink(p)) { "Symbolic links are forbidden" }
    if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
     require(safePath(name) && portableNames.add(name.lowercase(java.util.Locale.ROOT))) { "Unsafe or aliased save path" }
     require(files.size < MAX_SNAPSHOT_FILES)
     val remaining = MAX_SNAPSHOT_BYTES - total.toInt()
     val bytes = Files.newInputStream(p).use { it.readNBytes(remaining + 1) }
     total += bytes.size; require(total <= MAX_SNAPSHOT_BYTES)
     files[name] = bytes
    } else {
     require(Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) { "Save entry disappeared or is not a regular file/directory" }
    }
   }
  } }
  files.keys.filter { it.startsWith("game") }.map { it.substringBefore('/') }.distinct().forEach { require("$it/game.dat" in files) { "Incomplete run: $it" } }
  val output = java.io.ByteArrayOutputStream()
  ZipOutputStream(output).use { z -> files.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
  val zip = output.toByteArray(); require(zip.size <= MAX_SNAPSHOT_BYTES)
  return Snapshot(Manifest(game=adapter.id, profile=profile, version=version, id=UUID.randomUUID().toString(), parent=parent, sha256=hash(zip), files=files.mapValues { hash(it.value) }), zip)
 }
 fun verify(snapshot: Snapshot, adapter: GameAdapter): Map<String, ByteArray> {
  require(snapshot.zip.size <= MAX_SNAPSHOT_BYTES && snapshot.manifest.files.size <= MAX_SNAPSHOT_FILES)
  require(snapshot.manifest.schema == 1 && snapshot.manifest.game == adapter.id)
  require(hash(snapshot.zip) == snapshot.manifest.sha256) { "Archive checksum mismatch" }
  val files = mutableMapOf<String, ByteArray>(); var total = 0L; val portableNames = mutableSetOf<String>()
  ZipInputStream(snapshot.zip.inputStream()).use { zip -> while(true) {
   val entry = zip.nextEntry ?: break; val name = entry.name
   require(!entry.isDirectory && !name.startsWith('/') && !name.contains('\\') && name.split('/').none { it == ".." || it.isEmpty() } && !name.contains(':') && adapter.includes(name)) { "Unsafe archive path" }
   require(safePath(name) && portableNames.add(name.lowercase(java.util.Locale.ROOT))) { "Unsafe or aliased archive path" }
   require(name !in files && files.size < MAX_SNAPSHOT_FILES)
   val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
   while(true) { val n = zip.read(buffer); if(n < 0) break; total += n; require(total <= MAX_SNAPSHOT_BYTES); out.write(buffer,0,n) }
   files[name] = out.toByteArray()
  } }
  require(files.mapValues { hash(it.value) } == snapshot.manifest.files) { "File checksum mismatch" }
  files.keys.filter { it.startsWith("game") }.map { it.substringBefore('/') }.distinct().forEach { require("$it/game.dat" in files) }
  return files
 }
 fun restore(snapshot: Snapshot, root: Path, backupRoot: Path, adapter: GameAdapter, version: String, guard: () -> Boolean, beforeWrite: () -> Unit = {}, afterFileWrite: (String) -> Unit = {}, onBackupReady: (Snapshot, Path) -> Unit = { _, _ -> },
  validateCompatibility: (Snapshot, String) -> Unit = { candidate, target -> require(adapter.compatible(candidate.manifest.version, target)) },
  captureBackup: (Path) -> Snapshot = { capture(it, adapter, snapshot.manifest.profile, version, null) }) : Path {
  require(guard()) { "Game-running status must be confirmed stopped" }
  SafePaths.directory(root); SafePaths.checked(backupRoot)
  validateCompatibility(snapshot, version)
  val files = verify(snapshot,adapter)
  Files.walk(root).use { entries -> require(entries.noneMatch { Files.isSymbolicLink(it) }) { "Restore target contains symlinks" } }
  require(!Files.isSymbolicLink(root) && !Files.isSymbolicLink(backupRoot))
  backupRoot.createDirectories()
  val actualRoot = root.toRealPath()
  val actualBackup = backupRoot.toRealPath()
  require(!actualRoot.startsWith(actualBackup) && !actualBackup.startsWith(actualRoot)) { "Backup must be outside save directory" }
  val backup = captureBackup(root)
  verify(backup, adapter)
  backupRoot.createDirectories(); val backupPath = backupRoot.resolve("${backup.manifest.id}.zip")
  backupPath.writeBytes(backup.zip); backupRoot.resolve("${backup.manifest.id}.json").writeText(json.encodeToString(backup.manifest))
  fun replace(data: Map<String,ByteArray>, notify: Boolean = false) {
   Files.walk(root).use { s -> s.sorted(Comparator.reverseOrder()).forEach { p -> if(p != root && adapter.includes(root.relativize(p).toString().replace('\\','/'))) { require(!Files.isSymbolicLink(p)); Files.delete(p) } } }
   data.forEach { (name,bytes) -> val p = root.resolve(name); p.parent.createDirectories(); p.writeBytes(bytes); if(notify) afterFileWrite(name) }
  }
  onBackupReady(backup, backupPath)
  // No restore mutation has occurred yet. A restarted game must not trigger rollback.
  require(guard()) { "Game-running status changed before restore" }
  require(capture(root,adapter,snapshot.manifest.profile,version,null).manifest.files == backup.manifest.files) { "Save files changed after backup; restore cancelled without rollback" }
  try {
   replace(emptyMap()); beforeWrite(); replace(files,true)
   require(capture(root, adapter, snapshot.manifest.profile, version, null).manifest.files == snapshot.manifest.files) { "Restored files failed verification" }
  } catch(e: Exception) {
   try { replace(verify(backup,adapter)) } catch(rollback: Exception) { val failed = IllegalStateException("Restore and rollback failed; backup retained at $backupPath",e); failed.addSuppressed(rollback); throw failed }
   throw e
  }
  return backupPath
 }
}
data class RemoteHead(val manifest: Manifest, val etag: String?)
interface Store { fun head(): RemoteHead?; fun upload(snapshot: Snapshot); fun download(manifest: Manifest): Snapshot; fun publish(manifest: Manifest, expected: RemoteHead?) }
class Conflict(val local: Snapshot, val remote: RemoteHead): Exception("Offline branches preserved; choose explicitly")
class Sync(private val store: Store) {
 fun push(local: Snapshot): Manifest {
  val remote = store.head()
  if (remote?.manifest == local.manifest) { require(store.download(local.manifest).zip.contentEquals(local.zip)); return local.manifest }
  if(remote?.manifest?.id != local.manifest.parent) throw Conflict(local, remote ?: throw IllegalStateException("Remote history disappeared"))
  store.upload(local)
  require(hash(store.download(local.manifest).zip) == local.manifest.sha256)
  store.publish(local.manifest,remote)
  return local.manifest
 }
}

fun Manifest.toJson(): String = json.encodeToString(this)
