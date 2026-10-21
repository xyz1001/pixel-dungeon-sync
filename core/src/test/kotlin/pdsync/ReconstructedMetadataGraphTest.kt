package pdsync

import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pdsync.i18n.CoreValidationMessages
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.*

/** Newly reconstructed tests for APK92's metadata discovery contract; not recovered original tests. */
class ReconstructedMetadataGraphTest {
 @TempDir lateinit var temporary: Path
 private fun id() = UUID.randomUUID().toString()
 private fun directory() = temporary.resolve(id()).createDirectories()
 private fun repository(adapter: GameAdapter = Shattered) = AppendRepository(directory(), adapter, "hero")
 private fun snapshot(): Snapshot {
  val root = directory()
  root.resolve("game1").createDirectories()
  root.resolve("game1/game.dat").writeText("""{"version":922,"init_ver":922}""")
  return Snapshots.capture(root, Shattered, "hero", "4.0.2", null)
 }
 private fun commit(snapshot: Snapshot = snapshot(), parents: List<String> = emptyList(), id: String = id()) =
  AppendCommit(id = id, parents = parents, snapshot = snapshot.manifest, objectBytes = snapshot.zip.size)
 private fun encode(commit: AppendCommit) = json.encodeToString(commit).encodeToByteArray()
 private open class Memory : AppendStore {
  val manifests = linkedMapOf<String, ByteArray>()
  val objects = linkedMapOf<String, ByteArray>()
  val objectReads = AtomicInteger()
  val commitReads = AtomicInteger()
  var pages: ((String?) -> CommitPage)? = null
  var legacy = false
  override fun legacyPresent() = legacy
  override fun listCommits(cursor: String?) = pages?.invoke(cursor) ?: CommitPage(manifests.keys.toList())
  override fun readCommit(id: String): ByteArray? { commitReads.incrementAndGet(); return manifests[id] }
  override fun readObject(id: String): ByteArray? { objectReads.incrementAndGet(); return objects[id] }
  override fun writeCommit(id: String, bytes: ByteArray) { error("No metadata write is authorized") }
  override fun writeObject(id: String, bytes: ByteArray) { error("No metadata write is authorized") }
 }
 private fun Memory.add(commit: AppendCommit, zip: ByteArray? = null) {
  manifests[commit.id] = encode(commit)
  if (zip != null) objects[commit.snapshot.id] = zip
 }

 @Test fun previewReadsNoObjectsAndCannotEstablishVerifiedObservationOrBaseline() {
  val source = snapshot(); val commit = commit(source); val remote = Memory().apply { add(commit, source.zip) }
  val repo = repository()
  val preview = repo.discoverMetadata(remote)
  assertEquals(setOf(commit.id), preview.tips)
  assertEquals(RemoteVerification.METADATA_ONLY, preview.displayGraph.verification)
  assertEquals(0, remote.objectReads.get())
  assertNull(repo.observedAt(commit.id))
  assertTrue(repo.cachedGraph().commits.isEmpty())
  assertNull(repo.baseline())
  assertNull(repo.baselineFiles())
  assertFailsWith<IllegalArgumentException> { repo.setBaseline(commit.id, preview.displayGraph) }
  assertFailsWith<IllegalArgumentException> { preview.displayGraph.requireObjectsVerified() }
  val full = repo.discover(remote)
  assertEquals(RemoteVerification.OBJECTS_VERIFIED, full.verification)
  assertEquals(1, remote.objectReads.get())
  assertNotNull(repo.observedAt(commit.id))
  assertEquals(setOf(commit.id), repo.cachedGraph().tips)
  repo.setBaseline(commit.id, full)
  assertEquals(commit.id, repo.baseline())
 }

