package pdsync

import org.junit.jupiter.api.Test
import pdsync.android.app.AndroidArchiveSnapshot
import kotlin.test.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AndroidArchiveSnapshotTest {
 private fun zip(files:Map<String,ByteArray>):ByteArray {
  val out=ByteArrayOutputStream()
  ZipOutputStream(out).use {z->files.forEach {(name,bytes)->z.putNextEntry(ZipEntry(name));z.write(bytes);z.closeEntry()}}
  return out.toByteArray()
 }
 @Test fun hashesEachEntryOnceAndPreservesContentIdentity() {
  val files=mapOf("game1/game.dat" to "{\"version\":896}".encodeToByteArray(),"game1/depth1.dat" to byteArrayOf(1,2))
  val archive=zip(files);var hashes=0
  val result=AndroidArchiveSnapshot.read(archive,Shattered) {hashes++;hash(it)}
  assertEquals(files.size,hashes)
  assertEquals(contentSha256(files.mapValues {hash(it.value)}),contentSha256(result.files))
  files.forEach {(name,bytes)->assertContentEquals(bytes,result.entries.getValue(name))}
 }
 @Test fun rejectsUnsafePathAndHonorsCancellation() {
  assertFails {AndroidArchiveSnapshot.read(zip(mapOf("../game.dat" to byteArrayOf(1))),Shattered)}
  Thread.currentThread().interrupt()
  try {assertFailsWith<InterruptedException> {AndroidArchiveSnapshot.read(zip(mapOf("game1/game.dat" to byteArrayOf(1))),Shattered)}}
  finally {Thread.interrupted()}
 }
}
