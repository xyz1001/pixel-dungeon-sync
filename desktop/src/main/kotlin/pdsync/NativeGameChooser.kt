package pdsync

import pdsync.i18n.DesktopMessages

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** AWT delegates FileDialog to the platform's native chooser; no Swing file browser. */
internal object NativeGameChooser {
 fun accepts(name: String): Boolean = name.endsWith(".jar", ignoreCase = true)

 fun selection(directory: String?, file: String?): String? {
  if (file == null) return null // Cancel must not reuse the previous selection.
  require(accepts(file)) { DesktopMessages.SELECT_A_JAR_FILE_THE_PREVIOUS_GAME_SELECTION_IS_UNCHANGED.text() }
  require(!directory.isNullOrBlank()) { DesktopMessages.CANNOT_DETERMINE_THE_SELECTED_FILE_S_LOCATION.text() }
  return File(directory, file).absolutePath
 }

 fun dialog(parent: Frame, initial: String): FileDialog = FileDialog(parent, DesktopMessages.CHOOSE_TARGET_GAME_JAR_READ_ONLY_NOT_RUN.text(), FileDialog.LOAD).apply {
  isMultipleMode = false
  directory = initial.takeIf { it.isNotBlank() }?.let { File(it).absoluteFile.parent }
   ?: System.getProperty("user.home")
  // Windows does not implement FilenameFilter; the native filename pattern plus
  // selection validation enforce the same extension rule there.
  file = "*.jar"
  filenameFilter = java.io.FilenameFilter { _, name -> accepts(name) }
 }

 suspend fun choose(parent: Frame, initial: String): String? = suspendCoroutine { continuation ->
  SwingUtilities.invokeLater {
   try {
    val chooser = dialog(parent, initial)
    val selected = try {
     chooser.isVisible = true // Owner-modal native dialog; parent regains focus on close.
     selection(chooser.directory, chooser.file)
    } finally { chooser.dispose() }
    continuation.resume(selected)
   } catch (e: Exception) { continuation.resumeWithException(e) }
  }
 }
}
