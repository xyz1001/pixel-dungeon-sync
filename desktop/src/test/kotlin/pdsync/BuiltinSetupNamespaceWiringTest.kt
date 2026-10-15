package pdsync

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Host wiring: provisioning belongs to explicit setup comparison, not login or auto-upload. */
class BuiltinSetupNamespaceWiringTest {
    @Test fun desktopPreparesOnlyPendingBuiltinComparison() {
        val source = Files.readString(Path.of("src/main/kotlin/pdsync/Main.kt"))
        val comparison = source.substringAfter("fun inspectOnboarding()").substringBefore("fun continueDiscovery()")
        assertTrue(comparison.contains("connectionProvider == \"official\""))
        assertTrue(comparison.contains("isDesktopOfficialRoot(manager.config.endpoint)"))
        assertTrue(comparison.contains("onboardingProgress == OnboardingProgress.PENDING"))
        assertTrue(comparison.indexOf("store.ensureNamespace") < comparison.indexOf("manager.compareOnboarding"))
        assertTrue(comparison.contains("pageGeneration == generation && config == inspectedConfig"))
        val controller = Files.readString(Path.of("../shared-ui/src/main/kotlin/io/github/xyz1001/pdsync/android/SharedConnectionController.kt"))
        assertFalse(controller.substringAfter("private fun authenticate(").substringBefore("fun testConnection()").contains("ports.testConnection"))
    }

    @Test fun androidPreparesOnlyExplicitPendingBuiltinComparison() {
        val source = Files.readString(Path.of("../android-app/src/main/java/io/github/xyz1001/pdsync/android/MainActivity.java"))
        val comparison = source.substringAfter("private void readComparison()").substringBefore("private ")
        assertTrue(comparison.contains("this.onboardingActive && this.page == SyncPage.COMPARISON"))
        assertTrue(comparison.contains("this.progress == OnboardingProgress.PENDING && isOfficialAccount(this.connection)"))
        assertTrue(comparison.indexOf("activeFacade.connect()") < comparison.indexOf("activeFacade.inspect()"))
        assertTrue(comparison.contains("if (!preparedNamespace.isSuccess()) throw"))
        assertFalse(source.substringAfter("private void drainAutomaticHistoryRefresh()").substringBefore("private void readComparison()").contains(".connect()"))
    }
}
