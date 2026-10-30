package pdsync

import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*

/** Every remote is an in-memory fault-injecting store; no credentials or real server are used. */
class AppendRepositoryTest {
 @TempDir lateinit var temporary: Path
 private val profile = "hero"
 private fun id() = UUID.randomUUID().toString()
 private fun directory(label: String) = temporary.resolve("$label-${id()}").createDirectories()
 private fun repository(path: Path = directory("repository")) = AppendRepository(path, Shattered, profile)
 private fun snapshot(value: String = "original"): Snapshot {
  val save = directory("save")
  save.resolve("game1").createDirectories()
  save.resolve("game1/game.dat").writeText("""{"version":922,"init_ver":922,"marker":"$value"}""")
  save.resolve("game1/depth1.dat").writeText("floor-$value")
  save.resolve("rankings.dat").writeText("rankings-$value")
  save.resolve("badges.dat").writeText("badges-$value")
  save.resolve("journal.dat").writeText("journal-$value")
  return Snapshots.capture(save, Shattered, profile, "4.0.2", null)
 }
 private fun commit(snapshot: Snapshot = snapshot(), parents: List<String> = emptyList(), kind: String = "snapshot", id: String = id()) =
  AppendCommit(id = id, parents = parents, snapshot = snapshot.manifest, objectBytes = snapshot.zip.size, kind = kind)
 private fun encoded(commit: AppendCommit) = json.encodeToString(commit).encodeToByteArray()
 private data class Event(val operation: String, val id: String? = null, val bytes: ByteArray? = null)
 private enum class Fault {
  OBJECT_WRITE_BEFORE, OBJECT_WRITE_ACCEPTED_THEN_DISCONNECTED,
  OBJECT_READBACK_MISSING, OBJECT_READBACK_ERROR,
  COMMIT_WRITE_BEFORE, COMMIT_WRITE_ACCEPTED_THEN_DISCONNECTED,
  COMMIT_READBACK_MISSING, COMMIT_READBACK_ERROR
 }
 private class Memory : AppendStore {
  val commits = linkedMapOf<String, ByteArray>()
  val objects = linkedMapOf<String, ByteArray>()
  val events = mutableListOf<Event>()
  var legacy = false
  var pages: ((String?) -> CommitPage)? = null
  var fault: Fault? = null
  var throwAfterObjectAcceptance = false
  var throwAfterCommitAcceptance = false
  var objectOverride: ((String) -> ByteArray?)? = null
  var commitOverride: ((String) -> ByteArray?)? = null
  private var nextReadFailure: Pair<String, Boolean>? = null
  override fun legacyPresent(): Boolean { events += Event("legacy"); return legacy }
  override fun listCommits(cursor: String?): CommitPage {
   events += Event("list", cursor)
   return pages?.invoke(cursor) ?: CommitPage(commits.keys.toList())
  }
  private fun readFailure(operation: String): Boolean {
   val failure = nextReadFailure ?: return false
   if (failure.first != operation) return false
   nextReadFailure = null
   if (failure.second) throw IOException("Injected read disconnection")
   return true
  }
  override fun readCommit(id: String): ByteArray? {
   val value = if (readFailure("readCommit")) null else if (commitOverride != null) commitOverride!!.invoke(id) else commits[id]
   events += Event("readCommit", id, value?.copyOf())
   return value?.copyOf()
  }
  override fun readObject(id: String): ByteArray? {
   val value = if (readFailure("readObject")) null else if (objectOverride != null) objectOverride!!.invoke(id) else objects[id]
   events += Event("readObject", id, value?.copyOf())
   return value?.copyOf()
  }
  override fun writeObject(id: String, bytes: ByteArray) {
   events += Event("writeObject", id, bytes.copyOf())
   val selected = fault?.takeIf { it.name.startsWith("OBJECT_") }
   if (selected != null) fault = null
   if (selected == Fault.OBJECT_WRITE_BEFORE) throw IOException("Injected object write failure")
   objects[id] = bytes.copyOf()
   when (selected) {
    Fault.OBJECT_WRITE_ACCEPTED_THEN_DISCONNECTED -> { nextReadFailure = "readObject" to true; throw IOException("Object accepted before disconnect") }
    Fault.OBJECT_READBACK_MISSING -> nextReadFailure = "readObject" to false
    Fault.OBJECT_READBACK_ERROR -> nextReadFailure = "readObject" to true
    else -> Unit
   }
   if (throwAfterObjectAcceptance) { throwAfterObjectAcceptance = false; throw IOException("Object accepted, response lost") }
  }
  override fun writeCommit(id: String, bytes: ByteArray) {
   events += Event("writeCommit", id, bytes.copyOf())
   val selected = fault?.takeIf { it.name.startsWith("COMMIT_") }
   if (selected != null) fault = null
   if (selected == Fault.COMMIT_WRITE_BEFORE) throw IOException("Injected commit write failure")
   commits[id] = bytes.copyOf()
   when (selected) {
    Fault.COMMIT_WRITE_ACCEPTED_THEN_DISCONNECTED -> { nextReadFailure = "readCommit" to true; throw IOException("Commit accepted before disconnect") }
    Fault.COMMIT_READBACK_MISSING -> nextReadFailure = "readCommit" to false
    Fault.COMMIT_READBACK_ERROR -> nextReadFailure = "readCommit" to true
    else -> Unit
   }
   if (throwAfterCommitAcceptance) { throwAfterCommitAcceptance = false; throw IOException("Commit accepted, response lost") }
  }
  fun add(commit: AppendCommit, zip: ByteArray) { commits[commit.id] = encodedStatic(commit); objects[commit.snapshot.id] = zip.copyOf() }
  fun writes() = events.filter { it.operation.startsWith("write") }
  companion object { private fun encodedStatic(commit: AppendCommit) = json.encodeToString(commit).encodeToByteArray() }
 }
 private fun Memory.addSnapshot(value: String = "original", parents: List<String> = emptyList(), kind: String = "snapshot"): AppendCommit {
  val snapshot = snapshot(value)
  return commit(snapshot, parents, kind).also { add(it, snapshot.zip) }
 }
 private fun assertPlanEquals(expected: UploadPlan, actual: UploadPlan?) {
  assertNotNull(actual)
  assertEquals(expected.commit, actual.commit)
  assertContentEquals(expected.manifestBytes, actual.manifestBytes)
  assertContentEquals(expected.zip, actual.zip)
 }
 private fun assertPending(graph: CommitGraph, vararg ids: String) {
  assertTrue(graph.pending.keys.containsAll(ids.toList()), "Expected pending ${ids.toList()}, got ${graph.pending}")
  ids.forEach { assertFalse(it in graph.commits, "Unverified commit must not become complete: $it") }
  assertFailsWith<UnsettledRemoteException> { graph.requireSettled() }
 }

