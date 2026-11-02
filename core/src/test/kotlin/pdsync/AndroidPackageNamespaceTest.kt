package pdsync

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import java.io.ByteArrayOutputStream
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pdsync.android.app.*
import kotlin.io.path.*
import kotlin.test.*

/** Current package scopes never adopt or modify earlier test-generation state. */
class AndroidPackageNamespaceTest {
 @TempDir lateinit var temp: Path
 private val packageName = "com.shatteredpixel.shatteredpixeldungeon"
 private val origin = "a".repeat(64)
 private val connection = AndroidConnection("https://dav.example.test/synthetic/", "user", "test-only", "custom-profile")
 private fun game(source: String = origin, pkg: String = packageName) =
  AndroidGameIdentity.forPackage(pkg, "Synthetic game", source)
 private fun gzip(value: String) = ByteArrayOutputStream().also { bytes -> GZIPOutputStream(bytes).use { it.write(value.encodeToByteArray()) } }.toByteArray()
 private fun files(turn: Int) = mapOf("game1/game.dat" to gzip("""{"version":896,"turn":$turn}"""))
 private class Store: AppendStore {
  val commits = linkedMapOf<String, ByteArray>(); val objects = linkedMapOf<String, ByteArray>()
  var failObject = false
  override fun listCommits(cursor: String?) = CommitPage(commits.keys.toList())
  override fun readCommit(id: String) = commits[id]
  override fun readObject(id: String) = objects[id]
  override fun writeObject(id: String, bytes: ByteArray) { if (failObject) error("Synthetic interrupted publication"); objects[id] = bytes.copyOf() }
  override fun writeCommit(id: String, bytes: ByteArray) { commits[id] = bytes.copyOf() }
  override fun legacyPresent() = false
  fun last() = json.decodeFromString<AppendCommit>(commits.values.last().decodeToString())
 }
 private inner class Bridge(var identity: AndroidGameIdentity, var content: Map<String, ByteArray> = files(1)): AndroidGameBridge {
  var failRestore = false
  override fun targetGame() = AndroidGameTarget(identity, "synthetic", 896, "b".repeat(64), "c".repeat(64))
  override fun exportTo(emptyDestination: Path) { content.forEach { (name, bytes) -> emptyDestination.resolve(name).also { it.parent.createDirectories(); it.writeBytes(bytes) } } }
  override fun restoreFrom(preparedDirectory: Path, expectedCurrentFiles: Map<String, String>) {
   check(content.mapValues { hash(it.value) } == expectedCurrentFiles)
   if (failRestore) error("Synthetic restore interruption")
   content = Files.walk(preparedDirectory).use { paths -> paths.filter { Files.isRegularFile(it) }.toList().associate { preparedDirectory.relativize(it).toString() to it.readBytes() } }
  }
 }
 private fun facade(root: Path, bridge: Bridge, store: Store, connection: AndroidConnection = this.connection) =
  AndroidSyncFacade(root, connection, bridge, bridge.identity, store)
 private fun paths(root: Path, name: String) = Files.walk(root).use { paths -> paths.filter { it.fileName.toString() == name }.toList() }
 private fun write(root: Path, name: String, value: String) = root.resolve(name).also { it.parent.createDirectories(); it.writeText(value) }
 private fun fingerprint(root: Path) = Files.walk(root).use { paths ->
  paths.filter { Files.isRegularFile(it) }.toList().associate { root.relativize(it).toString() to hash(it.readBytes()) }
 }
 private fun oldScope(namespace: String) = hash(listOf(AppConfig(endpoint = connection.stateEndpoint, profile = connection.profile, username = connection.username).validated().endpoint,
  connection.profile, connection.username, packageName, origin, namespace).joinToString("\n").encodeToByteArray())
 @Test fun packageAloneDeterminesCloudNamespaceAndSourceRemainsProvenance() {
  val first = game(); val resigned = game("d".repeat(64))
  assertEquals("shattered", first.namespace)
  assertEquals(first.namespace, resigned.namespace)
  assertNotEquals(first.originalSourceIdentity, resigned.originalSourceIdentity)
  assertEquals(first.namespace, AndroidGameAdapter(first).id)
  assertEquals(packageName, AndroidGameAdapter(first).packageName)
  val other = game(pkg = "org.example.other")
  assertEquals(GameCloudIdentity.namespace("org.example.other"), other.namespace)
  assertTrue(other.namespace.startsWith("package-"))
  assertNotEquals(first.namespace, other.namespace)
  assertEquals(other.namespace, game("e".repeat(64), "org.example.other").namespace)
 }
 @Test fun oldBaselinesFrozenQueuesAndWorkspaceBindingsAreNeverAdoptedOrDeleted() {
  val root = temp.resolve("old-state")
  val legacyAccountScope = hash(listOf(connection.endpoint, connection.profile, connection.username).joinToString("\n").encodeToByteArray())
  val oldScopes = listOf("shattered", "android-$origin", "manual-cloud").map(::oldScope) + legacyAccountScope
  val retained = oldScopes.map { scope ->
   root.resolve("connections/$scope").also { workspace ->
    write(workspace, "mirror/game1/game.dat", "old test save")
    write(workspace, "state/profiles/old/append-v2/baseline.txt", "old baseline")
    write(workspace, "state/profiles/old/append-v2/active-plan.txt", "old-pending")
    write(workspace, "state/profiles/old/append-v2/uploads/old-pending.json", "old frozen upload")
   }
  }
  write(root, "legacy-workspaces/${oldScope("shattered")}.json", "old workspace binding")
  val before = fingerprint(root)
  val bridge = Bridge(game()); val store = Store(); val current = facade(root, bridge, store)
  val inspection = current.inspect()
  assertFalse(inspection.isRetryablePending)
  assertEquals("LOCAL_ONLY", inspection.kind)
  assertEquals("UPLOADED", current.autoUpload().status)
  assertTrue(store.last().parents.isEmpty())
  assertEquals("shattered", store.last().snapshot.game)
  assertEquals("custom-profile", store.last().snapshot.profile)
  assertFalse(store.commits.containsKey("old-pending"))
  before.forEach { (name, digest) -> assertEquals(digest, hash(root.resolve(name).readBytes()), name) }
  val currentMirror = paths(root, "mirror").single { it.parent !in retained }
  val expectedScope = hash(listOf(AndroidGameIdentity.LOCAL_STATE_GENERATION,
   AppConfig(endpoint = connection.stateEndpoint, profile = connection.profile, username = connection.username).validated().endpoint,
   connection.profile, connection.username, packageName, origin, "shattered").joinToString("\n").encodeToByteArray())
  assertEquals(expectedScope, currentMirror.parent.fileName.toString())
  assertTrue(facade(root, bridge, store).inspect().canCompleteWithoutTransfer)
 }
 @Test fun currentGenerationBaselineAndFrozenRetrySurviveReconstruction() {
  val root = temp.resolve("current-state"); val bridge = Bridge(game()); val store = Store()
  val first = facade(root, bridge, store)
  assertEquals("UPLOADED", first.autoUpload().status)
  val parent = store.last()
  assertTrue(facade(root, bridge, store).inspect().canCompleteWithoutTransfer)
  bridge.content = files(2)
  val next = facade(root, bridge, store); val inspection = next.inspect()
  store.failObject = true; assertFails { next.upload(inspection) }
  val marker = paths(root, "active-plan.txt").single(); val id = marker.readText()
  val frozen = marker.parent.resolve("uploads/$id.json").readBytes()
  val reopened = facade(root, bridge, store); val retry = reopened.inspect()
  assertTrue(retry.isRetryablePending)
  store.failObject = false; reopened.upload(retry)
  assertEquals(id, store.last().id)
  assertContentEquals(frozen, store.commits.getValue(id))
  assertEquals(listOf(parent.id), store.last().parents)
 }
 @Test fun oldRecoveryIsIgnoredAndUntouchedWhileCurrentRecoveryRemainsRequired() {
  val root = temp.resolve("recovery"); val bridge = Bridge(game(), emptyMap()); val store = Store()
  val oldGlobal = write(root, "android-restore.json", "unsupported old global restore")
  val oldSource = write(root, "restore-journals/$origin.json", "unsupported old source restore")
  val oldBytes = listOf(oldGlobal, oldSource).associateWith { it.readBytes() }
  assertFalse(AndroidSyncFacade.hasUnresolvedRestore(root))
  assertEquals("UPLOADED", facade(temp.resolve("publisher"), Bridge(game()), store).autoUpload().status)
  val current = facade(root, bridge, store); val inspection = current.inspect()
  assertFalse(inspection.needsRestoreRecovery)
  bridge.failRestore = true
  assertFails { current.restoreSelected(inspection, store.last().snapshot.id, false) }
  val journal = root.resolve("restore-journals/${AndroidGameIdentity.LOCAL_STATE_GENERATION}/$origin.json")
  val frozen = journal.readBytes()
  val reopened = facade(root, bridge, store)
  assertTrue(reopened.hasUnresolvedRestore()); assertTrue(reopened.inspect().needsRestoreRecovery)
  assertEquals("SKIPPED_UNCERTAIN", reopened.autoUpload().status)
  assertContentEquals(frozen, journal.readBytes())
  bridge.failRestore = false; reopened.recoverRestore()
  assertFalse(reopened.hasUnresolvedRestore())
  oldBytes.forEach { (file, bytes) -> assertContentEquals(bytes, file.readBytes()) }
 }
 @Test fun defaultAndCustomProfilesRemainActualSelectedProfiles() {
  for (profile in listOf("default", "custom-profile")) {
   val selected = AndroidConnection(connection.endpoint, connection.username, connection.password, profile)
   val store = Store(); val bridge = Bridge(game())
   assertEquals("UPLOADED", facade(temp.resolve(profile), bridge, store, selected).autoUpload().status)
   assertEquals(profile, store.last().snapshot.profile)
  }
 }
}
