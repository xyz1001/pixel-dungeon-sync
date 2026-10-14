package pdsync

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pdsync.presentation.HistoryPresentation

class SaveSummaryPlayCountTest {
 @TempDir lateinit var temp:Path

 private fun gzip(text:String):ByteArray = ByteArrayOutputStream().also { output ->
  GZIPOutputStream(output).use { it.write(text.encodeToByteArray()) }
 }.toByteArray()

 private fun summary(rankings:ByteArray?):SaveSummary {
  val files=linkedMapOf("game1/game.dat" to "{\"version\":896}".encodeToByteArray())
  rankings?.let { files["rankings.dat"]=it }
  val snapshot=Snapshots.fromFiles(files,Shattered,"play-count-test","test")
  val before=Snapshots.verify(snapshot,Shattered).mapValues { it.value.copyOf() }
  val actual=SaveSummary.from(snapshot)
  val after=Snapshots.verify(snapshot,Shattered)
  before.forEach { (path,bytes) -> assertContentEquals(bytes,after[path]) }
  assertNull(snapshot.manifest.metadata)
  return actual
 }

 @Test fun plainAndGzipRankingsReadActualZeroAndPositiveTotal() {
  for(count in listOf(0,23,Int.MAX_VALUE)) {
   val bundle="""{"records":[],"total":$count,"won":0,"latest":-1}"""
   for(bytes in listOf(bundle.encodeToByteArray(),gzip(bundle))) {
    assertEquals(count,summary(bytes).plays)
    assertEquals("已进行${count}场游戏",HistoryPresentation.plays(summary(bytes).plays))
   }
  }
 }

 @Test fun missingRankingsAndMissingTotalRemainNullable() {
  assertNull(summary(null).plays)
  for(bundle in listOf("{}","""{"records":[],"won":0}""","""{"record":{"total":23}}""")) {
   assertNull(summary(bundle.encodeToByteArray()).plays)
   assertNull(summary(gzip(bundle)).plays)
  }
 }

 @Test fun invalidRankingsStayUnknownInternallyAndDisplayZero() {
  val invalid=listOf(
   "not JSON".encodeToByteArray(),byteArrayOf(0x1f,0x8b.toByte(),0),
   """{"total":-1}""".encodeToByteArray(),"""{"total":2147483648}""".encodeToByteArray(),
   """{"total":null}""".encodeToByteArray(),"""{"total":1,"total":23}""".encodeToByteArray())
  for(bytes in invalid) {
   val actual=summary(bytes)
   assertNull(actual.plays)
   assertEquals("已进行0场游戏",HistoryPresentation.plays(actual.plays))
   assertNull(actual.plays)
  }
 }

 @Test fun liveRunWithoutRankingsDoesNotCreateCountOrMetadataFiles() {
  val root=temp.resolve("saves").createDirectory()
  root.resolve("game1").createDirectory()
  root.resolve("game1/game.dat").writeText("{\"version\":896}")
  root.resolve("game1/depth1.dat").writeText("synthetic-floor")
  root.resolve("journal.dat").writeText("{\"journal\":[]}")
  fun observed()=Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList()
   .associate { root.relativize(it).toString() to it.readBytes().toList() } }
  val before=observed()
  val actual=SaveSummary.read(root)
  assertEquals(setOf("game1"),actual.slots.keys)
  assertEquals(3,actual.files)
  assertNull(actual.plays)
  assertEquals("已进行0场游戏",HistoryPresentation.plays(actual.plays))
  assertEquals(before,observed())
  assertFalse(root.resolve("rankings.dat").exists())
 }
}
