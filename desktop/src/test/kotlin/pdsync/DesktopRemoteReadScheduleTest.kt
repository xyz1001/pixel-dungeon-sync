package pdsync

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.test.*

class DesktopRemoteReadScheduleTest {
 @Test fun firstReadAndExplicitRequestsAreConsumedWhenStartedRegardlessOfOutcome() {
  val schedule = DesktopRemoteReadSchedule()
  assertTrue(schedule.pending)
  schedule.started()
  repeat(100_000) { assertFalse(schedule.pending) } // Neither success nor failure schedules a timer.
  schedule.request()
  assertTrue(schedule.pending)
  schedule.started() // Explicit manual read also consumes the pending request.
  assertFalse(schedule.pending)
  schedule.request() // A session reload or completed operation may request one more read.
  assertTrue(schedule.pending)
  schedule.started()
  assertFalse(schedule.pending)
 }

 @Test fun homeWiringKeepsExplicitAndRenewalReadsButHasNoCloudPollingTimer() {
  val main = Files.readString(Path.of("src/main/kotlin/pdsync/Main.kt"))
  val remote = main.substringAfter("fun readRemote(").substringBefore("fun hasRemoteBranches()")
  assertContains(remote, "remoteRead.started()")
  assertTrue(remote.indexOf("remoteRead.started()") < remote.indexOf("scope.launch"))
  assertTrue(remote.indexOf("refreshProfile(readConfig,") < remote.lastIndexOf("remoteRead.started()"))
  assertTrue(remote.lastIndexOf("remoteRead.started()") < remote.indexOf("if (!localHistoryVerified)"))
  val homeLoop = main.substringAfter("if (connected && !remoteInspectionReady)").substringBefore("observationDiagnostics.recovered()")
  assertEquals(2, Regex("remoteRead\\.pending").findAll(homeLoop).count())
  assertFalse(homeLoop.contains("60000"))
  assertContains(homeLoop, "gameLauncher.status()")
  assertContains(homeLoop, "automatic.shouldUploadOwned(")
  assertContains(main, "readRemote(refreshLocal = true)")
  assertContains(main, "readRemote(request)")
 }
}
