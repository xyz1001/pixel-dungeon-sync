package pdsync.android.app

import java.nio.file.Path

interface AndroidGameBridge {
 fun exportTo(emptyDestination: Path)
 fun restoreFrom(preparedDirectory: Path, expectedCurrentFiles: Map<String, String>)
 fun targetGame(): AndroidGameTarget
}

data class AndroidGameTarget @JvmOverloads constructor(
 val identity: AndroidGameIdentity,
 val versionName: String,
 val versionCode: Long,
 val apkSha256: String,
 val sourceApkSha256: String = apkSha256,
 val legacyRecoveryReceiptVerified: Boolean = false
) {
 init {
  require(versionName.length <= 256 && versionName.none { it.isISOControl() }) { "Invalid installed game version label." }
  require(versionCode >= 0) { "Invalid installed APK version code." }
  require(Regex("[0-9a-f]{64}").matches(sourceApkSha256)) { "Original game APK has not been fingerprinted." }
  require(Regex("[0-9a-f]{64}").matches(apkSha256)) { "The installed game APK has not been fingerprinted." }
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
