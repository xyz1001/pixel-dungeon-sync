package pdsync

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.Test

class SaveStabilityGuardTest {
 @Test fun fingerprintsRetainFreshNativeFileIdentityAfterDirectoryEnumeration() {
  repeat(20) {
   val root = root()
   val fingerprint = SaveStabilityGuard(root, Shattered, { true }).fingerprint()
   for (name in listOf("game1", "game1/game.dat", "rankings.dat")) {
    val attrs = Files.readAttributes(root.resolve(name), java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
    assertEquals(attrs.fileKey()?.toString(), fingerprint.metadata.getValue(name).fileKey)
    assertEquals(attrs.lastModifiedTime(), fingerprint.metadata.getValue(name).modified)
   }
  }
 }
 @Test fun directoryMetadataChangesStillBlockEvenWhenContentsMatch() {
  val root = root(); val directory = root.resolve("game1")
  val guard = SaveStabilityGuard(root, Shattered, { true })
  val before = guard.fingerprint()
  Files.setLastModifiedTime(directory, FileTime.fromMillis(Files.getLastModifiedTime(directory).toMillis() + 10_000))
  assertEquals(before.files, guard.fingerprint().files)
  assertFailsWith<SaveNotStableException> { guard.requireUnchanged(before) }
 }
 private fun root(): Path = Files.createTempDirectory("stable-save-").also {
  it.resolve("game1").createDirectories(); it.resolve("game1/game.dat").writeText("initial")
  it.resolve("rankings.dat").writeText("rank")
  it.resolve("settings.xml").writeText("excluded")
 }
 @Test fun oneObservationHasNoQuietWaitAndKeepsDeterministicHashes() {
  val root = root(); var checks = 0
  val guard = SaveStabilityGuard(root, Shattered, { checks++; true })
  val first = guard.fingerprint(); val second = guard.awaitStable()
  assertEquals(first, second); assertEquals(1, checks)
  val capture = guard.capture("demo", "v1", null)
  assertEquals(first.files, capture.manifest.files)
  assertFalse(capture.manifest.files.containsKey("settings.xml"))
 }
 @Test fun sameSizeWriteWithRestoredMtimeStillChangesFingerprint() {
  val root = root(); val save = root.resolve("game1/game.dat"); val time = Files.getLastModifiedTime(save)
  val guard = SaveStabilityGuard(root, Shattered, { true })
  val before = guard.fingerprint(); save.writeText("altered"); Files.setLastModifiedTime(save, time)
  assertNotEquals(before.files, guard.fingerprint().files)
  assertFails { guard.requireUnchanged(before) }
 }
 @Test fun metadataOnlyChangesAreRejectedToo() {
  val root = root(); val save = root.resolve("game1/game.dat")
  val guard = SaveStabilityGuard(root, Shattered, { true })
  val before = guard.fingerprint()
  Files.setLastModifiedTime(save, FileTime.fromMillis(Files.getLastModifiedTime(save).toMillis() + 10_000))
  assertEquals(before.files, guard.fingerprint().files); assertFails { guard.requireUnchanged(before) }
 }
 @Test fun excludedFilesDoNotCreateFalseDirtyChanges() {
  val root = root(); val guard = SaveStabilityGuard(root, Shattered, { true })
  val first = guard.fingerprint(); root.resolve("settings.xml").writeText("new local settings")
  root.resolve("bones.dat").writeText("excluded bones")
  guard.requireUnchanged(first)
 }
 @Test fun callerGateIsEvaluatedOnceWithoutAQuietWindow() {
  val root = root(); var checks = 0
  val guard = SaveStabilityGuard(root, Shattered, { checks++; true })
  assertNotNull(guard.capture("demo", "v1", null))
  assertEquals(1, checks)
  assertEquals("initial", root.resolve("game1/game.dat").readText())
  assertNotNull(SaveStabilityGuard(root, Shattered, { false }).capture("demo", "v1", null))
  assertFailsWith<SecurityException> { SaveStabilityGuard(root, Shattered, { throw SecurityException("identity changed") }).capture("demo", "v1", null) }
 }
 @Test fun captureUsesTheBytesFromItsSingleActualRead() {
  val root = root()
  val guard = SaveStabilityGuard(root, Shattered, { true },
   captureSnapshot = { path, adapter, profile, version, parent, context ->
    if (profile == "demo") path.resolve("game1/game.dat").writeText("late mutation")
    Snapshots.capture(path, adapter, profile, version, parent, context)
   })
  assertEquals(hash("late mutation".encodeToByteArray()), guard.capture("demo", "v1", null).manifest.files["game1/game.dat"])
 }
 @Test fun laterMutationIsRejectedByTheExplicitWriteBoundaryCheck() {
  val root = root()
  val guard = SaveStabilityGuard(root, Shattered, { true },
   captureSnapshot = { path, adapter, profile, version, parent, context ->
    Snapshots.capture(path, adapter, profile, version, parent, context).also {
     if (profile == "demo") path.resolve("game1/game.dat").writeText("post-read mutation")
    }
   })
  val captured = guard.captureStable("demo", "v1", null)
  assertEquals(hash("initial".encodeToByteArray()), captured.snapshot.manifest.files["game1/game.dat"])
  assertFails { guard.requireUnchanged(captured.fingerprint) }
 }
 @Test fun anObservationDoesNotClaimThatAWriterCannotChangeFilesLater() {
  val root = root()
  val guard = SaveStabilityGuard(root, Shattered, { true },
   // Reconstructed adaptation: fingerprint no longer calls the ZIP-producing capture hook.
   captureFileHashes = { path, adapter ->
    Snapshots.fileHashes(path, adapter).also { path.resolve("rankings.dat").writeText("new rank bytes") }
   })
  val first = guard.fingerprint()
  assertEquals(hash("rank".encodeToByteArray()), first.files["rankings.dat"])
  assertFails { guard.requireUnchanged(first) }
 }
 @Test fun metadataContextIsPreservedOnlyForActualSnapshot() {
  val root = root(); val context = SnapshotCaptureContext(DeviceIdentity("12345678-1234-4234-9234-123456789abc", "Fixture"), "2026-10-09T00:00:00Z")
  val snapshot = SaveStabilityGuard(root, Shattered, { true }).capture("demo", "v1", null, context)
  assertEquals(context.device, snapshot.manifest.metadata?.device)
  assertEquals(context.capturedAt, snapshot.manifest.metadata?.capturedAt)
 }
 @Test fun singleCaptureRetainsExactFingerprintAndReadsMetadataProviderOnce() {
  val root = root(); var providerCalls = 0
  val guard = SaveStabilityGuard(root, Shattered, { true })
  val captured = guard.captureStable("demo", "v1", null, captureContextProvider = {
   providerCalls++
   SnapshotCaptureContext(DeviceIdentity("12345678-1234-4234-9234-123456789abc", "Fixture"), "2026-10-09T00:00:00Z")
  })
  assertEquals(1, providerCalls); assertEquals(captured.snapshot.manifest.files, captured.fingerprint.files)
  root.resolve("rankings.dat").writeText("late backup drift")
  assertFailsWith<SaveNotStableException> { guard.requireUnchanged(captured.fingerprint) }
 }
 @Test fun missingRootSymlinkAndOversizeFilesAreRejected() {
  val root = root(); val save = root.resolve("game1/game.dat")
  val outside = Files.createTempFile("outside", ".dat")
  save.deleteExisting(); Files.createSymbolicLink(save, outside)
  assertFails { SaveStabilityGuard(root, Shattered, { true }).fingerprint() }
  assertFails { SaveStabilityGuard(root.resolve("missing"), Shattered, { true }).fingerprint() }
  save.deleteExisting()
  java.io.RandomAccessFile(save.toFile(), "rw").use { it.setLength(MAX_SNAPSHOT_BYTES.toLong() + 1) }
  assertFails { SaveStabilityGuard(root, Shattered, { true }).fingerprint() }
 }
 @Test fun interruptPreservesThreadCancellation() {
  val root = root()
  try {
   Thread.currentThread().interrupt()
   assertFailsWith<InterruptedException> { SaveStabilityGuard(root, Shattered, { true }).awaitStable() }
   assertTrue(Thread.currentThread().isInterrupted)
  } finally { Thread.interrupted() }
 }
 @Test fun oneLocalReadCanProduceASnapshotWithoutReopeningSourceFiles() {
  val root = root()
  val read = Snapshots.readLocal(root, Shattered)
  root.resolve("game1/game.dat").deleteExisting()
  root.resolve("rankings.dat").deleteExisting()
  val snapshot = Snapshots.capture(read, Shattered, "demo", "v1", null)
  assertEquals(read.fingerprint.files, snapshot.manifest.files)
  assertEquals("initial", Snapshots.verify(snapshot, Shattered).getValue("game1/game.dat").decodeToString())
 }
}
