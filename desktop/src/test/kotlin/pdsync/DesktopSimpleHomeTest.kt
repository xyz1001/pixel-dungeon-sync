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
 @Test fun successfulEmptyCloudReadReplacesUnknownStatusWithoutDisablingUpload() {
  val empty=SyncHistory(CommitGraph(emptyMap(),emptyMap()),null,files,"",null,emptyMap(),
   simple=SimpleHistory(emptyList(),null,contentSha256(files)))
  val ready=context(empty).copy(remoteTips=emptySet(),selectedId=null,selectedByUser=false,
   automaticStatus=desktopAutomaticStatusAfterCloudRead(false,false))
  val state=DesktopSharedHomeAdapter.state(ready)
  assertTrue(state.canUpload);assertFalse(state.canRestore)
  assertEquals(pdsync.i18n.DesktopMessages.AUTOMATIC_UPLOAD_ENABLED.text(),state.status)
  val unknown=DesktopSharedHomeAdapter.state(ready.copy(historyVerified=false,
   automaticStatus=pdsync.i18n.DesktopMessages.CLOUD_CHECK_INCOMPLETE_AUTOMATIC_UPLOAD_PAUSED.text()))
  assertFalse(unknown.canUpload)
  assertEquals(pdsync.i18n.DesktopMessages.CLOUD_CHECK_INCOMPLETE_AUTOMATIC_UPLOAD_PAUSED.text(),unknown.status)
  assertEquals(pdsync.i18n.DesktopMessages.AUTOMATIC_UPLOAD_PAUSED_YOU_CAN_STILL_UPLOAD_MANUALLY.text(),
   desktopAutomaticStatusAfterCloudRead(true,false))
 }
 @Test fun oneLatestConflictUsesLocalVersusCloudExplanation() {
  val state=DesktopSharedHomeAdapter.state(context(history()).copy(hasConflict=true))
  assertEquals(pdsync.i18n.DesktopMessages.THERE_ARE_MULTIPLE_CLOUD_BRANCHES_CLICK_RESTORE_TO_CHOOSE_ONE.text(),state.status)
  assertEquals(io.github.xyz1001.pdsync.android.SharedHomeStatusKind.CONFLICT,state.statusKind)
 }
}
