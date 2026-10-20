package pdsync

import kotlinx.serialization.encodeToString
import okhttp3.Credentials
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pdsync.android.app.AndroidGameAdapter
import pdsync.android.app.AndroidGameIdentity
import java.nio.file.Path
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.*
import kotlin.test.*

/** Real OkHttp/loopback DAV integration. All accounts, archives and game origins are synthetic. */
class CanonicalCloudNamespaceTest {
 @TempDir lateinit var temp: Path
 private val profile = "default"
 private val source = "a".repeat(64)
 private val oldAlias = "android-$source"
 private val packageName = GameCloudIdentity.SHATTERED_PACKAGE
 private val canonical = GameCloudIdentity.namespace(packageName)
 private val version = "synthetic-896"
 private var serial = 0
 private fun directory(label: String) = temp.resolve("$label-${serial++}").createDirectories()
 private fun repository(path: Path = directory("repository"), adapter: GameAdapter = Shattered,
                        profile: String = this.profile) = AppendRepository(path, adapter, profile)
 private fun android(gamePackage: String = packageName, origin: String = source, label: String = "Synthetic game") =
  AndroidGameAdapter(AndroidGameIdentity.forPackage(gamePackage, label, origin))
 private fun files(marker: String) = linkedMapOf(
  "game1/game.dat" to """{"version":896,"init_ver":896,"turn":7,"marker":"$marker"}""".encodeToByteArray(),
  "game1/depth1.dat" to byteArrayOf(0, 1, 127, -1, 42),
  "badges.dat" to "synthetic-badges-$marker".encodeToByteArray()
 )
 private fun snapshot(marker: String = "android", gamePackage: String = packageName,
                      profile: String = this.profile, origin: String = source,
                      version: String = this.version): Snapshot {
  val captured = Snapshots.fromFiles(files(marker), android(gamePackage, origin), profile, version)
  return captured.copy(manifest = captured.manifest.copy(gamePackage = gamePackage,
   gameSource = GameSaveSource(origin, "b".repeat(64))))
 }
 private fun commit(snapshot: Snapshot, parents: List<String> = emptyList(), id: String = UUID.randomUUID().toString()) =
  AppendCommit(id = id, parents = parents, snapshot = snapshot.manifest, objectBytes = snapshot.zip.size)
 private fun encoded(commit: AppendCommit) = json.encodeToString(commit).encodeToByteArray()
 private fun commitPath(namespace: String, id: String, profile: String = this.profile) = "/dav/$namespace/$profile/pdsync-v2-commit-$id.json"
 private fun objectPath(namespace: String, id: String, profile: String = this.profile) = "/dav/$namespace/$profile/pdsync-v2-object-$id.zip"
 private fun writeFiles(root: Path, marker: String) = files(marker).forEach { (name, bytes) ->
  root.resolve(name).also { it.parent.createDirectories(); it.writeBytes(bytes) }
 }
 private fun assertFiles(root: Path, marker: String) = files(marker).forEach { (name, bytes) ->
  assertContentEquals(bytes, root.resolve(name).readBytes(), name)
 }

