package pdsync

import pdsync.i18n.DesktopMessages

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64

/** Uses the operating system folder chooser; no dependency download or shell interpolation. */
internal object NativeFolderChooser {
 internal val DEFAULT_TITLE get() = DesktopMessages.CHOOSE_GAME_SAVE_FOLDER.text()
 internal val UNAVAILABLE get() = DesktopMessages.SYSTEM_FOLDER_PICKER_UNAVAILABLE_PLEASE_TRY_AGAIN_LATER.text()

 suspend fun choose(initial: String, title: String = DEFAULT_TITLE): String? = withContext(Dispatchers.IO) {
  val request = command(initial, title)
  val process = try {
   ProcessBuilder(request.arguments).apply {
    environment().putAll(request.environment)
    redirectError(ProcessBuilder.Redirect.DISCARD)
   }.start()
  } catch (_: Exception) { error(UNAVAILABLE) }
  try {
   val output = process.inputStream.readNBytes(32769)
   check(output.size <= 32768) { UNAVAILABLE }
   selection(request.platform, process.waitFor(), output)
  } catch (_: Exception) { error(UNAVAILABLE) }
  finally { if (process.isAlive) process.destroy() }
 }

 internal data class Command(val platform: String, val arguments: List<String>, val environment: Map<String, String>)

 /** Titles and paths are passed as arguments/environment, never inserted into scripts. */
 internal fun command(initial: String, title: String, osName: String = System.getProperty("os.name"), windowsRoot: String? = System.getenv("SystemRoot")): Command {
  val os = osName.lowercase(java.util.Locale.ROOT)
  val environment = mapOf("PDSYNC_FOLDER_INITIAL" to initial, "PDSYNC_FOLDER_TITLE" to title)
  return when {
   os.startsWith("windows") -> Command("windows", listOf(
    (windowsRoot?.takeIf { it.isNotBlank() } ?: "C:\\Windows") + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
    "-NoProfile", "-STA", "-Command", """
     Add-Type -AssemblyName System.Windows.Forms
     ${'$'}picker = New-Object System.Windows.Forms.FolderBrowserDialog
     ${'$'}picker.Description = ${'$'}env:PDSYNC_FOLDER_TITLE
     ${'$'}picker.SelectedPath = ${'$'}env:PDSYNC_FOLDER_INITIAL
     try { if (${'$'}picker.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
       [Console]::Write([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(${'$'}picker.SelectedPath)))
     } } finally { ${'$'}picker.Dispose() }
    """.trimIndent()), environment)
   os.startsWith("mac") || os == "darwin" -> Command("macos", listOf("osascript", "-e", """
    on run argv
     try
      if (item 1 of argv) is "" then
       return POSIX path of (choose folder with prompt (item 2 of argv))
      else
       return POSIX path of (choose folder with prompt (item 2 of argv) default location (POSIX file (item 1 of argv)))
      end if
     on error number -128
      return ""
     end try
    end run
   """.trimIndent(), initial, title), environment)
   os.startsWith("linux") -> Command("linux", listOf("zenity", "--file-selection", "--directory", "--title=$title", "--filename=$initial"), environment)
   else -> error(UNAVAILABLE)
  }
 }

 internal fun selection(platform: String, exitCode: Int, output: ByteArray): String? {
  // zenity reserves 1 for user cancellation. Other failures must not pretend to be cancellation.
  if (platform == "linux" && exitCode == 1) return null
  check(exitCode == 0 && output.size <= 32768) { UNAVAILABLE }
  val value = output.decodeToString().removeSuffix("\n").removeSuffix("\r")
  if (value.isEmpty()) return null
  return if (platform == "windows") Base64.getDecoder().decode(value).decodeToString() else value
 }
}