 @Test fun preparingFreezesContentIdentityBytesAndSortedHashParents() {
  val source = snapshot(); val sourceBytes = source.zip.copyOf(); val parents = listOf(hash("parent-a".encodeToByteArray()), hash("parent-b".encodeToByteArray())).sortedDescending()
  val repo = repository(); val plan = repo.prepare(source, parents, "resolution")
  assertTrue(validContentHashId(plan.commit.id)); assertEquals(plan.commit.id,plan.commit.snapshot.id)
  assertEquals(contentSha256(source.manifest.files),plan.commit.id)
  assertEquals(parents.sorted(), plan.commit.parents); assertEquals("resolution", plan.commit.kind)
  assertNull(plan.commit.snapshot.parent); assertEquals(source.manifest.files, plan.commit.snapshot.files)
  assertContentEquals(sourceBytes, plan.zip)
  source.zip.fill(0)
  assertContentEquals(sourceBytes, repo.pending()!!.zip)
  assertContentEquals(encoded(plan.commit), plan.manifestBytes)
 }

 @Test fun separateDevicesPreparingIdenticalBytesUseTheSameContentIdentity() {
  val source = snapshot("identical")
  val first = repository().prepare(source, emptyList())
  val second = repository().prepare(source, emptyList())
  val identifiers = setOf(source.manifest.id, first.commit.id, first.commit.snapshot.id, second.commit.id, second.commit.snapshot.id)
  assertEquals(2, identifiers.size); assertEquals(first.commit.id,second.commit.id); assertTrue(validContentHashId(first.commit.id)); assertTrue(validCommitId(source.manifest.id))
  assertContentEquals(first.zip, second.zip); assertEquals(first.commit.snapshot.sha256, second.commit.snapshot.sha256)
 }

 @Test fun allInterruptedUploadAndReadbackStagesReplayExactPersistedPlanAfterRestart() {
  Fault.entries.forEach { failure ->
   val path = directory("state-${failure.name}"); val repo = repository(path); val remote = Memory()
   val parent = remote.addSnapshot("parent"); val original = snapshot("frozen")
   repo.discover(remote)
   val plan = repo.prepare(original, listOf(parent.id)); remote.fault = failure
   assertFails("Expected injected $failure") { repo.publish(remote, plan) }
   assertPlanEquals(plan, repo.pending()); assertNull(repo.baseline())
   if (failure.name.startsWith("OBJECT_")) assertTrue(remote.events.none { it.operation == "writeCommit" })
   // Mutating the original capture cannot silently recapture or change retry ancestry.
   original.zip.fill(1)
   val restarted = repository(path); val retry = restarted.pending()!!
   assertPlanEquals(plan, retry)
   assertEquals(plan.commit, restarted.publish(remote, retry), failure.name)
   assertContentEquals(plan.zip, remote.objects[plan.commit.snapshot.id])
   assertContentEquals(plan.manifestBytes, remote.commits[plan.commit.id])
   remote.writes().filter { it.operation == "writeObject" && it.id == plan.commit.snapshot.id }.forEach { assertContentEquals(plan.zip, it.bytes) }
   remote.writes().filter { it.operation == "writeCommit" && it.id == plan.commit.id }.forEach { assertContentEquals(plan.manifestBytes, it.bytes) }
   val objectAttempts = remote.writes().count { it.operation == "writeObject" }
   val commitAttempts = remote.writes().count { it.operation == "writeCommit" }
   assertEquals(if (failure == Fault.OBJECT_WRITE_BEFORE) 2 else 1, objectAttempts, failure.name)
   assertEquals(if (failure == Fault.COMMIT_WRITE_BEFORE) 2 else 1, commitAttempts, failure.name)
   assertPlanEquals(plan, restarted.pending()) // Publish alone does not clear recovery state.
   restarted.setBaseline(plan.commit.id, restarted.discover(remote)); restarted.clearPending()
   assertNull(repository(path).pending()); assertEquals(plan.commit.id, repository(path).baseline())
  }
 }

 @Test fun acceptedWriteWithLostResponseCanCompleteOnlyAfterExactReadback() {
  listOf("object", "commit").forEach { stage ->
   val remote = Memory(); val repo = repository(); val plan = repo.prepare(snapshot(), emptyList())
   remote.throwAfterObjectAcceptance = stage == "object"; remote.throwAfterCommitAcceptance = stage == "commit"
   assertEquals(plan.commit, repo.publish(remote, plan))
   assertEquals(listOf("writeObject", "writeCommit"), remote.writes().map { it.operation })
   assertContentEquals(plan.zip, remote.objects.getValue(plan.commit.snapshot.id))
   assertContentEquals(plan.manifestBytes, remote.commits.getValue(plan.commit.id))
  }
 }

 @Test fun retryNeverRewritesAlreadyAcceptedObjectOrCommit() {
  val remote = Memory(); val repo = repository(); val plan = repo.prepare(snapshot(), emptyList())
  repo.publish(remote, plan); remote.events.clear()
  repo.publish(remote, repo.pending()!!)
  assertTrue(remote.writes().isEmpty()); assertPlanEquals(plan, repo.pending())
 }

