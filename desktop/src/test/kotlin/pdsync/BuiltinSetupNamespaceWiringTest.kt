package pdsync

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Host wiring: app-folder preparation never uploads or establishes sync ancestry. */
class BuiltinSetupNamespaceWiringTest {
    @Test fun desktopPreparesCurrentLayoutAfterExplicitConfigurationForEveryProvider() {
        val source = Files.readString(Path.of("src/main/kotlin/pdsync/Main.kt"))
        val commit = source.substringAfter("onApplied = { applied, note ->").substringBefore("Page.CONFLICT ->")
        assertTrue(commit.indexOf("navigate(Page.HOME)") < commit.indexOf("loadProfile(applied.config"))
        assertTrue(commit.contains("prepareOfficialNamespace = prepareNamespace"))
        val home = source.substringAfter("fun loadProfile(").substringBefore("fun selectTargetGame()")
        assertTrue(home.contains("page == Page.HOME && pageGeneration == generation && config == value"))
        assertTrue(home.contains("prepareOfficialNamespace && current()"))
        assertFalse(home.contains("value.connectionProvider == \"official\""))
        assertTrue(home.contains("syncAccess.store(value, manager.adapter.id, secret)"))
        assertTrue(home.contains(".ensureSimpleNamespace()"))
        assertFalse(home.contains("authenticationFailed("))
        for (forbidden in listOf(".push(", ".adopt", "confirmOnboardingReady", "automatic.applied", "configFile.save"))
            assertFalse(home.contains(forbidden), forbidden)
        val execute = source.substringAfter("fun execute(").substringBefore("fun editDirectory(")
        assertTrue(execute.contains("syncAccess.store(manager.config, manager.adapter.id, secret)"))
        assertFalse(execute.contains("ensureNamespace"))
        assertFalse(execute.contains("ensureSimpleNamespace"))
        assertTrue(execute.contains("manager.pushFromHome("))
        val read = source.substringAfter("fun readRemote(").substringBefore("fun openRestore()")
        assertFalse(read.contains("ensureNamespace"));assertFalse(read.contains("ensureSimpleNamespace"))
        assertFalse(source.contains("Page.COMPARISON"))
    }

    @Test fun androidPreparesBuiltinNamespaceOnlyOnCompletedHome() {
        val source = Files.readString(Path.of("../android-app/src/main/java/io/github/xyz1001/pdsync/android/MainActivity.java"))
        val prepare = source.substringAfter("private void prepareOfficialHomeNamespace(").substringBefore("private ")
        assertTrue(prepare.contains("this.page != SyncPage.HOME || this.progress != OnboardingProgress.COMPLETE"))
        assertTrue(prepare.contains("!isOfficialAccount(account)"))
        assertTrue(prepare.contains("currentOfficialHomeNamespace(owner, account, selection, namespace)"))
        assertTrue(prepare.contains("owner.connect()"))
        assertTrue(prepare.contains("preparedNamespace.requireSuccess("))
        assertTrue(prepare.indexOf("preparedNamespace.requireSuccess(") < prepare.indexOf("this.preparedOfficialNamespace.succeeded(key)"))
        val comparison = source.substringAfter("private void readComparison(boolean prepareNamespace, boolean allowHomePreview)").substringBefore("private ")
        assertTrue(comparison.indexOf("prepareOfficialHomeNamespace(activeFacade)") < comparison.indexOf("activeFacade.inspect()"))
        val commit = source.substringAfter("private boolean drainSharedCommit()").substringBefore("private ")
        assertTrue(commit.indexOf("this.page = SyncPage.HOME") < commit.indexOf("refreshComparison(SyncPage.HOME)"))
        assertFalse(source.substringAfter("private void drainAutomaticHistoryRefresh()").substringBefore("private void prepareOfficialHomeNamespace(").contains(".connect()"))
        for (forbidden in listOf(".upload(", ".restore", ".adopt", "completeOnboarding", "setOnboardingProgress"))
            assertFalse(prepare.contains(forbidden), forbidden)
    }
}
