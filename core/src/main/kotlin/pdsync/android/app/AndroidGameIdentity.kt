package pdsync.android.app

import pdsync.i18n.CoreMessages
import java.nio.file.Path
import pdsync.GameAdapter
import pdsync.GameCloudIdentity
import pdsync.Shattered


data class AndroidSaveCapabilities @JvmOverloads constructor(
 val minimumRestoreVersion: Int? = null,
 val maximumRestoreVersion: Int? = null
) {
 init {
  require((minimumRestoreVersion == null) == (maximumRestoreVersion == null)) { CoreMessages.ANDROID_BOTH_SAVE_COMPATIBILITY_BOUNDS_ARE_REQUIRED.text() }
  if (minimumRestoreVersion != null) require(minimumRestoreVersion > 0 && maximumRestoreVersion!! >= minimumRestoreVersion) { CoreMessages.ANDROID_INVALID_SAVE_COMPATIBILITY_RANGE.text() }
 }
}

data class AndroidGameIdentity @JvmOverloads constructor(
 val packageName: String,
 val displayName: String,
 val originalSourceIdentity: String,
 val saveCapabilities: AndroidSaveCapabilities = AndroidSaveCapabilities()
) {
 /** Cloud routing is determined only by the verified application package. */
 val namespace: String = GameCloudIdentity.namespace(packageName)
 init {
  require(packageName.length <= 255 && Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(packageName)) { CoreMessages.ANDROID_THE_SELECTED_APK_HAS_NO_VALID_APPLICATION_PACKAGE_NAME.text() }
  require(displayName.length in 1..256 && displayName.none { it.isISOControl() }) { CoreMessages.ANDROID_THE_SELECTED_GAME_LABEL_IS_INVALID.text() }
  require(Regex("[0-9a-f]{64}").matches(originalSourceIdentity)) { CoreMessages.ANDROID_ORIGINAL_APK_SOURCE_IDENTITY_HAS_NOT_BEEN_VERIFIED.text() }
 }
 companion object {
  /** Current state never imports baselines, queues, or recovery metadata from test generations. */
  const val LOCAL_STATE_GENERATION = "package-current-v1"
  @JvmStatic fun forPackage(packageName: String, displayName: String, originalSourceIdentity: String): AndroidGameIdentity =
   AndroidGameIdentity(packageName, displayName, originalSourceIdentity)
 }
}

class AndroidGameAdapter(private val identity: AndroidGameIdentity): GameAdapter {
 override val id get() = identity.namespace
 override val packageName get() = identity.packageName
 override fun includes(name: String) = Shattered.includes(name)
 override fun compatible(source: String, target: String) = source == target
 override fun launchCommand(executable: Path): List<String> = error(CoreMessages.ANDROID_ANDROID_GAMES_ARE_LAUNCHED_THROUGH_ANDROID_NOT_A_DESKTOP_EXECUTABLE.text())
}