 @Test fun objectBytesAreReadBackAndVerifiedBeforeAnyCommitWrite() {
  val remote = Memory(); val repo = repository(); val plan = repo.prepare(snapshot(), emptyList())
  repo.publish(remote, plan)
  val objectWrite = remote.events.indexOfFirst { it.operation == "writeObject" }
  val commitWrite = remote.events.indexOfFirst { it.operation == "writeCommit" }
  val verifiedReads = remote.events.withIndex().filter { (_, event) ->
   event.operation == "readObject" && event.id == plan.commit.snapshot.id && event.bytes?.contentEquals(plan.zip) == true
  }
  assertTrue(objectWrite >= 0 && commitWrite > objectWrite)
  assertTrue(verifiedReads.any { it.index in (objectWrite + 1) until commitWrite })
  assertTrue(remote.events.take(objectWrite).any { it.operation == "readObject" && it.bytes == null })
  assertTrue(remote.events.take(commitWrite).any { it.operation == "readCommit" && it.id == plan.commit.id && it.bytes == null })
 }

 @Test fun collidingObjectIsNeverOverwrittenEvenAfterRestart() {
  val path = directory("collision"); val repo = repository(path); val remote = Memory()
  val plan = repo.prepare(snapshot(), emptyList()); val alien = "unrelated immutable object".encodeToByteArray()
  remote.objects[plan.commit.snapshot.id] = alien.copyOf()
  repeat(2) {
   val restarted = repository(path)
   assertFailsWith<RemoteIntegrityException> { restarted.publish(remote, restarted.pending()!!) }
   assertContentEquals(alien, remote.objects.getValue(plan.commit.snapshot.id))
   assertFalse(plan.commit.id in remote.commits); assertTrue(remote.writes().isEmpty()); assertPlanEquals(plan, restarted.pending())
  }
 }

 @Test fun commitCollisionHiddenByStaleListingIsNeverOverwritten() {
  val remote = Memory(); val repo = repository(); val plan = repo.prepare(snapshot(), emptyList())
  val alien = "{partial unrelated commit}".encodeToByteArray()
  remote.commits[plan.commit.id] = alien; remote.objects[plan.commit.snapshot.id] = plan.zip.copyOf()
  remote.pages = { CommitPage(emptyList()) }
  assertFailsWith<RemoteIntegrityException> { repo.publish(remote, plan) }
  assertContentEquals(alien, remote.commits[plan.commit.id]); assertTrue(remote.writes().isEmpty()); assertPlanEquals(plan, repo.pending())
 }

 @Test fun listedPartialPendingCommitStopsExplicitlyAndPreservesExactOperationAcrossRestart() {
  val path = directory("partial"); val repo = repository(path); val remote = Memory(); val plan = repo.prepare(snapshot(), emptyList())
  val partial = plan.manifestBytes.copyOf(plan.manifestBytes.size / 2)
  remote.commits[plan.commit.id] = partial; remote.objects[plan.commit.snapshot.id] = plan.zip.copyOf()
  repeat(2) {
   val restarted = repository(path)
   val graph = restarted.discover(remote); assertPending(graph, plan.commit.id)
   assertFailsWith<UnsettledRemoteException> { restarted.publish(remote, restarted.pending()!!) }
   assertContentEquals(partial, remote.commits[plan.commit.id]); assertTrue(remote.writes().isEmpty())
   assertPlanEquals(plan, restarted.pending()); assertNull(restarted.baseline())
  }
 }

 @Test fun objectCorruptionAfterSuccessfulWriteNeverPublishesCommit() {
  val remote = Memory(); val repo = repository(); val plan = repo.prepare(snapshot(), emptyList())
  remote.objectOverride = { key -> remote.objects[key]?.copyOf()?.also { it[0] = (it[0].toInt() xor 1).toByte() } }
  assertFailsWith<RemoteIntegrityException> { repo.publish(remote, plan) }
  assertFalse(plan.commit.id in remote.commits); assertTrue(remote.events.none { it.operation == "writeCommit" })
  assertPlanEquals(plan, repo.pending())
 }

 @Test fun commitCorruptionAfterSuccessfulWriteRetainsPendingAndNeverRetriesOverwrite() {
  val remote = Memory(); val repo = repository(); val plan = repo.prepare(snapshot(), emptyList())
  remote.commitOverride = { key -> remote.commits[key]?.plus(' '.code.toByte()) }
  assertFailsWith<RemoteIntegrityException> { repo.publish(remote, plan) }
  assertPlanEquals(plan, repo.pending()); assertNull(repo.baseline())
  val writes = remote.writes().size
  assertFails { repo.publish(remote, plan) }
  assertEquals(writes, remote.writes().size)
 }

 @Test fun modifiedCallerPlanCannotChangePersistedPublication() {
  val repo = repository(); val remote = Memory(); val plan = repo.prepare(snapshot(), emptyList())
  val forged = listOf(plan.copy(commit = plan.commit.copy(parents = listOf(id()))),
   plan.copy(manifestBytes = plan.manifestBytes + ' '.code.toByte()), plan.copy(zip = plan.zip + 0.toByte()))
  forged.forEach { assertFailsWith<IllegalArgumentException> { repo.publish(remote, it) } }
  assertTrue(remote.events.isEmpty()); assertPlanEquals(plan, repo.pending())
 }

 @Test fun pendingOperationMustBeExplicitlyClearedBeforeNewCaptureAndClearPreservesFiles() {
  val path = directory("pending"); val repo = repository(path); val first = repo.prepare(snapshot("first"), emptyList())
  assertFailsWith<IllegalArgumentException> { repo.prepare(snapshot("second"), emptyList()) }
  assertPlanEquals(first, repo.pending()); repo.clearPending()
  assertTrue(path.resolve("uploads/${first.commit.id}.zip").exists())
  assertTrue(path.resolve("uploads/${first.commit.id}.json").exists())
  val second = repo.prepare(snapshot("second"), emptyList())
  assertNotEquals(first.commit.id, second.commit.id); assertNotEquals(first.commit.snapshot.id, second.commit.snapshot.id)
 }

 @Test fun damagedLocalPendingBytesFailClosedWithoutRemoteActivity() {
  listOf("zip", "json", "marker").forEach { target ->
   val path = directory("damaged-$target"); val repo = repository(path); val remote = Memory(); val plan = repo.prepare(snapshot(), emptyList())
   val file = when (target) {
    "marker" -> path.resolve("active-plan.txt")
    else -> path.resolve("uploads/${plan.commit.id}.$target")
   }
   file.writeText("corrupt")
   assertFails { repository(path).pending() }; assertFails { repo.publish(remote, plan) }
   assertTrue(remote.events.isEmpty())
  }
 }