 @Test fun previewDoesNotTurnMissingOrCorruptObjectsIntoFullDiscoverySuccess() {
  for (bytes in listOf<ByteArray?>(null, byteArrayOf(1, 2, 3))) {
   val commit = commit(); val remote = Memory().apply { add(commit, bytes) }; val repo = repository()
   assertEquals(setOf(commit.id), repo.discoverMetadata(remote).tips)
   val full = repo.discover(remote)
   assertTrue(full.commits.isEmpty())
   assertEquals(if (bytes == null) "missing object" else "invalid object", full.pending[commit.id])
   assertNull(repo.observedAt(commit.id))
   assertTrue(repo.cachedGraph().commits.isEmpty())
  }
 }

 @Test fun metadataWrapperCannotBeCreatedFromObjectVerifiedGraph() {
  assertFailsWith<IllegalArgumentException> { CommitMetadataGraph(CommitGraph(emptyMap(), emptyMap())) }
 }

 @Test fun publishCannotAcceptFrozenCachedBytesWhenFreshObjectHasCollided() {
  val repo = repository(); val plan = repo.prepare(snapshot(), emptyList())
  var fresh = false; var writes = 0; var scopes = 0
  val remote = object : Memory() {
   override fun <T> withFreshReads(block: () -> T): T {
    scopes++; fresh = true
    return try { block() } finally { fresh = false }
   }
   override fun legacyPresent(): Boolean { assertFalse(fresh); return false }
   override fun listCommits(cursor: String?): CommitPage { assertFalse(fresh); return CommitPage(emptyList()) }
   override fun readObject(id: String) = if (fresh) "colliding-server-object".encodeToByteArray() else plan.zip
   override fun writeCommit(id: String, bytes: ByteArray) { writes++ }
   override fun writeObject(id: String, bytes: ByteArray) { writes++ }
  }
  assertFailsWith<RemoteIntegrityException> { repo.publish(remote, plan) }
  assertEquals(1, scopes)
  assertEquals(0, writes)
  assertFalse(fresh)
  assertNull(repo.observedAt(plan.commit.id))
  assertNull(repo.baseline())
  assertNotNull(repo.pending())
 }

 @Test fun publishWritesAndExactReadbacksShareOneFreshBoundary() {
  val repo = repository(); val plan = repo.prepare(snapshot(), emptyList())
  var fresh = false; var scopes = 0
  val operations = mutableListOf<String>()
  val remote = object : Memory() {
   override fun <T> withFreshReads(block: () -> T): T {
    scopes++; fresh = true
    return try { block() } finally { fresh = false }
   }
   override fun legacyPresent(): Boolean { assertFalse(fresh); return false }
   override fun listCommits(cursor: String?): CommitPage { assertFalse(fresh); return CommitPage(emptyList()) }
   override fun readObject(id: String): ByteArray? { assertTrue(fresh); operations += "read object"; return objects[id] }
   override fun readCommit(id: String): ByteArray? { assertTrue(fresh); operations += "read commit"; return manifests[id] }
   override fun writeObject(id: String, bytes: ByteArray) { assertTrue(fresh); operations += "write object"; objects[id] = bytes }
   override fun writeCommit(id: String, bytes: ByteArray) { assertTrue(fresh); operations += "write commit"; manifests[id] = bytes }
  }
  assertEquals(plan.commit, repo.publish(remote, plan))
  assertEquals(1, scopes)
  assertFalse(fresh)
  assertEquals(listOf("read commit", "read object", "write object", "read object", "read commit", "write commit", "read commit", "read object"), operations)
  assertNotNull(repo.observedAt(plan.commit.id))
  assertNull(repo.baseline())
 }

 @Test fun metadataRememberingStillDetectsLaterManifestMutationAndMissingListings() {
  val original = commit(); val remote = Memory().apply { add(original) }; val repo = repository()
  repo.discoverMetadata(remote)
  remote.pages = { CommitPage(emptyList()) }
  assertEquals(setOf(original.id), repo.discoverMetadata(remote).tips)
  remote.manifests[original.id] = encode(original.copy(objectBytes = original.objectBytes + 1))
  assertFailsWith<RemoteIntegrityException> { repo.discoverMetadata(remote) }
  remote.manifests.clear()
  assertEquals("missing manifest", repo.discoverMetadata(remote).pending[original.id])
 }

