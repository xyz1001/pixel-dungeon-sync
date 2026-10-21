package pdsync.android.app

import pdsync.i18n.CoreMessages
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
  return annotate(snapshot,detect(snapshot))
 }
 override fun annotate(verified: VerifiedSnapshot): Snapshot = annotate(verified.snapshot,detect(verified))
 private fun annotate(snapshot:Snapshot,detected:SaveVersions):Snapshot {
  val installed = installedTarget()
  val source = snapshot.manifest.gameSource ?: GameSaveSource(identity.originalSourceIdentity, installed.sourceApkSha256)
  return snapshot.copy(manifest = snapshot.manifest.copy(version = detected.summary(), saveVersions = detected, gameSource = source,
   gamePackage = snapshot.manifest.gamePackage ?: identity.packageName))
 }
 fun detect(snapshot: Snapshot): SaveVersions = detectFiles { Snapshots.verify(snapshot, adapter) }
 internal fun detect(verified:VerifiedSnapshot):SaveVersions = try { verified.saveVersions }
 catch (failure: IllegalArgumentException) { throw UnsupportedAndroidSaveFormatException(CoreMessages.ANDROID_UNSUPPORTED_OR_UNREADABLE_SAVE_FORMAT_FOR_1_S_2_S_FILES_WERE_NOT_TREATED_AS_AN_EMPTY_SAVE.text(identity.displayName, failure.message), failure) }
 internal fun detectVerifiedEntries(files: Map<String, ByteArray>): SaveVersions = detectFiles { files }
 private fun detectFiles(files: () -> Map<String, ByteArray>): SaveVersions = try { ShatteredVersions.detect(files()) }
 catch (failure: IllegalArgumentException) { throw UnsupportedAndroidSaveFormatException(CoreMessages.ANDROID_UNSUPPORTED_OR_UNREADABLE_SAVE_FORMAT_FOR_1_S_2_S_FILES_WERE_NOT_TREATED_AS_AN_EMPTY_SAVE.text(identity.displayName, failure.message), failure) }
 override fun target(path: String): TargetGame {
  val installed = installedTarget(); val capability = capabilities(installed)
  return TargetGame("android-package:${installed.packageName}", installed.versionName, capability.maximumRestoreVersion ?: 0,
   installed.apkSha256, true, capability.minimumRestoreVersion,
   if (capability.minimumRestoreVersion == null) CoreMessages.ANDROID_SAME_PACKAGE_RECOGNIZED_SAVES_CAN_BE_RESTORED_WITHOUT_CHANGING_THEIR_BYTES_THIS_PACKAGE_S.text()
   else CoreMessages.ANDROID_SAME_PACKAGE_RESTORE_USES_THE_KNOWN_SAVE_LOADER_VERSION_RANGE_ORIGINAL_SIGNER_AND_APK_FING.text(),
   if (capability.mergePolicy == AndroidMergePolicy.SHATTERED_896) "shattered-896" else null)
 }
 override fun checkRestore(snapshot: Snapshot, targetPath: String) {
  checkRestore(snapshot,targetPath,detect(snapshot))
 }
 override fun checkRestore(verified:VerifiedSnapshot,targetPath:String) =
  checkRestore(verified.snapshot,targetPath,detect(verified))
 private fun checkRestore(snapshot:Snapshot,targetPath:String,detected:SaveVersions) {
  snapshot.manifest.saveVersions?.let { require(it == detected) { CoreMessages.ANDROID_SAVE_VERSION_METADATA_DOES_NOT_MATCH_THE_SNAPSHOT_BYTES.text() } }
  val packageName = snapshot.manifest.gamePackage
  require(packageName != null) { CoreMessages.ANDROID_THIS_SNAPSHOT_HAS_NO_RECORDED_GAME_PACKAGE_AND_CANNOT_BE_RESTORED.text() }
  require(packageName == identity.packageName) { CoreMessages.ANDROID_THIS_SNAPSHOT_BELONGS_TO_A_DIFFERENT_GAME_PACKAGE_A_SHARED_CLOUD_NAMESPACE_DOES_NOT_COMBIN.text() }
  val target = target(targetPath)
  if (target.minimumSaveVersion != null) ShatteredVersions.requireCompatible(detected, target)
  else require(detected.runs.isNotEmpty() && detected.unknownReason == null) { CoreMessages.ANDROID_RESTORE_IS_UNSUPPORTED_BECAUSE_THESE_FILES_CONTAIN_NO_RECOGNIZED_LIVE_SAVE_WRITER_VERSION.text() }
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
   throw RunMergeException(CoreMessages.ANDROID_THIS_GAME_S_SEMANTIC_MERGE_FORMAT_HAS_NOT_BEEN_VERIFIED_ITS_SNAPSHOTS_CAN_BE_KEPT_AS_SEPAR.text())
 }
}
