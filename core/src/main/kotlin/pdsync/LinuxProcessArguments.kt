package pdsync

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Linux-only fallback for a kernel/JDK-identified Java executable whose JDK argv is absent.
 * Linux JDKs may omit argv when the command exceeds their internal buffer. Never use argv[0]
 * to establish executable identity; never print or persist the returned argument values.
 */
internal object LinuxProcessArguments {
 private const val MAX_BYTES = 2 * 1024 * 1024
 private const val MAX_STAT_BYTES = 8 * 1024
 private const val MAX_ARGUMENTS = 16_384
 private const val MAX_ARGUMENT_LENGTH = 32_768

 fun read(pid: Long): List<String>? = read(pid) { path, limit ->
  Files.newInputStream(path).use { it.readNBytes(limit + 1) }
 }

 /** The reader is injectable for denied reads, malformed bytes and PID-reuse races. */
 internal fun read(pid: Long, reader: (Path, Int) -> ByteArray): List<String>? {
  return try {
  if (pid <= 0) return null
  val base = Path.of("/proc", pid.toString())
  val before = identity(pid, reader(base.resolve("stat"), MAX_STAT_BYTES)) ?: return null
  val bytes = reader(base.resolve("cmdline"), MAX_BYTES)
  val arguments = decode(bytes) ?: return null
  val after = identity(pid, reader(base.resolve("stat"), MAX_STAT_BYTES)) ?: return null
  if (before != after) null else arguments
  } catch (_: Exception) { null }
 }

 private fun decode(bytes: ByteArray): List<String>? {
  if (bytes.isEmpty() || bytes.size > MAX_BYTES || bytes.last() != 0.toByte()) return null
  if (bytes.count { it == 0.toByte() } > MAX_ARGUMENTS + 1) return null
  val decoded = StandardCharsets.UTF_8.newDecoder()
   .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
   .decode(ByteBuffer.wrap(bytes)).toString()
  val values = decoded.dropLast(1).split('\u0000')
  if (values.isEmpty() || values.size > MAX_ARGUMENTS + 1 || values.any { it.length > MAX_ARGUMENT_LENGTH }) return null
  return values.drop(1) // argv[0] is process-controlled and is never treated as identity.
 }

 /** stat is bounded and its comm field may contain spaces or parentheses. */
 private fun identity(pid: Long, bytes: ByteArray): String? {
  if (bytes.size > MAX_STAT_BYTES) return null
  val value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
   .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
  val firstSpace = value.indexOf(' ')
  if (firstSpace <= 0 || value.substring(0, firstSpace).toLongOrNull() != pid) return null
  val close = value.lastIndexOf(')')
  if (close <= firstSpace || close + 2 >= value.length || value[firstSpace + 1] != '(') return null
  val fields = value.substring(close + 1).trim().split(Regex("\\s+"))
  // fields[0] is field 3 (state); fields[19] is field 22 (starttime, in clock ticks).
  if (fields.size < 20 || fields[0] !in setOf("R", "S", "D", "T", "t", "K", "W", "P", "I")) return null
  return fields[19].takeIf { it.matches(Regex("[0-9]+")) && it.toLongOrNull() != null }
 }
}
