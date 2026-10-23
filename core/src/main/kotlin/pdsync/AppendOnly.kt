package pdsync

import pdsync.i18n.CoreValidationMessages

import pdsync.i18n.CoreSessionMessages

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.file.Path
import java.time.Clock
import java.util.Locale
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
 fun listCommits(cursor:String?,cancellation:MetadataReadCancellation):CommitPage {
  cancellation.checkActive();return listCommits(cursor)
 }
 fun readCommit(id: String): ByteArray?
 fun readCommit(id: String, cancellation: MetadataReadCancellation): ByteArray? {
  cancellation.checkActive()
  return readCommit(id)
 }
 fun readObject(id: String): ByteArray?
 fun writeCommit(id: String, bytes: ByteArray)
 fun writeObject(id: String, bytes: ByteArray)
 fun deleteCommit(id:String):Unit = error("Store does not support confirmed deletion")
 fun deleteObject(id:String):Unit = error("Store does not support confirmed deletion")
 fun readDeletion(id:String):ByteArray? = null
 fun writeDeletion(id:String,bytes:ByteArray):Unit = error("Store does not support confirmed deletion")
 fun legacyPresent(): Boolean
 fun legacyPresent(cancellation:MetadataReadCancellation):Boolean {
  cancellation.checkActive();return legacyPresent()
 }
}

