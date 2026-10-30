package pdsync

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class DesktopHistoryRefreshWiringTest {
 @TempDir lateinit var temp: Path

 private class ReadCheckedStore : SimpleSaveStore {
  val nodes=linkedMapOf<String,SaveNode>();val objects=linkedMapOf<String,ByteArray>()
  var tip:String?=null;var writes=0;var objectReads=0;var failReads=false
  override fun latest():String? {check(!failReads){"history unavailable"};return tip}
  override fun node(hashId:String)=nodes[hashId]
  override fun objectBytes(hashId:String):ByteArray {objectReads++;return objects.getValue(hashId)}
  override fun createObject(hashId:String,bytes:ByteArray):Boolean {writes++;if(hashId in objects)return false;objects[hashId]=bytes;return true}
  override fun createNode(node:SaveNode):Boolean {writes++;if(node.hashId in nodes)return false;nodes[node.hashId]=node;return true}
  override fun setLatest(hashId:String) {writes++;tip=hashId}
 }
 private val versions = object : ProfileVersions {
  override fun annotate(snapshot: Snapshot) = snapshot.copy(manifest = snapshot.manifest.copy(version = "v1"))
  override fun target(path: String): TargetGame? = null
  override fun checkRestore(snapshot: Snapshot, targetPath: String) = Unit
 }
 private fun bytes(root: Path): Map<String, List<Byte>> = Files.walk(root).use { paths ->
  paths.filter { Files.isRegularFile(it) }.toList().associate {
   root.relativize(it).toString() to Files.readAllBytes(it).toList()
  }
 }

 @Test fun explicitHostCallbackUsesGuardedLocalAndCloudReadWithoutAnyTransferOrProvisioning() {
  val main = Files.readString(Path.of("src/main/kotlin/pdsync/Main.kt"))
  val callback = main.substringAfter("override fun refreshHistory() {").substringBefore("override fun upload()")
  assertContains(callback, "if (!homeState().refreshHistoryEnabled) return")
  assertContains(callback, "readRemote(refreshLocal = true)")
  val state = main.substringAfter("fun homeState()").substringBefore("fun reconcileHomeSelection")
  assertContains(state, "syncAccess.hasKnownAccess(config) && renewalReads.allowsManualRefresh(config)")
  val remote = main.substringAfter("fun readRemote(").substringBefore("fun hasRemoteBranches()")
  assertContains(remote, "if (refreshLocal && !homeState().refreshHistoryEnabled) return")
  assertContains(remote, "renewalReads.manualRequest(config) ?: return")
  assertContains(remote, "renewalReads.takeForHome(config, home = true, ready = true)")
  assertContains(remote, "if (previousManager == null && !reopenLocal) return")
  assertContains(remote, "homeSelectionGeneration == selectionGeneration")
  assertContains(remote, "renewalReads.isManualCurrent(manualRefresh, config)")
  assertContains(remote, "refreshProfile(readConfig, canPublish = ::currentPage)")
  assertContains(remote, "manager.readHomeHistory(syncAccess.store(manager.config, manager.adapter.id, secret), fullHistory=refreshLocal)")
  assertContains(remote, "if (!localHistoryVerified) return@launch")
  assertTrue(remote.indexOf("busy = true") < remote.indexOf("scope.launch"))
  assertTrue(remote.indexOf("refreshProfile(readConfig,") < remote.indexOf("manager.readHomeHistory("))
  assertEquals(2, Regex("automatic\\.skipped\\(\\)").findAll(remote).count(),
   "both the retained and reopened automatic policies must suppress old upload work")
  assertContains(remote, "catch (e: CancellationException) { throw e }")
  assertContains(remote, "currentPage() && session === readManager")
  assertContains(remote, "finally { archiveReads.end(read); busy = false }")
  for (forbidden in listOf("ensureNamespace", "prepareSelectedDirectory = true", "execute(", ".push(",
   ".restore", "hasPending()", "uploadSourceAvailable")) assertFalse(remote.contains(forbidden), forbidden)
  val profile = main.substringAfter("suspend fun refreshProfile(").substringBefore("fun loadProfile(")
  assertContains(profile, "config == value && canPublish()")
  assertTrue(profile.indexOf("if (!current()) return") < profile.indexOf("archiveReads.begin()"))
 }

 @Test fun reopenedEmptyAndPopulatedDirectoriesReadCloudHistoryWithoutWritingSavesOrPublishing() {
  val remoteRoot=temp.resolve("remote-fixture").createDirectory()
  remoteRoot.resolve("game1").createDirectory()
  remoteRoot.resolve("game1/game.dat").writeText("""{"version":896,"init_ver":896,"turn":1}""")
  val store=ReadCheckedStore()
  val snapshot=Snapshots.capture(remoteRoot,Shattered,"demo","896",null)
  SimpleSaveSync(store,temp.resolve("remote-baseline")).upload({}, {SaveUpload(snapshot.manifest.files,snapshot.zip,"cloud")})
  val cloudBefore=store.nodes.toMap() to store.objects.mapValues {it.value.toList()}
  store.writes=0
  // Inject only the transport: exercise the production desktop current-layout adapter.
  val unusedTransport=AppendOnlyWebDav("http://127.0.0.1:1","shattered","demo","","")
  for(populated in listOf(false,true)) {
   val root=temp.resolve("local-$populated").createDirectory()
   if(populated) {
    root.resolve("game1").createDirectory()
    root.resolve("game1/game.dat").writeText("""{"version":896,"init_ver":896,"turn":2}""")
   }
   val owner=AppConfig(selectedSaveRoot=root.toString(),saveRoot=root.toString(),username="account",onboardingProgress=OnboardingProgress.COMPLETE)
   val before=bytes(root)
   repeat(2) {
    assertEquals(root,DefaultProfileLocation().selected(owner))
    val manager=DesktopSimpleSession(owner,temp.resolve("local-state-$populated"),versions=versions,suppliedStore=store)
    manager.currentVersions();assertNull(manager.syncHistory().baseline)
    val preview=manager.readHomeHistory(unusedTransport,fullHistory=true)
    assertEquals(1,preview.simple!!.nodes.size)
    assertEquals(store.tip,preview.simple!!.latest)
    assertTrue(preview.graph.commits.isEmpty(),"current layout does not manufacture a legacy DAG")
    assertTrue(manager.history().isEmpty(),"metadata read must not remember a ZIP")
    assertEquals(before,bytes(root),"reading must not restore or create any local save")
    assertNull(preview.baseline,"reading never establishes sync ancestry")
    assertFalse(manager.hasPending(),"reading never creates an upload plan")
    store.failReads=true
    assertFailsWith<IllegalStateException>{manager.readHomeHistory(unusedTransport,true)}
    store.failReads=false
    assertEquals(before,bytes(root))
   }
  }
  assertEquals(0,store.writes);assertEquals(0,store.objectReads)
  assertEquals(cloudBefore,store.nodes.toMap() to store.objects.mapValues {it.value.toList()})
 }

 @Test fun missingDirectoryReadNeverPreparesIt() {
  val root = temp.resolve("not-created")
  val owner = AppConfig(selectedSaveRoot = root.toString(), onboardingProgress = OnboardingProgress.COMPLETE)
  assertFailsWith<SelectedSaveDirectoryMissing> { DefaultProfileLocation().selected(owner) }
  assertFalse(root.exists())
 }
}