 @Test fun completePaginationIsCollectedBeforeAnyCommitReadAndDuplicateIdsAreDeduplicated() {
  val remote = Memory(); val root = remote.addSnapshot("root")
  val left = remote.addSnapshot("left", listOf(root.id)); val right = remote.addSnapshot("right", listOf(root.id))
  remote.pages = { cursor -> when (cursor) {
   null -> CommitPage(listOf(left.id), "second")
   "second" -> CommitPage(emptyList(), "third")
   "third" -> CommitPage(listOf(right.id, left.id, root.id))
   else -> error("Unexpected continuation")
  } }
  val graph = repository().discover(remote); graph.requireSettled()
  assertEquals(setOf(root.id, left.id, right.id), graph.commits.keys); assertEquals(setOf(left.id, right.id), graph.tips)
  assertEquals(listOf(null, "second", "third"), remote.events.filter { it.operation == "list" }.map { it.id })
  assertTrue(remote.events.indexOfLast { it.operation == "list" } < remote.events.indexOfFirst { it.operation == "readCommit" })
  assertEquals(3, remote.events.count { it.operation == "readCommit" })
 }

 @Test fun repeatedContinuationNeverReturnsAPartialGraphOrPublishes() {
  val remote = Memory(); val root = remote.addSnapshot(); val repo = repository(); val plan = repo.prepare(snapshot(), emptyList())
  remote.pages = { CommitPage(listOf(root.id), "same-page") }
  assertFailsWith<IllegalArgumentException> { repo.discover(remote) }
  assertEquals(2, remote.events.count { it.operation == "list" }); assertTrue(remote.events.none { it.operation == "readCommit" })
  assertFailsWith<IllegalArgumentException> { repo.publish(remote, plan) }; assertTrue(remote.writes().isEmpty())
 }

 @Test fun laterListingFailureNeverLooksLikeEmptyOrPartialHistory() {
  val remote = Memory(); val root = remote.addSnapshot(); val repo = repository()
  remote.pages = { cursor -> if (cursor == null) CommitPage(listOf(root.id), "next") else throw IOException("Second page unavailable") }
  assertFailsWith<IOException> { repo.discover(remote) }
  assertTrue(remote.events.none { it.operation == "readCommit" }); assertTrue(remote.writes().isEmpty())
 }

 @Test fun remoteReadFailuresPropagateAndCannotBeMistakenForMissingOrEmptyHistory() {
  listOf("commit", "object").forEach { read ->
   val remote = Memory(); val root = remote.addSnapshot(); val repo = repository()
   repo.discover(remote)
   val plan = repo.prepare(snapshot("local"), listOf(root.id))
   if (read == "commit") remote.commitOverride = { throw IOException("Manifest connection failed") }
   else remote.objectOverride = { throw IOException("Object connection failed") }
   assertFailsWith<IOException> { repo.publish(remote, plan) }
   assertTrue(remote.writes().isEmpty()); assertPlanEquals(plan, repo.pending()); assertNull(repo.baseline())
  }
 }

 @Test fun tooManyListedCommitsFailBeforeReadsOrPublication() {
  val remote = Memory(); remote.pages = { CommitPage(List(MAX_COMMITS + 1) { id() }) }
  val repo = repository(); val plan = repo.prepare(snapshot(), emptyList())
  assertFailsWith<IllegalArgumentException> { repo.publish(remote, plan) }
  assertTrue(remote.events.none { it.operation == "readCommit" || it.operation == "readObject" })
  assertTrue(remote.writes().isEmpty()); assertPlanEquals(plan, repo.pending())
 }

 @Test fun unboundedUniquePaginationFailsRatherThanReturningEmptyHistory() {
  val remote = Memory(); var pageNumber = 0
  remote.pages = { CommitPage(emptyList(), "page-${++pageNumber}") }
  assertFailsWith<IllegalArgumentException> { repository().discover(remote) }
  assertEquals(MAX_COMMITS, pageNumber)
  assertTrue(remote.events.none { it.operation == "readCommit" || it.operation == "readObject" })
 }

 @Test fun aggregateDeclaredObjectBudgetFailsBeforeRequestingTheExcessObject() {
  val remote = Memory(); val source = snapshot(); val largest = MAX_SNAPSHOT_BYTES
  val count = (MAX_REMOTE_SCAN_BYTES / largest).toInt() + 1
  repeat(count) {
   val oversizedTotal = commit(source.copy(manifest = source.manifest.copy(id = id()))).copy(objectBytes = largest)
   remote.commits[oversizedTotal.id] = encoded(oversizedTotal)
  }
  // No large arrays are allocated; declared lengths consume the budget even for missing objects.
  val repo = repository(); val plan = repo.prepare(source, emptyList())
  assertFailsWith<IllegalArgumentException> { repo.publish(remote, plan) }
  assertTrue(remote.events.count { it.operation == "readObject" } < count)
  assertTrue(remote.writes().isEmpty()); assertPlanEquals(plan, repo.pending())
 }

 @Test fun serialDelegatingStoreKeepsAllReadOverridesOnTheOwner() {
  val remote=Memory();val source=snapshot();val record=commit(source)
  val calls=mutableListOf<String>();val owner=Thread.currentThread()
  fun called(name:String){assertSame(owner,Thread.currentThread());calls+=name}
  val store=object:AppendStore by remote {
   override fun legacyPresent():Boolean {called("legacy");return false}
   override fun listCommits(cursor:String?):CommitPage {called("list");return CommitPage(listOf(record.id))}
   override fun readCommit(id:String):ByteArray {called("commit");return encoded(record)}
   override fun readObject(id:String):ByteArray {called("object");return source.zip}
  }
  assertEquals(setOf(record.id),repository().discover(store).commits.keys)
  assertEquals(listOf("list","commit","object"),calls)
  assertTrue(remote.events.isEmpty(),"delegation must not bypass read overrides")
 }