fun validCommitId(id: String) = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(id)
fun validContentHashId(id: String) = Regex("[0-9a-f]{64}").matches(id)
fun validRecordId(id: String) = validCommitId(id) || validContentHashId(id)
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
data class AppendPublication(val commit: AppendCommit, val reused: Boolean)
internal class VerifiedUploadPlan(val plan:UploadPlan,val verified:VerifiedSnapshot)
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
 /** Physical identities and raw metadata stay intact, including legacy UUID ancestry. */
 fun findByContent(files: Map<String,String>): AppendCommit? = commits.values
  .filter { it.snapshot.files == files }.sortedWith(compareBy<AppendCommit> { it.schema != 3 }.thenBy { it.id }).firstOrNull()
 private val contentIds: Map<String,Set<String>> by lazy {
  commits.values.groupBy { contentSha256(it.snapshot.files) }
   .mapValues { (_,records)->records.mapTo(sortedSetOf()) { it.id } }
 }
 /** A hash identifies content, so every physical alias supplies that same parent evidence.
  * Legacy UUID references retain their one exact original physical edge. */
 fun resolveParentIds(parent: String): Set<String> = when {
  validContentHashId(parent) -> contentIds[parent].orEmpty()
  parent in commits -> setOf(parent)
  else -> emptySet()
 }
 fun parentIds(commit: AppendCommit): List<String> = commit.parents.flatMap(::resolveParentIds).distinct()
 val tips: Set<String> get() = commits.keys - commits.values.flatMap(::parentIds).toSet()
 fun requireSettled() { if (pending.isNotEmpty()) throw UnsettledRemoteException(CoreSessionMessages.THE_CLOUD_HAS_MISSING_INCOMPLETE_OR_UNVERIFIABLE_COMMITS_LOCAL_SAVES.text(pending.size)) }
 fun descendsFrom(child: String, ancestor: String): Boolean {
  val remaining = ArrayDeque<String>(); val seen = mutableSetOf<String>(); remaining.add(child)
  while (remaining.isNotEmpty()) { val id = remaining.removeFirst(); if (id == ancestor) return true; if (seen.add(id)) commits[id]?.let(::parentIds)?.forEach { remaining.add(it) } }
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
internal class MetadataReadSeed(val known:Map<String,String>,val observed:Set<String>,val baseline:String?)
internal class DetachedMetadataRead(val graph:CommitMetadataGraph,val known:Map<String,ByteArray>,val observed:Set<String>,val retired:Set<String> = emptySet())
private class MetadataEvidence(seed:MetadataReadSeed) {
 val retired=linkedSetOf<String>()
 val expected=seed.known
 val known=linkedMapOf<String,ByteArray>()
 val observed=LinkedHashSet(seed.observed)
 val baseline=seed.baseline
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
  require((commit.schema == 2 && validCommitId(commit.id) || commit.schema == 3 && validContentHashId(commit.id)) && commit.id == expected) { CoreValidationMessages.INVALID_COMMIT_IDENTITY.text() }
  require(commit.parents.size <= MAX_COMMIT_PARENTS && commit.parents.distinct().size == commit.parents.size && commit.parents.all { (if(commit.schema == 2) validCommitId(it) else validContentHashId(it)) && it != commit.id }) { CoreValidationMessages.INVALID_COMMIT_PARENTS.text() }
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
  require((if(commit.schema == 2) validCommitId(snapshot.id) && snapshot.id != commit.id else snapshot.id == commit.id && commit.id == contentSha256(snapshot.files)) && validContentHashId(snapshot.sha256))
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
   require(path.fileName.toString().endsWith(".json")); path.fileName.toString().removeSuffix(".json").also { require(validRecordId(it)) }
  }.toSet() }
 }
 private fun observe(id: String) {
  require(validRecordId(id))
  val path = observedRoot.resolve("$id.json")
  if (!SafePaths.checked(path).exists()) SafePaths.atomicWrite(path, byteArrayOf())
 }
 /** First successful verification observed by this installation, never a server receipt time. */
 fun observedAt(id: String): String? {
  require(validRecordId(id))
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
 internal fun forgetDeleted(id:String) {
  require(validRecordId(id))
  listOf(knownRoot.resolve("$id.json"),observedRoot.resolve("$id.json"),observationRoot.resolve("$id.txt")).forEach {SafePaths.checked(it).deleteIfExists()}
  if(baseline()==id) {SafePaths.checked(baselinePath).deleteIfExists();SafePaths.checked(baselineMarker).deleteIfExists()}
 }
 internal fun overwriteDirectory():Path=directory.resolve("confirmed-overwrite")
 fun baseline(): String? {
  if (!SafePaths.checked(baselinePath).exists()) {
   check(!SafePaths.checked(baselineMarker).exists()) { CoreSessionMessages.THE_SYNC_BASELINE_IS_MISSING_EXISTING_HISTORY_WAS_NOT_TREATED.text() }; return null
  }
  return SafePaths.boundedRead(baselinePath, 128).decodeToString().also { require(validRecordId(it)) }
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
  val resolved=CommitGraph(candidates,emptyMap())
  do { val additions=candidates.filter { (id,c)->id !in complete && c.parents.all { parent -> resolved.resolveParentIds(parent).let { it.isNotEmpty() && it.all(complete::containsKey) } } };complete.putAll(additions) } while(additions.isNotEmpty())
  return CommitGraph(complete,emptyMap())
 }
 fun setBaseline(id: String, graph: CommitGraph) {
  graph.requireObjectsVerified()
  graph.requireSettled(); val verified = requireNotNull(graph.commits[id]) { CoreValidationMessages.CANNOT_BASELINE_AN_INCOMPLETE_COMMIT.text() }
  val bytes = requireNotNull(known(id)); require(decode(bytes, id) == verified)
  SafePaths.atomicWrite(baselineMarker, "established".encodeToByteArray())
  SafePaths.atomicWrite(baselinePath, id.encodeToByteArray())
 }
 /** Selected-object evidence never upgrades the rest of a metadata graph. */
 internal fun setSelectedBaseline(commit: AppendCommit, graph: CommitMetadataGraph, verified: VerifiedSnapshot) {
  graph.requireSettled()
  require(graph.commits[commit.id] == commit && verified.snapshot.manifest == commit.snapshot)
  require(verified.snapshot.zip.size == commit.objectBytes && observedAt(commit.id) != null)
  require(decode(requireNotNull(known(commit.id)),commit.id) == commit)
  SafePaths.atomicWrite(baselineMarker,"established".encodeToByteArray())
  SafePaths.atomicWrite(baselinePath,commit.id.encodeToByteArray())
 }
 internal fun verifiedCommitIds(graph: CommitGraph): Set<String> =
  graph.commits.filter { (id,commit) -> observedAt(id)!=null && known(id)?.let { decode(it,id)==commit }==true }.keys

 /** Missing later listings never erase remembered IDs. Parents are fetched even when omitted. */
 fun discover(store: AppendStore): CommitGraph = discoverGraph(store, verifyObjects = true)

 /** Only called with this upload's freshly verified graph and a server-read-back commit.
  * Re-list IDs and re-read every manifest to detect mutation/deletion and new branches.
  * Only unchanged manifests reuse this operation's already verified object evidence. */
 internal fun discoverUploadDelta(store:AppendStore,verified:CommitGraph,published:AppendCommit):CommitGraph {
  verified.requireObjectsVerified();verified.requireSettled()
  require(known(published.id)?.let {decode(it,published.id)==published}==true && observedAt(published.id)!=null)
  return discoverGraph(store,true,operationSeed=verified.commits+(published.id to published))
 }
 fun discoverMetadata(store: AppendStore, parallelism: Int = 4): CommitMetadataGraph {
  require(parallelism in 1..4)
  return CommitMetadataGraph(discoverGraph(store, verifyObjects = false, metadataParallelism = parallelism))
 }
 internal fun metadataReadSeed():MetadataReadSeed {
  var total=0L
  val known=idsIn(knownRoot).associateWith {
   val bytes=requireNotNull(known(it))
   total+=bytes.size;require(total<=MAX_REMOTE_SCAN_BYTES)
   hash(bytes)
  }
  return MetadataReadSeed(known,idsIn(observedRoot),baseline())
 }
 /** No filesystem reads or writes occur after the seed has been captured under the profile lock. */
 internal fun readMetadata(store:AppendStore,seed:MetadataReadSeed,cancellation:MetadataReadCancellation):DetachedMetadataRead {
  val evidence=MetadataEvidence(seed)
  val graph=CommitMetadataGraph(discoverGraph(store,false,4,evidence,cancellation))
  return DetachedMetadataRead(graph,evidence.known.toMap(),evidence.observed.toSet(),evidence.retired.toSet())
 }
 internal fun mergeMetadata(read:DetachedMetadataRead) {
  // Validate the complete delta before the first write. This never records object verification.
  read.known.forEach { (id,bytes) ->
   val old=known(id)
   if(old!=null && !old.contentEquals(bytes))throw RemoteIntegrityException(CoreSessionMessages.KNOWN_COMMIT_WAS_MODIFIED_SYNC_STOPPED.text(id))
   decode(bytes,id)
  }
  read.retired.forEach(::forgetDeleted)
  read.observed.forEach(::observe)
  read.known.forEach { (id,bytes)->remember(id,bytes) }
 }

 private fun discoverGraph(store: AppendStore, verifyObjects: Boolean, metadataParallelism: Int = 1,
  evidence:MetadataEvidence?=null,externalCancellation:MetadataReadCancellation?=null,operationSeed:Map<String,AppendCommit> = emptyMap()): CommitGraph {
  val cancellation = externalCancellation ?: if (verifyObjects) null else MetadataReadCancellation()
  fun observed(id:String) { if(evidence==null)observe(id) else evidence.observed.add(id) }
  fun knownBytes(id:String):ByteArray? = if(evidence==null)known(id) else evidence.known[id]
  fun rememberBytes(id:String,bytes:ByteArray) { if(evidence==null)remember(id,bytes) else evidence.known[id]=bytes }
  if (if(cancellation==null)store.legacyPresent() else store.legacyPresent(cancellation)) throw LegacyProtocolException()
  val ids = (if(evidence==null)idsIn(knownRoot)+idsIn(observedRoot) else evidence.expected.keys+evidence.observed).toMutableSet()
  (if(evidence==null)baseline() else evidence.baseline)?.let { ids.add(it) }; require(ids.size <= MAX_COMMITS)
  val cursors = mutableSetOf<String>(); var cursor: String? = null; var pages = 0; var listed = 0L
  do {
   require(++pages <= MAX_COMMITS) { CoreValidationMessages.TOO_MANY_LISTING_PAGES_NO_PARTIAL_DISCOVERY_ACCEPTED.text() }
   val page = if(cancellation==null)store.listCommits(cursor) else store.listCommits(cursor,cancellation)
   listed += page.ids.size; require(listed <= MAX_COMMITS) { CoreValidationMessages.LISTING_ENTRY_WORK_BUDGET_EXCEEDED_NO_PARTIAL_DISCOVERY_ACCEPTED.text() }
   require(page.ids.all(::validRecordId)) { CoreValidationMessages.INVALID_COMMIT_FILENAME.text() }
   ids.addAll(page.ids); require(ids.size <= MAX_COMMITS) { CoreValidationMessages.TOO_MANY_COMMITS_NO_PARTIAL_DISCOVERY_ACCEPTED.text() }
   page.ids.forEach(::observed)
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
  try {
   while (queue.isNotEmpty()) {
    if (Thread.currentThread().isInterrupted) throw InterruptedException()
    cancellation?.checkActive()
    val batch = mutableListOf<String>()
    while (queue.isNotEmpty() && batch.size < if (verifyObjects) 1 else metadataParallelism) {
     val id = queue.removeFirst()
     if (seen.add(id)) {
      require(seen.size <= MAX_COMMITS) { CoreValidationMessages.HISTORY_TOO_LARGE_NO_PARTIAL_DISCOVERY_ACCEPTED.text() }
      observed(id)
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
     if (bytes == null) {
      val receipt=store.readDeletion(id)?.let { DeletionReceipt.decode(it,id,adapter.id,profile) }
      if(receipt!=null) {
       evidence?.expected?.get(id)?.let {require(it==receipt.recordHash)}
       knownBytes(id)?.let { require(hash(it)==receipt.recordHash) {"Deleted record proof changed"} }
       if(evidence==null)forgetDeleted(id) else { evidence.known.remove(id);evidence.observed.remove(id);evidence.retired.add(id) }
       continue
      }
      pending[id] = "missing manifest"; continue
     }
     scanBytes += bytes.size; require(scanBytes <= MAX_REMOTE_SCAN_BYTES) { CoreValidationMessages.REMOTE_SCAN_BYTE_BUDGET_EXCEEDED_NO_PARTIAL_DISCOVERY_ACCEPTED.text() }
     val old = knownBytes(id)
     if (old != null && !old.contentEquals(bytes) || evidence?.expected?.get(id)?.let {it!=hash(bytes)}==true)
      throw RemoteIntegrityException(CoreSessionMessages.KNOWN_COMMIT_WAS_MODIFIED_SYNC_STOPPED.text(id))
     val commit = try { decode(bytes, id) } catch (_: Exception) { pending[id] = "invalid manifest"; continue }
     rememberBytes(id, bytes)
     if(commit.schema == 2) commit.parents.forEach { queue.add(it) }
     if (verifyObjects) {
      scanBytes += commit.objectBytes; require(scanBytes <= MAX_REMOTE_SCAN_BYTES) { CoreValidationMessages.REMOTE_SCAN_BYTE_BUDGET_EXCEEDED_NO_PARTIAL_DISCOVERY_ACCEPTED.text() }
      if(operationSeed[id]!=commit) {
      val zip = store.readObject(commit.snapshot.id)
      if (zip == null) { pending[id] = "missing object"; continue }
      scanBytes += maxOf(0, zip.size - commit.objectBytes)
      require(scanBytes <= MAX_REMOTE_SCAN_BYTES) { CoreValidationMessages.REMOTE_SCAN_BYTE_BUDGET_EXCEEDED_NO_PARTIAL_DISCOVERY_ACCEPTED.text() }
      try { require(zip.size == commit.objectBytes); Snapshots.verify(Snapshot(commit.snapshot, zip), adapter) }
      catch (_: Exception) { pending[id] = "invalid object"; continue }
      }
      observeVerified(id)
     }
     candidates[id] = commit
    }
    if(queue.isEmpty()) {
     val observedGraph=CommitGraph(candidates,emptyMap())
     val observedContent=candidates.values.mapTo(mutableSetOf()) { contentSha256(it.snapshot.files) }
     candidates.values.flatMap { it.parents }.filter { validContentHashId(it) && observedGraph.resolveParentIds(it).isEmpty() && it !in observedContent && it !in seen }.distinct().forEach(queue::add)
    }
   }
  } catch(failure:Throwable) {
   cancellation?.cancel()
   throw failure
  } finally {
   if(externalCancellation==null)cancellation?.cancel()
   futures.forEach { it.cancel(true) }
   executor?.shutdownNow()
   try { executor?.awaitTermination(100, TimeUnit.MILLISECONDS) }
   catch (_: InterruptedException) { Thread.currentThread().interrupt() }
  }
  // Iterative closure avoids recursive-stack attacks. Cycles and incomplete ancestors stay pending.
  val complete = linkedMapOf<String, AppendCommit>()
  val resolved=CommitGraph(candidates,emptyMap())
  do {
   val additions = candidates.filter { (id, commit) -> id !in complete && commit.parents.all { parent -> resolved.resolveParentIds(parent).let { it.isNotEmpty() && it.all(complete::containsKey) } } }
   complete.putAll(additions)
  } while (additions.isNotEmpty())
  candidates.keys.filter { it !in complete }.forEach { pending[it] = "incomplete ancestry or cycle" }
  return CommitGraph(complete, pending, if (verifyObjects) RemoteVerification.OBJECTS_VERIFIED else RemoteVerification.METADATA_ONLY)
 }

 fun download(store: AppendStore, commit: AppendCommit): Snapshot = downloadVerified(store,commit).snapshot
 internal fun downloadVerified(store: AppendStore, commit: AppendCommit): VerifiedSnapshot {
  checkCommit(commit)
  val bytes = store.readCommit(commit.id) ?: throw UnsettledRemoteException(CoreSessionMessages.THE_COMMIT_IS_NOT_VISIBLE_YET_TRY_AGAIN.text())
  require(decode(bytes, commit.id) == commit); remember(commit.id, bytes)
  val zip = store.readObject(commit.snapshot.id) ?: throw UnsettledRemoteException(CoreSessionMessages.THE_SNAPSHOT_IS_NOT_VISIBLE_YET_TRY_AGAIN.text())
  require(zip.size == commit.objectBytes)
  return VerifiedSnapshot.read(Snapshot(commit.snapshot, zip),adapter).also { observeVerified(commit.id) }
 }

 fun pending(): UploadPlan? {
  if (!SafePaths.checked(marker).exists()) return null
  val id = SafePaths.boundedRead(marker, 128).decodeToString(); require(validRecordId(id))
  val bytes = SafePaths.boundedRead(pendingRoot.resolve("$id.json"), MAX_MANIFEST_BYTES)
  val commit = decode(bytes, id)
  val zip = SafePaths.boundedRead(pendingRoot.resolve("$id.zip"), MAX_SNAPSHOT_BYTES)
  require(zip.size == commit.objectBytes); Snapshots.verify(Snapshot(commit.snapshot, zip), adapter)
  return UploadPlan(commit, bytes, zip)
 }
 private fun validateRequestedParents(parents:List<String>,kind:String) {
  require(parents.size<=MAX_COMMIT_PARENTS && parents.distinct().size==parents.size && parents.all(::validRecordId)) { CoreValidationMessages.INVALID_COMMIT_PARENTS.text() }
  require(kind in setOf("snapshot","resolution","restore","legacy-import"))
  require(kind=="resolution" || parents.size<=1) { CoreValidationMessages.ORDINARY_COMMITS_CANNOT_ABSORB_BRANCHES.text() }
  require(kind!="resolution" || parents.size>=2) { CoreValidationMessages.RESOLUTION_MUST_NAME_MULTIPLE_PARENTS.text() }
  require(kind!="legacy-import" || parents.isEmpty())
 }
 private fun parentHashes(parents:List<String>,self:String):List<String> = parents.map { id ->
  if(validContentHashId(id)) id else contentSha256(decode(requireNotNull(known(id)) { CoreSessionMessages.PARENT_COMMITS_HAVE_NOT_BEEN_FULLY_VERIFIED_NOTHING_WAS_PUBLISHED.text() },id).snapshot.files)
 }.filter { it != self }.distinct().sorted()
 private fun normalizedKind(kind:String,parents:List<String>) = if(kind=="resolution" && parents.size<2) "snapshot" else kind
 fun prepare(snapshot: Snapshot, parents: List<String>, kind: String = "snapshot", operation: CommitOperationMetadata? = null): UploadPlan {
  require(pending() == null) { CoreValidationMessages.RETRY_OR_EXPLICITLY_ARCHIVE_THE_PENDING_OPERATION_FIRST.text() }
  validateRequestedParents(parents,kind)
  Snapshots.verify(snapshot, adapter)
  // Content identity is independent of capture time, ZIP encoding, and operation metadata.
  val family = adapter.packageName?.takeIf {
   recordedGamePackage(snapshot.manifest) == it ||
    (snapshot.manifest.game == adapter.id && snapshot.manifest.gameSource == null)
  }
  val frozen = snapshot.copy(manifest = snapshot.manifest.copy(
   gamePackage = snapshot.manifest.gamePackage ?: family, id = contentSha256(snapshot.manifest.files), parent = null))
  val commit = AppendCommit(schema = 3, id = frozen.manifest.id, parents = parentHashes(parents, frozen.manifest.id), snapshot = frozen.manifest, objectBytes = frozen.zip.size, kind = normalizedKind(kind, parentHashes(parents,frozen.manifest.id)), operation = operation)
  checkCommit(commit)
  val bytes = json.encodeToString(commit).encodeToByteArray(); require(bytes.size <= MAX_MANIFEST_BYTES)
  SafePaths.atomicWrite(pendingRoot.resolve("${commit.id}.zip"), frozen.zip)
  SafePaths.atomicWrite(pendingRoot.resolve("${commit.id}.json"), bytes)
  SafePaths.atomicWrite(marker, commit.id.encodeToByteArray())
  return requireNotNull(pending())
 }
 /** Freeze a fully verified request while retaining its selected-object proof, not an entire DAG. */
 internal fun prepareVerified(verified:VerifiedSnapshot,parents:List<String>,kind:String="snapshot",operation:CommitOperationMetadata?=null):VerifiedUploadPlan {
  require(pending()==null) { CoreValidationMessages.RETRY_OR_EXPLICITLY_ARCHIVE_THE_PENDING_OPERATION_FIRST.text() }
  validateRequestedParents(parents,kind)
  val snapshot=verified.snapshot
  val family=adapter.packageName?.takeIf {recordedGamePackage(snapshot.manifest)==it || snapshot.manifest.game==adapter.id&&snapshot.manifest.gameSource==null}
  val frozen=verified.withManifest(snapshot.manifest.copy(gamePackage=snapshot.manifest.gamePackage ?: family,id=contentSha256(snapshot.manifest.files),parent=null))
  val commit=AppendCommit(schema=3,id=frozen.snapshot.manifest.id,parents=parentHashes(parents,frozen.snapshot.manifest.id),snapshot=frozen.snapshot.manifest,objectBytes=frozen.snapshot.zip.size,kind=normalizedKind(kind,parentHashes(parents,frozen.snapshot.manifest.id)),operation=operation)
  checkCommit(commit)
  val bytes=json.encodeToString(commit).encodeToByteArray();require(bytes.size<=MAX_MANIFEST_BYTES)
  val plan=UploadPlan(commit,bytes,frozen.snapshot.zip)
  SafePaths.atomicWrite(pendingRoot.resolve("${commit.id}.zip"),plan.zip)
  SafePaths.atomicWrite(pendingRoot.resolve("${commit.id}.json"),bytes)
  SafePaths.atomicWrite(marker,commit.id.encodeToByteArray())
  return VerifiedUploadPlan(plan,frozen).also(::requireFrozen)
 }
 private fun requireFrozen(verified:VerifiedUploadPlan) {
  val plan=verified.plan
  require(SafePaths.boundedRead(marker,128).decodeToString()==plan.commit.id)
  require(SafePaths.boundedRead(pendingRoot.resolve("${plan.commit.id}.json"),MAX_MANIFEST_BYTES).contentEquals(plan.manifestBytes))
  require(SafePaths.boundedRead(pendingRoot.resolve("${plan.commit.id}.zip"),MAX_SNAPSHOT_BYTES).contentEquals(plan.zip))
  require(decode(plan.manifestBytes,plan.commit.id)==plan.commit && verified.verified.snapshot.manifest==plan.commit.snapshot)
 }
 /** Keep old pending bytes as recoverable evidence; switch the durable pointer only after the
  * converted archive and manifest are both safely written. Never replay UUID writes. */
 private fun hashPlan(plan:UploadPlan):UploadPlan {
  if(plan.commit.schema==3)return plan
  val id=contentSha256(plan.commit.snapshot.files)
  val parents=parentHashes(plan.commit.parents,id)
  val commit=plan.commit.copy(schema=3,id=id,parents=parents,snapshot=plan.commit.snapshot.copy(id=id),kind=normalizedKind(plan.commit.kind,parents))
  checkCommit(commit)
  val converted=UploadPlan(commit,json.encodeToString(commit).encodeToByteArray(),plan.zip)
  SafePaths.atomicWrite(pendingRoot.resolve("$id.zip"),converted.zip)
  SafePaths.atomicWrite(pendingRoot.resolve("$id.json"),converted.manifestBytes)
  SafePaths.atomicWrite(marker,id.encodeToByteArray())
  return converted
 }
 private fun readWinner(store:AppendStore,id:String,files:Map<String,String>):AppendCommit? {
  val bytes=store.readCommit(id) ?: return null
  val commit=try { decode(bytes,id) } catch(error:Exception) { throw RemoteIntegrityException(CoreSessionMessages.IMMUTABLE_OBJECT_IDENTITY_CONFLICT_EXISTING_CONTENT_WAS_NOT_OVERWRITTEN.text()) }
  if(commit.snapshot.files!=files)throw RemoteIntegrityException(CoreSessionMessages.IMMUTABLE_OBJECT_IDENTITY_CONFLICT_EXISTING_CONTENT_WAS_NOT_OVERWRITTEN.text())
  val old=known(id)
  if(old!=null && !old.contentEquals(bytes))throw RemoteIntegrityException(CoreSessionMessages.KNOWN_COMMIT_WAS_MODIFIED_SYNC_STOPPED.text(id))
  val zip=store.readObject(commit.snapshot.id) ?: throw UnsettledRemoteException(CoreSessionMessages.THE_SNAPSHOT_IS_NOT_VISIBLE_YET_TRY_AGAIN.text())
  try { require(zip.size==commit.objectBytes);Snapshots.verify(Snapshot(commit.snapshot,zip),adapter) }
  catch(error:Exception) { throw RemoteIntegrityException(CoreSessionMessages.IMMUTABLE_OBJECT_IDENTITY_CONFLICT_EXISTING_CONTENT_WAS_NOT_OVERWRITTEN.text()) }
  remember(id,bytes);observeVerified(id)
  return commit
 }
 /** Conditional creation is supplied by the transport. An existing valid content record wins,
  * even if ZIP encoding, capture metadata, or proposed parents differ. Its ancestry never changes. */
 private fun publishHashPlan(store:AppendStore,plan:UploadPlan):AppendPublication = store.withFreshReads {
  val id=plan.commit.id
  require(plan.commit.schema==3)
  readWinner(store,id,plan.commit.snapshot.files)?.let { return@withFreshReads AppendPublication(it,true) }
  var zip=store.readObject(id)
  if(zip==null) {
   try { store.writeObject(id,plan.zip) } catch(error:Exception) {
    if(error is io.github.xyz1001.pdsync.android.OfficialSyncAccess.AccessException)throw error
    zip=store.readObject(id)
    if(zip==null)throw error
   }
   if(zip==null)zip=store.readObject(id)
  }
  val actualZip=zip ?: throw UnsettledRemoteException(CoreSessionMessages.THE_UPLOADED_OBJECT_IS_NOT_VISIBLE_YET_THE_FROZEN_OPERATION.text())
  val actual=plan.commit.copy(snapshot=plan.commit.snapshot.copy(sha256=hash(actualZip)),objectBytes=actualZip.size)
  try { checkCommit(actual);Snapshots.verify(Snapshot(actual.snapshot,actualZip),adapter) }
  catch(error:Exception) { throw RemoteIntegrityException(CoreSessionMessages.IMMUTABLE_OBJECT_IDENTITY_CONFLICT_EXISTING_CONTENT_WAS_NOT_OVERWRITTEN.text()) }
  // A competitor may have completed while the object was being checked.
  readWinner(store,id,plan.commit.snapshot.files)?.let { return@withFreshReads AppendPublication(it,true) }
  val bytes=json.encodeToString(actual).encodeToByteArray()
  require(bytes.size<=MAX_MANIFEST_BYTES)
  try { store.writeCommit(id,bytes) } catch(error:Exception) {
   if(error is io.github.xyz1001.pdsync.android.OfficialSyncAccess.AccessException)throw error
   return@withFreshReads AppendPublication(readWinner(store,id,plan.commit.snapshot.files) ?: throw error,true)
  }
  // An acknowledged conditional create proves our exact bytes won. Persist that proof before readback.
  remember(id,bytes)
  AppendPublication(readWinner(store,id,plan.commit.snapshot.files)
   ?: throw UnsettledRemoteException(CoreSessionMessages.THE_COMMIT_IS_NOT_VISIBLE_YET_TRY_AGAIN.text()),false)
 }
 fun publish(store: AppendStore, plan: UploadPlan): AppendCommit = publishResult(store,plan).commit
 fun publishResult(store: AppendStore, plan: UploadPlan, expected: CommitGraph? = null): AppendPublication {
  val frozen=requireNotNull(pending())
  require(frozen.commit==plan.commit && frozen.manifestBytes.contentEquals(plan.manifestBytes) && frozen.zip.contentEquals(plan.zip))
  val graph=expected ?: discover(store);graph.requireObjectsVerified();graph.requireSettled()
  graph.findByContent(plan.commit.snapshot.files)?.let { return AppendPublication(it,true) }
  val converted=hashPlan(plan)
  require(converted.commit.parents.all {graph.resolveParentIds(it).isNotEmpty()}) { CoreSessionMessages.PARENT_COMMITS_HAVE_NOT_BEEN_FULLY_VERIFIED_NOTHING_WAS_PUBLISHED.text() }
  return publishHashPlan(store,converted)
 }
 /** Reuse the already-read graph; verify any metadata-only candidate's object before adopting it. */
 internal fun publishVerified(store:AppendStore,verified:VerifiedUploadPlan,expected:CommitMetadataGraph):AppendCommit = publishVerifiedResult(store,verified,expected).commit
 internal fun publishVerifiedResult(store:AppendStore,verified:VerifiedUploadPlan,expected:CommitMetadataGraph):AppendPublication {
  requireFrozen(verified)
  expected.requireSettled()
  val plan=verified.plan
  expected.displayGraph.findByContent(plan.commit.snapshot.files)?.let { candidate ->
   return store.withFreshReads { AppendPublication(readWinner(store,candidate.id,plan.commit.snapshot.files)
    ?: throw UnsettledRemoteException(CoreSessionMessages.THE_COMMIT_IS_NOT_VISIBLE_YET_TRY_AGAIN.text()),true) }
  }
  require(plan.commit.parents.all {expected.displayGraph.resolveParentIds(it).isNotEmpty()}) { CoreSessionMessages.PARENT_COMMITS_HAVE_NOT_BEEN_FULLY_VERIFIED_NOTHING_WAS_PUBLISHED.text() }
  return publishHashPlan(store,plan)
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
