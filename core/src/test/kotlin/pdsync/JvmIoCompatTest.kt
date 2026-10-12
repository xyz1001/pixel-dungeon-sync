package pdsync

import java.io.InputStream
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
}