 @Test fun underreportedObjectLengthsCannotBypassTheActualByteBudget() {
  val remote = Memory(); val source = snapshot(); val payload = ByteArray(8 * 1024 * 1024)
  val expectedMaximumReads = (MAX_REMOTE_SCAN_BYTES / payload.size).toInt()
  repeat(expectedMaximumReads + 1) {
   val dishonest = commit(source.copy(manifest = source.manifest.copy(id = id()))).copy(objectBytes = 1)
   remote.commits[dishonest.id] = encoded(dishonest)
  }
  var objectReads = 0
  // Reuse one bounded buffer: exercising 256 MiB of charged traffic must not allocate it all.
  val store = object : AppendStore by remote {
   override fun readObject(id: String): ByteArray { objectReads++; return payload }
  }
  assertFailsWith<IllegalArgumentException> { repository().discover(store) }
  assertEquals(expectedMaximumReads, objectReads)
  assertTrue(remote.writes().isEmpty())
 }

 @Test fun invalidListedIdentifiersNeverReachDirectFetchOrPublication() {
  listOf("../head", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA", "00000000-0000-1000-8000-000000000000", "", "${id()}.json").forEach { unsafe ->
   val remote = Memory(); remote.pages = { CommitPage(listOf(unsafe)) }
   assertFailsWith<IllegalArgumentException> { repository().discover(remote) }
   assertTrue(remote.events.none { it.operation == "readCommit" || it.operation == "readObject" })
   assertTrue(remote.writes().isEmpty())
  }
 }

 @Test fun omittedParentsAreFetchedDirectlyUntilAncestryIsComplete() {
  val remote = Memory(); val root = remote.addSnapshot("root"); val middle = remote.addSnapshot("middle", listOf(root.id))
  val leaf = remote.addSnapshot("leaf", listOf(middle.id)); remote.pages = { CommitPage(listOf(leaf.id)) }
  val graph = repository().discover(remote); graph.requireSettled()
  assertEquals(setOf(root.id, middle.id, leaf.id), graph.commits.keys); assertEquals(setOf(leaf.id), graph.tips)
  assertEquals(listOf(leaf.id, middle.id, root.id), remote.events.filter { it.operation == "readCommit" }.map { it.id })
  assertTrue(graph.descendsFrom(leaf.id, root.id)); assertTrue(graph.descendsFrom(leaf.id, leaf.id))
  assertFalse(graph.descendsFrom(root.id, leaf.id)); assertFalse(graph.descendsFrom(leaf.id, id()))
 }

 @Test fun missingParentsAndTheirDescendantsRemainPendingUntilTheActualParentAppears() {
  val remote = Memory(); val missing = id(); val child = remote.addSnapshot("child", listOf(missing))
  val descendant = remote.addSnapshot("descendant", listOf(child.id)); val repo = repository()
  val graph = repo.discover(remote); assertPending(graph, missing, child.id, descendant.id)
  assertTrue(graph.commits.isEmpty()); assertTrue(graph.tips.isEmpty())
  val source = snapshot("parent"); val actual = commit(source, id = missing); remote.add(actual, source.zip)
  val complete = repo.discover(remote); complete.requireSettled(); assertEquals(setOf(descendant.id), complete.tips)
 }

 @Test fun missingOrCorruptObjectsNeverTurnDescendantsIntoGenesis() {
  listOf("missing", "size", "archive-hash", "file-hash", "zip-parser").forEach { defect ->
   val source = snapshot(); val remote = Memory(); var parent = commit(source)
   var archive = source.zip.copyOf()
   when (defect) {
    "size" -> parent = parent.copy(objectBytes = archive.size + 1)
    "archive-hash" -> parent = parent.copy(snapshot = parent.snapshot.copy(sha256 = "0".repeat(64)))
    "file-hash" -> parent = parent.copy(snapshot = parent.snapshot.copy(files = parent.snapshot.files.mapValues { "0".repeat(64) }))
    "zip-parser" -> { archive[8] = 99; archive[9] = 0; parent = parent.copy(snapshot = parent.snapshot.copy(sha256 = hash(archive))) }
   }
   remote.add(parent, archive); if (defect == "missing") remote.objects.remove(parent.snapshot.id)
   val child = remote.addSnapshot("child", listOf(parent.id)); val graph = repository().discover(remote)
   assertPending(graph, parent.id, child.id); assertTrue(graph.commits.isEmpty(), defect); assertTrue(graph.tips.isEmpty(), defect)
  }
 }

 @Test fun invalidParentManifestCannotBeSkippedToAdoptItsChild() {
  val remote = Memory(); val missing = id(); remote.commits[missing] = "{broken".encodeToByteArray()
  val child = remote.addSnapshot("child", listOf(missing)); val graph = repository().discover(remote)
  assertPending(graph, missing, child.id); assertTrue(graph.commits.isEmpty())
 }

 @Test fun cyclesAndDescendantsRemainPendingAlongsideUnrelatedVerifiedRoots() {
  val remote = Memory(); val firstId = id(); val secondId = id()
  val firstSnapshot = snapshot("first"); val secondSnapshot = snapshot("second")
  val first = commit(firstSnapshot, listOf(secondId), id = firstId); val second = commit(secondSnapshot, listOf(firstId), id = secondId)
  remote.add(first, firstSnapshot.zip); remote.add(second, secondSnapshot.zip)
  val child = remote.addSnapshot("cycle-child", listOf(firstId)); val valid = remote.addSnapshot("valid-root")
  val graph = repository().discover(remote)
  assertPending(graph, firstId, secondId, child.id); assertEquals(setOf(valid.id), graph.commits.keys)
  assertEquals(setOf(valid.id), graph.tips)
 }

 @Test fun missingAndMalformedObservedIdsRemainPendingWhenFutureListingsOmitThem() {
  listOf(false, true).forEach { malformed ->
   val path = directory("observed"); val repo = repository(path); val remote = Memory(); val broken = id()
   val healthy = remote.addSnapshot("healthy")
   if (malformed) remote.commits[broken] = "{broken".encodeToByteArray()
   remote.pages = { CommitPage(listOf(healthy.id, broken)) }
   assertPending(repo.discover(remote), broken)
   remote.pages = { CommitPage(listOf(healthy.id)) }
   val restarted = repository(path); val graph = restarted.discover(remote)
   assertPending(graph, broken); assertEquals(setOf(healthy.id), graph.commits.keys)
   assertFailsWith<UnsettledRemoteException> { restarted.setBaseline(healthy.id, graph) }; assertNull(restarted.baseline())
   val plan = restarted.prepare(snapshot("local"), emptyList())
   assertFailsWith<UnsettledRemoteException> { restarted.publish(remote, plan) }; assertTrue(remote.writes().isEmpty())
  }
 }

