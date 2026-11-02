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
/** Safe to retry after a fresh observation; distinct from invalid paths/archives. */
class SaveNotStableException(message: String) : IllegalStateException(message)

/**
 * One bounded read under the existing profile lock, not an OS lock on external writers.
 * An action may explicitly compare a later observation at its write boundary. Reading a save
 * does not wait for a quiet period or repeatedly scan the same content.
 */
class SaveStabilityGuard(
 private val root: Path,
 private val adapter: GameAdapter,
 private val stopped: () -> Boolean,
 private val captureSnapshot: ((Path, GameAdapter, String, String, String?, SnapshotCaptureContext?) -> Snapshot)? = null,
 private val captureFileHashes: ((Path, GameAdapter) -> Map<String, String>)? = null
) {
 /** Uses the existing bounded capture/path checks; does not inspect excluded device files. */
 fun fingerprint(): SaveFingerprint {
  val injected = captureFileHashes
  return if (injected == null) Snapshots.fingerprint(root, adapter)
   else SaveFingerprint(injected(root, adapter).toSortedMap(), metadata())
 }

 /** Compatibility name; this now performs one observation with no delay. */
 fun awaitStable(): SaveFingerprint {
  requireContext()
  return fingerprint()
 }

 fun requireUnchanged(expected: SaveFingerprint) {
  requireContext()
  stableCheck(fingerprint() == expected, CoreMessages.SAVES_CHANGED_AFTER_THE_CHECK_OPERATION_CANCELED.text())
 }

 /** All checks precede returning bytes to history/publication; no save mutation occurs here. */
 fun capture(profile: String, version: String, parent: String?, captureContext: SnapshotCaptureContext? = null,
  captureContextProvider: (() -> SnapshotCaptureContext)? = null): Snapshot =
  captureStable(profile, version, parent, captureContext, captureContextProvider).snapshot

 /** Keep this exact fingerprint for the final restore check before replacement. */
 fun captureStable(profile: String, version: String, parent: String?, captureContext: SnapshotCaptureContext? = null,
  captureContextProvider: (() -> SnapshotCaptureContext)? = null): StableSaveCapture {
  requireContext()
  val context = captureContextProvider?.invoke() ?: captureContext
  val injected = captureSnapshot
  if (injected != null) {
   val captured = injected(root, adapter, profile, version, parent, context)
   return StableSaveCapture(captured, SaveFingerprint(captured.manifest.files, metadata()))
  }
  val read = Snapshots.readLocal(root, adapter)
  return StableSaveCapture(Snapshots.capture(read, adapter, profile, version, parent, context), read.fingerprint)
 }

 private fun requireContext() {
  // Android's existing callback also verifies package identity. Preserve that exception boundary,
  // but a process-state Boolean is no longer a reason to wait or reject a save operation.
  stopped()
  if (Thread.currentThread().isInterrupted) throw InterruptedException()
 }
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
