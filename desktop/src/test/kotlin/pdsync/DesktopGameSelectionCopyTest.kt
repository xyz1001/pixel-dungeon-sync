package pdsync

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopGameSelectionCopyTest {
    @Test fun desktopSelectionKeepsInitialScanAndNativeChooserWithoutRescanAction() {
        val main = Files.readString(Path.of("src/main/kotlin/pdsync/Main.kt"))
        val page = main.substringAfter("Page.DISCOVERY -> {").substringBefore("Page.PATH ->")
        assertTrue(page.contains("else \"重新选择\""))
        assertFalse(page.contains("SharedGameSelectionRow(SharedGameSelectionControl.RESCAN"))
        assertTrue(page.contains("chooseSetupGame()"))
        assertTrue(main.contains("NativeGameChooser.choose"))
        assertTrue(main.contains("rescanDiscovery()"))
        val shared = Files.readString(Path.of("../shared-ui/src/main/kotlin/io/github/xyz1001/pdsync/android/SharedGameSelectionScreen.kt"))
        assertFalse(shared.contains("详细信息"))
        assertTrue(shared.contains("下一步"))
    }
}
