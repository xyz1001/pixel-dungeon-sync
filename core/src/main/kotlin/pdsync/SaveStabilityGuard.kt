package pdsync

import pdsync.i18n.CoreMessages
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

data class SaveFileMetadata(val directory: Boolean, val size: Long, val modified: FileTime, val fileKey: String?)
/** files contains deterministic allowed-file content hashes, independent of ZIP IDs and timestamps. */
data class SaveFingerprint(val files: Map<String, String>, val metadata: Map<String, SaveFileMetadata>)
data class StableSaveCapture(val snapshot: Snapshot, val fingerprint: SaveFingerprint)
/** Safe to retry after a new stopped/quiescent observation; distinct from invalid paths/archives. */
class SaveNotStableException(message: String) : IllegalStateException(message)

/**
 * This is a race-reduction guard, not an OS lock on the game or Steam Cloud. Call on a worker
 * thread under the existing profile lock. The stopped callback must make a fresh process query.
 * A quiet observation is never a promise that an external writer cannot start later.
 */
class SaveStabilityGuard(
 private val root: Path,
 private val adapter: GameAdapter,
 private val stopped: () -> Boolean,
 private val quietMillis: Long = 2_000,
 private val pause: (Long) -> Unit = { Thread.sleep(it) },
 private val captureSnapshot: (Path, GameAdapter, String, String, String?, SnapshotCaptureContext?) -> Snapshot = Snapshots::capture
) {
 init { require(quietMillis >= 0) }

 /** Uses the existing bounded capture/path checks; does not inspect excluded device files. */
 fun fingerprint(): SaveFingerprint {
  val before = metadata()
  val captured = captureSnapshot(root, adapter, "stability", "自动识别", null, null)
  val after = metadata()
  stableCheck(before == after, CoreMessages.SAVES_CHANGED_WHILE_READING_WAIT_FOR_THE_GAME_TO_EXIT_AND_RETRY.text())
  return SaveFingerprint(captured.manifest.files.toSortedMap(), after)
 }

 fun awaitStable(): SaveFingerprint {
  requireStopped()
  val first = fingerprint()
  requireStopped()
  try { pause(quietMillis) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
  requireStopped()
  val second = fingerprint()
  requireStopped()
  stableCheck(first == second, CoreMessages.SAVES_ARE_STILL_BEING_WRITTEN_WAIT_FOR_FILES_TO_STABILIZE_AND_RETRY.text())
  return second
 }

 fun requireUnchanged(expected: SaveFingerprint) {
  requireStopped()
  stableCheck(fingerprint() == expected, CoreMessages.SAVES_CHANGED_AFTER_THE_CHECK_OPERATION_CANCELED.text())
  requireStopped()
 }

 /** All checks precede returning bytes to history/publication; no save mutation occurs here. */
 fun capture(profile: String, version: String, parent: String?, captureContext: SnapshotCaptureContext? = null,
  captureContextProvider: (() -> SnapshotCaptureContext)? = null): Snapshot =
  captureStable(profile, version, parent, captureContext, captureContextProvider).snapshot

 /** Keep this exact fingerprint for the final restore check after backup registration. */
 fun captureStable(profile: String, version: String, parent: String?, captureContext: SnapshotCaptureContext? = null,
  captureContextProvider: (() -> SnapshotCaptureContext)? = null): StableSaveCapture {
  val stable = awaitStable()
  requireUnchanged(stable)
  val captured = captureSnapshot(root, adapter, profile, version, parent, captureContextProvider?.invoke() ?: captureContext)
  stableCheck(captured.manifest.files == stable.files, CoreMessages.SAVES_CHANGED_WHILE_CREATING_THE_SNAPSHOT_OPERATION_CANCELED.text())
  requireUnchanged(stable)
  return StableSaveCapture(captured, stable)
 }

 private fun requireStopped() { stableCheck(stopped(), CoreMessages.THE_GAME_IS_RUNNING_OR_ITS_PROCESS_STATUS_IS_UNKNOWN_SAVE_OPERATIONS_PAUSED.text()) }
 private fun stableCheck(condition: Boolean, message: String) { if (!condition) throw SaveNotStableException(message) }

 private fun metadata(): Map<String, SaveFileMetadata> {
  SafePaths.checked(root)
  require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { CoreMessages.VALIDATION_SAVE_ROOT_MUST_BE_A_REAL_DIRECTORY.text() }
  val result = sortedMapOf<String, SaveFileMetadata>()
  var bytes = 0L
  var files = 0
  fun record(path: Path) {
   // Windows directory enumeration can return cached timestamps and no file key.
   // Read the entry itself for fresh metadata and identity; never follow links.
   val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
   require(!attrs.isSymbolicLink && (attrs.isDirectory || attrs.isRegularFile)) { CoreMessages.VALIDATION_UNSAFE_SAVE_ENTRY.text() }
   require(result.size < MAX_SNAPSHOT_FILES * 4) { CoreMessages.VALIDATION_TOO_MANY_SAVE_ENTRIES.text() }
   if (attrs.isRegularFile) {
    files++; bytes += attrs.size()
    require(files <= MAX_SNAPSHOT_FILES && bytes <= MAX_SNAPSHOT_BYTES) { CoreMessages.VALIDATION_SAVE_FILES_EXCEED_SNAPSHOT_LIMITS.text() }
   }
   val name = root.relativize(path).toString().replace('\\', '/')
   result[name] = SaveFileMetadata(attrs.isDirectory, if (attrs.isDirectory) 0 else attrs.size(), attrs.lastModifiedTime(), attrs.fileKey()?.toString())
  }
  Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
   override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
    if (dir == root) return FileVisitResult.CONTINUE
    val name = root.relativize(dir).toString().replace('\\', '/')
    if (!adapter.includes(name) || isDirectoryMetadata(name)) return FileVisitResult.SKIP_SUBTREE
    record(dir)
    return FileVisitResult.CONTINUE
   }
   override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
    val name=root.relativize(file).toString().replace('\\', '/')
    if (adapter.includes(name) && !isDirectoryMetadata(name)) record(file)
    return FileVisitResult.CONTINUE
   }
  })
  return result
 }
}
