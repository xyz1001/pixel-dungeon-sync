package pdsync

import java.nio.file.Files
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.Test

class OnboardingConfigTest {
 @Test fun legacyConfigKeepsNamespaceAndResumesAsLegacy() {
  val path = Files.createTempDirectory("onboarding-config").resolve("config.json")
  path.writeText("""{"endpoint":"https://example.com/dav","profile":"existing","targetGame":"/chosen.jar","username":"name","rememberPassword":true,"autoUploadPaused":true}""")
  val config = ConfigFile(path).load()!!
  assertEquals(OnboardingProgress.LEGACY, config.onboardingProgress)
  assertEquals("existing", config.profile); assertTrue(config.rememberPassword); assertTrue(config.autoUploadPaused)
 }
 @Test fun pendingAndDeferredRoundTripWithoutPasswordsOrManualRoots() {
  for (progress in OnboardingProgress.entries) {
   val path = Files.createTempDirectory("onboarding-state").resolve("config.json")
   val config = AppConfig(endpoint = "https://example.com/dav", profile = "preserved", targetGame = "/game.jar", onboardingProgress = progress)
   ConfigFile(path).save(config)
   assertEquals(config, ConfigFile(path).load())
   assertFalse(path.readText().contains("saveRoot")); assertFalse(path.readText().contains("\"password\""))
  }
 }
 @Test fun changedServiceIdentityInvalidatesCompletedDecision() {
  val complete = AppConfig(endpoint = "https://example.com/dav", username = "alice", onboardingProgress = OnboardingProgress.COMPLETE, rememberPassword = true)
  val draft = ConnectionDraft(complete, "test-only")
  val changed = draft.editingConfig(complete.copy(username = "bob"))
  assertEquals(OnboardingProgress.PENDING, changed.config.onboardingProgress)
  assertEquals("", changed.password); assertFalse(changed.config.rememberPassword)
  assertEquals(OnboardingProgress.COMPLETE, draft.editingConfig(complete).config.onboardingProgress)
 }
 @Test fun freshConnectionAlwaysStartsPendingWithAutomaticNamespace() {
  val config = newConnectionConfig()
  assertEquals(OnboardingProgress.PENDING, config.onboardingProgress)
  assertEquals(Shattered.defaultRemoteProfile, config.profile)
 }
}