 @Test fun knownBranchesSurviveStaleOrEmptyListingsAcrossRestart() {
  val path = directory("known"); val repo = repository(path); val remote = Memory(); val root = remote.addSnapshot("root")
  val left = remote.addSnapshot("left", listOf(root.id)); val right = remote.addSnapshot("right", listOf(root.id))
  assertEquals(setOf(left.id, right.id), repo.discover(remote).tips)
  listOf(listOf(left.id), emptyList()).forEach { visible ->
   remote.pages = { CommitPage(visible) }
   val graph = repository(path).discover(remote); graph.requireSettled()
   assertEquals(setOf(root.id, left.id, right.id), graph.commits.keys); assertEquals(setOf(left.id, right.id), graph.tips)
  }
 }

 @Test fun disappearedKnownManifestStaysPendingRatherThanUsingCachedBytesAsRemoteTruth() {
  val path = directory("disappeared"); val repo = repository(path); val remote = Memory(); val known = remote.addSnapshot()
  repo.discover(remote).requireSettled(); remote.commits.clear()
  val graph = repository(path).discover(remote); assertPending(graph, known.id); assertTrue(graph.commits.isEmpty())
 }

 @Test fun knownManifestRewriteEvenWhitespaceOnlyIsAnIntegrityErrorAcrossRestart() {
  val path = directory("rewrite"); val repo = repository(path); val remote = Memory(); val known = remote.addSnapshot()
  repo.discover(remote).requireSettled(); remote.commits[known.id] = remote.commits.getValue(known.id) + '\n'.code.toByte()
  remote.pages = { CommitPage(emptyList()) }
  assertFailsWith<RemoteIntegrityException> { repository(path).discover(remote) }; assertTrue(remote.writes().isEmpty())
 }

 @Test fun structurallyValidButIncompleteManifestCannotBecomeBaseline() {
  listOf("object", "parent").forEach { missing ->
   val remote = Memory(); val parent = if (missing == "parent") listOf(id()) else emptyList()
   val candidate = remote.addSnapshot(parents = parent)
   if (missing == "object") remote.objects.clear()
   val repo = repository(); val graph = repo.discover(remote); assertPending(graph, candidate.id)
   assertFailsWith<UnsettledRemoteException> { repo.setBaseline(candidate.id, graph) }; assertNull(repo.baseline())
  }
 }

 @Test fun onlySettledVerifiedKnownCommitCanBePersistedAsBaseline() {
  val path = directory("baseline"); val remote = Memory(); val known = remote.addSnapshot(); val repo = repository(path)
  val graph = repo.discover(remote); repo.setBaseline(known.id, graph)
  assertEquals(known.id, repository(path).baseline())
  assertFailsWith<IllegalArgumentException> { repo.setBaseline(id(), graph) }
  val unknown = commit(); assertFailsWith<IllegalArgumentException> { repo.setBaseline(unknown.id, CommitGraph(mapOf(unknown.id to unknown), emptyMap())) }
  val altered = known.copy(kind = "restore")
  assertFailsWith<IllegalArgumentException> { repo.setBaseline(known.id, CommitGraph(mapOf(known.id to altered), emptyMap())) }
  assertEquals(known.id, repo.baseline())
 }

 @Test fun lostOrCorruptedEstablishedBaselineNeverLooksLikeFirstUse() {
  listOf("missing", "corrupt").forEach { defect ->
   val path = directory("lost-baseline"); val remote = Memory(); val known = remote.addSnapshot(); val repo = repository(path)
   repo.setBaseline(known.id, repo.discover(remote))
   if (defect == "missing") path.resolve("baseline.txt").deleteExisting() else path.resolve("baseline.txt").writeText("not-a-commit")
   val restarted = repository(path)
   assertFails { restarted.baseline() }; assertFails { restarted.discover(remote) }; assertTrue(remote.writes().isEmpty())
  }
 }

 @Test fun malformedPublishedMetadataRemainsPendingAndIsNeverAnEmptyRemote() {
  val source = snapshot(); val valid = commit(source); val parent = id()
  val invalid = listOf(
   valid.copy(schema = 3), valid.copy(id = id()), valid.copy(parents = listOf(valid.id)),
   valid.copy(parents = listOf(parent, parent)), valid.copy(parents = listOf("../parent")),
   valid.copy(parents = List(MAX_COMMIT_PARENTS + 1) { id() }, kind = "resolution"),
   valid.copy(parents = listOf(id(), id())), valid.copy(kind = "unknown"),
   valid.copy(kind = "resolution"), valid.copy(kind = "resolution", parents = listOf(parent)),
   valid.copy(kind = "legacy-import", parents = listOf(parent)), valid.copy(objectBytes = 0),
   valid.copy(objectBytes = -1), valid.copy(objectBytes = MAX_SNAPSHOT_BYTES + 1),
   valid.copy(snapshot = valid.snapshot.copy(schema = 2)), valid.copy(snapshot = valid.snapshot.copy(game = "other-game")),
   valid.copy(snapshot = valid.snapshot.copy(profile = "other-profile")), valid.copy(snapshot = valid.snapshot.copy(parent = parent)),
   valid.copy(snapshot = valid.snapshot.copy(id = valid.id)), valid.copy(snapshot = valid.snapshot.copy(id = "not-a-uuid")),
   valid.copy(snapshot = valid.snapshot.copy(sha256 = "A".repeat(64))), valid.copy(snapshot = valid.snapshot.copy(sha256 = "0")),
   valid.copy(snapshot = valid.snapshot.copy(files = emptyMap())),
   valid.copy(snapshot = valid.snapshot.copy(files = mapOf("game1/game.dat" to "invalid"))),
   valid.copy(snapshot = valid.snapshot.copy(files = (0..MAX_SNAPSHOT_FILES).associate { "game1/$it.dat" to "0".repeat(64) }))
  )
  invalid.forEachIndexed { index, malformed ->
   val remote = Memory(); remote.commits[valid.id] = encoded(malformed); remote.objects[valid.snapshot.id] = source.zip.copyOf()
   val graph = repository().discover(remote); assertPending(graph, valid.id)
   assertTrue(graph.commits.isEmpty(), "Malformed case $index became complete")
   assertTrue(remote.events.none { it.operation == "readObject" }, "Malformed case $index fetched object")
  }
 }

