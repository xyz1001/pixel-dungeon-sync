package pdsync

import pdsync.android.app.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class AndroidProfileVersionsTest {
 private val origin = "a".repeat(64)
 private fun identity(packageName: String = "org.example.game", source: String = origin, capabilities: AndroidSaveCapabilities = AndroidSaveCapabilities()) =
  AndroidGameIdentity(packageName, "Test game", source, capabilities)
 private fun target(identity: AndroidGameIdentity, version: Long = 42) = AndroidGameTarget(identity, "arbitrary release", version, "b".repeat(64), "c".repeat(64))
 private fun policy(identity: AndroidGameIdentity, version: Long = 42) = AndroidProfileVersions(identity, AndroidGameAdapter(identity)) { target(identity, version) }
 private fun snapshot(identity: AndroidGameIdentity, version: Int = 896): Snapshot = Snapshots.fromFiles(
  mapOf("game1/game.dat" to "{\"version\":$version}".encodeToByteArray()), AndroidGameAdapter(identity), "demo", "capture")
 @Test fun everyCurrentCaptureRecordsPackageAndSourceProvenance() {
  for (source in listOf(origin, "32cd68da7bd2cdb467330cf33ca7c0e9b18eaae94ecf0b4b944313def1732f89")) {
   val id = identity("com.shatteredpixel.shatteredpixeldungeon", source)
   val captured = policy(id, 896).annotate(snapshot(id))
   assertEquals(id.packageName, captured.manifest.gamePackage)
   assertEquals(GameSaveSource(source, "c".repeat(64)), captured.manifest.gameSource)
  }
 }
 @Test fun samePackageDifferentSourceAndApkRemainCompatible() {
  val id = identity(); val original = policy(id).annotate(snapshot(id))
  val other = identity(source = "d".repeat(64)); val versions = policy(other)
  versions.checkRestore(original, "")
  assertEquals(original.manifest.gameSource, versions.annotate(original).manifest.gameSource)
  assertEquals(id.packageName, versions.annotate(original).manifest.gamePackage)
 }
 @Test fun differentPackageNeverBecomesCompatibleThroughSharedNamespace() {
  val id = identity(); val original = policy(id).annotate(snapshot(id))
  assertFails { policy(identity("org.example.different")).checkRestore(original, "") }
 }
 @Test fun unknownLegacyPackageCannotBeInferredFromNamespace() {
  val id = identity(); val original = snapshot(id)
  val missingPackage = original.copy(manifest = original.manifest.copy(gamePackage = null))
  assertFails { policy(id).checkRestore(missingPackage, "") }
  val sourceOnly = missingPackage.copy(manifest = missingPackage.manifest.copy(gameSource = GameSaveSource(origin, "b".repeat(64))))
  assertFails { policy(id).checkRestore(sourceOnly, "") }
  assertFails { policy(identity(source = "d".repeat(64))).checkRestore(sourceOnly, "") }
 }
 @Test fun unknownLoaderAllowsRecognizedWriter() {
  val id = identity(); val versions = policy(id)
  versions.checkRestore(versions.annotate(snapshot(id, 1234)), "")
  assertNull(versions.target("").minimumSaveVersion)
 }
 @Test fun knownPackageUsesLoaderBoundsWithoutSignerGate() {
  val id = identity("com.shatteredpixel.shatteredpixeldungeon")
  val versions = policy(id, 896)
  assertEquals(802, versions.target("").minimumSaveVersion)
  versions.checkRestore(versions.annotate(snapshot(id, 896)), "")
  assertFails { versions.checkRestore(versions.annotate(snapshot(id, 897)), "") }
  assertFails { versions.checkRestore(versions.annotate(snapshot(id, 801)), "") }
 }
 @Test fun fourPointLoaderHasKnownRestoreBounds() {
  val id = identity("com.shatteredpixel.shatteredpixeldungeon"); val versions = policy(id, 922)
  assertEquals(850, versions.target("").minimumSaveVersion)
 }
 @Test fun explicitCapabilityWorksForAnotherPackage() {
  val id = identity(capabilities = AndroidSaveCapabilities(802, 896)); val versions = policy(id)
  assertEquals(802, versions.target("").minimumSaveVersion)
  versions.checkRestore(versions.annotate(snapshot(id, 896)), "")
 }
 @Test fun malformedAndGlobalOnlySavesNeverProveCompatibility() {
  val id = identity(); val adapter = AndroidGameAdapter(id); val versions = policy(id)
  val malformed = Snapshots.fromFiles(mapOf("game1/game.dat" to "invalid".encodeToByteArray()), adapter, "demo", "capture")
  val failure = assertFailsWith<UnsupportedAndroidSaveFormatException> { versions.annotate(malformed) }
  assertEquals(SyncBlockReason.UNSUPPORTED_SAVE_FORMAT, failure.syncBlockReason)
  val globals = versions.annotate(Snapshots.fromFiles(mapOf("badges.dat" to "opaque".encodeToByteArray()), adapter, "demo", "capture"))
  assertFails { versions.checkRestore(globals, "") }
 }
 @Test fun manifestVersionMetadataCannotOverrideBytes() {
  val id = identity(); val versions = policy(id); val save = versions.annotate(snapshot(id))
  assertFails { versions.checkRestore(save.copy(manifest = save.manifest.copy(saveVersions = SaveVersions(mapOf("game1" to 1)))), "") }
 }
 @Test fun capabilityAndPackageValidationAndConnectionRedaction() {
  assertFails { AndroidSaveCapabilities(802, null) }; assertFails { AndroidSaveCapabilities(900, 896) }
  assertFails { identity("../bad") }; assertFails { identity(source = "not-a-digest") }
  assertFails { AndroidGameIdentity("org.example.game", "bad\nlabel", origin) }
  assertFalse(AndroidConnection("https://localhost/", "user", "secret").toString().contains("secret"))
  assertEquals("shattered", identity("com.shatteredpixel.shatteredpixeldungeon").namespace)
 }

}
