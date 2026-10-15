package pdsync

import io.github.xyz1001.pdsync.android.SharedSavePathReadStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.test.*

class DesktopSavePathNavigationTest {
 @TempDir lateinit var temp: Path

 private fun adapter(path: String) = DesktopSavePathAdapter(path, null,
  DefaultProfileLocation(SavePathContext("Linux", temp.toString(), emptyMap())))

 @Test fun settingsBackRestoresSavedPathAndPreviousDraftWithoutChangingSavedConfiguration() {
  val original = temp.resolve("original").createDirectory().toString()
  val changed = temp.resolve("changed").createDirectory().toString()
  for (progress in listOf(OnboardingProgress.COMPLETE, OnboardingProgress.PENDING, OnboardingProgress.DEFERRED)) {
   val saved = AppConfig(selectedSaveRoot = original, profile = "saved", onboardingProgress = progress)
   val previousDraft = saved.copy(profile = "previous-draft")
   val configFile = ConfigFile(temp.resolve("${progress.name}.json"))
   configFile.save(saved)
   val adapter = adapter("stale-draft")
   val navigation = DesktopSavePathNavigation()
   val wasOnboarding = progress != OnboardingProgress.COMPLETE
   navigation.beginSettings(adapter, original, previousDraft, wasOnboarding)
   adapter.pageChanged(true)
   assertEquals(original, adapter.state.path)
   adapter.editPath(changed)
   val preview = assertNotNull(adapter.beginPreview())
   val read = Result.success(adapter.inspect(preview))
   assertFalse(adapter.requestContinue())
   assertTrue(adapter.state.continueAfterRead)

   val previous = assertNotNull(navigation.cancel(adapter))
   adapter.pageChanged(false)
   assertEquals(original, previous.path)
   assertEquals(previousDraft, previous.setupDraft)
   assertEquals(wasOnboarding, previous.onboardingActive)
   assertEquals(original, adapter.state.path)
   assertEquals(saved, configFile.load())
   assertFalse(adapter.completePreview(preview, read))
   assertNull(adapter.state.summary)
   assertFalse(adapter.state.continueAfterRead)
   assertEquals(SharedSavePathReadStatus.UNREAD, adapter.state.readStatus)

   navigation.beginSettings(adapter, original, saved, wasOnboarding)
   adapter.pageChanged(true)
   assertFalse(adapter.completePreview(preview, read), "an old read cannot advance a reopened edit")
   assertEquals(original, assertNotNull(navigation.cancel(adapter)).path)
   assertNull(navigation.cancel(adapter), "a cancelled settings origin is consumed once")
  }
 }

 @Test fun setupBackKeepsDraftAndCommittedSettingsEditNoLongerRollsBackToOldPath() {
  val original = temp.resolve("saved").createDirectory().toString()
  val changed = temp.resolve("draft").createDirectory().toString()
  val saved = AppConfig(selectedSaveRoot = original, onboardingProgress = OnboardingProgress.COMPLETE)
  val adapter = adapter(original)
  val navigation = DesktopSavePathNavigation()
  adapter.editPath(changed)
  assertNull(navigation.cancel(adapter), "ordinary setup Back still returns to game selection")
  assertEquals(changed, adapter.state.path)

  navigation.beginSettings(adapter, original, saved, false)
  adapter.editPath(changed)
  navigation.beginSetup() // The host calls this only after the validated path was saved as PENDING.
  assertNull(navigation.cancel(adapter), "after commit, Back stays in the required setup flow")
  assertEquals(changed, adapter.state.path)
 }

 @Test fun failedPreviewCanStillCancelAndRetainedCallbacksCannotCancelAReopenedEdit() {
  val original = temp.resolve("saved").createDirectory().toString()
  val saved = AppConfig(selectedSaveRoot = original, onboardingProgress = OnboardingProgress.COMPLETE)
  val adapter = adapter(original)
  val navigation = DesktopSavePathNavigation()
  var generation = 1
  navigation.beginSettings(adapter, original, saved, false)
  adapter.pageChanged(true)
  val owner = generation
  var settingsReturns = 0
  val actions = DesktopSavePathActions(adapter, { owner == generation }, {}, {
   if (navigation.cancel(adapter) != null) settingsReturns++
   adapter.pageChanged(false)
   generation++
  }, { error("failed read cannot continue") })
  adapter.editPath("/invalid/draft")
  val preview = assertNotNull(adapter.beginPreview())
  adapter.completePreview(preview, Result.failure(IllegalStateException("read failed")))
  actions.back()
  assertEquals(1, settingsReturns)
  assertEquals(original, adapter.state.path)

  navigation.beginSettings(adapter, original, saved, false)
  adapter.pageChanged(true)
  val currentState = adapter.state
  actions.editPath("/old/callback")
  actions.back()
  actions.next()
  assertEquals(1, settingsReturns)
  assertEquals(currentState, adapter.state)
  assertNotNull(navigation.cancel(adapter), "stale Back must not consume the new settings origin")
 }
}
