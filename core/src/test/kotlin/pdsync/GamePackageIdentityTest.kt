package pdsync

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.io.path.*
import kotlin.test.*

/** Isolated core regression: package identity never depends on an APK, server, or user directory. */
class GamePackageIdentityTest {
 @TempDir lateinit var temp: Path
 private val spd = GameCloudIdentity.SHATTERED_PACKAGE
 private val alias = "android-${"a".repeat(64)}"
 private val profile = "demo"
 private val oldOrigin = "32cd68da7bd2cdb467330cf33ca7c0e9b18eaae94ecf0b4b944313def1732f89"
 private fun id() = UUID.randomUUID().toString()
 private class Adapter(override val id: String, override val packageName: String? = null): GameAdapter {
  override fun includes(name: String) = Shattered.includes(name)
  override fun compatible(source: String, target: String) = source == target
  override fun launchCommand(executable: Path) = listOf(executable.toString())
 }
 private fun snapshot() = Snapshots.fromFiles(mapOf("game1/game.dat" to "{\"version\":896,\"turn\":1}".encodeToByteArray(),
  "game1/depth1.dat" to "floor".encodeToByteArray()), Shattered, profile, "3.3.8")
 private fun aliased(source: Snapshot = snapshot()) = source.copy(manifest = source.manifest.copy(game = alias, gamePackage = spd))
 private fun commit(snapshot: Snapshot, parents: List<String> = emptyList()) = AppendCommit(id = id(), parents = parents,
  snapshot = snapshot.manifest, objectBytes = snapshot.zip.size)
 private class Memory : AppendStore {
  val commits = linkedMapOf<String, ByteArray>(); val objects = linkedMapOf<String, ByteArray>()
  val events = mutableListOf<String>(); var listed: List<String>? = null
  override fun legacyPresent(): Boolean { events += "legacy"; return false }
  override fun listCommits(cursor: String?): CommitPage { events += "list"; return CommitPage(listed ?: commits.keys.toList()) }
  override fun readCommit(id: String): ByteArray? { events += "read-commit"; return commits[id]?.copyOf() }
  override fun readObject(id: String): ByteArray? { events += "read-object"; return objects[id]?.copyOf() }
  override fun writeCommit(id: String, bytes: ByteArray) { events += "write-commit"; commits[id] = bytes.copyOf() }
  override fun writeObject(id: String, bytes: ByteArray) { events += "write-object"; objects[id] = bytes.copyOf() }
  fun add(commit: AppendCommit, snapshot: Snapshot) {
   commits[commit.id] = json.encodeToString(commit).encodeToByteArray(); objects[commit.snapshot.id] = snapshot.zip.copyOf()
  }
 }
 private fun targetJar(): Path {
  val path = temp.resolve("target-${id()}.jar")
  val manifest = "Manifest-Version: 1.0\r\nMain-Class: com.shatteredpixel.shatteredpixeldungeon.desktop.DesktopLauncher\r\n" +
   "Specification-Title: Shattered Pixel Dungeon\r\nImplementation-Title: $spd\r\n" +
   "Specification-Version: 3.3.8\r\nImplementation-Version: 896\r\n\r\n"
  ZipOutputStream(Files.newOutputStream(path)).use { zip ->
   mapOf("META-INF/MANIFEST.MF" to manifest.encodeToByteArray(),
    "com/shatteredpixel/shatteredpixeldungeon/desktop/DesktopLauncher.class" to byteArrayOf(1)).forEach { (name, bytes) ->
    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
   }
  }
  return path
 }
 private fun storedBytes(directory: Path): Map<String, String> = Files.walk(directory).use { paths ->
  paths.filter { Files.isRegularFile(it) }.iterator().asSequence().associate { directory.relativize(it).toString() to hash(it.readBytes()) }
 }

