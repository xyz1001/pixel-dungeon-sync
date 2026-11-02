package pdsync

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.io.path.*
import kotlin.test.*

class DetachedHomeReadTest {
 @TempDir lateinit var temp:Path
 private val policy=object:ProfileVersions {
  override fun annotate(snapshot:Snapshot)=snapshot
  override fun target(path:String):TargetGame?=null
  override fun checkRestore(snapshot:Snapshot,targetPath:String) {}
 }
 private class Store(val commit:AppendCommit,val bytes:ByteArray):AppendStore {
  var objectReads=0
  override fun listCommits(cursor:String?)=CommitPage(listOf(commit.id))
  override fun readCommit(id:String)=bytes.takeIf {id==commit.id}
  override fun readObject(id:String):ByteArray? {objectReads++;error("HOME requested an object")}
  override fun writeCommit(id:String,bytes:ByteArray)=error("HOME wrote a commit")
  override fun writeObject(id:String,bytes:ByteArray)=error("HOME wrote an object")
  override fun legacyPresent()=false
 }
 private fun fixture():Pair<AppendProfileSession,Store> {
  val root=temp.resolve("save").createDirectories()
  root.resolve("game1").createDirectories().resolve("game.dat").writeText("""{"version":896}""")
  val snapshot=Snapshots.capture(root,Shattered,"test","test",null)
  val commit=AppendCommit(id=UUID.randomUUID().toString(),parents=emptyList(),snapshot=snapshot.manifest,objectBytes=snapshot.zip.size)
  val session=AppendProfileSession(AppConfig(saveRoot=root.toString(),endpoint="https://dav.test/",profile="test"),temp.resolve("state"),Shattered,policy)
  return session to Store(commit,json.encodeToString(commit).encodeToByteArray())
 }
 private fun tree()=Files.walk(temp).use {paths->paths.filter(Files::isRegularFile).toList().associate {temp.relativize(it).toString() to hash(it.readBytes())}}

 @Test fun networkPhaseIsDetachedAndAcceptOnlyMergesMetadataEvidence() {
  val (session,store)=fixture()
  val files=store.commit.snapshot.files
  val context=session.beginHomeRead(files,SaveSummary(mapOf("game1" to null),null,1))
  val before=tree()
  val result=session.readHome(context,store,MetadataReadCancellation())
  assertEquals(before,tree());assertEquals(0,store.objectReads)
  val (comparison,history)=assertNotNull(session.acceptHome(result))
  assertEquals(OnboardingKind.IDENTICAL,comparison.kind)
  assertEquals(LocalObservationSource.BRIDGE_INVENTORY,comparison.localSource)
  assertNull(comparison.fingerprint);assertEquals(RemoteVerification.METADATA_ONLY,comparison.verification)
  assertFalse(history.currentProtected);assertNull(session.observedAt(store.commit.id));assertNull(session.baseline())
  assertTrue(Files.exists(session.directory.resolve("append-v2/known/${store.commit.id}.json")))
 }

 @Test fun changedLocalControlStateRejectsWithoutMergingReadEvidence() {
  val (session,store)=fixture()
  val context=session.beginHomeRead(store.commit.snapshot.files,null)
  val result=session.readHome(context,store,MetadataReadCancellation())
  session.renameCurrent("changed") {true}
  val before=tree()
  assertNull(session.acceptHome(result));assertEquals(before,tree())
  assertFalse(Files.exists(session.directory.resolve("append-v2/known/${store.commit.id}.json")))
 }

 @Test fun cancelledReadAndForeignContextCannotPublishEvidence() {
  val (session,store)=fixture()
  val context=session.beginHomeRead(store.commit.snapshot.files,null)
  val before=tree()
  val cancelled=MetadataReadCancellation().also {it.cancel()}
  assertFailsWith<java.util.concurrent.CancellationException> {session.readHome(context,store,cancelled)}
  assertEquals(before,tree())
 }
}
