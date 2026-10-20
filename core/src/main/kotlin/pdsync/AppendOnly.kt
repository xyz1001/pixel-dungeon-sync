package pdsync

import pdsync.i18n.CoreValidationMessages

import pdsync.i18n.CoreSessionMessages

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.file.Path
import java.time.Clock
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

/** A transport page is only an observation, never a globally authoritative head. */
data class CommitPage(val ids: List<String>, val next: String? = null)
interface AppendStore {
 fun <T> withReadScope(cacheDirectory: Path? = null, block: () -> T): T = block()
 fun <T> withFreshReads(block: () -> T): T = block()
 fun listCommits(cursor: String? = null): CommitPage
 fun readCommit(id: String): ByteArray?
 fun readCommit(id: String, cancellation: MetadataReadCancellation): ByteArray? {
  cancellation.checkActive()
  return readCommit(id)
 }
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
class LegacyProtocolException: IllegalStateException(CoreSessionMessages.A_LEGACY_HEAD_JSON_WAS_FOUND_NOTHING_WAS_MIGRATED_OR.text())

enum class RemoteVerification { METADATA_ONLY, OBJECTS_VERIFIED }

data class CommitGraph(
 val commits: Map<String, AppendCommit>,
 val pending: Map<String, String>,
 val verification: RemoteVerification = RemoteVerification.OBJECTS_VERIFIED
) {
 fun requireObjectsVerified() {
  require(verification == RemoteVerification.OBJECTS_VERIFIED) { CoreSessionMessages.COMPLETE_A_SAFE_COMPARISON_OR_RESOLVE_THE_BLOCKED_OPERATION_FIRST.text() }
 }
 val tips: Set<String> get() = commits.keys - commits.values.flatMap { it.parents }.toSet()
 fun requireSettled() { if (pending.isNotEmpty()) throw UnsettledRemoteException(CoreSessionMessages.THE_CLOUD_HAS_MISSING_INCOMPLETE_OR_UNVERIFIABLE_COMMITS_LOCAL_SAVES.text(pending.size)) }
 fun descendsFrom(child: String, ancestor: String): Boolean {
  val remaining = ArrayDeque<String>(); val seen = mutableSetOf<String>(); remaining.add(child)
  while (remaining.isNotEmpty()) { val id = remaining.removeFirst(); if (id == ancestor) return true; if (seen.add(id)) commits[id]?.parents?.forEach { remaining.add(it) } }
  return false
 }
}

/** Display-only manifests carry no proof that any remote object has been read or verified. */
@ConsistentCopyVisibility
data class CommitMetadataGraph internal constructor(val displayGraph: CommitGraph) {
 init { require(displayGraph.verification == RemoteVerification.METADATA_ONLY) }
 val commits: Map<String, AppendCommit> get() = displayGraph.commits
 val pending: Map<String, String> get() = displayGraph.pending
 val tips: Set<String> get() = displayGraph.tips
 fun requireSettled() = displayGraph.requireSettled()
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
  require(commit.schema == 2 && validCommitId(commit.id) && commit.id == expected) { CoreValidationMessages.INVALID_COMMIT_IDENTITY.text() }
  require(commit.parents.size <= MAX_COMMIT_PARENTS && commit.parents.distinct().size == commit.parents.size && commit.parents.all { validCommitId(it) && it != commit.id }) { CoreValidationMessages.INVALID_COMMIT_PARENTS.text() }
  commit.operation?.validated()
  require(commit.kind in setOf("snapshot", "resolution", "restore", "legacy-import"))
  require(commit.kind == "resolution" || commit.parents.size <= 1) { CoreValidationMessages.ORDINARY_COMMITS_CANNOT_ABSORB_BRANCHES.text() }
  require(commit.kind != "resolution" || commit.parents.size >= 2) { CoreValidationMessages.RESOLUTION_MUST_NAME_MULTIPLE_PARENTS.text() }
  require(commit.kind != "legacy-import" || commit.parents.isEmpty())
  val snapshot = commit.snapshot
  require(snapshot.schema == 1 && adapter.acceptsManifest(snapshot) && snapshot.profile == profile && snapshot.parent == null)
  snapshot.gameSource?.validated()
  snapshot.gamePackage?.let(::requireGamePackage)
  snapshot.metadata?.validated(snapshot.files.keys)
  snapshot.directoryMetadata?.validated(snapshot.files)
  require(validCommitId(snapshot.id) && snapshot.id != commit.id && snapshot.sha256.matches(Regex("[0-9a-f]{64}")))
  require(snapshot.files.isNotEmpty() && snapshot.files.size <= MAX_SNAPSHOT_FILES && snapshot.files.values.all { it.matches(Regex("[0-9a-f]{64}")) })
  val portableNames = hashSetOf<String>()
  snapshot.files.keys.forEach { name ->
   require(safePath(name) && adapter.includes(name) && !isDirectoryMetadata(name) && portableNames.add(name.lowercase(Locale.ROOT))) { CoreValidationMessages.UNSAFE_OR_ALIASED_SAVE_PATH.text() }
  }
  snapshot.files.keys.filter { it.startsWith("game") }.map { it.substringBefore('/') }.distinct().forEach {
   require("$it/game.dat" in snapshot.files) { CoreValidationMessages.INCOMPLETE_RUN.text(it) }
  }
  snapshot.saveVersions?.let { versions ->
   val slots = snapshot.files.keys.mapNotNull { Regex("(game[0-9]+)/game\\.dat").matchEntire(it)?.groupValues?.get(1) }.toSet()
   require(versions.runs.size <= MAX_SNAPSHOT_FILES && versions.inactiveSlots.size <= MAX_SNAPSHOT_FILES)
   require(versions.runs.keys.all { it in slots } && versions.runs.values.all { it > 0 })
   require(versions.inactiveSlots.distinct() == versions.inactiveSlots && versions.inactiveSlots.all { it in slots && it !in versions.runs })
   require(versions.unknownReason == null || versions.runs.isEmpty())
   require(versions.unknownReason != null || versions.runs.keys + versions.inactiveSlots == slots)
  }
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
  return java.nio.file.Files.list(root).use { files -> files.iterator().asSequence().toList().also { require(it.size <= MAX_COMMITS) }.map { path ->
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
  if (old != null && !old.contentEquals(bytes)) throw RemoteIntegrityException(CoreSessionMessages.KNOWN_COMMIT_WAS_MODIFIED_SYNC_STOPPED.text(id))
  if (old == null) SafePaths.atomicWrite(knownRoot.resolve("$id.json"), bytes)
 }
 fun baseline(): String? {
  if (!SafePaths.checked(baselinePath).exists()) {
   check(!SafePaths.checked(baselineMarker).exists()) { CoreSessionMessages.THE_SYNC_BASELINE_IS_MISSING_EXISTING_HISTORY_WAS_NOT_TREATED.text() }; return null
  }
  return SafePaths.boundedRead(baselinePath, 128).decodeToString().also { require(validCommitId(it)) }
 }
 /** Only a previously established, validated local baseline; never infer one from the remote tips. */
 fun baselineFiles(): Map<String, String>? {
  val id = baseline() ?: return null
  val bytes = checkNotNull(known(id)) { CoreSessionMessages.SYNC_BASELINE_METADATA_IS_MISSING_THIS_WAS_NOT_TREATED_AS.text() }
  return decode(bytes, id).snapshot.files
 }
 /** Offline presentation cache: only object-verified commits with complete cached ancestry. */
 fun cachedGraph(): CommitGraph {
  val candidates=idsIn(knownRoot).filter { observedAt(it)!=null }.associateWith { decode(requireNotNull(known(it)),it) }
  val complete=linkedMapOf<String,AppendCommit>()
  do { val additions=candidates.filter { (id,c)->id !in complete && c.parents.all { it in complete } };complete.putAll(additions) } while(additions.isNotEmpty())
  return CommitGraph(complete,emptyMap())
 }
 fun setBaseline(id: String, graph: CommitGraph) {
  graph.requireObjectsVerified()
  graph.requireSettled(); val verified = requireNotNull(graph.commits[id]) { CoreValidationMessages.CANNOT_BASELINE_AN_INCOMPLETE_COMMIT.text() }
  val bytes = requireNotNull(known(id)); require(decode(bytes, id) == verified)
  SafePaths.atomicWrite(baselineMarker, "established".encodeToByteArray())
  SafePaths.atomicWrite(baselinePath, id.encodeToByteArray())
 }

 /** Missing later listings never erase remembered IDs. Parents are fetched even when omitted. */
 fun discover(store: AppendStore): CommitGraph = discoverGraph(store, verifyObjects = true)

 fun discoverMetadata(store: AppendStore, parallelism: Int = 4): CommitMetadataGraph {
  require(parallelism in 1..4)
  return CommitMetadataGraph(discoverGraph(store, verifyObjects = false, metadataParallelism = parallelism))
 }

 private fun discoverGraph(store: AppendStore, verifyObjects: Boolean, metadataParallelism: Int = 1): CommitGraph {
  if (store.legacyPresent()) throw LegacyProtocolException()
  val ids = (idsIn(knownRoot) + idsIn(observedRoot)).toMutableSet(); baseline()?.let { ids.add(it) }; require(ids.size <= MAX_COMMITS)
  val cursors = mutableSetOf<String>(); var cursor: String? = null; var pages = 0; var listed = 0L
  do {
   require(++pages <= MAX_COMMITS) { CoreValidationMessages.TOO_MANY_LISTING_PAGES_NO_PARTIAL_DISCOVERY_ACCEPTED.text() }
   val page = store.listCommits(cursor)
   listed += page.ids.size; require(listed <= MAX_COMMITS) { CoreValidationMessages.LISTING_ENTRY_WORK_BUDGET_EXCEEDED_NO_PARTIAL_DISCOVERY_ACCEPTED.text() }
   require(page.ids.all(::validCommitId)) { CoreValidationMessages.INVALID_COMMIT_FILENAME.text() }
   ids.addAll(page.ids); require(ids.size <= MAX_COMMITS) { CoreValidationMessages.TOO_MANY_COMMITS_NO_PARTIAL_DISCOVERY_ACCEPTED.text() }
   page.ids.forEach(::observe)
   cursor = page.next
   if (cursor != null) require(cursors.add(cursor)) { CoreValidationMessages.REPEATED_LISTING_CONTINUATION_NO_PARTIAL_DISCOVERY_ACCEPTED.text() }
  } while (cursor != null)
  val pending = mutableMapOf<String, String>(); val candidates = linkedMapOf<String, AppendCommit>()
  val queue = ArrayDeque(ids); val seen = mutableSetOf<String>(); var scanBytes = 0L
  val executor = if (verifyObjects) null else Executors.newFixedThreadPool(metadataParallelism) { runnable ->
   Thread(runnable, "pdsync-metadata-read").apply { isDaemon = true }
  }
  val trace = DiagnosticLog.currentTrace()
  val futures = mutableListOf<Future<Pair<String, ByteArray?>>>()
  val cancellation = if (verifyObjects) null else MetadataReadCancellation()
  try {
   while (queue.isNotEmpty()) {
    if (Thread.currentThread().isInterrupted) throw InterruptedException()
    val batch = mutableListOf<String>()
    while (queue.isNotEmpty() && batch.size < if (verifyObjects) 1 else metadataParallelism) {
     val id = queue.removeFirst()
     if (seen.add(id)) {
      require(seen.size <= MAX_COMMITS) { CoreValidationMessages.HISTORY_TOO_LARGE_NO_PARTIAL_DISCOVERY_ACCEPTED.text() }
      observe(id)
      batch += id
     }
    }
    futures.clear()
    val fetched = linkedMapOf<String, ByteArray?>()
    if (executor != null) {
     val completed = ExecutorCompletionService<Pair<String, ByteArray?>>(executor)
     batch.forEach { id ->
      futures += completed.submit(Callable {
       val binding = trace?.let(DiagnosticLog::bind)
       try { id to store.readCommit(id, requireNotNull(cancellation)) }
       finally { binding?.close() }
      })
     }
     repeat(batch.size) {
      val result = try { completed.take().get() } catch (error: ExecutionException) { throw (error.cause ?: error) }
      fetched[result.first] = result.second
     }
    }
    // Completion order detects failures promptly; decoding still follows discovery order.
    for (id in batch) {
     val bytes = if (executor != null) fetched[id] else if (cancellation != null) store.readCommit(id, cancellation) else store.readCommit(id)
     if (bytes == null) { pending[id] = "missing manifest"; continue }
     scanBytes += bytes.size; require(scanBytes <= MAX_REMOTE_SCAN_BYTES) { CoreValidationMessages.REMOTE_SCAN_BYTE_BUDGET_EXCEEDED_NO_PARTIAL_DISCOVERY_ACCEPTED.text() }
     val old = known(id)
     if (old != null && !old.contentEquals(bytes)) throw RemoteIntegrityException(CoreSessionMessages.KNOWN_COMMIT_WAS_MODIFIED_SYNC_STOPPED.text(id))
     val commit = try { decode(bytes, id) } catch (_: Exception) { pending[id] = "invalid manifest"; continue }
     remember(id, bytes)
     commit.parents.forEach { queue.add(it) }
     if (verifyObjects) {
      scanBytes += commit.objectBytes; require(scanBytes <= MAX_REMOTE_SCAN_BYTES) { CoreValidationMessages.REMOTE_SCAN_BYTE_BUDGET_EXCEEDED_NO_PARTIAL_DISCOVERY_ACCEPTED.text() }
      val zip = store.readObject(commit.snapshot.id)
      if (zip == null) { pending[id] = "missing object"; continue }
      scanBytes += maxOf(0, zip.size - commit.objectBytes)
      require(scanBytes <= MAX_REMOTE_SCAN_BYTES) { CoreValidationMessages.REMOTE_SCAN_BYTE_BUDGET_EXCEEDED_NO_PARTIAL_DISCOVERY_ACCEPTED.text() }
      try { require(zip.size == commit.objectBytes); Snapshots.verify(Snapshot(commit.snapshot, zip), adapter) }
      catch (_: Exception) { pending[id] = "invalid object"; continue }
      observeVerified(id)
     }
     candidates[id] = commit
    }
   }
  } finally {
   cancellation?.cancel()
   futures.forEach { it.cancel(true) }
   executor?.shutdownNow()
   try { executor?.awaitTermination(100, TimeUnit.MILLISECONDS) }
   catch (_: InterruptedException) { Thread.currentThread().interrupt() }
  }
  // Iterative closure avoids recursive-stack attacks. Cycles and incomplete ancestors stay pending.
  val complete = linkedMapOf<String, AppendCommit>()
  do {
   val additions = candidates.filter { (id, commit) -> id !in complete && commit.parents.all { it in complete } }
   complete.putAll(additions)
  } while (additions.isNotEmpty())
  candidates.keys.filter { it !in complete }.forEach { pending[it] = "incomplete ancestry or cycle" }
  return CommitGraph(complete, pending, if (verifyObjects) RemoteVerification.OBJECTS_VERIFIED else RemoteVerification.METADATA_ONLY)
 }

 fun download(store: AppendStore, commit: AppendCommit): Snapshot {
  checkCommit(commit)
  val bytes = store.readCommit(commit.id) ?: throw UnsettledRemoteException(CoreSessionMessages.THE_COMMIT_IS_NOT_VISIBLE_YET_TRY_AGAIN.text())
  require(decode(bytes, commit.id) == commit); remember(commit.id, bytes)
  val zip = store.readObject(commit.snapshot.id) ?: throw UnsettledRemoteException(CoreSessionMessages.THE_SNAPSHOT_IS_NOT_VISIBLE_YET_TRY_AGAIN.text())
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
  require(pending() == null) { CoreValidationMessages.RETRY_OR_EXPLICITLY_ARCHIVE_THE_PENDING_OPERATION_FIRST.text() }
  Snapshots.verify(snapshot, adapter)
  // A new publication always uses new independent object and commit IDs, including restore/resolution.
  val family = adapter.packageName?.takeIf {
   recordedGamePackage(snapshot.manifest) == it ||
    (snapshot.manifest.game == adapter.id && snapshot.manifest.gameSource == null)
  }
  val frozen = snapshot.copy(manifest = snapshot.manifest.copy(
   gamePackage = snapshot.manifest.gamePackage ?: family, id = UUID.randomUUID().toString(), parent = null))
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
   if (!before.contentEquals(expected)) throw RemoteIntegrityException(CoreSessionMessages.IMMUTABLE_OBJECT_IDENTITY_CONFLICT_EXISTING_CONTENT_WAS_NOT_OVERWRITTEN.text())
   return
  }
  try { write() } catch (e: Exception) {
   if (e is io.github.xyz1001.pdsync.android.OfficialSyncAccess.AccessException) throw e
   val observed = try { read() } catch (readFailure: Exception) {
    if (readFailure is io.github.xyz1001.pdsync.android.OfficialSyncAccess.AccessException) throw readFailure
    throw e
   }
   if (observed == null) throw e
   if (!observed.contentEquals(expected)) throw RemoteIntegrityException(CoreSessionMessages.UPLOAD_STATUS_IS_UNCERTAIN_AND_THE_EXISTING_OBJECT_DIFFERS_IT.text())
   return
  }
  val after = read() ?: throw UnsettledRemoteException(CoreSessionMessages.THE_UPLOADED_OBJECT_IS_NOT_VISIBLE_YET_THE_FROZEN_OPERATION.text())
  if (!after.contentEquals(expected)) throw RemoteIntegrityException(CoreSessionMessages.UPLOAD_VERIFICATION_FAILED_PUBLICATION_STOPPED.text())
 }
 fun publish(store: AppendStore, plan: UploadPlan): AppendCommit {
  val frozen = requireNotNull(pending())
  require(frozen.commit == plan.commit && frozen.manifestBytes.contentEquals(plan.manifestBytes) && frozen.zip.contentEquals(plan.zip))
  val graph = discover(store); graph.requireSettled()
  require(plan.commit.parents.all { it in graph.commits }) { CoreSessionMessages.PARENT_COMMITS_HAVE_NOT_BEEN_FULLY_VERIFIED_NOTHING_WAS_PUBLISHED.text() }
  store.withFreshReads {
   putVerified({ store.readObject(plan.commit.snapshot.id) }, { store.writeObject(plan.commit.snapshot.id, plan.zip) }, plan.zip)
   putVerified({ store.readCommit(plan.commit.id) }, { store.writeCommit(plan.commit.id, plan.manifestBytes) }, plan.manifestBytes)
  }
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
   '{' -> { require(scopes.size < 64) { CoreValidationMessages.MANIFEST_NESTING_TOO_DEEP.text() }; scopes.addLast(mutableSetOf()) }
   '[' -> { require(scopes.size < 64) { CoreValidationMessages.MANIFEST_NESTING_TOO_DEEP.text() }; scopes.addLast(null) }
   '}', ']' -> { require(scopes.isNotEmpty()); scopes.removeLast() }
   '"' -> {
    val start = position++; var escaped = false
    while (position < text.length) {
     val character = text[position]
     if (!escaped && character == '"') break
     escaped = !escaped && character == '\\'; position++
    }
    require(position < text.length) { CoreValidationMessages.UNTERMINATED_JSON_STRING.text() }
    var next = position + 1; while (next < text.length && text[next].isWhitespace()) next++
    if (next < text.length && text[next] == ':') {
     val keys = scopes.lastOrNull() ?: throw IllegalArgumentException(CoreValidationMessages.JSON_KEY_OUTSIDE_OBJECT.text())
     require(keys.add(json.decodeFromString<String>(text.substring(start, position + 1)))) { CoreValidationMessages.DUPLICATE_JSON_KEY.text() }
    }
   }
  }
  position++
 }
 require(scopes.isEmpty()) { CoreValidationMessages.UNTERMINATED_JSON_OBJECT.text() }
}
