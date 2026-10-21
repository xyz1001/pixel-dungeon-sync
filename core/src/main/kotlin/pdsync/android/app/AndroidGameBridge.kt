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
 /** A complete single-read inventory with only summary inputs. Null means unsupported. */
 fun readSnapshot(): AndroidSaveObservation? = null
 /** Null is an unsupported capability, with no save mutation or transfer having occurred. */
 fun restoreVerified(snapshot: Snapshot, expectedCurrentFiles: Map<String, String>): AndroidRestoreReceipt? = null
}

data class AndroidSaveFile(val sha256:String,val size:Long,val modifiedMillis:Long) {
 init {
  require(Regex("[0-9a-f]{64}").matches(sha256))
  require(size in 0..pdsync.MAX_SNAPSHOT_BYTES.toLong())
 }
}

/** Detached backend evidence, never a filesystem fingerprint or a long-lived live-save cache. */
class AndroidSaveObservation(
 val target:AndroidGameTarget,
 inventory:Map<String,AndroidSaveFile>,
 summaryBytes:Map<String,ByteArray>
) {
 val inventory:Map<String,AndroidSaveFile> = Collections.unmodifiableMap(LinkedHashMap(inventory))
 val files:Map<String,String> = Collections.unmodifiableMap(this.inventory.mapValues { it.value.sha256 })
 val byteCount:Long = this.inventory.values.sumOf { it.size }
 internal val summaryEntries:Map<String,ByteArray>
 init {
  require(this.inventory.size<=pdsync.MAX_SNAPSHOT_FILES && byteCount<=pdsync.MAX_SNAPSHOT_BYTES)
  this.inventory.keys.forEach { name ->
   require(pdsync.safePath(name) && pdsync.Shattered.includes(name) && !pdsync.isDirectoryMetadata(name))
  }
  require(this.inventory.keys.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size==this.inventory.size)
  this.inventory.keys.forEach { name ->
   var parent=name.substringBeforeLast('/',"")
   while(parent.isNotEmpty()) {
    require(parent !in this.inventory)
    parent=parent.substringBeforeLast('/',"")
   }
  }
  this.inventory.keys.filter { it.startsWith("game") }.map { it.substringBefore('/') }.distinct().forEach { slot ->
   require("$slot/game.dat" in this.inventory)
  }
  val wanted=this.inventory.keys.filter { it=="rankings.dat" || Regex("game[0-9]+/game\\.dat").matches(it) }.toSet()
  require(summaryBytes.keys==wanted)
  summaryEntries=Collections.unmodifiableMap(summaryBytes.mapValues { (name,value) ->
   val copy=value.copyOf();val entry=this.inventory.getValue(name)
   require(copy.size.toLong()==entry.size && pdsync.hash(copy)==entry.sha256)
   copy
  })
 }
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
