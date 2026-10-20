package pdsync

import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeFalse

class NativeGameChooserTest {
 @Test fun jarFilterIsCaseInsensitiveAndRejectsOtherFiles() {
  assertTrue(NativeGameChooser.accepts("game.jar"))
  assertTrue(NativeGameChooser.accepts("GAME.JAR"))
  for (name in listOf("game.exe", "game.jar.txt", "game", "*.zip")) assertFalse(NativeGameChooser.accepts(name))
 }
 @Test fun cancelNeverReturnsTheInitialDirectoryOrPreviousSelection() {
  assertNull(NativeGameChooser.selection("previous-directory", null))
  assertNull(NativeGameChooser.selection(null, null))
 }
 @Test fun manuallyEnteredNonJarIsRejectedAndValidPathPreservesSpaces() {
  assertFailsWith<IllegalArgumentException> { NativeGameChooser.selection("directory", "game.exe") }
  assertFailsWith<IllegalArgumentException> { NativeGameChooser.selection(null, "game.jar") }
  assertEquals(File("folder with spaces", "game.JAR").absolutePath, NativeGameChooser.selection("folder with spaces", "game.JAR"))
 }
 @Test fun eachOpeningHasANewOwnedModalNativeDialogAndJarPattern() {
  assumeFalse(GraphicsEnvironment.isHeadless(), "A real native dialog requires a graphical display")
  SwingUtilities.invokeAndWait {
   val parent = Frame()
   try {
    repeat(2) {
     val chooser = NativeGameChooser.dialog(parent, File("folder with spaces", "previous.jar").absolutePath)
     try {
      assertSame(parent, chooser.owner)
      assertTrue(chooser.isModal)
      assertEquals(FileDialog.LOAD, chooser.mode)
      assertFalse(chooser.isMultipleMode)
      assertEquals("*.jar", chooser.file)
      assertEquals(File("folder with spaces").absolutePath, chooser.directory)
      assertTrue(chooser.filenameFilter.accept(null, "game.JAR"))
      assertFalse(chooser.filenameFilter.accept(null, "game.exe"))
     } finally { chooser.dispose() }
     assertFalse(chooser.isDisplayable)
    }
   } finally { parent.dispose() }
  }
 }
}
