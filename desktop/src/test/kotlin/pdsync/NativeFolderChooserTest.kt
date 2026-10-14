package pdsync

import java.util.Base64
import org.junit.jupiter.api.Test
import kotlin.test.*

class NativeFolderChooserTest {
 @Test fun windowsUsesSystemFolderPickerAndPassesPrivateValuesOutsideTheScript() {
  val initial = "C:\\private folder\\'; exit 1 #"
  val title = "导出日志'; exit 1 #"
  val command = NativeFolderChooser.command(initial, title, "Windows 11", "C:\\Windows")
  assertEquals("windows", command.platform)
  assertEquals("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", command.arguments.first())
  assertContains(command.arguments, "-STA")
  assertContains(command.arguments.last(), "System.Windows.Forms.FolderBrowserDialog")
  assertContains(command.arguments.last(), "env:PDSYNC_FOLDER_TITLE")
  assertFalse(command.arguments.last().contains(initial))
  assertFalse(command.arguments.last().contains(title))
  assertEquals(initial, command.environment["PDSYNC_FOLDER_INITIAL"])
  assertEquals(title, command.environment["PDSYNC_FOLDER_TITLE"])
 }

 @Test fun macUsesChooseFolderWithSeparateTitleAndInitialArguments() {
  val initial = "/private folder/quoted \"name\""
  val title = "导出日志 \"title\""
  val command = NativeFolderChooser.command(initial, title, "Mac OS X")
  assertEquals("macos", command.platform)
  assertEquals("osascript", command.arguments.first())
  assertContains(command.arguments[2], "choose folder with prompt (item 2 of argv)")
  assertContains(command.arguments[2], "on error number -128")
  assertFalse(command.arguments[2].contains(initial))
  assertFalse(command.arguments[2].contains(title))
  assertEquals(listOf(initial, title), command.arguments.takeLast(2))
  assertEquals("macos", NativeFolderChooser.command(initial, title, "Darwin").platform)
 }

 @Test fun linuxUsesTheSystemDirectoryDialogWithASingleArgumentPerValue() {
  val command = NativeFolderChooser.command("/private folder/$(touch nope)", "导出日志", "Linux")
  assertEquals(listOf("zenity", "--file-selection", "--directory", "--title=导出日志", "--filename=/private folder/$(touch nope)"), command.arguments)
 }

 @Test fun unsupportedPlatformNeverSilentlyUsesAnotherPlatformsChooser() {
  assertFailsWith<IllegalStateException> { NativeFolderChooser.command("", "导出日志", "unknown") }
 }

 @Test fun cancellationDoesNotReturnTheInitialDirectoryOrProduceAnError() {
  assertNull(NativeFolderChooser.selection("windows", 0, byteArrayOf()))
  assertNull(NativeFolderChooser.selection("macos", 0, "\n".toByteArray()))
  assertNull(NativeFolderChooser.selection("linux", 1, "private error".toByteArray()))
 }

 @Test fun actualChooserFailuresAreNotMisreportedAsCancellation() {
  for ((platform, code) in listOf("linux" to 2, "linux" to 255, "macos" to 1, "windows" to 1)) {
   val failure = assertFailsWith<IllegalStateException> { NativeFolderChooser.selection(platform, code, "PRIVATE_SENTINEL".toByteArray()) }
   assertEquals(NativeFolderChooser.UNAVAILABLE, failure.message)
  }
 }

 @Test fun selectedDirectoriesPreserveUnicodeAndWhitespace() {
  val selected = "C:\\目录 with spaces\\ selected "
  val encoded = Base64.getEncoder().encode(selected.toByteArray())
  assertEquals(selected, NativeFolderChooser.selection("windows", 0, encoded))
  assertEquals("/目录/ selected ", NativeFolderChooser.selection("linux", 0, "/目录/ selected \n".toByteArray()))
 }

 @Test fun oversizedOutputIsRejected() {
  assertFailsWith<IllegalStateException> { NativeFolderChooser.selection("linux", 0, ByteArray(32769)) }
 }
}
