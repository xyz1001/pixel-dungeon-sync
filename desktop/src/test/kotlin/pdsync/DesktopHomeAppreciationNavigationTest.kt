package pdsync

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Host routing contract supplements the actual shared button and controller navigation tests. */
class DesktopHomeAppreciationNavigationTest {
 @Test fun homeUsesExistingAppreciationOnlyWithFreshIdentityGuardAndReturnsHome() {
  val main = Files.readString(Path.of("src/main/kotlin/pdsync/Main.kt"))
  val action = main.substringAfter("fun openHomeAppreciation() {").substringBefore("fun requestConfirmation(")
  assertContains(action, "desktopHomeAppreciationAllowed(config, homeState())")
  assertContains(action, "!syncAccess.isExpired(config)")
  assertTrue(action.indexOf("syncAccess.isExpired") < action.indexOf("navigate(Page.CONNECTION)"))
  assertContains(action, "connectionAppreciationOnly = true")
  assertContains(action, "connectionReturnPage = Page.HOME")
  assertFalse(action.contains("execute("))
  assertFalse(action.contains("store("))
  assertFalse(action.contains("save("))
  assertContains(main, "override fun appreciate() = openHomeAppreciation()")
  assertContains(main, "appreciationOnly = connectionAppreciationOnly")
  assertContains(main, "DesktopSharedConnectionPage(configFile, if (onboardingActive && !connectionAppreciationOnly) setupDraft else config, password,")
  assertContains(main, "DesktopConnectionAdapter(initial, currentPassword, configFile,")
  assertContains(main, "SharedConnectionController(origin, adapter.initialConnection, adapter.profile, adapter, io, ui)")
  assertContains(main, "if (appreciationOnly) controller.openAppreciationOnly()")
  assertContains(main, "if (connectionAppreciationOnly) entitlementRetryAt = 0L")
  assertContains(main, "navigate(connectionReturnPage)")
 }

 @Test fun leavingHomeAndOrdinaryConnectionEntryCannotReplayTheStandaloneRequest() {
  val main = Files.readString(Path.of("src/main/kotlin/pdsync/Main.kt"))
  val navigate = main.substringAfter("fun navigate(next: Page) {").substringBefore("suspend fun reconnectSaved(")
  assertContains(navigate, "if (next != Page.CONNECTION) connectionAppreciationOnly = false")
  assertContains(navigate, "pageGeneration++; page = next")
  val ordinary = main.substringAfter("fun openConnection(mode: SettingsMode) {").substringBefore("fun openHomeAppreciation()")
  assertContains(ordinary, "connectionAppreciationOnly = false")
  val page = main.substringAfter("Page.CONNECTION -> key(pageGeneration) {").substringBefore("Page.CONFLICT ->")
  assertContains(page, "pageGeneration == generation")
  val controller = main.substringAfter("private fun DesktopSharedConnectionPage(").substringBefore("private fun ConflictPage(")
  assertContains(controller, "override fun onBack(destination: ConnectionEntryModel.Back) { latestBusy(false); latestDismiss() }")
  assertContains(controller, "controller.close()")
  assertContains(controller, "io.shutdownNow()")
 }
}
