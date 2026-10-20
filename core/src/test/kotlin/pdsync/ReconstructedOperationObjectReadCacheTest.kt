package pdsync

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlin.io.path.*
import kotlin.test.*

/** Newly reconstructed regression tests, not recovered original code90 tests. */
class ReconstructedOperationObjectReadCacheTest {
 @TempDir lateinit var root: Path
 private fun directories() = Files.list(root).use { it.filter { p -> Files.isDirectory(p, NOFOLLOW_LINKS) }.toList() }
 private fun bin(directory: Path) = Files.list(directory).use { it.filter { p -> p.name.endsWith(".bin") }.toList().single() }

 @Test fun memoryModeCopiesBytesAndEvictsByAccessOrderAndBudget() {
  OperationObjectReadCache.open(null).use { cache ->
   val bytes = byteArrayOf(1, 2, 3)
   cache.remember("source", bytes); bytes[0] = 9
   val returned = assertNotNull(cache.read("source")); returned[1] = 9
   assertContentEquals(byteArrayOf(1, 2, 3), cache.read("source"))
   repeat(255) { cache.remember("entry-$it", byteArrayOf(it.toByte())) }
   cache.read("source")
   cache.remember("new", byteArrayOf(4))
   assertFalse(cache.contains("entry-0")); assertTrue(cache.contains("source"))
   cache.remember("too-large", ByteArray(8 * 1024 * 1024 + 1))
   assertFalse(cache.contains("too-large"))
   cache.remember("full", ByteArray(8 * 1024 * 1024))
   assertFalse(cache.contains("source")); assertTrue(cache.contains("full"))
  }
 }

 @Test fun diskModeSupportsAboveEightMiBAndCleansOnlyItsScope() {
  root.resolve("unrelated").writeText("keep")
  val cache = OperationObjectReadCache.open(root)
  val directory = directories().single()
  if ("posix" in directory.fileSystem.supportedFileAttributeViews()) {
   assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory))
  }
  val bytes = ByteArray(8 * 1024 * 1024 + 19) { (it % 251).toByte() }
  cache.remember("large", bytes)
  assertEquals(bytes.size.toLong(), Files.size(bin(directory)))
  assertContentEquals(bytes, cache.read("large"))
  cache.close()
  assertFalse(directory.exists()); assertFalse(cache.contains("large")); assertNull(cache.read("large"))
  assertEquals("keep", root.resolve("unrelated").readText())
 }

 @Test fun damagedOrMissingDiskBytesAreEvictedRatherThanTrusted() {
  OperationObjectReadCache.open(root).use { cache ->
   val directory = directories().single()
   cache.remember("data", byteArrayOf(1, 2, 3))
   bin(directory).writeBytes(byteArrayOf(3, 2, 1))
   assertNull(cache.read("data")); assertFalse(cache.contains("data"))
   cache.remember("data", byteArrayOf(1, 2, 3))
   bin(directory).deleteExisting()
   assertNull(cache.read("data"))
   cache.remember("data", byteArrayOf(1, 2, 3))
   bin(directory).writeBytes(byteArrayOf(1, 2, 3, 4))
   assertNull(cache.read("data"))
  }
 }

 @Test fun activeScopesSurviveOtherScopeMaintenance() {
  val first = OperationObjectReadCache.open(root)
  first.remember("one", byteArrayOf(1))
  val firstDirectory = directories().single()
  OperationObjectReadCache.open(root).use { second ->
   assertTrue(firstDirectory.exists())
   assertContentEquals(byteArrayOf(1), first.read("one"))
   assertNull(second.read("one"))
   second.remember("two", byteArrayOf(2))
   assertEquals(2, directories().size)
  }
  assertEquals(listOf(firstDirectory), directories())
  first.close(); assertTrue(directories().isEmpty())
 }

 @Test fun cleanupRequiresExactMarkerAndRecognizedChildren() {
  fun stale(marker: String, extra: Boolean = false): Path {
   val directory = root.resolve("pdsync-object-read-v1-${UUID.randomUUID()}").createDirectory()
   directory.resolve("owner-v1").writeText(marker)
   directory.resolve("active.lock").writeBytes(byteArrayOf())
   directory.resolve("${UUID.randomUUID()}.bin").writeBytes(byteArrayOf(1))
   if (extra) directory.resolve("unknown.txt").writeText("keep")
   return directory
  }
  val recognized = stale("pdsync-operation-object-read-v1\n")
  val wrongMarker = stale("not-owned\n")
  val unknownChild = stale("pdsync-operation-object-read-v1\n", extra = true)
  OperationObjectReadCache.open(root).use {
   assertFalse(recognized.exists())
   assertTrue(wrongMarker.exists()); assertTrue(unknownChild.exists())
  }
 }

 @Test fun unsafeRootDisablesCacheWithoutFollowingLinks() {
  val elsewhere = root.resolve("elsewhere").createDirectory()
  val link = root.resolve("link")
  Files.createSymbolicLink(link, elsewhere)
  OperationObjectReadCache.open(link).use {
   it.remember("secret", byteArrayOf(1))
   assertFalse(it.contains("secret")); assertNull(it.read("secret"))
  }
  assertTrue(Files.list(elsewhere).use { it.count() } == 0L)
  val file = root.resolve("regular-file").also { it.writeText("keep") }
  OperationObjectReadCache.open(file).use { it.remember("x", byteArrayOf(1)); assertNull(it.read("x")) }
  assertEquals("keep", file.readText())
 }

 @Test fun cumulativeDiskReservationIsNotReclaimedByRemoval() {
  OperationObjectReadCache.open(root).use { cache ->
   val bytes = ByteArray(32 * 1024 * 1024)
   repeat(8) {
    cache.remember("same", bytes)
    assertTrue(cache.contains("same"))
    cache.remove("same")
   }
   cache.remember("over-budget", byteArrayOf(1))
   assertFalse(cache.contains("over-budget"))
  }
 }

 @Test fun cumulativeEntryReservationRemainsBoundedForEmptyObjects() {
  OperationObjectReadCache.open(root).use { cache ->
   repeat(MAX_COMMITS) {
    cache.remember("same", byteArrayOf())
    assertTrue(cache.contains("same"))
    cache.remove("same")
   }
   cache.remember("over-count", byteArrayOf())
   assertFalse(cache.contains("over-count"))
  }
 }
}