 @Test fun metadataStateAndProofStayWithinRepositoryOwner() {
  val source = snapshot(); val commit = commit(source); val remote = Memory().apply { add(commit, source.zip) }
  val first = repository(); val second = repository()
  first.discover(remote)
  second.discoverMetadata(remote)
  assertNotNull(first.observedAt(commit.id))
  assertNull(second.observedAt(commit.id))
  assertTrue(second.cachedGraph().commits.isEmpty())
  assertNull(second.baseline())
 }

 @Test fun parentsOmittedByListingAreFetchedOnceAndClosedBeforeChildren() {
  val source = snapshot(); val parent = commit(source); val child = commit(source, listOf(parent.id))
  val remote = Memory().apply {
   add(parent); add(child)
   pages = { CommitPage(listOf(child.id, child.id)) }
  }
  val graph = repository().discoverMetadata(remote)
  assertEquals(listOf(parent.id, child.id), graph.commits.keys.toList())
  assertEquals(setOf(child.id), graph.tips)
  assertEquals(2, remote.commitReads.get())
  assertEquals(0, remote.objectReads.get())
 }

 @Test fun missingAncestorsAndCyclesRemainPending() {
  val source = snapshot(); val missing = id(); val child = commit(source, listOf(missing))
  val firstId = id(); val secondId = id()
  val first = commit(source, listOf(secondId), firstId); val second = commit(source, listOf(firstId), secondId)
  val remote = Memory().apply { add(child); add(first); add(second) }
  val graph = repository().discoverMetadata(remote)
  assertTrue(graph.commits.isEmpty())
  assertEquals("missing manifest", graph.pending[missing])
  listOf(child.id, first.id, second.id).forEach { assertEquals("incomplete ancestry or cycle", graph.pending[it]) }
  assertFailsWith<UnsettledRemoteException> { graph.requireSettled() }
 }

 @Test fun pathsAreValidatedWithoutReadingAnyObject() {
  val base = commit()
  val invalidFiles = listOf(
   mapOf("../game.dat" to "a".repeat(64)),
   mapOf("/game1/game.dat" to "a".repeat(64)),
   mapOf("game1\\game.dat" to "a".repeat(64)),
   mapOf("game1/a:b.dat" to "a".repeat(64)),
   mapOf("preferences.xml" to "a".repeat(64)),
   mapOf("game1/depth1.dat" to "a".repeat(64))
  )
  invalidFiles.forEach { files ->
   val bad = base.copy(id = id(), snapshot = base.snapshot.copy(files = files, saveVersions = null, metadata = null, directoryMetadata = null))
   val remote = Memory().apply { add(bad) }
   assertEquals("invalid manifest", repository().discoverMetadata(remote).pending[bad.id], files.toString())
   assertEquals(0, remote.objectReads.get())
  }
  val permissive = object : GameAdapter by Shattered { override fun includes(name: String) = true }
  val aliased = base.copy(snapshot = base.snapshot.copy(files = linkedMapOf("badges.dat" to "a".repeat(64), "BADGES.DAT" to "a".repeat(64)), saveVersions = null, metadata = null, directoryMetadata = null))
  assertEquals("invalid manifest", repository(permissive).discoverMetadata(Memory().apply { add(aliased) }).pending[aliased.id])
 }

