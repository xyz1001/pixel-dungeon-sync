package pdsync

import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Local snapshots use the same complete content identity as schema-3 records. */
class ExistingUploadContentTest {
 @TempDir lateinit var temp:Path
 private var sequence=0
 private val versions=object:ProfileVersions {
  override fun annotate(snapshot:Snapshot)=snapshot.copy(manifest=snapshot.manifest.copy(version="v1"))
  override fun target(path:String):TargetGame?=null
  override fun checkRestore(snapshot:Snapshot,targetPath:String)=Unit
 }
 private fun session(content:String="A"):AppendProfileSession {
  val id=sequence++;val root=temp.resolve("root-$id").createDirectory()
  root.resolve("game1").createDirectory().resolve("game.dat").writeText(content)
  return AppendProfileSession(AppConfig(saveRoot=root.toString(),profile="reuse"),temp.resolve("state-$id"),versions=versions,captureQuietMillis=0)
 }
 private fun edit(session:AppendProfileSession,content:String)=session.root.resolve("game1/game.dat").writeText(content)
 @Test fun localSnapshotsUseContentIdentityWithoutGeneratingDuplicateHistory() {
  val session=session()
  val first=session.createSnapshot {true}
  assertEquals(contentSha256(first.manifest.files),first.manifest.id);assertNull(first.manifest.parent)
  session.renameCurrent("display name does not change identity"){true}
  val second=session.createSnapshot {true}
  assertEquals(first.manifest.id,second.manifest.id)
  assertEquals(1,session.history().size)
  edit(session,"new local content")
  val changed=session.createSnapshot {true}
  assertEquals(contentSha256(changed.manifest.files),changed.manifest.id)
  assertNotEquals(first.manifest.id,changed.manifest.id);assertNull(changed.manifest.parent)
  assertEquals(2,session.history().size)
 }
}
