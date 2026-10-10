package pdsync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64

/** Uses the operating system folder chooser; no dependency download or shell interpolation. */
internal object NativeFolderChooser {
 suspend fun choose(initial: String): String? = withContext(Dispatchers.IO) {
  val os=System.getProperty("os.name").lowercase()
  val windows=os.contains("win")
  val command=when {
   windows -> listOf(System.getenv("SystemRoot")+"\\System32\\WindowsPowerShell\\v1.0\\powershell.exe","-NoProfile","-STA","-Command", """
    Add-Type -AssemblyName System.Windows.Forms
    ${'$'}picker = New-Object System.Windows.Forms.FolderBrowserDialog
    ${'$'}picker.Description = '选择游戏存档目录'
    ${'$'}picker.SelectedPath = ${'$'}env:PDSYNC_FOLDER_INITIAL
    try { if (${'$'}picker.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
      [Console]::Write([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(${'$'}picker.SelectedPath)))
    } } finally { ${'$'}picker.Dispose() }
   """.trimIndent())
   os.contains("mac") -> listOf("osascript","-e","on run argv\ntry\nreturn POSIX path of (choose folder with prompt \"选择游戏存档目录\")\non error number -128\nreturn \"\"\nend try\nend run",initial)
   else -> listOf("zenity","--file-selection","--directory","--title=选择游戏存档目录","--filename=$initial")
  }
  val process=try { ProcessBuilder(command).apply { environment()["PDSYNC_FOLDER_INITIAL"]=initial }.start() }
   catch(e:Exception) { error("系统目录选择器不可用，请直接填写路径。") }
  try {
   val output=process.inputStream.readNBytes(32769)
   require(output.size<=32768) { "目录选择结果过长" }
   val code=process.waitFor()
   if(code!=0) return@withContext null
   val value=output.decodeToString().trim()
   if(value.isEmpty())null else if(windows)Base64.getDecoder().decode(value).decodeToString() else value
  } finally { if(process.isAlive)process.destroy() }
 }
}
