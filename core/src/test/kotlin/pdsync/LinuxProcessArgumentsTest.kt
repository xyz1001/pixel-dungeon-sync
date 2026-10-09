package pdsync

import java.nio.file.AccessDeniedException
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test

class LinuxProcessArgumentsTest {
 private val pid = 42L
 private fun stat(start: String = "123456", state: String = "S", actualPid: Long = pid): ByteArray =
  "$actualPid (java name (with) spaces) $state ${List(18) { "0" }.joinToString(" ")} $start 0 0\n".encodeToByteArray()
 private fun args(vararg arguments: String) = (listOf("arbitrary argv zero") + arguments).joinToString("\u0000", postfix = "\u0000").encodeToByteArray()
 private fun read(bytes: ByteArray, before: ByteArray = stat(), after: ByteArray = before): List<String>? {
  var reads = 0
  return LinuxProcessArguments.read(pid) { path, _ -> if (path.fileName.toString() == "cmdline") bytes else if (reads++ == 0) before else after }
 }
 @Test fun boundedNullDelimitedArgumentsPreserveSpacesAndEmptyValuesWithoutTrustingArgvZero() {
  assertEquals(listOf("-cp", "path with spaces", "", "my.Main"), read(args("-cp", "path with spaces", "", "my.Main")))
 }
 @Test fun longClasspathIsReadableEvenBeyondJdkBuffer() {
  val classpath = "x".repeat(12_000)
  assertEquals(listOf("-cp", classpath, "my.Main"), read(args("-cp", classpath, "my.Main")))
 }
 @Test fun deniedReadStaysUnavailableAndNeverRetriesAnotherPath() {
  val visited = mutableListOf<Path>()
  assertNull(LinuxProcessArguments.read(pid) { path, _ -> visited.add(path); throw AccessDeniedException(path.toString()) })
  assertEquals(listOf(Path.of("/proc/42/stat")), visited)
  var reads = 0
  assertNull(LinuxProcessArguments.read(pid) { path, _ -> reads++; if (path.fileName.toString() == "stat") stat() else throw AccessDeniedException(path.toString()) })
  assertEquals(2, reads)
 }
 @Test fun truncatedNonTerminatedEmptyAndInvalidUtf8ArgumentsFailClosed() {
  assertNull(read(ByteArray(0))); assertNull(read("java\u0000-jar\u0000file.jar".encodeToByteArray()))
  assertNull(read(byteArrayOf(0xc3.toByte(), 0x28, 0)))
  assertNull(read(ByteArray(2 * 1024 * 1024 + 1)))
 }
 @Test fun argumentCountAndLengthAreBoundedBeforeLargeSplits() {
  assertNull(read(ByteArray(16_386)))
  assertNull(read(args("x".repeat(32_769))))
 }
 @Test fun pidReuseChangedStartTicksAndUnreadableIdentityFailClosed() {
  assertNull(read(args("main"), after = stat(start = "999999")))
  assertNull(read(args("main"), before = stat(actualPid = 43)))
  assertNull(read(args("main"), before = "42 (java) S 0".encodeToByteArray()))
  assertNull(read(args("main"), before = stat(state = "Z")))
  assertNull(read(args("main"), after = stat(state = "X")))
  assertNull(read(args("main"), before = stat(state = "invalid")))
 }
 @Test fun onlyKnownCurrentAccountJavaCanUseTheLinuxFallback() {
  assertTrue(mayReadLinuxJavaArguments("/usr/bin/java", "player", "player", GameProcessPlatform.LINUX))
  assertFalse(mayReadLinuxJavaArguments("/usr/bin/java", null, "player", GameProcessPlatform.LINUX))
  assertFalse(mayReadLinuxJavaArguments("/usr/bin/java", "player", null, GameProcessPlatform.LINUX))
  assertFalse(mayReadLinuxJavaArguments("/usr/bin/java", "player", "root", GameProcessPlatform.LINUX))
  assertFalse(mayReadLinuxJavaArguments(null, "player", "player", GameProcessPlatform.LINUX))
  assertFalse(mayReadLinuxJavaArguments("/usr/bin/bash", "player", "player", GameProcessPlatform.LINUX))
  assertFalse(mayReadLinuxJavaArguments("/usr/bin/java", "player", "player", GameProcessPlatform.MAC))
 }
}
