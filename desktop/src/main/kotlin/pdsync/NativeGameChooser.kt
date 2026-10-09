package pdsync

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
  require(accepts(file)) { "请选择 JAR 文件；未更改原有游戏选择。" }
  require(!directory.isNullOrBlank()) { "无法确定所选文件的位置。" }
  return File(directory, file).absolutePath
 }

 fun dialog(parent: Frame, initial: String): FileDialog = FileDialog(parent, "选择目标游戏 JAR（只读取，不运行）", FileDialog.LOAD).apply {
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
