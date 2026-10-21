package pdsync

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*

class HashLocalHistoryCompatibilityTest {
 @TempDir lateinit var temp: Path
 private fun session(): ProfileSession {
  val root = temp.resolve("saves").createDirectories()
  root.resolve("game1").createDirectories(); root.resolve("game1/game.dat").writeText("save")
  return ProfileSession(AppConfig(saveRoot=root.toString()),temp.resolve("state"),Shattered,object:ProfileVersions {
   override fun annotate(snapshot:Snapshot)=snapshot
   override fun target(path:String):TargetGame?=null
   override fun checkRestore(snapshot:Snapshot,targetPath:String) {}
  },captureQuietMillis=0)
 }
 private fun snapshot(session:ProfileSession):Snapshot {
  val captured=Snapshots.capture(session.root,Shattered,"demo","test",null)
  return captured.copy(manifest=captured.manifest.copy(id=contentSha256(captured.manifest.files)))
 }
 @Test fun contentAddressedSnapshotCanBeRememberedAndLoaded() {
  val session=session();val source=snapshot(session)
  assertEquals(source.manifest,session.remember(source,"hash").manifest)
  assertContentEquals(source.zip,session.loadSnapshot(source.manifest.id).zip)
  assertEquals(source.manifest.id,session.history().single().manifest.id)
 }
 @Test fun sameContentDifferentMetadataAndZipKeepsVerifiedFirstArchive() {
  val session=session();val first=snapshot(session);session.remember(first,"first")
  val bytes=ByteArrayOutputStream().also { stream -> ZipOutputStream(stream).use { zip ->
   zip.setComment("different encoding");zip.putNextEntry(ZipEntry("game1/game.dat"));zip.write("save".toByteArray());zip.closeEntry()
  } }.toByteArray()
  val second=Snapshot(first.manifest.copy(version="other",sha256=hash(bytes)),bytes)
  assertEquals(first.manifest,session.remember(VerifiedSnapshot.read(second,Shattered),"second").manifest)
  assertContentEquals(first.zip,session.loadSnapshot(first.manifest.id).zip)
 }
 @Test fun badContentAddressIsRejectedBeforeWritingHistory() {
  val session=session();val source=snapshot(session)
  assertFails { session.remember(source.copy(manifest=source.manifest.copy(id="a".repeat(64))),"bad") }
  assertTrue(session.history().isEmpty())
 }
 @Test fun corruptedExistingArchiveCannotWinOrBeOverwritten() {
  val session=session();val source=snapshot(session);session.remember(source,"first")
  val path=session.directory.resolve("history/${source.manifest.id}.zip");path.writeText("corrupt")
  assertFails {session.remember(VerifiedSnapshot.read(source,Shattered),"retry")}
  assertEquals("corrupt",path.readText())
 }
 @Test fun orphanContentAddressedArchiveIsNotOverwritten() {
  val session=session();val source=snapshot(session)
  val path=session.directory.resolve("history/${source.manifest.id}.zip");path.parent.createDirectories();path.writeText("untrusted")
  assertFails {session.remember(source,"retry")};assertEquals("untrusted",path.readText())
 }
 @Test fun legacyUuidArchiveStillRoundTrips() {
  val session=session();val source=Snapshots.capture(session.root,Shattered,"demo","test",null)
  assertTrue(validCommitId(source.manifest.id));session.remember(source,"legacy")
  assertContentEquals(source.zip,session.loadSnapshot(source.manifest.id).zip)
 }
 @Test fun hashAssociationMustMatchContentAndDoesNotBroadenRunIds() {
  val id=contentSha256(mapOf("game1/game.dat" to hash("save".toByteArray())))
  DirectorySyncBaseline("b".repeat(64),id,id).validated()
  assertFails {DirectorySyncBaseline("b".repeat(64),id,"c".repeat(64)).validated()}
  assertFails {RunIdentity(id,id).validated()}
 }
 @Test fun diagnosticsAcceptHashRecordWithoutExposingIt() {
  val id="a".repeat(64)
  val facts=HomeRestoreDiagnosticEvidence.capture(null,null,id,false,emptyMap()).values
  assertEquals(hash(id.toByteArray()),facts["selected.commit_sha256"])
 }
}