 @Test fun canonicalNamespaceIsVersionedPackageOnlyAndValidatesThePackage() {
  assertEquals("shattered", GameCloudIdentity.namespace(spd))
  assertEquals("package-104a78534b1d53e2c5eb04015e962b178cb77ae0c2407da8de59cfb8ee0ab0eb", GameCloudIdentity.namespace("org.example.game"))
  assertNotEquals(GameCloudIdentity.namespace("org.example.game"), GameCloudIdentity.namespace("org.example.other"))
  assertNotEquals(GameCloudIdentity.namespace("org.example.game"), GameCloudIdentity.namespace("org.example.Game"))
  listOf("", "game", "../org.game", "org.game/other", "org.game\nother", "org." + "a".repeat(256)).forEach {
   assertFailsWith<IllegalArgumentException>(it) { GameCloudIdentity.namespace(it) }
  }
 }
 @Test fun manifestsRequireTheExactNamespaceRegardlessOfPackageOrSourceEvidence() {
  val current = snapshot()
  assertTrue(Shattered.acceptsManifest(current.manifest)) // Raw local capture before annotation.
  assertEquals(spd, ShatteredProfileVersions.annotate(current).manifest.gamePackage)
  for (foreign in listOf(aliased(current),
   aliased(current).let { it.copy(manifest = it.manifest.copy(gamePackage = null)) },
   aliased(current).let { it.copy(manifest = it.manifest.copy(gamePackage = null, gameSource = GameSaveSource(oldOrigin, "b".repeat(64)))) })) {
   assertFalse(Shattered.acceptsManifest(foreign.manifest)); assertFails { Snapshots.verify(foreign, Shattered) }
  }
  for (origin in listOf(oldOrigin, "c".repeat(64))) {
   val withSource = current.copy(manifest = current.manifest.copy(gameSource = GameSaveSource(origin, "d".repeat(64))))
   assertNull(recordedGamePackage(withSource.manifest))
   assertFails { ShatteredProfileVersions.annotate(withSource) }
   val annotated = withSource.copy(manifest = withSource.manifest.copy(gamePackage = spd))
   assertTrue(Shattered.acceptsManifest(annotated.manifest)); Snapshots.verify(annotated, Shattered)
  }
  val wrongPackage = current.copy(manifest = current.manifest.copy(gamePackage = "org.other.game"))
  assertFalse(Shattered.acceptsManifest(wrongPackage.manifest)); assertFails { Snapshots.verify(wrongPackage, Shattered) }
  assertFails { Snapshots.verify(current.copy(manifest = current.manifest.copy(gamePackage = "../invalid")), Shattered) }
 }
 @Test fun adaptersWithAndWithoutPackagesAlwaysRequireTheirOwnNamespace() {
  val strict = Adapter("strict"); val source = snapshot().manifest
  assertTrue(strict.acceptsManifest(source.copy(game = "strict")))
  assertFalse(strict.acceptsManifest(source)); assertFalse(strict.acceptsManifest(source.copy(game = alias, gamePackage = spd)))
  val packageName = "org.example.game"; val adapter = Adapter(GameCloudIdentity.namespace(packageName), packageName)
  val raw = source.copy(game = adapter.id)
  assertTrue(adapter.acceptsManifest(raw))
  assertTrue(adapter.acceptsManifest(raw.copy(gamePackage = packageName)))
  assertFalse(adapter.acceptsManifest(raw.copy(gamePackage = spd)))
  assertFalse(adapter.acceptsManifest(raw.copy(game = alias, gamePackage = packageName)))
 }
 @Test fun currentNamespaceDownloadRestoreAndHistoryPreserveVerifiedBytes() {
  val source = ShatteredProfileVersions.annotate(snapshot()); val remote = Memory(); val current = commit(source); remote.add(current, source)
  val repository = AppendRepository(temp.resolve("repository"), Shattered, profile)
  val downloaded = repository.download(remote, current)
  assertEquals(source.manifest, downloaded.manifest); assertContentEquals(source.zip, downloaded.zip)
  val target = targetJar(); ShatteredProfileVersions.checkRestore(source, target.toString())
  val root = temp.resolve("target-save").createDirectories()
  val session = ProfileSession(AppConfig(saveRoot = root.toString(), profile = profile, targetGame = target.toString()),
   temp.resolve("state"))
  session.remember(downloaded, "verified current snapshot")
  session.restoreHistory(source.manifest.id) { true }
  assertEquals("{\"version\":896,\"turn\":1}", root.resolve("game1/game.dat").readText())
  val newLocal = session.createSnapshot { true }
  assertEquals(Shattered.id, newLocal.manifest.game); assertEquals(spd, newLocal.manifest.gamePackage)
 }
 @Test fun foreignNamespaceParentsStayIncompleteAndCannotBeRemappedForPublication() {
  val oldSnapshot = aliased(); val old = commit(oldSnapshot); val remote = Memory(); remote.add(old, oldSnapshot)
  val childSnapshot = ShatteredProfileVersions.annotate(snapshot())
  val child = commit(childSnapshot, listOf(old.id)); remote.add(child, childSnapshot); remote.listed = listOf(child.id)
  val repository = AppendRepository(temp.resolve("parents"), Shattered, profile)
  val graph = repository.discover(remote)
  assertTrue(graph.commits.isEmpty()); assertEquals("invalid manifest", graph.pending[old.id])
  assertEquals("incomplete ancestry or cycle", graph.pending[child.id]); assertFails { graph.requireSettled() }
  assertFails { repository.prepare(oldSnapshot, listOf(child.id), "restore") }; assertNull(repository.pending())
  assertFails { repository.download(remote, old) }
  val plan = repository.prepare(snapshot(), emptyList())
  assertEquals(Shattered.id, plan.commit.snapshot.game); assertEquals(spd, plan.commit.snapshot.gamePackage)
  remote.events.clear(); assertFails { repository.publish(remote, plan) }
  assertTrue(remote.events.none { it.startsWith("write-") })
 }
 @Test fun currentFrozenPendingRetriesWithoutChangingAnyManifestOrObjectBytes() {
  val directory = temp.resolve("frozen")
  val repository = AppendRepository(directory, Shattered, profile)
  val plan = repository.prepare(snapshot(), emptyList())
  val restarted = AppendRepository(directory, Shattered, profile)
  val pending = assertNotNull(restarted.pending())
  assertEquals(plan.commit, pending.commit); assertContentEquals(plan.manifestBytes, pending.manifestBytes); assertContentEquals(plan.zip, pending.zip)
  val before = storedBytes(directory); val remote = Memory(); restarted.publish(remote, pending)
  assertContentEquals(plan.manifestBytes, remote.commits.getValue(plan.commit.id))
  assertContentEquals(plan.zip, remote.objects.getValue(plan.commit.snapshot.id)); assertEquals(plan.commit, restarted.pending()!!.commit)
  before.forEach { (path, digest) -> assertEquals(digest, hash(directory.resolve(path).readBytes())) }
  remote.events.clear(); restarted.publish(remote, pending)
  assertTrue(remote.events.none { it.startsWith("write-") })
  remote.events.clear()
  assertFails { restarted.publish(remote, pending.copy(commit = pending.commit.copy(snapshot = pending.commit.snapshot.copy(game = alias)))) }
  assertTrue(remote.events.isEmpty())
 }
 @Test fun crossNamespaceFrozenQueueIsRejectedEvenWithTheSamePackageAndRemainsUntouched() {
  val directory = temp.resolve("foreign-frozen")
  val oldRepository = AppendRepository(directory, Adapter(alias, spd), profile)
  val plan = oldRepository.prepare(aliased(), emptyList())
  val before = storedBytes(directory)
  val restarted = AppendRepository(directory, Shattered, profile); val remote = Memory()
  assertFails { restarted.pending() }
  assertFails { restarted.publish(remote, plan) }
  assertFails { restarted.prepare(snapshot(), emptyList()) }
  assertTrue(remote.events.isEmpty()); assertEquals(before, storedBytes(directory))
 }
 @Test fun foreignNamespaceAndDifferentPackageCannotBeRestoredOrRemembered() {
  val root = temp.resolve("unchanged-save").createDirectories()
  val session = ProfileSession(AppConfig(saveRoot = root.toString(), profile = profile), temp.resolve("isolated-state"))
  for (other in listOf(aliased(), snapshot().let { it.copy(manifest = it.manifest.copy(gamePackage = "org.other.game")) })) {
   assertFails { ShatteredProfileVersions.annotate(other) }
   assertFails { ShatteredProfileVersions.checkRestore(other, targetJar().toString()) }
   assertFails { session.remember(other, "foreign snapshot") }
  }
  assertTrue(session.history().isEmpty()); assertEquals(0L, Files.list(root).use { it.count() })
 }
 @Test fun currentScopeUsesItsAdapterIdentityAndDoesNotReuseAnotherNamespace() {
  val root = temp.resolve("scope-save").createDirectories(); val state = temp.resolve("scope-state")
  val config = AppConfig(saveRoot = root.toString(), profile = profile)
  val old = ProfileSession(config, state, Adapter(alias, spd))
  val current = ProfileSession(config, state, Shattered)
  val restarted = ProfileSession(config, state, Shattered)
  val key = hash(listOf("package-current-v1", Shattered.id, root.toRealPath().toString(), config.validated().endpoint, profile).joinToString("\n").encodeToByteArray())
  assertEquals(state.resolve("profiles/$key"), current.directory)
  assertNotEquals(old.directory, current.directory); assertEquals(current.directory, restarted.directory)
 }

 @Test fun noopLineageRequiresExactNamespaceAndUnchangedPackageAndProvenance() {
  val original = snapshot(); val base = commit(original.copy(manifest = original.manifest.copy(gamePackage = spd)))
  fun child(manifest: Manifest) = base.copy(id = id(), parents = listOf(base.id), snapshot = manifest.copy(id = id()))
  fun graph(vararg commits: AppendCommit) = CommitGraph(commits.associateBy { it.id }, emptyMap())
  val same = child(base.snapshot)
  assertEquals(same, unchangedDescendantTip(graph(base, same), base.id, false))
  for (manifest in listOf(base.snapshot.copy(game = alias),
   base.snapshot.copy(gamePackage = null), base.snapshot.copy(gamePackage = "org.other.game"),
   base.snapshot.copy(gameSource = GameSaveSource("a".repeat(64), "b".repeat(64))),
   base.snapshot.copy(version = "new version"))) {
   val changed = child(manifest); assertNull(unchangedDescendantTip(graph(base, changed), base.id, false))
  }
 }
}
