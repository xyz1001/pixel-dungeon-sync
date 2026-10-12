package pdsync

import java.io.InputStream
import java.io.IOException
import kotlin.test.*
import org.junit.jupiter.api.Test

class JvmIoCompatTest {
 @Test fun boundsAndEndOfStream() {
  assertContentEquals(byteArrayOf(), byteArrayOf(1).inputStream().readUpTo(0))
  assertContentEquals(byteArrayOf(1, 2), byteArrayOf(1, 2, 3).inputStream().readUpTo(2))
  assertContentEquals(byteArrayOf(1, 2), byteArrayOf(1, 2).inputStream().readUpTo(4))
  assertFailsWith<IllegalArgumentException> { byteArrayOf().inputStream().readUpTo(-1) }
 }
 @Test fun compressedSaveSummaryUsesTheAndroidSafeBoundedReader() {
  val output = java.io.ByteArrayOutputStream()
  java.util.zip.GZIPOutputStream(output).use { it.write("{\"total\":7}".encodeToByteArray()) }
  val snapshot = Snapshots.fromFiles(mapOf("game1/game.dat" to "{\"version\":896}".encodeToByteArray(), "rankings.dat" to output.toByteArray()), Shattered, "demo", "test")
  assertEquals(7, SaveSummary.from(snapshot).plays)
 }
 @Test fun handlesZeroLengthReadsWithoutSpinningOrExceedingLimit() {
  val stream = object : InputStream() {
   var next = 0
   override fun read(): Int = if(next < 4) next++ else -1
   override fun read(b: ByteArray, off: Int, len: Int): Int = 0
  }
  assertContentEquals(byteArrayOf(0, 1, 2), stream.readUpTo(3))
  assertEquals(3, stream.read())
 }
 @Test fun handlesShortReadsAndLeavesBytesBeyondTheLimitUnread() {
  val source = ByteArray(20_000) { (it % 251).toByte() }
  val stream = object : InputStream() {
   var position = 0
   var closed = false
   override fun read(): Int = if (position < source.size) source[position++].toInt() and 255 else -1
   override fun read(b: ByteArray, off: Int, len: Int): Int {
    if (position == source.size) return -1
    val count = minOf(len, 7, source.size - position)
    source.copyInto(b, off, position, position + count); position += count
    return count
   }
   override fun close() { closed = true }
  }
  assertContentEquals(source.copyOfRange(0, 16_384), stream.readUpTo(16_384))
  assertEquals(16_384, stream.position); assertFalse(stream.closed)
  assertContentEquals(source.copyOfRange(16_384, source.size), stream.readUpTo(10_000))
  assertContentEquals(byteArrayOf(), stream.readUpTo(1))
 }
 @Test fun hugeLimitDoesNotAllocateTheLimitUpFrontAndReadFailuresPropagate() {
  assertContentEquals(byteArrayOf(1, 2), byteArrayOf(1, 2).inputStream().readUpTo(Int.MAX_VALUE))
  val failure = IOException("synthetic read failure")
  val stream = object : InputStream() {
   override fun read(): Int = throw failure
  }
  assertContentEquals(byteArrayOf(), stream.readUpTo(0))
  assertSame(failure, assertFailsWith<IOException> { stream.readUpTo(1) })
 }
}
