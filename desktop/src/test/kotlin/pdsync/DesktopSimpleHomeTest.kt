package pdsync

import io.github.xyz1001.pdsync.android.SharedGameIdentity
import org.junit.jupiter.api.Test
import kotlin.test.*

class DesktopSimpleHomeTest {
 private val config=AppConfig(username="test",profile="default",onboardingProgress=OnboardingProgress.COMPLETE)
 private val a="a".repeat(64);private val b="b".repeat(64)
 private val files=mapOf("game1/game.dat" to "c".repeat(64))
 private fun history(current:String=contentSha256(files))=SyncHistory(CommitGraph(emptyMap(),emptyMap()),a,files,"",null,
  mapOf(a to "A",b to "B"),simple=SimpleHistory(listOf(SaveNode(b,a,"B"),SaveNode(a,null,"A")),b,current))
 private fun context(history:SyncHistory,selected:String=a)=DesktopHomeContext(
  config,true,true,false,false,false,true,GameProcessStatus.Stopped,false,false,false,setOf(b),selected,true,null,
  history,0,game=SharedGameIdentity("Shattered","3.3.8"),historyOwner=config,historyVerified=true,localHistoryVerified=true)

 @Test fun currentProtocolHistoryOffersUploadAndHistoricalRestoreWithoutLegacyGraph() {
  val history=history();val state=DesktopSharedHomeAdapter.state(context(history))
  assertTrue(state.canUpload);assertTrue(state.canRestore)
  assertEquals(a,state.restoreTarget!!.snapshotId)
  assertTrue(desktopHasExplicitCloudRestoreSelection(history,a,true))
  assertEquals(DesktopHomeSelection(a,true),desktopHomeSelection(history,a,true))
  assertFalse(desktopHasExplicitCloudRestoreSelection(history,"d".repeat(64),true))
 }
 @Test fun identicalCurrentContentDisablesOnlyRedundantTransfers() {
  val state=DesktopSharedHomeAdapter.state(context(history(b),b))
  assertFalse(state.canUpload);assertFalse(state.canRestore)
  val older=DesktopSharedHomeAdapter.state(context(history(b),a))
  assertTrue(older.canRestore)
 }
 @Test fun accountChangeDoesNotRetainHistoryAuthority() {
  val state=DesktopSharedHomeAdapter.state(context(history()).copy(config=config.copy(username="other")))
  assertFalse(state.canUpload);assertFalse(state.canRestore)
 }
}