 @Test fun invalidIdentityPackageHashesAndSaveVersionMapsAreRejectedWithoutZip() {
  val base = commit()
  val invalid = listOf(
   base.copy(schema = 99),
   base.copy(parents = listOf(base.id)),
   base.copy(snapshot = base.snapshot.copy(gamePackage = "org.example.anothergame")),
   base.copy(snapshot = base.snapshot.copy(profile = "another-profile")),
   base.copy(snapshot = base.snapshot.copy(files = mapOf("game1/game.dat" to "bad"))),
   base.copy(snapshot = base.snapshot.copy(saveVersions = SaveVersions(mapOf("game2" to 922)))),
   base.copy(snapshot = base.snapshot.copy(saveVersions = SaveVersions(mapOf("game1" to 0)))),
   base.copy(snapshot = base.snapshot.copy(saveVersions = SaveVersions(mapOf("game1" to 922), listOf("game1")))),
   base.copy(snapshot = base.snapshot.copy(saveVersions = SaveVersions(mapOf("game1" to 922), unknownReason = "unknown"))),
   base.copy(snapshot = base.snapshot.copy(saveVersions = SaveVersions()))
  )
  invalid.forEach { bad ->
   val remote = Memory().apply { add(bad) }
   assertEquals("invalid manifest", repository().discoverMetadata(remote).pending[bad.id])
   assertEquals(0, remote.objectReads.get())
  }
  val differentFilename = id()
  val remote = Memory().apply { manifests[differentFilename] = encode(base) }
  assertEquals("invalid manifest", repository().discoverMetadata(remote).pending[differentFilename])
 }

 @Test fun fourWayReadBatchIsBoundedAndDecodeOrderIsDeterministic() {
  val source = snapshot(); val commits = List(8) { commit(source) }
  val active = AtomicInteger(); val maximum = AtomicInteger(); val firstBatch = CountDownLatch(4)
  val completed = ConcurrentLinkedQueue<String>()
  val remote = object : Memory() {
   override fun readCommit(id: String): ByteArray? {
    val count = active.incrementAndGet(); maximum.accumulateAndGet(count, ::maxOf)
    try {
     firstBatch.countDown()
     check(firstBatch.await(2, TimeUnit.SECONDS))
     if (id == commits.first().id) Thread.sleep(50)
     completed += id
     return super.readCommit(id)
    } finally { active.decrementAndGet() }
   }
  }.apply { commits.forEach { add(it) } }
  val result = repository().discoverMetadata(remote)
  assertEquals(4, maximum.get())
  assertEquals(commits.map { it.id }, result.commits.keys.toList())
  assertNotEquals(commits.first().id, completed.first())
  assertEquals(0, active.get())
 }

 @Test fun rejectedParallelismDoesNotReadRemote() {
  val remote = object : Memory() { override fun legacyPresent(): Boolean = error("Must reject before remote access") }
  listOf(0, 5, Int.MAX_VALUE).forEach { count ->
   assertFailsWith<IllegalArgumentException> { repository().discoverMetadata(remote, count) }
  }
 }

