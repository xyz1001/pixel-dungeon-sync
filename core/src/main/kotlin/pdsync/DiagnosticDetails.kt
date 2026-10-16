package pdsync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Collections

/**
 * Small, closed diagnostic evidence shared by Java Android adapters and Kotlin desktop/core code.
 * Most values are fixed tokens, booleans or bounded integers. Preparation evidence has its own
 * bounded, typed allowlist for APK hashes/package metadata, verifier issues and code locations. No arbitrary
 * text, exception, URL, account, package name, path, filename, version label or identifier field.
 * Missing evidence stays absent. Unknown tokens become UNKNOWN; invalid numbers are omitted.
 * Deserialization rejects invalid on-disk values instead of sanitizing them into exportable data.
 */
@Serializable(with = DiagnosticDetails.Serializer::class)
class DiagnosticDetails private constructor(values: Map<Field, String>) {
 val values: Map<Field, String> = Collections.unmodifiableMap(LinkedHashMap(values))

 @Serializable enum class Field {
  @SerialName("mode") MODE,
  @SerialName("readinessCode") READINESS_CODE,
  @SerialName("access") ACCESS,
  @SerialName("gamePackageStatus") GAME_PACKAGE_STATUS,
  @SerialName("helperPackageStatus") HELPER_PACKAGE_STATUS,
  @SerialName("identityVerified") IDENTITY_VERIFIED,
  @SerialName("systemRequestActive") SYSTEM_REQUEST_ACTIVE,
  @SerialName("restorePending") RESTORE_PENDING,
  @SerialName("saveLayoutState") SAVE_LAYOUT_STATE,
  @SerialName("saveLayoutIncluded") SAVE_LAYOUT_INCLUDED,
  @SerialName("saveLayoutExcluded") SAVE_LAYOUT_EXCLUDED,
  @SerialName("saveLayoutUnrecognized") SAVE_LAYOUT_UNRECOGNIZED,
  @SerialName("saveLayoutTruncated") SAVE_LAYOUT_TRUNCATED,
  @SerialName("saveLayoutValid") SAVE_LAYOUT_VALID,
  @SerialName("rootCommand") ROOT_COMMAND,
  @SerialName("rootStage") ROOT_STAGE,
  @SerialName("rootCategory") ROOT_CATEGORY,
  @SerialName("errno") ERRNO,
  @SerialName("uidState") UID_STATE,
  @SerialName("observationState") OBSERVATION_STATE,
  @SerialName("observationReliable") OBSERVATION_RELIABLE,
  @SerialName("fileCount") FILE_COUNT,
  @SerialName("contentBytes") CONTENT_BYTES,
  @SerialName("networkTarget") NETWORK_TARGET,
  @SerialName("requestDurationMs") REQUEST_DURATION_MS,
  @SerialName("bridgeStage") BRIDGE_STAGE,
  @SerialName("failureSource") FAILURE_SOURCE,
  @SerialName("syncCompare") SYNC_COMPARE,
  @SerialName("syncDecision") SYNC_DECISION,
  @SerialName("syncBlockReason") SYNC_BLOCK_REASON,
  @SerialName("localFileCount") LOCAL_FILE_COUNT,
  @SerialName("remoteFileCount") REMOTE_FILE_COUNT,
  @SerialName("gameVersionCode") GAME_VERSION_CODE,
  @SerialName("saveVersion") SAVE_VERSION,
  @SerialName("preparation") PREPARATION,
  @SerialName("installationPhase") INSTALLATION_PHASE,
  @SerialName("installationCache") INSTALLATION_CACHE,
  @SerialName("installationElapsedMs") INSTALLATION_ELAPSED_MS
 }
 enum class Mode { ROOT_DIRECT, EMBEDDED_BRIDGE, LEGACY_HELPER_BRIDGE, DESKTOP, UNKNOWN }
 enum class NetworkTarget { DAV, OFFICIAL_AUTH, ENTITLEMENT, ASSET, UNKNOWN }
 enum class FailureSource {
  PACKAGE_TRUST, PROVIDER_ACQUIRE, HELPER_ACTIVATION, IPC_CALL, IPC_CLOSE, PROTOCOL_REPLY,
  SAVE_READINESS, TARGET_IDENTITY, EXPORT, RESTORE, RECOVERY, ROOT, LOCAL_SAVE,
  REMOTE_HISTORY, SYNC_COMPARE, NETWORK, INSTALLATION, PREPARATION, UNKNOWN
 }

