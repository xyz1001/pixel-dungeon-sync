package pdsync.android.app

import java.nio.file.Path
import pdsync.GameAdapter
import pdsync.Shattered

enum class AndroidMergePolicy { NONE, SHATTERED_896 }

data class AndroidSaveCapabilities @JvmOverloads constructor(
 val minimumRestoreVersion: Int? = null,
 val maximumRestoreVersion: Int? = null,
 val mergePolicy: AndroidMergePolicy = AndroidMergePolicy.NONE
) {
 init {
  require((minimumRestoreVersion == null) == (maximumRestoreVersion == null)) { "Both save compatibility bounds are required." }
  if (minimumRestoreVersion != null) require(minimumRestoreVersion > 0 && maximumRestoreVersion!! >= minimumRestoreVersion) { "Invalid save compatibility range." }
  if (mergePolicy == AndroidMergePolicy.SHATTERED_896) require(minimumRestoreVersion != null && 896 in minimumRestoreVersion..maximumRestoreVersion!!) { "Shattered 896 merge requires its verified restore capability." }
 }
}

data class AndroidGameIdentity @JvmOverloads constructor(
 val packageName: String,
 val displayName: String,
 val originalSourceIdentity: String,
 val namespace: String,
 val saveCapabilities: AndroidSaveCapabilities = AndroidSaveCapabilities()
) {
 init {
  require(packageName.length <= 255 && Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(packageName)) { "The selected APK has no valid application package name." }
  require(displayName.length in 1..256 && displayName.none { it.isISOControl() }) { "The selected game label is invalid." }
  require(Regex("[0-9a-f]{64}").matches(originalSourceIdentity)) { "Original APK source identity has not been verified." }
  require(Regex("[A-Za-z0-9_-]{1,96}").matches(namespace)) { "Cloud game namespace must be a safe path segment." }
 }
 companion object {
  const val KNOWN_SHATTERED_ORIGIN = "32cd68da7bd2cdb467330cf33ca7c0e9b18eaae94ecf0b4b944313def1732f89"
  @JvmStatic fun defaultNamespace(originalSourceIdentity: String): String {
   require(Regex("[0-9a-f]{64}").matches(originalSourceIdentity)) { "Original source identity is invalid." }
   return if (originalSourceIdentity == KNOWN_SHATTERED_ORIGIN) "shattered" else "android-$originalSourceIdentity"
  }
 }
}

class AndroidGameAdapter(private val identity: AndroidGameIdentity): GameAdapter {
 override val id get() = identity.namespace
 override fun includes(name: String) = Shattered.includes(name)
 override fun compatible(source: String, target: String) = source == target
 override fun launchCommand(executable: Path): List<String> = error("Android games are launched through Android, not a desktop executable.")
}
