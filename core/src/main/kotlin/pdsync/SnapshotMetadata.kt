package pdsync

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.net.InetAddress
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.text.Normalizer
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.io.path.*

const val MAX_DEVICE_LABEL_CODE_POINTS = 64

/** Presentation only: never a synchronization identity, clock ordering rule, or authenticated claim. */
@Serializable data class DeviceIdentity(val id: String, val label: String) {
 val displayName: String get() = "$label · ${id.take(8)}"
 fun validated(): DeviceIdentity = also {
  require(validCommitId(id)) { "Invalid device identity" }
  require(label.isNotEmpty() && label == sanitizeDeviceLabel(label)) { "Invalid device label" }
 }
}

/** Absent on older snapshots. Never fill absent provenance by guessing from receipt or file times. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class SnapshotMetadata(
 @EncodeDefault val version: Int = 1,
 val device: DeviceIdentity,
 val capturedAt: String,
 val fileModifiedAt: Map<String, String>
) {
 val latestFileModifiedAt: String? get() = fileModifiedAt.values.maxByOrNull { Instant.parse(it) }
 fun validated(files: Set<String>): SnapshotMetadata = also {
  require(version == 1) { "Unsupported snapshot metadata version" }
  device.validated(); requireUtcTimestamp(capturedAt)
  require(fileModifiedAt.size <= MAX_SNAPSHOT_FILES && fileModifiedAt.keys == files) { "Invalid file modification metadata" }
  fileModifiedAt.values.forEach(::requireUtcTimestamp)
 }
}

/** The author/time of this new commit, separate from the chosen snapshot's original provenance. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class CommitOperationMetadata(
 @EncodeDefault val version: Int = 1,
 val device: DeviceIdentity,
 val preparedAt: String
) {
 fun validated(): CommitOperationMetadata = also {
  require(version == 1) { "Unsupported operation metadata version" }
  device.validated(); requireUtcTimestamp(preparedAt)
 }
}

/** Nonserialized capture input; mtimes are collected next to the actual file reads. */
data class SnapshotCaptureContext(val device: DeviceIdentity, val capturedAt: String)

internal fun requireUtcTimestamp(value: String) {
 require(value.length in 20..30 && value.endsWith('Z')) { "Invalid UTC metadata timestamp" }
 require(Instant.parse(value).toString() == value) { "Metadata timestamp must be canonical UTC" }
}

/** Bound work and Unicode code points, remove controls/bidi formatting, and never split a surrogate. */
fun sanitizeDeviceLabel(raw: String?): String {
 val normalized = Normalizer.normalize(raw.orEmpty().take(4096), Normalizer.Form.NFC)
 val out = StringBuilder(); var count = 0; var pendingSpace = false
 val points = normalized.codePoints().iterator()
 while (points.hasNext() && count < MAX_DEVICE_LABEL_CODE_POINTS) {
  val point = points.nextInt()
  val type = Character.getType(point)
  if (Character.isWhitespace(point) || Character.isSpaceChar(point)) { pendingSpace = out.isNotEmpty(); continue }
  if (type in setOf(Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.SURROGATE.toInt(),
    Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt())) continue
  if (pendingSpace && count + 1 < MAX_DEVICE_LABEL_CODE_POINTS) { out.append(' '); count++ }
  pendingSpace = false
  if (count < MAX_DEVICE_LABEL_CODE_POINTS) { out.appendCodePoint(point); count++ }
 }
 return out.toString().ifEmpty { "此设备" }
}

/** One random identity per app state root, shared by profiles. No MAC/serial/account/path derivation. */
class DeviceMetadata(
 private val stateRoot: Path,
 private val clock: Clock = Clock.systemUTC(),
 private val hostname: () -> String? = ::systemDeviceLabel
) {
 @Serializable private data class StoredIdentity(val schema: Int = 1, val id: String)
 private val id: String by lazy { loadOrCreateId() }
 fun currentDevice() = DeviceIdentity(id, sanitizeDeviceLabel(hostname())).validated()
 fun captureContext() = SnapshotCaptureContext(currentDevice(), clock.instant().toString())
 fun operation() = CommitOperationMetadata(device = currentDevice(), preparedAt = clock.instant().toString()).validated()

 private fun loadOrCreateId(): String = synchronized(identityLock) {
  val state = SafePaths.checked(stateRoot); state.createDirectories()
  val path = SafePaths.checked(state.resolve("device.json"))
  val lockPath = SafePaths.checked(state.resolve("device.lock"))
  FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
   channel.lock().use {
    if (!path.exists()) SafePaths.atomicWrite(path, json.encodeToString(StoredIdentity(id = UUID.randomUUID().toString())).encodeToByteArray())
    val stored = json.decodeFromString<StoredIdentity>(SafePaths.boundedRead(path, 1024).decodeToString(throwOnInvalidSequence = true))
    require(stored.schema == 1 && validCommitId(stored.id)) { "本机设备身份损坏；未自动替换已有身份" }
    stored.id
   }
  }
 }
 companion object { private val identityLock = Any() }
}

private fun systemDeviceLabel(): String? {
 listOf("COMPUTERNAME", "HOSTNAME", "HOST").forEach { key -> System.getenv(key)?.takeIf { it.isNotBlank() }?.let { return it } }
 val hostnameFile = java.nio.file.Paths.get("/etc/hostname")
 if (Files.isRegularFile(hostnameFile)) runCatching {
  Files.newInputStream(hostnameFile).use { it.readUpTo(4096).decodeToString().trim() }
 }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
 return runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
}
