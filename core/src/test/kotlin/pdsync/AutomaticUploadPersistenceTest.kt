package pdsync

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.SocketException
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class AutomaticUploadPersistenceTest {
 @TempDir lateinit var temp: Path
 private val versions = object: ProfileVersions {
  override fun annotate(snapshot: Snapshot) = snapshot
  override fun target(path: String): TargetGame? = null
  override fun checkRestore(snapshot: Snapshot, targetPath: String) = Unit
 }
 private class Memory: AppendStore {
  var offline = true
  val commits=mutableMapOf<String,ByteArray>(); val objects=mutableMapOf<String,ByteArray>()
  override fun legacyPresent(): Boolean = error("Removed legacy probe must not be called")
  override fun listCommits(cursor:String?):CommitPage { if(offline) throw SocketException("offline"); return CommitPage(commits.keys.toList()) }
  override fun readCommit(id:String)=commits[id]
  override fun readObject(id:String)=objects[id]
  override fun writeCommit(id:String,bytes:ByteArray) { commits[id]=bytes }
  override fun writeObject(id:String,bytes:ByteArray) { objects[id]=bytes }
 }
 private fun config(): AppConfig {
  val root=temp.resolve("save").createDirectories(); root.resolve("game1").createDirectories()
  if(!root.resolve("game1/game.dat").exists()) root.resolve("game1/game.dat").writeText("original")
  return AppConfig(saveRoot=root.toString())
 }
 private fun session()=AppendProfileSession(config(),temp.resolve("state"),versions=versions,captureQuietMillis=0)
 @Test fun `first discovery failure persists exact snapshot and restart retries it`() {
  val a=session(); val store=Memory()
  assertFailsWith<SocketException> { a.push(store) { true } }
  assertTrue(a.retryablePending()); val repository=AppendRepository(a.directory.resolve("append-v2"),Shattered,"demo"); val frozen=repository.pending()!!
  a.root.resolve("game1/game.dat").writeText("later")
  val restarted=session(); assertTrue(restarted.retryablePending()); store.offline=false
  val result=assertIs<AppendPushResult.Success>(restarted.push(store) { true })
  assertEquals(frozen.commit,result.commit); assertContentEquals(frozen.zip,store.objects[frozen.commit.snapshot.id]); assertContentEquals(frozen.manifestBytes,store.commits[frozen.commit.id]); assertTrue(result.localDirty)
  assertEquals(frozen.commit.snapshot.files,restarted.baselineFiles()); assertFalse(restarted.retryablePending())
 }
 @Test fun `running status does not suppress an offline frozen upload`() {
  val a=session(); val store=Memory(); assertFailsWith<SocketException> { a.push(store) { false } }
  assertTrue(a.hasPending()); assertTrue(a.history().isNotEmpty()); assertTrue(store.commits.isEmpty())
 }
 @Test fun `interrupted restore marker excludes background retry`() {
  val a=session(); assertFails { a.push(Memory()) { true } }; assertTrue(a.retryablePending())
  assertTrue(a.retryablePendingSourceAvailable())
  a.directory.resolve("append-v2/apply-required.txt").writeText("restore")
  assertTrue(a.hasPending()); assertFalse(a.retryablePending())
  assertFalse(a.retryablePendingSourceAvailable())
 }
 @Test fun `verified frozen retry remains available after live save disappears`() {
  val a=session(); val store=Memory()
  assertFalse(a.retryablePendingSourceAvailable())
  assertFailsWith<SocketException> { a.push(store) { true } }
  val repository=AppendRepository(a.directory.resolve("append-v2"),Shattered,"demo")
  val frozen=repository.pending()!!
  a.root.resolve("game1/game.dat").deleteExisting()
  assertTrue(a.syncHistory().currentFiles.isEmpty())
  assertTrue(a.retryablePendingSourceAvailable())
  store.offline=false
  assertEquals(frozen.commit, assertIs<AppendPushResult.Success>(a.push(store) { true }).commit)
  assertFalse(a.retryablePendingSourceAvailable())
 }
 @Test fun `corrupt frozen bytes cannot qualify as an upload source`() {
  val a=session(); assertFails { a.push(Memory()) { true } }
  val repository=AppendRepository(a.directory.resolve("append-v2"),Shattered,"demo")
  val frozen=repository.pending()!!
  a.directory.resolve("append-v2/uploads/${frozen.commit.id}.zip").writeBytes(byteArrayOf(1,2,3))
  assertFails { a.retryablePendingSourceAvailable() }
 }
 @Test fun `empty frozen plan is rejected even with a nonempty live source`() {
  val a=session()
  val empty=temp.resolve("empty").createDirectory()
  val repository=AppendRepository(a.directory.resolve("append-v2"),Shattered,"demo")
  assertFailsWith<IllegalArgumentException> {
   repository.prepare(Snapshots.capture(empty,Shattered,"demo","test",null),emptyList())
  }
  assertFalse(a.hasPending()); assertFalse(a.retryablePending())
  assertTrue(a.observedFiles().isNotEmpty())
  assertFalse(a.retryablePendingSourceAvailable(), "retry uses the frozen plan, never the current live content")
 }
 @Test fun `pause preference round trips without secrets`() {
  val file=ConfigFile(temp.resolve("config.json")); file.save(AppConfig(autoUploadPaused=true))
  assertTrue(file.load()!!.autoUploadPaused); file.save(file.load()!!.copy(autoUploadPaused=false)); assertFalse(file.load()!!.autoUploadPaused)
 }
}