 fun isEmpty(): Boolean = values.isEmpty()
 fun isValid(): Boolean = values.all { (field, value) -> valid(field, value) } && validLayoutTotal(values)
 override fun equals(other: Any?): Boolean = other is DiagnosticDetails && values == other.values
 override fun hashCode(): Int = values.hashCode()
 override fun toString(): String = values.toString()

 class Builder internal constructor() {
  private val values = linkedMapOf<Field, String>()
  private fun token(field: Field, value: String?): Builder = apply {
   if (value == null) values.remove(field)
   else values[field] = if (value in tokens(field)) value else "UNKNOWN"
  }
  /** No arbitrary string setter is exposed. Every token method selects its own allowlist. */
  fun preparationEvidence(evidence: Map<String, String>?): Builder = apply {
   PreparationDiagnosticEvidence.encode(evidence)?.let { values[Field.PREPARATION] = it } ?: values.remove(Field.PREPARATION)
  }
  fun installationPhase(value: String?): Builder = token(Field.INSTALLATION_PHASE, value)
  fun installationCache(value: String?): Builder = token(Field.INSTALLATION_CACHE, value)
  fun installationElapsedMs(value: Long): Builder = number(Field.INSTALLATION_ELAPSED_MS, value)
  fun mode(value: Mode): Builder = token(Field.MODE, value.name)
  fun mode(value: String?): Builder = token(Field.MODE, when (value) {
   "ROOT" -> "ROOT_DIRECT"; "EMBEDDED" -> "EMBEDDED_BRIDGE"
   "LEGACY", "LEGACY_HELPER" -> "LEGACY_HELPER_BRIDGE"; else -> value
  })
  fun readinessCode(value: String?): Builder = token(Field.READINESS_CODE, value)
  fun access(value: String?): Builder = token(Field.ACCESS, value)
  fun gamePackageStatus(value: String?): Builder = token(Field.GAME_PACKAGE_STATUS, value)
  fun helperPackageStatus(value: String?): Builder = token(Field.HELPER_PACKAGE_STATUS, value)
  fun identityVerified(value: Boolean): Builder = flag(Field.IDENTITY_VERIFIED, value)
  fun systemRequestActive(value: Boolean): Builder = flag(Field.SYSTEM_REQUEST_ACTIVE, value)
  fun restorePending(value: Boolean): Builder = flag(Field.RESTORE_PENDING, value)
  fun saveLayoutState(value: String?): Builder = token(Field.SAVE_LAYOUT_STATE, value)
  fun rootCommand(value: String?): Builder = token(Field.ROOT_COMMAND, value)
  fun rootStage(value: String?): Builder = token(Field.ROOT_STAGE, value)
  fun rootCategory(value: String?): Builder = token(Field.ROOT_CATEGORY, value)
  fun bridgeStage(value: String?): Builder = token(Field.BRIDGE_STAGE, value)
  fun failureSource(value: FailureSource): Builder = token(Field.FAILURE_SOURCE, value.name)
  fun failureSource(value: String?): Builder = token(Field.FAILURE_SOURCE, value)
  fun observationState(value: String?): Builder = token(Field.OBSERVATION_STATE, value)
  fun observationReliable(value: Boolean): Builder = flag(Field.OBSERVATION_RELIABLE, value)
  fun networkTarget(value: NetworkTarget): Builder = token(Field.NETWORK_TARGET, value.name)
  fun syncCompare(value: String?): Builder = token(Field.SYNC_COMPARE, value)
  fun syncDecision(value: String?): Builder = token(Field.SYNC_DECISION, value)
  fun syncBlockReason(value: String?): Builder = token(Field.SYNC_BLOCK_REASON, value)
  fun errno(value: Int): Builder = number(Field.ERRNO, value.toLong())
  fun uidState(value: Int): Builder = number(Field.UID_STATE, value.toLong())
  fun fileCount(value: Int): Builder = number(Field.FILE_COUNT, value.toLong())
  fun contentBytes(value: Long): Builder = number(Field.CONTENT_BYTES, value)
  fun requestDurationMs(value: Long): Builder = number(Field.REQUEST_DURATION_MS, value)
  fun localFileCount(value: Int): Builder = number(Field.LOCAL_FILE_COUNT, value.toLong())
  fun remoteFileCount(value: Int): Builder = number(Field.REMOTE_FILE_COUNT, value.toLong())
  fun gameVersionCode(value: Long): Builder = number(Field.GAME_VERSION_CODE, value)
  fun saveVersion(value: Int): Builder = number(Field.SAVE_VERSION, value.toLong())
  fun saveLayout(state: String?, included: Int, excluded: Int, unrecognized: Int,
                 truncated: Boolean, valid: Boolean): Builder = apply {
   saveLayoutState(state)
   number(Field.SAVE_LAYOUT_INCLUDED, included.toLong())
   number(Field.SAVE_LAYOUT_EXCLUDED, excluded.toLong())
   number(Field.SAVE_LAYOUT_UNRECOGNIZED, unrecognized.toLong())
   flag(Field.SAVE_LAYOUT_TRUNCATED, truncated)
   flag(Field.SAVE_LAYOUT_VALID, valid && included >= 0 && excluded >= 0 && unrecognized >= 0 &&
    included.toLong() + excluded + unrecognized <= MAX_FILE_COUNT)
  }
  /** Wrong field types and out-of-range values are dropped; diagnostics cannot fail the operation. */
  fun number(field: Field, value: Long): Builder = apply {
   val range = numberRange(field)
   if (range != null && value in range) values[field] = value.toString() else values.remove(field)
  }
  fun flag(field: Field, value: Boolean): Builder = apply {
   if (field in booleanFields) values[field] = value.toString()
  }
  fun build(): DiagnosticDetails {
   val copy = LinkedHashMap(values)
   if (!validLayoutTotal(copy)) {
    layoutCounts.forEach(copy::remove)
    copy[Field.SAVE_LAYOUT_VALID] = "false"
   }
   return DiagnosticDetails(copy)
  }
 }