 @Test fun malformedJsonUnknownFieldsInvalidUtf8AndOversizedMetadataStayPending() {
  val source = snapshot(); val valid = commit(source); val bytes = encoded(valid)
  val cases = listOf("{broken".encodeToByteArray(), "[]".encodeToByteArray(), "null".encodeToByteArray(),
   byteArrayOf(0xc3.toByte(), 0x28), ByteArray(MAX_MANIFEST_BYTES + 1) { ' '.code.toByte() },
   (bytes.decodeToString().dropLast(1) + ",\"unexpected\":true}").encodeToByteArray())
  cases.forEach { malformed ->
   val remote = Memory(); remote.commits[valid.id] = malformed
   val graph = repository().discover(remote); assertPending(graph, valid.id); assertTrue(graph.commits.isEmpty())
   assertTrue(remote.events.none { it.operation == "readObject" })
  }
 }

 @Test fun duplicateOuterNestedAndEscapedEquivalentMetadataKeysStayPending() {
  val source = snapshot(); val valid = commit(source); val canonical = encoded(valid).decodeToString()
  val duplicateId = "\"id\":\"${valid.id}\","
  val fileHash = source.manifest.files.getValue("game1/game.dat")
  val malformed = listOf(
   "{$duplicateId${canonical.drop(1)}",
   "{\"id\":\"${id()}\",${canonical.drop(1)}",
   """{"\u0069d":"${valid.id}",""" + canonical.drop(1),
   canonical.replaceFirst("\"snapshot\": {", "\"snapshot\": {\"id\":\"${valid.snapshot.id}\","),
   canonical.replaceFirst("\"snapshot\": {", """"snapshot": {"\u0069d":"${valid.snapshot.id}","""),
   canonical.replaceFirst("\"files\": {", "\"files\": {\"game1/game.dat\":\"$fileHash\","),
   canonical.replaceFirst("\"files\": {", """"files": {"game1/\u0067ame.dat":"$fileHash",""")
  )
  malformed.forEachIndexed { index, body ->
   assertNotEquals(canonical, body, "Fixture $index must inject the intended duplicate")
   val remote = Memory(); remote.commits[valid.id] = body.encodeToByteArray(); remote.objects[valid.snapshot.id] = source.zip.copyOf()
   val graph = repository().discover(remote); assertPending(graph, valid.id)
   assertTrue(remote.events.none { it.operation == "readObject" }, "Ambiguous metadata case $index must fail before ZIP fetch")
  }
 }

 @Test fun excessivelyNestedMetadataRemainsPendingWithoutRecursiveParserFailure() {
  val remote = Memory(); val identifier = id()
  val body = "{\"nested\":" + "[".repeat(4096) + "0" + "]".repeat(4096) + "}"
  remote.commits[identifier] = body.encodeToByteArray()
  val graph = repository().discover(remote); assertPending(graph, identifier)
  assertTrue(remote.events.none { it.operation == "readObject" })
 }

 @Test fun archiveContentsMustExactlyMatchDeclaredFullFileSet() {
  val source = snapshot(); val remote = Memory()
  val commit = commit(source).copy(snapshot = source.manifest.copy(files = source.manifest.files - "journal.dat"))
  remote.add(commit, source.zip)
  assertPending(repository().discover(remote), commit.id)
 }

 @Test fun unsafeArchiveNamesAndIncompleteRunsAreRejectedEvenWhenHashesMatch() {
  listOf("../game1/game.dat", "game1/../game.dat", "game1/depth1.dat", "settings.xml").forEach { name ->
   val payload = "not a permitted whole save".encodeToByteArray(); val out = ByteArrayOutputStream()
   ZipOutputStream(out).use { zip -> zip.putNextEntry(ZipEntry(name)); zip.write(payload); zip.closeEntry() }
   val bytes = out.toByteArray(); val normal = snapshot()
   val unsafe = Snapshot(normal.manifest.copy(sha256 = hash(bytes), files = mapOf(name to hash(payload))), bytes)
   val remote = Memory(); val commit = commit(unsafe); remote.add(commit, bytes)
   assertPending(repository().discover(remote), commit.id)
  }
 }

 @Test fun independentRootsRemainSeparateAndDoNotGetAnImplicitWinner() {
  val remote = Memory(); val first = remote.addSnapshot("first"); val second = remote.addSnapshot("second")
  val graph = repository().discover(remote); graph.requireSettled()
  assertEquals(setOf(first.id, second.id), graph.tips)
  assertFalse(graph.descendsFrom(first.id, second.id)); assertFalse(graph.descendsFrom(second.id, first.id))
 }

 @Test fun twoOfflineDevicesPublishingFromOneParentPreserveBothBranchesWithoutCas() {
  val remote = Memory(); val root = remote.addSnapshot("root")
  val firstDevice = repository(); val secondDevice = repository()
  firstDevice.setBaseline(root.id, firstDevice.discover(remote)); secondDevice.setBaseline(root.id, secondDevice.discover(remote))
  val firstPlan = firstDevice.prepare(snapshot("first-offline"), listOf(root.id))
  val secondPlan = secondDevice.prepare(snapshot("second-offline"), listOf(root.id))
  firstDevice.publish(remote, firstPlan); secondDevice.publish(remote, secondPlan)
  val graph = repository().discover(remote); graph.requireSettled()
  assertEquals(setOf(firstPlan.commit.id, secondPlan.commit.id), graph.tips)
  assertContentEquals(firstPlan.manifestBytes, remote.commits[firstPlan.commit.id])
  assertContentEquals(secondPlan.manifestBytes, remote.commits[secondPlan.commit.id])
  assertContentEquals(firstPlan.zip, remote.objects[firstPlan.commit.snapshot.id])
  assertContentEquals(secondPlan.zip, remote.objects[secondPlan.commit.snapshot.id])
  assertEquals(4, remote.writes().size)
 }

