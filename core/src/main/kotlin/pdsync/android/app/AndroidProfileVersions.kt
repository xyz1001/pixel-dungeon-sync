package pdsync.android.app

import pdsync.*

class UnsupportedAndroidSaveFormatException(message: String, cause: Throwable? = null): IllegalArgumentException(message, cause), ReasonedSyncFailure {
 override val syncBlockReason get() = SyncBlockReason.UNSUPPORTED_SAVE_FORMAT
}

/** Package family establishes compatibility; signing provenance never acts as a save allowlist. */
class AndroidProfileVersions(
 private val identity: AndroidGameIdentity,
 private val adapter: GameAdapter,
 private val installedTarget: () -> AndroidGameTarget
): ProfileVersions {
 override fun annotate(snapshot: Snapshot): Snapshot {
  val detected = detect(snapshot)
  val installed = installedTarget()
  val source = snapshot.manifest.gameSource ?: if (identity.originalSourceIdentity == AndroidGameIdentity.KNOWN_SHATTERED_ORIGIN && capabilities(installed).minimumRestoreVersion != null) null
   else GameSaveSource(identity.originalSourceIdentity, installed.sourceApkSha256)
  val sameSource = snapshot.manifest.gameSource == null || snapshot.manifest.gameSource.originalSourceIdentity == identity.originalSourceIdentity
  val legacyShattered = snapshot.manifest.game == Shattered.id && source == null && identity.packageName == "com.shatteredpixel.shatteredpixeldungeon"
  return snapshot.copy(manifest = snapshot.manifest.copy(version = detected.summary(), saveVersions = detected, gameSource = source,
   gamePackage = snapshot.manifest.gamePackage ?: if (sameSource && !legacyShattered) identity.packageName else null))
 }
 fun detect(snapshot: Snapshot): SaveVersions = try { ShatteredVersions.detect(Snapshots.verify(snapshot, adapter)) }
 catch (failure: IllegalArgumentException) { throw UnsupportedAndroidSaveFormatException("Unsupported or unreadable save format for ${identity.displayName}: ${failure.message}. Files were not treated as an empty save directory.", failure) }
 override fun target(path: String): TargetGame {
  val installed = installedTarget(); val capability = capabilities(installed)
  return TargetGame("android-package:${installed.packageName}", installed.versionName, capability.maximumRestoreVersion ?: 0,
   installed.apkSha256, true, capability.minimumRestoreVersion,
   if (capability.minimumRestoreVersion == null) "Same-package recognized saves can be restored without changing their bytes. This package's loader version bounds are unknown; arbitrary APK version labels do not establish them. Semantic merge requires a known schema."
   else "Same-package restore uses the known save-loader version range; original signer and APK fingerprints remain provenance, not compatibility restrictions.",
   if (capability.mergePolicy == AndroidMergePolicy.SHATTERED_896) "shattered-896" else null)
 }
 override fun checkRestore(snapshot: Snapshot, targetPath: String) {
  val detected = detect(snapshot)
  snapshot.manifest.saveVersions?.let { require(it == detected) { "Save-version metadata does not match the snapshot bytes." } }
  val packageName = recordedGamePackage(snapshot.manifest)
   ?: if (snapshot.manifest.gameSource?.originalSourceIdentity == identity.originalSourceIdentity) identity.packageName else null
  require(packageName != null) { "This legacy snapshot has no recorded game package. Its cloud namespace alone cannot establish the package family." }
  require(packageName == identity.packageName) { "This snapshot belongs to a different game package. A shared cloud namespace does not combine different package families." }
  val target = target(targetPath)
  if (target.minimumSaveVersion != null) ShatteredVersions.requireCompatible(detected, target)
  else require(detected.runs.isNotEmpty() && detected.unknownReason == null) { "Restore is unsupported because these files contain no recognized live save writer version." }
 }
 private fun capabilities(installed: AndroidGameTarget): AndroidSaveCapabilities {
  if (identity.saveCapabilities.minimumRestoreVersion != null) return identity.saveCapabilities
  if (identity.packageName == "com.shatteredpixel.shatteredpixeldungeon") return when (installed.versionCode) {
   896L -> AndroidSaveCapabilities(802, 896, AndroidMergePolicy.SHATTERED_896)
   922L -> AndroidSaveCapabilities(850, 922)
   else -> identity.saveCapabilities
  }
  return identity.saveCapabilities
 }
 fun requireMerge() {
  if (capabilities(installedTarget()).mergePolicy != AndroidMergePolicy.SHATTERED_896)
   throw RunMergeException("This game's semantic merge format has not been verified; its snapshots can be kept as separate branches")
 }
}