 /** Re-validates even when called directly, outside DiagnosticLog's export path. */
 object Serializer : KSerializer<DiagnosticDetails> {
  private val delegate = MapSerializer(Field.serializer(), String.serializer())
  override val descriptor: SerialDescriptor = delegate.descriptor
  override fun serialize(encoder: Encoder, value: DiagnosticDetails) {
   require(value.isValid()) { "Invalid diagnostic details" }
   delegate.serialize(encoder, value.values)
  }
  override fun deserialize(decoder: Decoder): DiagnosticDetails {
   val result = DiagnosticDetails(delegate.deserialize(decoder))
   require(result.isValid()) { "Invalid diagnostic details" }
   return result
  }
 }

 companion object {
  const val MAX_FILE_COUNT = 16384L
  const val MAX_CONTENT_BYTES = 1024L * 1024 * 1024
  const val MAX_REQUEST_DURATION_MS = 7L * 24 * 60 * 60 * 1000
  @JvmStatic fun builder(): Builder = Builder()
  private val layoutCounts = setOf(Field.SAVE_LAYOUT_INCLUDED, Field.SAVE_LAYOUT_EXCLUDED, Field.SAVE_LAYOUT_UNRECOGNIZED)
  private val booleanFields = setOf(Field.IDENTITY_VERIFIED, Field.SYSTEM_REQUEST_ACTIVE,
   Field.RESTORE_PENDING, Field.SAVE_LAYOUT_TRUNCATED, Field.SAVE_LAYOUT_VALID, Field.OBSERVATION_RELIABLE)
  // BridgeDiagnostics.CODES plus MainActivity's real READY/NOT_CHECKED observations.
  // A parity test requires changes to the protocol allowlist to be reviewed here too.
  private val readinessCodes = setOf(
   "READY", "NOT_CHECKED",
   "PURE_HANDSHAKE_REQUIRED", "ACCESS_HANDSHAKE_TIMEOUT", "ACCESS_HANDSHAKE_CANCELED",
   "ACCESS_WORKERS_BUSY", "ACCESS_AUTHENTICATION_FAILED", "UNKNOWN_METHOD", "UNSUPPORTED_METHOD",
   "ROOT_RUNNER_TIMEOUT", "ROOT_REPLY_MISMATCH", "ROOT_RECOVERY_REQUIRED", "ROOT_RESTORE_CONFLICT",
   "ROOT_RECOVERY_CONFLICT", "ROOT_ATTRIBUTES_UNSUPPORTED", "ROOT_MUTATION_UNAVAILABLE", "ROOT_RESTORE_FAILED",
   "ROOT_RECOVERY_FAILED", "ROOT_NO_PENDING_RECOVERY", "ROOT_RESTORE_COMMITTED_CLEANUP_PENDING", "ROOT_RECOVERY_COMMITTED_CLEANUP_PENDING",
   "ROOT_RESTORE_RECEIPT_INVALID", "ROOT_RECOVERY_RECEIPT_INVALID", "ROOT_RESTORE_VERIFY_FAILED", "ROOT_RECOVERY_VERIFY_FAILED",
   "ROOT_AUTH_REQUIRED", "ROOT_UNAVAILABLE", "ROOT_DENIED_OR_EXITED", "ROOT_TIMEOUT",
   "ROOT_CANCELLED", "ROOT_INVALID_REPLY", "ROOT_PIPE_FAILED", "ROOT_DIAGNOSTIC_LIMIT",
   "ROOT_TARGET_CHANGED", "ROOT_BINDING_MISMATCH", "ROOT_ACCESS_FAILED", "ROOT_SAVE_NOT_READY",
   "ROOT_UNEXPECTED_ARCHIVE", "ROOT_EXPORT_MISMATCH", "ROOT_EXPORT_FAILED", "ROOT_ARCHIVE_INCOMPLETE",
   "ROOT_RESTORE_UNSUPPORTED", "ROOT_RECOVERY_UNSUPPORTED", "ROOT_UNSUPPORTED", "ROOT_NOT_ROOT",
   "ROOT_INVALID_REQUEST", "ROOT_UNSAFE_PATH", "ROOT_CHANGED", "ROOT_TOO_LARGE",
   "ROOT_UNRELIABLE", "ROOT_IO_ERROR", "ROOT_MISSING_DIRECTORY", "ROOT_UNRECOGNIZED_LAYOUT",
   "ROOT_BUSY", "ACTIVATION_COMPONENT_UNAVAILABLE", "ACTIVATION_LAUNCH_DENIED", "PROVIDER_ACQUIRE_NULL",
   "PROVIDER_ACQUIRE_FAILED", "PROVIDER_ACQUIRE_SECURITY_REJECTED", "IPC_CLOSE_FAILED", "IPC_REPLY_CLOSE_FAILED",
   "ANDROID_VERSION_UNSUPPORTED", "ARCHIVE_CHANGED", "BRIDGE_OPERATION_REJECTED", "EXPORT_FAILED",
   "FIRST_LAUNCH_NEEDED", "GAME_BINDING_MISMATCH", "GAME_FOREGROUND", "GAME_MISSING",
   "GAME_RUNNING", "GAME_SAVE_RACE", "GAME_SIGNER_MISMATCH", "INSPECT_FAILED",
   "INSTALLED_APK_SET_MISMATCH", "INTERRUPTED", "INVALID_GAME_BINDING", "INVALID_PROTOCOL",
   "IPC_CALL_FAILED", "IPC_SECURITY_REJECTED", "LEGACY_RECOVERY_ENDED", "NO_PENDING_RECOVERY",
   "OK", "PACKAGE_TRUST_CHANGED", "PACKAGE_TRUST_FAILED", "PROCESS_STATE_UNKNOWN",
   "READINESS_FIELDS_MISSING", "RECOVERY_FAILED", "RECOVERY_IDENTITY_CHANGED", "RECOVERY_REQUIRED",
   "REPLY_IDENTITY_MISMATCH", "RESTORE_COMMITTED_CLEANUP_PENDING", "RESTORE_FAILED", "RESTORE_ROLLED_BACK",
   "RESTORE_VERIFY_FAILED", "SAVE_CHANGED", "SAVE_IO_ERROR", "SAVE_NOT_QUIET",
   "SAVE_NOT_READY", "SAVE_ROOT_UNKNOWN", "SAVE_TOO_LARGE", "SAVE_UNREADABLE",
   "STALE_EXPORT", "TARGET_IDENTITY_FAILED", "UID_MISMATCH", "UNEXPECTED_SHARED_UID",
   "UNKNOWN", "UNKNOWN_BRIDGE_FAILURE", "UNRECOGNIZED_SAVE_LAYOUT", "UNSAFE_ARCHIVE_DESCRIPTOR",
   "UNSAFE_SAVE_PATH"
  )
  private val accessStates = setOf("NOT_CHECKED", "READY", "FIRST_LAUNCH_NEEDED", "GAME_BUSY", "SYNC_BUSY",
   "UNRECOGNIZED_LAYOUT", "RECOVERY_NEEDED", "SECURITY_ERROR", "IPC_ERROR", "ERROR", "CAPABILITY_UNAVAILABLE", "UNKNOWN")
  private val packageStates = setOf("NOT_SELECTED", "NOT_CHECKED", "FRESH_INSTALL", "COMPATIBLE_UPDATE",
   "REQUIRES_UNINSTALL", "VERIFIED", "NOT_APPLICABLE", "UNKNOWN")
  private val layoutStates = setOf("MISSING_DIRECTORY", "EMPTY_DIRECTORY", "NO_TRANSFERABLE_FILES",
   "ALLOWLISTED_FILES_PRESENT", "UNRECOGNIZED_LAYOUT", "UNKNOWN")
  private val rootStages = setOf("NONE", "EUID", "PM_BINDING", "ROOT_OPEN", "LAYOUT_SCAN", "UID_STATE", "ARCHIVE_READ", "PRIVATE_WORKSPACE", "NATIVE_LOAD", "MUTATION_PREFLIGHT", "BACKUP_COPY", "STAGE_PREPARE", "ATTRIBUTES_APPLY", "JOURNAL_READ", "JOURNAL_WRITE", "MOVE", "MOVE_OLD", "MOVE_NEW", "VERIFY", "ROLLBACK", "RECOVERY", "CLEANUP", "KERNEL_OPEN_DIRECTORY", "KERNEL_OPEN_REGULAR", "KERNEL_DUP", "KERNEL_FCNTL", "KERNEL_FSTAT", "KERNEL_LSTAT", "KERNEL_READDIR", "KERNEL_MKDIR", "KERNEL_PREAD", "KERNEL_PWRITE", "KERNEL_FCHMOD", "KERNEL_FCHOWN", "KERNEL_FSYNC", "KERNEL_GETXATTR", "KERNEL_SETXATTR", "KERNEL_RENAME", "KERNEL_LINK", "KERNEL_REMOVE", "KERNEL_LOCK", "KERNEL_TIMESTAMP", "KERNEL_LSTAT_DATA_PACKAGE", "KERNEL_LSTAT_SAVE_FILES", "KERNEL_LSTAT_WORKSPACE", "UNKNOWN")
  private val rootCategories = setOf("NONE", "PERMISSION", "UNSUPPORTED", "CROSS_DEVICE", "EXISTS", "CHANGED", "MALFORMED", "LIMIT", "IO", "MISSING", "UNKNOWN")
  private val bridgeStages = setOf("PACKAGE_TRUST", "PROVIDER_ACQUIRE", "HELPER_ACTIVATION", "IPC_CALL", "IPC_CLOSE",
   "PROTOCOL_REPLY", "SAVE_READINESS", "TARGET_IDENTITY", "EXPORT", "RESTORE", "RECOVERY", "UNKNOWN")
  private val compareStates = OnboardingKind.entries.map { it.name }.toSet() + "UNKNOWN"
  private val blockReasons = SyncBlockReason.entries.map { it.name }.toSet() + "UNKNOWN"
  private fun tokens(field: Field): Set<String> = when (field) {
   Field.INSTALLATION_PHASE -> setOf("PREPARE_VALIDATION", "UNINSTALL_REQUEST", "INSTALL_SESSION", "SESSION_WRITE", "INSTALL_COMMIT", "INSTALL_RESULT", "UNKNOWN")
   Field.INSTALLATION_CACHE -> setOf("CACHE_HIT", "SESSION_MISSING", "FILE_CHANGED", "REVALIDATED", "NOT_APPLICABLE", "UNKNOWN")
   Field.MODE -> Mode.entries.map { it.name }.toSet()
   Field.READINESS_CODE -> readinessCodes
   Field.ACCESS -> accessStates
   Field.GAME_PACKAGE_STATUS, Field.HELPER_PACKAGE_STATUS -> packageStates
   Field.SAVE_LAYOUT_STATE -> layoutStates
   Field.ROOT_COMMAND -> setOf("PROBE", "INSPECT", "EXPORT", "OBSERVE", "RESTORE", "RECOVER", "UNKNOWN")
   Field.ROOT_STAGE -> rootStages
   Field.ROOT_CATEGORY -> rootCategories
   Field.BRIDGE_STAGE -> bridgeStages
   Field.OBSERVATION_STATE -> setOf("FOREGROUND", "BACKGROUND", "CACHED", "ABSENT", "UNKNOWN")
   Field.NETWORK_TARGET -> NetworkTarget.entries.map { it.name }.toSet()
   Field.FAILURE_SOURCE -> FailureSource.entries.map { it.name }.toSet()
   Field.SYNC_COMPARE, Field.SYNC_DECISION -> compareStates
   Field.SYNC_BLOCK_REASON -> blockReasons
   else -> emptySet()
  }
  private fun numberRange(field: Field): LongRange? = when (field) {
   Field.ERRNO -> 0L..4095L
   Field.UID_STATE -> 0L..20L
   Field.SAVE_LAYOUT_INCLUDED, Field.SAVE_LAYOUT_EXCLUDED, Field.SAVE_LAYOUT_UNRECOGNIZED,
   Field.FILE_COUNT, Field.LOCAL_FILE_COUNT, Field.REMOTE_FILE_COUNT -> 0L..MAX_FILE_COUNT
   Field.CONTENT_BYTES -> 0L..MAX_CONTENT_BYTES
   Field.REQUEST_DURATION_MS, Field.INSTALLATION_ELAPSED_MS -> 0L..MAX_REQUEST_DURATION_MS
   Field.GAME_VERSION_CODE -> 0L..Long.MAX_VALUE
   Field.SAVE_VERSION -> 0L..Int.MAX_VALUE.toLong()
   else -> null
  }
  private fun valid(field: Field, value: String): Boolean {
   if (field == Field.PREPARATION) return PreparationDiagnosticEvidence.isValid(value)
   if (field in booleanFields) return value == "true" || value == "false"
   numberRange(field)?.let { range ->
    val number = value.toLongOrNull() ?: return false
    return number in range && value == number.toString()
   }
   return value in tokens(field)
  }
  private fun validLayoutTotal(values: Map<Field, String>): Boolean =
   layoutCounts.sumOf { values[it]?.toLongOrNull() ?: 0L } <= MAX_FILE_COUNT
 }
}