 /** A minimal server which exercises actual PROPFIND, GET, PUT and explicit MKCOL requests. */
 private inner class Dav : AutoCloseable {
  val server = MockWebServer()
  val collections = ConcurrentHashMap.newKeySet<String>().apply { add("/dav/") }
  val bytes = ConcurrentHashMap<String, ByteArray>()
  val omitted = ConcurrentHashMap.newKeySet<String>()
  val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
  var failNextPutAt: String? = null
  init {
   server.dispatcher = object : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
     requests += request
     val path = requireNotNull(request.requestUrl).encodedPath
     if (request.getHeader("Authorization") != Credentials.basic("synthetic", "test-only"))
      return MockResponse().setResponseCode(401)
     return when (request.method) {
      "GET" -> bytes[path]?.let { MockResponse().setBody(Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
      "PROPFIND" -> {
       if (path !in collections) MockResponse().setResponseCode(404)
       else {
        val entries = mutableListOf(entry(path, true))
        if (request.getHeader("Depth") == "1") {
         collections.filter { it != path && it !in omitted && it.startsWith(path) &&
          it.removePrefix(path).trimEnd('/').let { name -> name.isNotEmpty() && '/' !in name } }
          .sorted().forEach { entries += entry(it, true) }
         bytes.keys.filter { it !in omitted && it.startsWith(path) && '/' !in it.removePrefix(path) }
          .sorted().forEach { entries += entry(it, false) }
        }
        MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml")
         .setBody("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">${entries.joinToString("")}</d:multistatus>""")
       }
      }
      "MKCOL" -> when {
       path in collections -> MockResponse().setResponseCode(405)
       path.trimEnd('/').substringBeforeLast('/') + "/" !in collections -> MockResponse().setResponseCode(409)
       else -> { collections += path; MockResponse().setResponseCode(201) }
      }
      "PUT" -> when {
       path == failNextPutAt -> { failNextPutAt = null; MockResponse().setResponseCode(500) }
       path.substringBeforeLast('/') + "/" !in collections -> MockResponse().setResponseCode(409)
       else -> { bytes[path] = request.body.clone().readByteArray(); MockResponse().setResponseCode(201) }
      }
      else -> MockResponse().setResponseCode(405)
     }
    }
   }
   server.start()
  }
  private fun entry(path: String, collection: Boolean) =
   """<d:response><d:href>$path</d:href><d:propstat><d:prop><d:resourcetype>${if (collection) "<d:collection/>" else ""}</d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
  fun namespace(name: String = canonical, profile: String = this@CanonicalCloudNamespaceTest.profile) {
   collections += "/dav/$name/"; collections += "/dav/$name/$profile/"
  }
  fun seed(commit: AppendCommit, archive: ByteArray, namespace: String = commit.snapshot.game) {
   this.namespace(namespace, commit.snapshot.profile)
   bytes[commitPath(namespace, commit.id, commit.snapshot.profile)] = encoded(commit)
   bytes[objectPath(namespace, commit.snapshot.id, commit.snapshot.profile)] = archive.copyOf()
  }
  fun store(adapter: GameAdapter = Shattered, profile: String = this@CanonicalCloudNamespaceTest.profile) =
   AppendOnlyWebDav(server.url("/dav/").toString(), adapter.id, profile, "synthetic", "test-only")
  fun mutations() = requests.filter { it.method !in setOf("GET", "PROPFIND") }
  fun assertOnlyNamespace(namespace: String = canonical, profile: String = this@CanonicalCloudNamespaceTest.profile) {
   assertTrue(requests.none { it.path == "/dav/" && it.getHeader("Depth") == "1" }, "Never discover namespace aliases from the DAV root")
   assertTrue(requests.all {
    val path = requireNotNull(it.requestUrl).encodedPath
    path.startsWith("/dav/$namespace/$profile/") ||
     (it.method == "PROPFIND" && it.getHeader("Depth") == "0" && path in setOf("/dav/", "/dav/$namespace/")) ||
     (it.method == "MKCOL" && path == "/dav/$namespace/")
   }, "Every history request must stay in the selected current namespace and profile")
  }
  override fun close() = server.close()
 }

 @Test fun freshAndroidUploadPcRestoreProgressUploadAndAndroidRestoreUseTheSameCanonicalCollection(): Unit = Dav().use { dav ->
  val androidAdapter = android()
  assertEquals(Shattered.id, androidAdapter.id)
  val androidRepo = repository(adapter = androidAdapter)
  val androidStore = dav.store(adapter = androidAdapter)
  androidStore.ensureNamespace()
  assertEquals(setOf("/dav/$canonical/", "/dav/$canonical/$profile/"), dav.mutations().map { it.path }.toSet())
  val initial = snapshot("android-upload")
  val first = androidRepo.prepare(initial, emptyList())
  assertEquals(first.commit, androidRepo.publish(androidStore, first))
  assertContentEquals(first.zip, dav.bytes[objectPath(canonical, first.commit.snapshot.id)])

  // A separate PC has no graph, baseline, source fingerprint or namespace discovery state.
  val pcRepo = repository(); val pcStore = dav.store()
  val beforeRead = dav.mutations().size
  val pcGraph = pcRepo.discover(pcStore); pcGraph.requireSettled()
  assertEquals(setOf(first.commit.id), pcGraph.tips); assertNull(pcRepo.baseline())
  assertFalse(AutomaticUploadEligibility.allows(OnboardingComparison(
   kind = OnboardingKind.DIFFERENT, localFiles = files("unrelated-local").mapValues { hash(it.value) },
   baselineId = null, baselineLineage = OnboardingLineage.NONE, remoteEntries = emptyList(),
   observedTips = pcGraph.tips, owner = this)))
  val downloaded = pcRepo.download(pcStore, pcGraph.commits.getValue(first.commit.id))
  assertContentEquals(first.zip, downloaded.zip)
  val desktopSave = directory("fresh-pc-save"); writeFiles(desktopSave, "old-pc-local")
  Snapshots.restore(downloaded, desktopSave, directory("pc-backups"), Shattered, version, { true })
  assertFiles(desktopSave, "android-upload")
  assertEquals(beforeRead, dav.mutations().size, "Discovery, download and restore do not mutate DAV")
  writeFiles(desktopSave, "pc-progress")
  val progressed = Snapshots.capture(desktopSave, Shattered, profile, version, null)
  val child = pcRepo.prepare(progressed, listOf(first.commit.id))
  assertEquals(child.commit, pcRepo.publish(pcStore, child))

  val returningAndroid = repository(adapter = androidAdapter); val returningStore = dav.store(adapter = androidAdapter)
  val androidGraph = returningAndroid.discover(returningStore); androidGraph.requireSettled()
  assertEquals(setOf(child.commit.id), androidGraph.tips)
  assertEquals(setOf(first.commit.id, child.commit.id), androidGraph.commits.keys)
  assertTrue(androidGraph.descendsFrom(child.commit.id, first.commit.id))
  val returned = returningAndroid.download(returningStore, androidGraph.commits.getValue(child.commit.id))
  assertContentEquals(child.zip, returned.zip)
  val androidSave = directory("returning-android-save"); writeFiles(androidSave, "android-before-return")
  Snapshots.restore(returned, androidSave, directory("android-backups"), androidAdapter, version, { true })
  assertFiles(androidSave, "pc-progress")
  assertEquals(4, dav.mutations().count { it.method == "PUT" })
  assertEquals(2, dav.mutations().count { it.method == "MKCOL" })
  assertContentEquals(first.manifestBytes, dav.bytes[commitPath(canonical, first.commit.id)])
  assertContentEquals(first.zip, dav.bytes[objectPath(canonical, first.commit.snapshot.id)])
  assertFalse(dav.collections.any { it.startsWith("/dav/$oldAlias/") })
  dav.assertOnlyNamespace()
 }

 @Test fun samePackageWithDifferentSourcesVersionsAndRootOrEmbeddedLabelsSharesOneCloudNamespace(): Unit = Dav().use { dav ->
  val root = android(origin = source, label = "Synthetic Root game")
  val embedded = android(origin = "d".repeat(64), label = "Synthetic embedded game")
  assertEquals(canonical, root.id); assertEquals(root.id, embedded.id)
  dav.store(root).ensureNamespace()
  val rootSnapshot = snapshot("root-version-1", origin = source, version = "synthetic-1")
  val rootRepo = repository(adapter = root); val first = rootRepo.prepare(rootSnapshot, emptyList())
  rootRepo.publish(dav.store(root), first)
  val embeddedRepo = repository(adapter = embedded); val embeddedStore = dav.store(embedded)
  val observed = embeddedRepo.discover(embeddedStore); observed.requireSettled()
  assertEquals(setOf(first.commit.id), observed.tips)
  assertContentEquals(first.zip, embeddedRepo.download(embeddedStore, observed.commits.getValue(first.commit.id)).zip)
  val changedVersion = snapshot("embedded-version-2", origin = "d".repeat(64), version = "synthetic-2")
  val second = embeddedRepo.prepare(changedVersion, listOf(first.commit.id)); embeddedRepo.publish(embeddedStore, second)
  val rootGraph = repository(adapter = root).discover(dav.store(root)); rootGraph.requireSettled()
  assertEquals(setOf(second.commit.id), rootGraph.tips)
  assertNotEquals(first.commit.snapshot.version, second.commit.snapshot.version)
  assertNotEquals(first.commit.snapshot.gameSource, second.commit.snapshot.gameSource)
  assertEquals(first.commit.snapshot.game, second.commit.snapshot.game)
  assertEquals(first.commit.snapshot.gamePackage, second.commit.snapshot.gamePackage)
  dav.assertOnlyNamespace()
 }

 @Test fun differentPackageAndProfileNeverJoinTheSelectedHistory(): Unit = Dav().use { dav ->
  val selected = snapshot(); val selectedCommit = commit(selected); dav.seed(selectedCommit, selected.zip)
  val otherPackage = "org.example.unrelated"
  val other = snapshot("other-package", gamePackage = otherPackage)
   .let { it.copy(manifest = it.manifest.copy(id = selected.manifest.id)) }
  val unrelated = commit(other, id = selectedCommit.id); dav.seed(unrelated, other.zip)
  val otherProfile = snapshot("other-profile", profile = "separate")
  dav.seed(commit(otherProfile, id = selectedCommit.id), otherProfile.zip)
  assertNotEquals(canonical, android(otherPackage).id)
  val graph = repository().discover(dav.store()); graph.requireSettled()
  assertEquals(setOf(selectedCommit.id), graph.commits.keys)
  assertContentEquals(selected.zip, repository().download(dav.store(), selectedCommit).zip)
  assertTrue(dav.mutations().isEmpty()); dav.assertOnlyNamespace()
 }

 @Test fun availableOldDirectoriesAreNeverListedReadOrUsedAsFallback(): Unit = Dav().use { dav ->
  for (alias in listOf(oldAlias, "manual-cloud", "android-${"f".repeat(64)}")) {
   val ignored = snapshot("ignored-$alias").let { it.copy(manifest = it.manifest.copy(game = alias)) }
   val oldCommit = commit(ignored); dav.seed(oldCommit, ignored.zip)
   dav.bytes["/dav/$alias/$profile/head.json"] = "old protocol".encodeToByteArray()
  }
  // The fixture would list every old collection on Depth:1 /dav/, but the client must never ask.
  val selected = snapshot("current"); val current = commit(selected); dav.seed(current, selected.zip)
  val repo = repository(); val graph = repo.discover(dav.store()); graph.requireSettled()
  assertEquals(setOf(current.id), graph.commits.keys)
  assertContentEquals(selected.zip, repo.download(dav.store(), current).zip)
  assertTrue(dav.mutations().isEmpty()); dav.assertOnlyNamespace()
 }

 @Test fun absentCurrentCollectionRequiresExplicitSetupWithoutLookingForOldHistory(): Unit = Dav().use { dav ->
  val old = snapshot().let { it.copy(manifest = it.manifest.copy(game = oldAlias)) }
  dav.seed(commit(old), old.zip)
  val repo = repository(); val store = dav.store(); val plan = repo.prepare(snapshot(), emptyList())
  assertFailsWith<WebDavException> { repo.discover(store) }
  assertFailsWith<WebDavException> { repo.publish(store, plan) }
  assertTrue(dav.mutations().isEmpty()); assertFalse("/dav/$canonical/" in dav.collections)
  store.ensureNamespace()
  assertEquals(plan.commit, repo.publish(store, plan))
  assertEquals(2, dav.mutations().count { it.method == "MKCOL" })
  assertEquals(2, dav.mutations().count { it.method == "PUT" })
  dav.assertOnlyNamespace()
 }

 @Test fun currentPcCaptureWithoutAndroidProvenanceIsReadable(): Unit = Dav().use { dav ->
  val captured = Snapshots.fromFiles(files("current-pc"), Shattered, profile, version)
  val original = commit(captured); dav.seed(original, captured.zip)
  val reader = repository(adapter = android()); val graph = reader.discover(dav.store(adapter = android()))
  graph.requireSettled(); assertEquals(setOf(original.id), graph.tips)
  assertContentEquals(captured.zip, reader.download(dav.store(), original).zip)
  assertTrue(dav.mutations().isEmpty()); dav.assertOnlyNamespace()
 }

 @Test fun anOldGameNameInsideCurrentCollectionIsRejectedEvenWithMatchingPackageAndSource(): Unit = Dav().use { dav ->
  val old = snapshot().let { it.copy(manifest = it.manifest.copy(game = oldAlias)) }
  val copied = commit(old); dav.seed(copied, old.zip, canonical)
  val repo = repository(adapter = android()); val graph = repo.discover(dav.store())
  assertTrue(graph.commits.isEmpty()); assertEquals(setOf(copied.id), graph.pending.keys)
  assertFailsWith<UnsettledRemoteException> { graph.requireSettled() }
  assertFailsWith<IllegalArgumentException> { repo.prepare(old, emptyList()) }
  assertTrue(dav.mutations().isEmpty()); dav.assertOnlyNamespace()
 }

 @Test fun differentPackageClaimInsideCurrentCollectionIsRejected(): Unit = Dav().use { dav ->
  val incorrect = snapshot().let { it.copy(manifest = it.manifest.copy(gamePackage = "org.example.other")) }
  val record = commit(incorrect); dav.seed(record, incorrect.zip)
  val repo = repository(); val graph = repo.discover(dav.store())
  assertEquals(setOf(record.id), graph.pending.keys); assertTrue(graph.commits.isEmpty())
  assertFailsWith<IllegalArgumentException> { repo.prepare(incorrect, emptyList()) }
  assertTrue(dav.mutations().isEmpty()); dav.assertOnlyNamespace()
 }

 @Test fun parentPresentOnlyOutsideCurrentNamespaceRemainsUnresolvedAndBlocksPublication(): Unit = Dav().use { dav ->
  val parentSave = snapshot("outside-parent"); val parent = commit(parentSave)
  // Even exact canonical bytes at an old path are not an ancestry source.
  dav.seed(parent, parentSave.zip, oldAlias)
  val childSave = snapshot("current-child"); val child = commit(childSave, listOf(parent.id)); dav.seed(child, childSave.zip)
  val repo = repository(); val store = dav.store(); val graph = repo.discover(store)
  assertTrue(graph.commits.isEmpty()); assertEquals(setOf(parent.id, child.id), graph.pending.keys)
  assertFailsWith<UnsettledRemoteException> { graph.requireSettled() }
  val plan = repo.prepare(snapshot("blocked-descendant"), listOf(child.id))
  assertFailsWith<UnsettledRemoteException> { repo.publish(store, plan) }
  assertContentEquals(plan.manifestBytes, assertNotNull(repo.pending()).manifestBytes)
  assertTrue(dav.requests.any { it.method == "GET" && it.path == commitPath(canonical, parent.id) })
  assertTrue(dav.mutations().isEmpty()); dav.assertOnlyNamespace()
 }

 @Test fun missingCurrentParentSettlesOnlyWhenItAppearsAndIsFetchedDespiteAnOmittedListing(): Unit = Dav().use { dav ->
  val parentSave = snapshot("parent"); val parent = commit(parentSave)
  val childSave = snapshot("child"); val child = commit(childSave, listOf(parent.id)); dav.seed(child, childSave.zip)
  val repo = repository(); val store = dav.store(); val first = repo.discover(store)
  assertEquals(setOf(parent.id, child.id), first.pending.keys); assertTrue(first.commits.isEmpty())
  dav.seed(parent, parentSave.zip); dav.omitted += commitPath(canonical, parent.id)
  val settled = repo.discover(store); settled.requireSettled()
  assertEquals(setOf(child.id), settled.tips); assertEquals(setOf(parent.id, child.id), settled.commits.keys)
  assertTrue(settled.descendsFrom(child.id, parent.id)); assertNull(repo.baseline())
  assertTrue(dav.mutations().isEmpty()); dav.assertOnlyNamespace()
 }

 @Test fun independentRootsRemainConflictingAndOnlyExplicitResolutionJoinsTheirAncestry(): Unit = Dav().use { dav ->
  val leftSave = snapshot("android-root"); val rightSave = snapshot("pc-root")
  val left = commit(leftSave); val right = commit(rightSave); dav.seed(left, leftSave.zip); dav.seed(right, rightSave.zip)
  val leftBytes = encoded(left); val rightBytes = encoded(right)
  val repo = repository(); val store = dav.store(); val graph = repo.discover(store); graph.requireSettled()
  assertEquals(setOf(left.id, right.id), graph.tips)
  assertFalse(graph.descendsFrom(left.id, right.id)); assertFalse(graph.descendsFrom(right.id, left.id))
  assertNull(repo.baseline()); assertNull(repo.baselineFiles())
  assertFalse(AutomaticUploadEligibility.allows(OnboardingComparison(
   kind = OnboardingKind.BRANCHED, localFiles = leftSave.manifest.files, baselineId = null,
   baselineLineage = OnboardingLineage.NONE, remoteEntries = emptyList(), observedTips = graph.tips, owner = this)))
  assertTrue(dav.mutations().isEmpty())
  val resolution = repo.prepare(leftSave, graph.tips.toList(), "resolution")
  assertEquals(resolution.commit, repo.publish(store, resolution))
  val resolved = repository().discover(dav.store()); resolved.requireSettled()
  assertEquals(setOf(resolution.commit.id), resolved.tips)
  assertTrue(resolved.descendsFrom(resolution.commit.id, left.id)); assertTrue(resolved.descendsFrom(resolution.commit.id, right.id))
  assertEquals(3, resolved.commits.size); assertNull(repo.baseline())
  assertContentEquals(leftBytes, dav.bytes[commitPath(canonical, left.id)])
  assertContentEquals(rightBytes, dav.bytes[commitPath(canonical, right.id)])
  dav.assertOnlyNamespace()
 }

 @Test fun oldGameInFrozenPlanIsRejectedWithoutAnyRemoteRequestOrPendingRewrite(): Unit = Dav().use { dav ->
  val state = directory("old-frozen-state"); val original = repository(state).prepare(snapshot(), emptyList())
  val oldCommit = original.commit.copy(snapshot = original.commit.snapshot.copy(game = oldAlias))
  val oldBytes = encoded(oldCommit); val oldPlan = UploadPlan(oldCommit, oldBytes, original.zip)
  val pendingManifest = state.resolve("uploads/${oldCommit.id}.json"); pendingManifest.writeBytes(oldBytes)
  val pendingZip = state.resolve("uploads/${oldCommit.id}.zip")
  val marker = state.resolve("active-plan.txt"); val markerBefore = marker.readBytes()
  val current = repository(state, android())
  assertFailsWith<IllegalArgumentException> { current.pending() }
  assertFailsWith<IllegalArgumentException> { current.publish(dav.store(), oldPlan) }
  assertContentEquals(oldBytes, pendingManifest.readBytes()); assertContentEquals(original.zip, pendingZip.readBytes())
  assertContentEquals(markerBefore, marker.readBytes())
  assertTrue(dav.requests.isEmpty(), "Reject the incompatible frozen plan before DAV reads or writes")
 }

 @Test fun interruptedCurrentPublicationRetriesTheSameFrozenIdsManifestAndObjectBytes(): Unit = Dav().use { dav ->
  val adapter = android(); val store = dav.store(adapter); store.ensureNamespace()
  val state = directory("current-frozen-state"); val repo = repository(state, adapter)
  val original = repo.prepare(snapshot("frozen-current"), emptyList())
  val commitKey = commitPath(canonical, original.commit.id); val objectKey = objectPath(canonical, original.commit.snapshot.id)
  dav.failNextPutAt = commitKey
  assertFailsWith<WebDavException> { repo.publish(store, original) }
  assertContentEquals(original.zip, dav.bytes[objectKey]); assertNull(dav.bytes[commitKey])
  val restarted = repository(state, adapter); val frozen = assertNotNull(restarted.pending())
  assertEquals(original.commit, frozen.commit)
  assertContentEquals(original.manifestBytes, frozen.manifestBytes); assertContentEquals(original.zip, frozen.zip)
  assertEquals(original.commit, restarted.publish(dav.store(adapter), frozen))
  assertContentEquals(original.manifestBytes, dav.bytes[commitKey]); assertContentEquals(original.zip, dav.bytes[objectKey])
  val objectPuts = dav.requests.filter { it.method == "PUT" && it.path == objectKey }
  val commitPuts = dav.requests.filter { it.method == "PUT" && it.path == commitKey }
  assertEquals(1, objectPuts.size); assertEquals(2, commitPuts.size)
  assertContentEquals(original.zip, objectPuts.single().body.clone().readByteArray())
  commitPuts.forEach { assertContentEquals(original.manifestBytes, it.body.clone().readByteArray()) }
  assertNotNull(restarted.pending(), "Publication alone preserves the frozen recovery state")
  val graph = restarted.discover(dav.store(adapter)); graph.requireSettled(); assertEquals(setOf(original.commit.id), graph.tips)
  dav.assertOnlyNamespace()
 }

 @Test fun listedOrOmittedMalformedCurrentFrozenCommitIsNeverOverwritten() {
  for (hidden in listOf(false, true)) Dav().use { dav ->
   dav.namespace(); val repo = repository(); val frozen = repo.prepare(snapshot(), emptyList())
   val target = commitPath(canonical, frozen.commit.id); val malformed = "{\"schema\":2,".encodeToByteArray()
   dav.bytes[target] = malformed.copyOf(); dav.bytes[objectPath(canonical, frozen.commit.snapshot.id)] = frozen.zip.copyOf()
   if (hidden) dav.omitted += target
   if (hidden) assertFailsWith<RemoteIntegrityException> { repo.publish(dav.store(), frozen) }
   else assertFailsWith<UnsettledRemoteException> { repo.publish(dav.store(), frozen) }
   assertContentEquals(malformed, dav.bytes[target])
   assertContentEquals(frozen.manifestBytes, assertNotNull(repo.pending()).manifestBytes)
   assertTrue(dav.mutations().isEmpty()); dav.assertOnlyNamespace()
  }
 }

 @Test fun conflictingObjectAtCurrentFrozenDestinationIsNeverOverwritten(): Unit = Dav().use { dav ->
  dav.namespace(); val repo = repository(); val frozen = repo.prepare(snapshot(), emptyList())
  val target = objectPath(canonical, frozen.commit.snapshot.id); val conflicting = byteArrayOf(1, 2, 3, 4)
  dav.bytes[target] = conflicting.copyOf()
  assertFailsWith<RemoteIntegrityException> { repo.publish(dav.store(), frozen) }
  assertContentEquals(conflicting, dav.bytes[target]); assertContentEquals(frozen.zip, assertNotNull(repo.pending()).zip)
  assertTrue(dav.mutations().isEmpty()); dav.assertOnlyNamespace()
 }

 @Test fun malformedCurrentHistoryBlocksPublicationAndRetainsTheFrozenPlan(): Unit = Dav().use { dav ->
  val parentSave = snapshot("parent"); val parent = commit(parentSave); dav.seed(parent, parentSave.zip)
  dav.bytes[commitPath(canonical, UUID.randomUUID().toString())] = "{not-json".encodeToByteArray()
  val repo = repository(); val plan = repo.prepare(snapshot("child"), listOf(parent.id))
  assertFailsWith<UnsettledRemoteException> { repo.publish(dav.store(), plan) }
  assertContentEquals(plan.manifestBytes, assertNotNull(repo.pending()).manifestBytes)
  assertContentEquals(plan.zip, assertNotNull(repo.pending()).zip)
  assertTrue(dav.mutations().isEmpty()); dav.assertOnlyNamespace()
 }
}
