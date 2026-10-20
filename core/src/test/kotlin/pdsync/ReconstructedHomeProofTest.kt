package pdsync

import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** New reconstruction regression tests, not recovered original test sources. */
class ReconstructedHomeProofTest {
 @TempDir lateinit var temp:Path
 private var next=0
 private class Store:AppendStore {
  val commits=linkedMapOf<String,ByteArray>();val objects=linkedMapOf<String,ByteArray>();var gets=0;var puts=0
  override fun listCommits(cursor:String?)=CommitPage(commits.keys.toList())
  override fun readCommit(id:String)=commits[id]?.copyOf()
  override fun readObject(id:String):ByteArray? {gets++;return objects[id]?.copyOf()}
  override fun writeCommit(id:String,bytes:ByteArray){puts++;commits[id]=bytes.copyOf()}
  override fun writeObject(id:String,bytes:ByteArray){puts++;objects[id]=bytes.copyOf()}
  override fun legacyPresent()=false
 }
 private fun session(value:String="same"):AppendProfileSession {
  val root=temp.resolve("r${next++}").createDirectory();root.resolve("game1").createDirectory();root.resolve("game1/game.dat").writeText(value)
  val versions=object:ProfileVersions {
   override fun annotate(snapshot:Snapshot)=snapshot
   override fun target(path:String):TargetGame?=null
   override fun checkRestore(snapshot:Snapshot,targetPath:String){}
  }
  return AppendProfileSession(AppConfig(saveRoot=root.toString()),temp.resolve("s${next++}"),versions=versions,captureQuietMillis=0)
 }
 private fun seeded():Pair<Store,AppendProfileSession>{val store=Store();session().push(store){true};store.gets=0;store.puts=0;return store to session()}
 @Test fun previewHasNoObjectAuthorityThenFullUpgradeProvesIt(){
  val(store,s)=seeded();val preview=s.compareHomeMetadata(store){true};assertEquals(OnboardingKind.IDENTICAL,preview.kind);assertEquals(RemoteVerification.METADATA_ONLY,preview.verification);assertEquals(0,store.gets);assertFalse(preview.canAdoptRemote);assertFalse(AutomaticUploadEligibility.allows(preview))
  val history=s.syncHistory(preview);assertFalse(history.currentProtected);assertTrue(history.verifiedCommitIds.isEmpty())
  val full=s.verifyComparison(preview,store){true};assertEquals(RemoteVerification.OBJECTS_VERIFIED,full.verification);assertTrue(store.gets>0);assertTrue(s.syncHistory(full).currentProtected);assertEquals(0,store.puts);assertNull(s.baseline())
 }
 @Test fun missingObjectCanPreviewButNeverUpgrade(){val(store,s)=seeded();store.objects.clear();val p=s.compareHomeMetadata(store){true};assertEquals(OnboardingKind.IDENTICAL,p.kind);assertFails{s.verifyComparison(p,store){true}};assertEquals(0,store.puts)}
 @Test fun metadataCannotAuthorizeDirectUpload(){val(store,s)=seeded();val p=s.compareHomeMetadata(store){true};assertFails{s.onboardingUpload(p,store){true}};assertEquals(0,store.puts)}
 @Test fun localChangeRejectsOldPreview(){val(store,s)=seeded();val p=s.compareHomeMetadata(store){true};s.root.resolve("game1/game.dat").writeText("changed");assertFails{s.verifyComparison(p,store){true}};assertEquals(0,store.puts)}
 @Test fun observationsBelongToOneSession(){val(store,s)=seeded();val p=s.compareHomeMetadata(store){true};val other=session();assertFails{other.verifyComparison(p,store){true}};assertFails{other.syncHistory(s.syncHistory(p))}}
 @Test fun failedPreviewNeverClaimsVerification(){val(_,s)=seeded();val bad=object:AppendStore by Store(){override fun listCommits(cursor:String?):CommitPage=error("offline")};val p=s.compareHomeMetadata(bad){true};assertEquals(OnboardingKind.BLOCKED,p.kind);assertEquals(RemoteVerification.METADATA_ONLY,p.verification);assertFalse(AutomaticUploadEligibility.allows(p))}
}
