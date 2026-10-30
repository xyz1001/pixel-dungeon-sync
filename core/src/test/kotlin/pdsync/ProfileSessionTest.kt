package pdsync

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class ProfileSessionTest {
 private class Memory : Store {
  var current: RemoteHead? = null
  val data = mutableMapOf<String, Snapshot>()
  var fail = false
  var beforePublish: (() -> Unit)? = null
  override fun head() = current
  override fun upload(snapshot: Snapshot) { if (fail) error("interrupted"); data[snapshot.manifest.id] = snapshot }
  override fun download(manifest: Manifest) = data.getValue(manifest.id)
  override fun publish(manifest: Manifest, expected: RemoteHead?) {
   beforePublish?.invoke()
   check(current == expected) { "412" }
   current = RemoteHead(manifest, "\"${manifest.id}\"")
  }
 }
 private fun root(value: String = "alive") = Files.createTempDirectory("profile-save").also {
  it.resolve("game1").createDirectories(); it.resolve("game1/game.dat").writeText(value)
  it.resolve("game1/depth1.dat").writeText("floor-$value")
  it.resolve("rankings.dat").writeText("rank-$value"); it.resolve("badges.dat").writeText("badges-$value")
  it.resolve("journal.dat").writeText("journal-$value"); it.resolve("settings.xml").writeText("device"); it.resolve("bones.dat").writeText("excluded-bones")
 }
 private fun session(root: java.nio.file.Path = root(), state: java.nio.file.Path = Files.createTempDirectory("profile-state"), endpoint: String = "http://127.0.0.1:8765", version: String = "v1") =
  ProfileSession(AppConfig(root.toString(), endpoint, "demo"), state, Shattered, object : ProfileVersions {
   override fun annotate(snapshot: Snapshot) = snapshot.copy(manifest = snapshot.manifest.copy(version = version))
   override fun target(path: String): TargetGame? = null
   override fun checkRestore(snapshot: Snapshot, targetPath: String) { require(snapshot.manifest.version == version) }
  }, captureQuietMillis = 0)
 private fun divergent(): Triple<ProfileSession, Memory, PreservedConflict> {
  val store = Memory(); session(root("remote")).push(store) { true }
  val local = session(root("local"))
  val conflict = (local.push(store) { true } as PushResult.Diverged).conflict
  return Triple(local, store, conflict)
 }
 @Test fun configPersistsOnlyEnumeratedNonsecretFields() {
  val path = Files.createTempDirectory("config").resolve("config.json")
  val config = AppConfig("/tmp/save", "https://example.com/dav/", "demo", "v1")
  ConfigFile(path).save(config)
  assertEquals(config.copy(saveRoot = "", endpoint = "https://example.com/dav", version = null), ConfigFile(path).load())
  val text = path.readText(); assertFalse(text.contains("password")); assertFalse(text.contains("username"))
  assertFails { ConfigFile(path).save(config.copy(endpoint = "https://secret:password@example.com")) }
 }
 @Test fun configRejectsUnsafeUrlsProfilesAndVersions() {
  listOf("http://evil.test", "https://example.test/?token=x", "https://example.test/#x").forEach { assertFails { AppConfig(endpoint = it).validated() } }
  assertFails { AppConfig(profile = "../demo").validated() }; assertNull(AppConfig(version = "manually-forged").validated().version)
 }
 @Test fun api26Utf8DecoderPreservesUnicodeAndPlusWithoutRelaxingEndpointChecks() {
  val encoded = "https://example.test/%E5%AD%98%E6%A1%A3+a%2Bb"
  assertEquals(encoded, AppConfig(endpoint = encoded).validated().endpoint)
  assertEquals("https://example.test/%E5%AD%98%E6%A1%A3+a+b", AppConfig(endpoint = "https://example.test/存档+a+b").validated().endpoint)
  listOf("%2e", "%2e%2e", "%2F", "%5c", "%252e", "%00", "%ZZ", "%").forEach { segment ->
   assertFailsWith<IllegalArgumentException>(segment) { AppConfig(endpoint = "https://example.test/dav/$segment").validated() }
  }
 }
 @Test fun unsafeAndOverlappingRootsRejected() {
  val save = root()
  assertFails { session(save, save.resolve("state")) }
  assertFails { session(Path.of("/")) }
  val alias = Files.createTempDirectory("link-holder").resolve("link")
  Files.createSymbolicLink(alias, save)
  assertFails { session(alias) }
  assertFails { session(alias.resolve("game1")) }
 }
 @Test fun physicalContainmentRejectsMissingDescendantsBeforeCreatingThem() {
  val save=root();val missing=save.resolve("not-created/nested")
  assertFalse(Files.exists(missing))
  assertFailsWith<IllegalArgumentException> {SafePaths.disjoint(save.toRealPath(),missing)}
  assertFailsWith<IllegalArgumentException> {SafePaths.disjoint(missing,save.toRealPath())}
  assertFalse(Files.exists(save.resolve("not-created")))
  val sibling=save.resolveSibling(save.fileName.toString()+"-separate")
  SafePaths.disjoint(save,sibling)
  assertFalse(Files.exists(sibling))
 }
 @Test fun unrecognizedRootRejected() { val path = Files.createTempDirectory("wrong-root"); path.resolve("unrelated.txt").writeText("no saves"); assertFails { session(path) } }
 @Test fun emptyRootCanReceiveButCannotPublish() {
  val target = session(Files.createTempDirectory("empty-save"))
  assertFails { target.createSnapshot { true } }
  assertFails { target.push(Memory()) { true } }
  val store = Memory(); val source = session(); source.push(store) { true }
  val remote = target.readRemote(store)!!
  target.restoreRemote(remote.manifest.id, store) { true }
  assertEquals("alive", target.root.resolve("game1/game.dat").readText())
  assertTrue(target.push(store) { true } is PushResult.Success)
 }
 @Test fun historyAndBackupRestoreWholeProfileWithoutSettings() {
  val save = root(); val manager = session(save)
  val first = manager.createSnapshot { true }
  save.resolve("game1/game.dat").writeText("new"); save.resolve("rankings.dat").writeText("new-rank")
  save.resolve("game2").createDirectories(); save.resolve("game2/game.dat").writeText("second run")
  save.resolve("settings.xml").writeText("new device settings")
  val restored = manager.restoreHistory(first.manifest.id) { true }
  assertEquals("alive", save.resolve("game1/game.dat").readText()); assertEquals("rank-alive", save.resolve("rankings.dat").readText())
  assertFalse(save.resolve("game2").exists()); assertEquals("new device settings", save.resolve("settings.xml").readText())
  assertEquals("excluded-bones", save.resolve("bones.dat").readText()); assertFalse("bones.dat" in first.manifest.files)
  assertFalse(manager.directory.resolve("backups").exists()); assertEquals(1, manager.history().size)
 }
 @Test fun restartKeepsHistoryPendingAndFrozenBytes() {
  val save = root(); val state = Files.createTempDirectory("state"); val manager = session(save, state); val store = Memory(); store.fail = true
  assertFails { manager.push(store) { true } }; assertTrue(manager.hasPending())
  val original = manager.history().single().manifest
  save.resolve("game1/game.dat").writeText("newer")
  val restarted = session(save, state); store.fail = false
  val result = restarted.push(store) { true } as PushResult.Success
  assertEquals(original.id, result.manifest.id); assertFalse(restarted.hasPending())
  assertEquals("alive", Snapshots.verify(store.data.getValue(original.id), Shattered).getValue("game1/game.dat").decodeToString())
 }
 @Test fun independentEndpointAndRootScopesDoNotReuseHead() {
  val root = root(); val state = Files.createTempDirectory("scopes"); val first = session(root, state); first.push(Memory()) { true }
  assertTrue(session(root, state, "http://localhost:9999").history().isEmpty())
  assertTrue(session(root("other"), state).history().isEmpty())
  assertEquals(1, session(root, state).history().size)
 }
 @Test fun runningBooleanDoesNotBlockSnapshotUploadOrRestore() {
  val manager = session(); val store = Memory()
  val snapshot = manager.createSnapshot { false }
  assertNotNull(manager.push(store) { false })
  assertNotNull(manager.restoreHistory(snapshot.manifest.id) { false })
  assertEquals("alive", manager.root.resolve("game1/game.dat").readText()); assertNotNull(store.current)
 }
 @Test fun contextAuthorizationFailureStillRejectsBeforePublication() {
  val manager = session(); val store = Memory()
  assertFailsWith<SecurityException> { manager.push(store) { throw SecurityException("identity changed") } }
  assertNull(store.current); assertFalse(manager.hasPending())
 }
 @Test fun mutationAfterBackupCancelsWithoutStaleRollback() {
  val source = root("old"); val target = root("before"); var calls = 0
  val snapshot = Snapshots.capture(source, Shattered, "demo", "v1", null)
  assertFails { Snapshots.restore(snapshot, target, Files.createTempDirectory("backup"), Shattered, "v1", {
   calls++; if (calls == 2) target.resolve("game1/game.dat").writeText("external-new"); true
  }) }
  assertEquals("external-new", target.resolve("game1/game.dat").readText())
 }
 @Test fun legacyBackupCallbacksAreNeverInvokedAndNoBackupIsCreated() {
  val target = root("current"); val snapshot = Snapshots.capture(root("old"), Shattered, "demo", "v1", null)
  val backupRoot=Files.createTempDirectory("unused-backup").resolve("absent")
  Snapshots.restore(snapshot,target,backupRoot,Shattered,"v1",{true},
   onBackupReady={_,_->error("must not register backup")}, captureBackup={error("must not capture backup")})
  assertFalse(backupRoot.exists()); assertEquals("old",target.resolve("game1/game.dat").readText())
 }
 @Test fun conflictPreservesBothAndLocalChoiceCreatesChild() {
  val (manager, store, conflict) = divergent()
  assertEquals(setOf(conflict.local.manifest.id, conflict.remote.manifest.id), manager.history().map { it.manifest.id }.toSet())
  val result = manager.chooseLocal(conflict, store) { true } as PushResult.Success
  assertEquals(conflict.remote.manifest.id, result.manifest.parent)
  assertEquals(conflict.local.manifest.files, result.manifest.files)
  assertEquals(3, manager.history().size); assertFalse(manager.hasPending())
  assertEquals("local", manager.root.resolve("game1/game.dat").readText())
 }
 @Test fun remoteChoiceBacksUpActualLatestLocalAndPreservesBranches() {
  val (manager, store, conflict) = divergent()
  manager.root.resolve("game1/game.dat").writeText("local-newer-than-conflict")
  val result = manager.chooseRemote(conflict, store) { true }.first!!
  assertEquals("remote", manager.root.resolve("game1/game.dat").readText())
  assertFalse(manager.directory.resolve("backups").exists())
  assertTrue(manager.history().none { it.manifest.files["game1/game.dat"] == hash("local-newer-than-conflict".encodeToByteArray()) })
  assertTrue(manager.history().map { it.manifest.id }.containsAll(listOf(conflict.local.manifest.id, conflict.remote.manifest.id)))
  assertTrue(manager.push(store) { true } is PushResult.Success)
 }
 @Test fun remoteChangingDuringChoiceDoesNotRestoreStaleSnapshot() {
  val (manager, store, conflict) = divergent()
  val newer = Snapshots.capture(root("remote-new"), Shattered, "demo", "v1", conflict.remote.manifest.id)
  Sync(store).push(newer)
  val result = manager.chooseRemote(conflict, store) { true }
  assertNull(result.first); assertEquals(newer.manifest.id, result.second!!.conflict.remote.manifest.id)
  assertEquals("local", manager.root.resolve("game1/game.dat").readText())
 }
 @Test fun concurrentPublishDoesNotOverwriteRemote() {
  val manager = session(); val store = Memory()
  val external = Snapshots.capture(root("external"), Shattered, "demo", "v1", null)
  store.beforePublish = { store.current = RemoteHead(external.manifest, "\"external\""); store.data[external.manifest.id] = external }
  assertFails { manager.push(store) { true } }
  assertEquals(external.manifest.id, store.current!!.manifest.id); assertTrue(manager.hasPending())
 }
 @Test fun explicitArchiveRetainsSnapshotAndUnblocksRestore() {
  val manager = session(); val store = Memory(); store.fail = true
  assertFails { manager.push(store) { true } }
  val id = manager.history().single().manifest.id
  assertFails { manager.restoreHistory(id) { true } }
  manager.archivePending(); assertFalse(manager.hasPending()); assertEquals(id, manager.history().single().manifest.id)
  manager.restoreHistory(id) { true }
 }
 @Test fun corruptHistoryAndIncompatibleVersionsNeverRestore() {
  val manager = session(); val entry = manager.createSnapshot { true }
  manager.directory.resolve("history/${entry.manifest.id}.zip").writeText("corrupt")
  assertFails { manager.restoreHistory(entry.manifest.id) { true } }; assertEquals("alive", manager.root.resolve("game1/game.dat").readText())
  val store = Memory(); session(version = "v2").push(store) { true }
  val target = session(); val remote = target.readRemote(store)!!
  assertFails { target.restoreRemote(remote.manifest.id, store) { true } }
 }
 @Test fun staleReadRemoteRequiresAnotherRead() {
  val source = session(); val store = Memory(); source.push(store) { true }
  val target = session(root("local")); val old = target.readRemote(store)!!
  source.root.resolve("game1/game.dat").writeText("new"); source.push(store) { true }
  assertFails { target.restoreRemote(old.manifest.id, store) { true } }
  assertEquals("local", target.root.resolve("game1/game.dat").readText())
 }
}