 @Test fun resolutionNamesEveryChosenBranchWithoutRewritingParentsOrInventingMergeBytes() {
  val remote = Memory(); val root = remote.addSnapshot("root"); val left = remote.addSnapshot("left", listOf(root.id))
  val right = remote.addSnapshot("right", listOf(root.id)); val repo = repository(); val chosen = snapshot("explicit-choice")
  repo.discover(remote)
  val plan = repo.prepare(chosen, listOf(right.id, left.id), "resolution")
  val published = repo.publish(remote, plan); val graph = repo.discover(remote); graph.requireSettled()
  assertEquals(setOf(published.id), graph.tips); assertEquals(setOf(contentSha256(left.snapshot.files), contentSha256(right.snapshot.files)), published.parents.toSet())
  assertTrue(graph.descendsFrom(published.id, root.id)); assertTrue(graph.descendsFrom(published.id, left.id)); assertTrue(graph.descendsFrom(published.id, right.id))
  assertEquals(chosen.manifest.files, published.snapshot.files); assertContentEquals(chosen.zip, remote.objects[published.snapshot.id])
  assertContentEquals(encoded(left), remote.commits[left.id]); assertContentEquals(encoded(right), remote.commits[right.id])
 }

 @Test fun branchAppearingAfterPlanIsFrozenIsNotSilentlyAddedToResolutionParents() {
  val remote = Memory(); val root = remote.addSnapshot("root"); val left = remote.addSnapshot("left", listOf(root.id))
  val right = remote.addSnapshot("right", listOf(root.id)); val repo = repository()
  repo.discover(remote)
  val plan = repo.prepare(snapshot("choice"), listOf(left.id, right.id), "resolution")
  val late = remote.addSnapshot("late", listOf(root.id)); val published = repo.publish(remote, plan)
  assertEquals(setOf(contentSha256(left.snapshot.files), contentSha256(right.snapshot.files)), published.parents.toSet())
  val graph = repo.discover(remote); graph.requireSettled(); assertEquals(setOf(late.id, published.id), graph.tips)
  assertFalse(graph.descendsFrom(published.id, late.id)); assertPlanEquals(plan, repo.pending())
 }

 @Test fun invalidLocalAncestryKindsAndEmptySnapshotsCannotPreparePublication() {
  val valid = snapshot(); val duplicate = id()
  listOf(emptyList<String>() to "resolution", listOf(id()) to "resolution", listOf(id(), id()) to "snapshot",
   listOf(id(), id()) to "restore", listOf(duplicate, duplicate) to "resolution", listOf(id()) to "legacy-import",
   emptyList<String>() to "unknown", listOf("unsafe") to "snapshot").forEach { (parents, kind) ->
   val repo = repository(); assertFailsWith<IllegalArgumentException> { repo.prepare(valid, parents, kind) }; assertNull(repo.pending())
  }
  val empty = Snapshots.capture(directory("empty-save"), Shattered, profile, "4.0.2", null)
  assertFailsWith<IllegalArgumentException> { repository().prepare(empty, emptyList()) }
 }

 @Test fun nonexistentParentBlocksPublicationBeforeObjectUpload() {
  val repo = repository(); val remote = Memory(); val plan = repo.prepare(snapshot(), listOf(hash("missing-parent".encodeToByteArray())))
  assertFailsWith<IllegalArgumentException> { repo.publish(remote, plan) }
  assertTrue(remote.writes().isEmpty()); assertPlanEquals(plan, repo.pending())
 }

 @Test fun removedLegacyFlagDoesNotBlockCurrentDiscoveryOrPublish() {
  val remote = Memory(); remote.addSnapshot(); remote.legacy = true
  val path = directory("legacy"); val repo = repository(path); val plan = repo.prepare(snapshot(), emptyList())
  assertTrue(repo.discover(remote).commits.isNotEmpty())
  repo.publish(remote, plan)
  assertTrue(remote.events.none { it.operation == "legacy" })
  assertPlanEquals(plan, repository(path).pending())
 }

 @Test fun explicitImportIsRootOnlyWithoutProbingOldHead() {
  val repo = repository(); val remote = Memory(); val plan = repo.prepare(snapshot(), emptyList(), "legacy-import")
  assertEquals("legacy-import", plan.commit.kind); assertTrue(plan.commit.parents.isEmpty())
  remote.legacy = true; assertEquals(plan.commit, repo.publish(remote, plan))
  assertTrue(remote.events.none { it.operation == "legacy" })
 }

 @Test fun downloadRevalidatesRemoteManifestObjectLengthHashAndFileContents() {
  listOf("manifest-missing", "manifest-changed", "object-missing", "length", "hash").forEach { defect ->
   val repo = repository(); val remote = Memory(); val original = remote.addSnapshot(); repo.discover(remote).requireSettled()
   when (defect) {
    "manifest-missing" -> remote.commits.remove(original.id)
    "manifest-changed" -> remote.commits[original.id] = encoded(original.copy(kind = "restore"))
    "object-missing" -> remote.objects.remove(original.snapshot.id)
    "length" -> remote.objects[original.snapshot.id] = remote.objects.getValue(original.snapshot.id) + 0.toByte()
    "hash" -> remote.objects.getValue(original.snapshot.id).let { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
   }
   assertFails(defect) { repo.download(remote, original) }; assertTrue(remote.writes().isEmpty())
  }
 }

 @Test fun downloadReturnsOnlyTheVerifiedFrozenSnapshot() {
  val remote = Memory(); val source = snapshot("download"); val commit = commit(source); remote.add(commit, source.zip)
  val received = repository().download(remote, commit)
  assertEquals(commit.snapshot, received.manifest); assertContentEquals(source.zip, received.zip)
  assertEquals(source.manifest.files.keys, Snapshots.verify(received, Shattered).keys); assertTrue(remote.writes().isEmpty())
 }
}
