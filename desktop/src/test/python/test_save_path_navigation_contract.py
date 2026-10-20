"""Host navigation seams; behavior is executed by DesktopSavePathNavigationTest."""
from pathlib import Path
import unittest

REPO = Path(__file__).resolve().parents[4]
MAIN = (REPO / "desktop/src/main/kotlin/pdsync/Main.kt").read_text()
ANDROID = (REPO / "android-app/src/main/java/io/github/xyz1001/pdsync/android/MainActivity.java").read_text()


def body(source, marker):
    start = source.index("{", source.index(marker))
    depth = 1
    for end in range(start + 1, len(source)):
        depth += (source[end] == "{") - (source[end] == "}")
        if depth == 0:
            return source[start + 1:end]
    raise AssertionError("unclosed body: " + marker)


class SavePathNavigationContract(unittest.TestCase):
    def test_settings_entry_captures_saved_path_without_restarting_onboarding(self):
        entry = body(MAIN, "SharedSettingsKey.SAVE_PATH ->")
        self.assertIn("config?.selectedSaveRoot", entry)
        self.assertIn("pathNavigation.beginSettings(pathAdapter, savedPath, setupDraft, onboardingActive)", entry)
        self.assertIn("navigate(Page.PATH)", entry)
        self.assertNotIn("onboardingActive = true", entry)
        self.assertNotIn("configFile.save", entry)

    def test_path_back_restores_only_settings_draft_and_checks_current_visit(self):
        path = body(MAIN, "Page.PATH -> key(pageGeneration)")
        self.assertIn("pageGeneration == generation", path)
        self.assertIn("canAct = { !busy && !connectingAtStartup && current() }", path)
        self.assertIn("canPublish = ::current", path)
        back = body(path, "goBack =")
        self.assertIn("pathNavigation.cancel(pathAdapter)", back)
        self.assertIn("if (previous == null) navigate(Page.DISCOVERY)", back)
        self.assertIn("setupDraft = previous.setupDraft", back)
        self.assertIn("onboardingActive = previous.onboardingActive", back)
        self.assertIn("navigate(Page.SETTINGS)", back)
        self.assertNotIn("configFile.save", back)
        self.assertNotIn("config =", back)

    def test_successful_path_commit_still_requires_connection_completion(self):
        commit = body(MAIN, "fun continuePath()")
        self.assertIn("onboardingProgress=OnboardingProgress.PENDING", commit)
        self.assertLess(commit.index("inspectSelectedSaveDirectory(root)"), commit.index("configFile.save(next)"))
        self.assertLess(commit.index("configFile.save(next)"), commit.index("pathNavigation.beginSetup()"))
        self.assertIn("onboardingActive=true", commit)
        self.assertIn("navigate(Page.CONNECTION)", commit)
        self.assertNotIn("OnboardingProgress.COMPLETE", commit)
        self.assertNotIn("navigate(Page.HOME)", commit)
        discovery = body(MAIN, "fun continueDiscovery()")
        self.assertIn("navigate(Page.CONNECTION)", discovery)
        self.assertNotIn("Page.PATH", discovery)
        self.assertNotIn("pathNavigation.beginSetup()", discovery)
        # The current settings path editor is a dialog over an inactive settings page.
        # It delegates completion to the same guarded commit rather than activating HOME.
        path = body(MAIN, "Page.PATH -> key(pageGeneration)")
        self.assertIn("SharedSettingsScreen(DesktopSharedSettingsAdapter.state", path)
        self.assertIn("pageActive = false", path)
        self.assertIn("SharedSavePathDialog(pathAdapter.state.copy(busy = busy || connectingAtStartup)", path)
        self.assertIn("continuePath = { continuePath() }", path)
        self.assertNotIn("OnboardingProgress.COMPLETE", path)
        self.assertNotIn("navigate(Page.HOME)", path)

    def test_android_settings_path_remains_read_only_and_legacy_path_back_returns_to_game(self):
        settings = body(ANDROID, "private void renderSettings()")
        self.assertIn('SharedSettingsKey.SAVE_PATH, SAVE_LOCATION_189.text(), savePathLabel(), null, false', settings)
        catalog = (REPO / "core/src/main/java/pdsync/i18n/AndroidHostMessages.java").read_text()
        self.assertIn('new UiText("android.host.save_location_189", "存档位置", "Save location")', catalog)
        action = settings[settings.index("case SAVE_PATH:"):].split("}", 1)[0]
        self.assertNotIn("navigate(", action)
        self.assertIn("break;", action)
        self.assertIn("navigate(SyncPage.DISCOVERY)", body(ANDROID, "void m109x58567797()"))
        system_back = body(body(ANDROID, "public void onBackPressed()"), "if (this.page == SyncPage.PATH) {\n            navigate")
        self.assertIn("navigate(SyncPage.DISCOVERY)", system_back)


if __name__ == "__main__":
    unittest.main()
