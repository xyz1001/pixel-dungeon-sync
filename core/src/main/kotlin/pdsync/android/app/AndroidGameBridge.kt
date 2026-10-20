package pdsync.android.app

import pdsync.i18n.CoreMessages
import java.nio.file.Path
import java.util.Collections
import pdsync.Snapshot

interface AndroidGameBridge {
 fun exportTo(emptyDestination: Path)
 fun restoreFrom(preparedDirectory: Path, expectedCurrentFiles: Map<String, String>)
 fun targetGame(): AndroidGameTarget
 fun beginHomeReadIdentityScope(): AutoCloseable = AutoCloseable {}
 /** Null is an unsupported capability, with no save mutation or transfer having occurred. */
 fun restoreVerified(snapshot: Snapshot, expectedCurrentFiles: Map<String, String>): AndroidRestoreReceipt? = null
}

/**
 * Successful whole-request verification by the selected backend after its actual readback.
 * The receipt is operation-local evidence; it is never stored as a reusable live-save cache.
 */
class AndroidRestoreReceipt(
 val target: AndroidGameTarget,
 files: Map<String, String>,
 val byteCount: Long
) {
 val files: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(files))
 init {
  require(byteCount in 0..pdsync.MAX_SNAPSHOT_BYTES.toLong())
  require(this.files.size <= pdsync.MAX_SNAPSHOT_FILES)
  this.files.forEach { (name, digest) ->
   require(pdsync.safePath(name) && pdsync.Shattered.includes(name) && !pdsync.isDirectoryMetadata(name))
   require(Regex("[0-9a-f]{64}").matches(digest))
  }
 }
}

data class AndroidGameTarget @JvmOverloads constructor(
 val identity: AndroidGameIdentity,
 val versionName: String,
 val versionCode: Long,
 val apkSha256: String,
 val sourceApkSha256: String = apkSha256
) {
 init {
  require(versionName.length <= 256 && versionName.none { it.isISOControl() }) { CoreMessages.ANDROID_INVALID_INSTALLED_GAME_VERSION_LABEL.text() }
  require(versionCode >= 0) { CoreMessages.ANDROID_INVALID_INSTALLED_APK_VERSION_CODE.text() }
  require(Regex("[0-9a-f]{64}").matches(sourceApkSha256)) { CoreMessages.ANDROID_ORIGINAL_GAME_APK_HAS_NOT_BEEN_FINGERPRINTED.text() }
  require(Regex("[0-9a-f]{64}").matches(apkSha256)) { CoreMessages.ANDROID_THE_INSTALLED_GAME_APK_HAS_NOT_BEEN_FINGERPRINTED.text() }
 }
 val packageName get() = identity.packageName
}

class AndroidConnection @JvmOverloads constructor(
 val endpoint: String, val username: String, val password: String, val profile: String = "default",
 retainLegacyOfficialScope: Boolean = false
) {
 // This narrow migration changes transport only. No arbitrary endpoint/account alias is accepted.
 init { require(!retainLegacyOfficialScope || endpoint == "https://webdav.xyz1001.top:8443/") }
 val stateEndpoint = if(retainLegacyOfficialScope) "https://webdav.xyz1001.top:8443/dav/" else endpoint
 override fun toString() = "AndroidConnection(profile=$profile, credentials=<redacted>)"
}
