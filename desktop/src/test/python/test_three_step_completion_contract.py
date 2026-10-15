"""Desktop configuration completion stops at connection; save actions belong to HOME."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[4]
MAIN = (ROOT / "desktop/src/main/kotlin/pdsync/Main.kt").read_text()
ADAPTER = (ROOT / "desktop/src/main/kotlin/pdsync/DesktopConnectionAdapter.kt").read_text()


def body(source, marker):
    start = source.index("{", source.index(marker))
    depth = 1
    for end in range(start + 1, len(source)):
        depth += (source[end] == "{") - (source[end] == "}")
        if depth == 0:
            return source[start + 1:end]
    raise AssertionError("unclosed body: " + marker)


class ThreeStepCompletionContract(unittest.TestCase):
    def test_connection_completion_enters_home_before_namespace_or_history_work(self):
        page = body(MAIN, "Page.CONNECTION -> key(pageGeneration)")
        self.assertIn("pageGeneration == generation", page)
        commit = body(page, "onApplied =")
        self.assertIn("if (current())", commit)
        self.assertIn("onboardingActive = false", commit)
        self.assertLess(commit.index("navigate(Page.HOME)"), commit.index("loadProfile(applied.config"))
        self.assertIn("prepareOfficialNamespace = prepareNamespace", commit)
        for forbidden in (".push(", ".restore", ".adopt", "baseline", "automatic.applied", "configFile.save"):
            self.assertNotIn(forbidden, commit)
        self.assertIn("navigate(connectionReturnPage)", page)

    def test_namespace_preparation_is_home_scoped_and_finishes_before_background_history(self):
        load = body(MAIN, "fun loadProfile(")
        self.assertIn("page == Page.HOME && pageGeneration == generation && config == value", load)
        self.assertIn("prepareOfficialNamespace && current()", load)
        self.assertIn('value.connectionProvider == "official" && isDesktopOfficialRoot(value.endpoint)', load)
        self.assertIn(".ensureNamespace { current() && session === manager }", load)
        self.assertLess(load.index("busy = true"), load.index(".ensureNamespace"))
        self.assertLess(load.index(".ensureNamespace"), load.index("finally { busy = false }"))
        self.assertNotIn("authenticationFailed(", load)
        self.assertNotIn("navigate(", load)
        self.assertNotIn(".readRemote(", load)
        observer = body(MAIN, "LaunchedEffect(session, connected)")
        self.assertLess(observer.index("if (busy || connectingAtStartup || page != Page.HOME"), observer.index("readRemote()"))
        self.assertNotIn("ensureNamespace", observer)
        self.assertNotIn("ensureNamespace", body(MAIN, "fun readRemote()"))

    def test_incomplete_restart_resumes_connection_and_completed_restart_stays_home(self):
        startup = MAIN.split("// No observation/upload engine", 1)[1].split("LaunchedEffect(page, pathAdapter.revision", 1)[0]
        self.assertIn("OnboardingGate.resumedProgress", startup)
        self.assertIn("onboardingActive = progress != OnboardingProgress.COMPLETE", startup)
        self.assertIn("if (onboardingActive) navigate(Page.CONNECTION) else navigate(Page.HOME)", startup)
        self.assertNotIn("if (!connected) page = Page.CONNECTION", startup)
        self.assertNotIn("ensureNamespace", startup)
        self.assertNotIn("automatic.applied", startup)
        self.assertNotIn("Page.COMPARISON", MAIN)
        self.assertNotIn("inspectOnboarding", MAIN)
        self.assertNotIn("SharedComparisonScreen", MAIN)

    def test_home_manual_actions_do_not_reenter_setup(self):
        home = MAIN.split("Page.HOME -> SharedHomeScreen", 1)[1].split("Page.SETTINGS ->", 1)[0]
        upload = body(home, "override fun upload()")
        restore = body(home, "override fun restore()")
        self.assertIn("execute(operationEvents.begin(Action.PUSH))", upload)
        self.assertIn("openRestore()", restore)
        for action in (upload, restore):
            self.assertNotIn("onboardingProgress", action)
            self.assertNotIn("onboardingActive", action)
            self.assertNotIn("COMPARISON", action)

    def test_configuration_save_marks_complete_without_sync_io(self):
        custom_test = body(ADAPTER, "override fun testConnection(")
        custom_save = body(ADAPTER, "override fun saveAndActivate(")
        official_save = body(ADAPTER, "override fun saveAuthenticatedOfficial(")
        self.assertIn("copy(onboardingProgress = OnboardingProgress.COMPLETE)", custom_test)
        self.assertNotIn("configFile.save", custom_test)
        self.assertIn("sameConnection(verified, config)", custom_save)
        self.assertIn("candidate.applyFilePassword(configFile)", custom_save)
        self.assertIn("onboardingProgress = OnboardingProgress.COMPLETE", official_save)
        self.assertIn("configFile.save(candidate)", official_save)
        for save in (custom_save, official_save):
            for forbidden in (".connect(", ".push(", ".restore", ".adopt", "baseline", "ensureNamespace"):
                self.assertNotIn(forbidden, save)


if __name__ == "__main__":
    unittest.main()
