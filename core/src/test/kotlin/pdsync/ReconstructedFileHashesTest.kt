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
  assertEquals(1, captures); assertEquals(1, hashReads); assertEquals(-1L, paused)
  assertEquals(Snapshots.fileHashes(root, Shattered), snapshot.manifest.files)
 }
 @Test fun incompleteRunsAndAliasesAreRejected() {
  valid()
  root.resolve("game1/game.dat").deleteExisting()
  assertFailsWith<IllegalArgumentException> { Snapshots.fileHashes(root, Shattered) }
  root.resolve("game1/game.dat").writeText("save")
  val original=root.resolve("game1/level.dat");val alias=root.resolve("game1/LEVEL.dat")
  alias.writeText("alias")
  if(Files.isSameFile(original,alias)) {
   // Windows exposes one file. Exercise the collision guard with two real ZIP entries.
   assertEquals(hash("alias".encodeToByteArray()),Snapshots.fileHashes(root,Shattered).getValue("game1/level.dat"))
   val bytes=java.io.ByteArrayOutputStream()
   java.util.zip.ZipOutputStream(bytes).use {zip->
    for(name in listOf("game1/game.dat","game1/level.dat","game1/LEVEL.dat")) {
     zip.putNextEntry(java.util.zip.ZipEntry(name));zip.write("save".encodeToByteArray());zip.closeEntry()
    }
   }
   val payload=bytes.toByteArray()
   val manifest=Manifest(game="shattered",profile="demo",version="v1",id=java.util.UUID.randomUUID().toString(),parent=null,sha256=hash(payload),
    files=listOf("game1/game.dat","game1/level.dat","game1/LEVEL.dat").associateWith {hash("save".encodeToByteArray())})
   val failure=assertFailsWith<IllegalArgumentException> {Snapshots.verify(Snapshot(manifest,payload),Shattered)}
   assertEquals(pdsync.i18n.CoreValidationMessages.UNSAFE_OR_ALIASED_ARCHIVE_PATH.text(),failure.message)
  } else {
   val failure=assertFailsWith<IllegalArgumentException> {Snapshots.fileHashes(root,Shattered)}
   assertEquals(pdsync.i18n.CoreValidationMessages.UNSAFE_OR_ALIASED_SAVE_PATH.text(),failure.message)
  }
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
