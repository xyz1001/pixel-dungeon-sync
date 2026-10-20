package pdsync

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

/** Newly reconstructed coverage for the APK-derived hash-only reader. */
class ReconstructedFileHashesTest {
 @TempDir lateinit var root: Path
 private fun valid() {
  root.resolve("game1").createDirectories()
  root.resolve("game1/game.dat").writeText("save")
  root.resolve("game1/level.dat").writeText("level")
  root.resolve("rankings.dat").writeText("rank")
  root.resolve("settings.xml").writeText("excluded")
 }
 @Test fun hashesEqualCaptureAndIgnoreExcludedFiles() {
  valid()
  val expected = Snapshots.capture(root, Shattered, "demo", "v1", null).manifest.files
  assertEquals(expected, Snapshots.fileHashes(root, Shattered))
  root.resolve("settings.xml").writeText("changed")
  assertEquals(expected, Snapshots.fileHashes(root, Shattered))
 }
 @Test fun emptyRootHasEmptyHashesAndKeepsEmptyZipBehavior() {
  assertTrue(Snapshots.fileHashes(root, Shattered).isEmpty())
  assertEquals(22, Snapshots.capture(root, Shattered, "demo", "v1", null).zip.size)
 }
 @Test fun fingerprintUsesHashHookWhileActualCaptureBuildsOneArchive() {
  valid(); var captures = 0; var hashReads = 0; var paused = -1L
  val guard = SaveStabilityGuard(root, Shattered, { true }, pause = { paused = it },
   captureSnapshot = { path, adapter, profile, version, parent, context ->
    captures++; Snapshots.capture(path, adapter, profile, version, parent, context)
   },
   captureFileHashes = { path, adapter -> hashReads++; Snapshots.fileHashes(path, adapter) })
  guard.fingerprint(); assertEquals(0, captures)
  val snapshot = guard.capture("demo", "v1", null)
  assertEquals(1, captures); assertTrue(hashReads >= 5); assertEquals(2_000L, paused)
  assertEquals(Snapshots.fileHashes(root, Shattered), snapshot.manifest.files)
 }
 @Test fun incompleteRunsAndAliasesAreRejected() {
  valid()
  root.resolve("game1/game.dat").deleteExisting()
  assertFailsWith<IllegalArgumentException> { Snapshots.fileHashes(root, Shattered) }
  root.resolve("game1/game.dat").writeText("save")
  root.resolve("game1/LEVEL.dat").writeText("alias")
  assertFailsWith<IllegalArgumentException> { Snapshots.fileHashes(root, Shattered) }
 }
 @Test fun symlinkAndOversizeAreRejected() {
  valid()
  val save = root.resolve("game1/game.dat")
  save.deleteExisting(); Files.createSymbolicLink(save, root.resolve("rankings.dat"))
  assertFailsWith<IllegalArgumentException> { Snapshots.fileHashes(root, Shattered) }
  save.deleteExisting()
  RandomAccessFile(save.toFile(), "rw").use { it.setLength(MAX_SNAPSHOT_BYTES.toLong() + 1) }
  assertFailsWith<IllegalArgumentException> { Snapshots.fileHashes(root, Shattered) }
 }
 @Test fun fileCountLimitMatchesCaptureReader() {
  root.resolve("game1").createDirectory()
  root.resolve("game1/game.dat").writeBytes(byteArrayOf())
  repeat(MAX_SNAPSHOT_FILES) { root.resolve("game1/level-$it.dat").writeBytes(byteArrayOf()) }
  assertFailsWith<IllegalArgumentException> { Snapshots.fileHashes(root, Shattered) }
 }
}