 @Test fun completed401CancelsBlockedEarlierReadAndEscapesUnchanged() {
  val first = id(); val second = id(); val started = CountDownLatch(1)
  val cancelled = CountDownLatch(1); val finished = CountDownLatch(1)
  val failure = WebDavException(401)
  val remote = object : Memory() {
   override fun listCommits(cursor: String?) = CommitPage(listOf(first, second))
   override fun readCommit(id: String, cancellation: MetadataReadCancellation): ByteArray? {
    if (id == second) { check(started.await(2, TimeUnit.SECONDS)); throw failure }
    val unblock = CountDownLatch(1)
    val registration = cancellation.register { cancelled.countDown(); unblock.countDown() }
    try {
     started.countDown()
     check(unblock.await(5, TimeUnit.SECONDS))
     cancellation.checkActive()
     return null
    } finally { registration.close(); finished.countDown() }
   }
  }
  val startedAt = System.nanoTime()
  assertSame(failure, assertFailsWith<WebDavException> { repository().discoverMetadata(remote) })
  assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 2500)
  assertTrue(cancelled.await(1, TimeUnit.SECONDS))
  assertTrue(finished.await(1, TimeUnit.SECONDS))
 }

 @Test fun interruptStopsDiscoveryAndCancelsOwnedCalls() {
  val started = CountDownLatch(1); val cancelled = CountDownLatch(1); val workerFinished = CountDownLatch(1)
  val failure = AtomicReference<Throwable?>()
  val remote = object : Memory() {
   override fun listCommits(cursor: String?) = CommitPage(listOf(id()))
   override fun readCommit(id: String, cancellation: MetadataReadCancellation): ByteArray? {
    val unblock = CountDownLatch(1)
    val registration = cancellation.register { cancelled.countDown(); unblock.countDown() }
    try {
     started.countDown(); unblock.await()
     cancellation.checkActive()
     return null
    } finally { registration.close(); workerFinished.countDown() }
   }
  }
  val repo = repository()
  val caller = Thread {
   try { repo.discoverMetadata(remote) } catch (error: Throwable) { failure.set(error) }
  }
  caller.start(); assertTrue(started.await(2, TimeUnit.SECONDS))
  caller.interrupt(); caller.join(2500)
  assertFalse(caller.isAlive)
  assertIs<InterruptedException>(failure.get())
  assertTrue(cancelled.await(1, TimeUnit.SECONDS))
  assertTrue(workerFinished.await(1, TimeUnit.SECONDS))
 }

 @Test fun workerCallsRetainCapturedTraceAndParentBinding() {
  val trace = DiagnosticLog.begin(DiagnosticAction.INSPECT, false)
  val source = snapshot(); val commits = List(4) { commit(source) }
  val traces = ConcurrentLinkedQueue<DiagnosticTrace?>()
  val remote = object : Memory() {
   override fun readCommit(id: String): ByteArray? {
    traces.add(DiagnosticLog.currentTrace())
    return super.readCommit(id)
   }
  }.apply { commits.forEach { add(it) } }
  DiagnosticLog.bind(trace).use {
   repository().discoverMetadata(remote)
   assertSame(trace, DiagnosticLog.currentTrace())
  }
  assertEquals(4, traces.size)
  assertTrue(traces.all { it === trace })
  assertNull(DiagnosticLog.currentTrace())
 }

 @Test fun cancellationRegistrationIsIdempotentAndClosedCallsAreNotCancelled() {
  val token = MetadataReadCancellation(); val count = AtomicInteger()
  val closed = token.register { error("Already closed request must not be cancelled") }
  closed.close(); closed.close()
  token.register { count.incrementAndGet() }
  token.register { throw IllegalStateException("A transport cancellation failure must not stop the rest") }
  token.register { count.incrementAndGet() }
  token.cancel(); token.cancel()
  assertEquals(2, count.get())
  assertFailsWith<CancellationException> { token.checkActive() }
  token.register { count.incrementAndGet() }.close()
  assertEquals(3, count.get())
 }

 @Test fun metadataPreservesLegacyAndListingBudgetGuards() {
  assertFailsWith<LegacyProtocolException> { repository().discoverMetadata(Memory().apply { legacy = true }) }
  val invalidName = Memory().apply { pages = { CommitPage(listOf("../bad.json")) } }
  assertFailsWith<IllegalArgumentException> { repository().discoverMetadata(invalidName) }
  val repeated = Memory().apply { pages = { CommitPage(emptyList(), "again") } }
  assertFailsWith<IllegalArgumentException> { repository().discoverMetadata(repeated) }
  val tooMany = Memory().apply { pages = { CommitPage(List(MAX_COMMITS + 1) { id() }) } }
  assertFailsWith<IllegalArgumentException> { repository().discoverMetadata(tooMany) }
 }

 @Test fun totalManifestBytesHaveABoundedWholeDiscoveryBudget() {
  val ids = List((MAX_REMOTE_SCAN_BYTES / MAX_MANIFEST_BYTES).toInt() + 1) { id() }
  val bytes = ByteArray(MAX_MANIFEST_BYTES) { 'x'.code.toByte() }
  val remote = object : Memory() {
   override fun listCommits(cursor: String?) = CommitPage(ids)
   override fun readCommit(id: String) = bytes
  }
  val failure = assertFailsWith<IllegalArgumentException> { repository().discoverMetadata(remote) }
  assertEquals(CoreValidationMessages.REMOTE_SCAN_BYTE_BUDGET_EXCEEDED_NO_PARTIAL_DISCOVERY_ACCEPTED.text(), failure.message)
  assertEquals(0, remote.objectReads.get())
 }
}
