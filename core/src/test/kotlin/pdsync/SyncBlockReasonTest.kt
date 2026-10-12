package pdsync

import org.junit.jupiter.api.Test
import kotlin.test.*

class SyncBlockReasonTest {
 @Test fun failuresUseClosedSafeReasonsWithoutRawMessages() {
  val cases = listOf(
   SaveNotStableException("secret") to SyncBlockReason.SAVE_NOT_STABLE,
   LegacyProtocolException() to SyncBlockReason.LEGACY_REMOTE_PROTOCOL,
   UnsettledRemoteException("secret") to SyncBlockReason.REMOTE_INCOMPLETE,
   RemoteIntegrityException("secret") to SyncBlockReason.REMOTE_INTEGRITY_FAILED,
   InterruptedException("secret") to SyncBlockReason.INTERRUPTED,
   IllegalStateException("https://user:secret@example.invalid/") to SyncBlockReason.COMPARISON_FAILED
  )
  cases.forEach { (failure, reason) -> assertEquals(reason, SyncBlockReason.fromFailure(failure)); assertFalse(reason.safeMessage.contains("secret")) }
 }
 @Test fun platformReasonsArePreserved() {
  val failure = object : IllegalStateException("private details"), ReasonedSyncFailure {
   override val syncBlockReason = SyncBlockReason.TARGET_CHANGED
  }
  assertEquals(SyncBlockReason.TARGET_CHANGED, SyncBlockReason.fromFailure(failure))
 }
}
