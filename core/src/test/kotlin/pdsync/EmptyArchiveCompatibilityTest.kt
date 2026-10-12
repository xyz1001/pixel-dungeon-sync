package pdsync

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.io.path.*
import kotlin.test.*

class EmptyArchiveCompatibilityTest {
 @TempDir lateinit var temp: Path
 private val eocd = byteArrayOf(0x50,0x4b,0x05,0x06) + ByteArray(18)
 private fun assertEmpty(snapshot: Snapshot) {
  assertContentEquals(eocd,snapshot.zip)
  assertTrue(snapshot.manifest.files.isEmpty())
  assertTrue(Snapshots.verify(snapshot,Shattered).isEmpty())
  ZipInputStream(snapshot.zip.inputStream()).use { assertNull(it.nextEntry) }
  val path=temp.resolve("${snapshot.manifest.id}.zip").also { it.writeBytes(snapshot.zip) }
  ZipFile(path.toFile()).use { assertEquals(0,it.size()); assertFalse(it.entries().hasMoreElements()) }
 }
 @Test fun oldWriterReallyFailsWhenAndroidEmulationIsEnabled() {
  if(System.getProperty("pdsync.androidEmptyZipEmulation")=="true") {
   val error=assertFailsWith<ZipException> { ZipOutputStream(ByteArrayOutputStream()).use { } }
   assertEquals("No entries",error.message)
  }
 }
 @Test fun bothCoreWritersProduceCanonicalEmptyArchives() {
  assertEmpty(Snapshots.fromFiles(emptyMap(),Shattered,"test","v1"))
  assertEmpty(Snapshots.capture(temp.resolve("empty").createDirectory(),Shattered,"test","v1",null))
 }
 @Test fun directoriesWithoutFilesDoNotCreateFakeArchiveEntries() {
  val root=temp.resolve("directories").createDirectory();root.resolve("game1").createDirectory()
  assertEmpty(Snapshots.capture(root,Shattered,"test","v1",null))
 }
 @Test fun nonemptyArchiveStillRoundtripsAndRejectsChecksumCorruption() {
  val files=mapOf("game1/game.dat" to """{"version":896,"init_ver":896}""".encodeToByteArray())
  val snapshot=Snapshots.fromFiles(files,Shattered,"test","v1")
  assertContentEquals(files.getValue("game1/game.dat"),Snapshots.verify(snapshot,Shattered).getValue("game1/game.dat"))
  val broken=snapshot.zip.copyOf().also { it[it.lastIndex]=(it.last()+1).toByte() }
  assertFails { Snapshots.verify(snapshot.copy(zip=broken),Shattered) }
  assertFails { Snapshots.fromFiles(mapOf("game1/../evil" to byteArrayOf(1)),Shattered,"test","v1") }
 }
 @Test fun emptyArchivesRestoreAndBackUpWithoutDummyFiles() {
  val root=temp.resolve("restore").createDirectory();root.resolve("game1").createDirectory();root.resolve("game1/game.dat").writeText("alive")
  val snapshot=Snapshots.fromFiles(emptyMap(),Shattered,"test","v1")
  val backup=Snapshots.restore(snapshot,root,temp.resolve("backups"),Shattered,"v1",{true})
  assertTrue(root.listDirectoryEntries().isEmpty())
  ZipFile(backup.toFile()).use { assertEquals("game1/game.dat",it.entries().nextElement().name) }
 }
}
