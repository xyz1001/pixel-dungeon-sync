package pdsync

import pdsync.i18n.CoreMessages
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.io.UncheckedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.net.ssl.SSLException

/** Only closed diagnostic labels are accepted. None of these fields carry business identifiers. */
@Serializable enum class DiagnosticAction { APP_START, CONFIGURE, CONNECT, INSPECT, HISTORY, PREPARE, INSTALL, ACCESS_CHECK, LAUNCH, CAPTURE, PUSH, RESTORE, RECOVERY, RESOLVE, MERGE, ARCHIVE, EXPORT_LOGS, AUTOMATIC_SYNC, OTHER }
@Serializable enum class DiagnosticStage { QUEUED, STARTED, CONFIRMATION, GUARD, WORK, REFRESH, FINISHED, RUNTIME, CONNECTION, NAMESPACE, HTTP, PREPARATION, INSTALLATION, BRIDGE, READINESS, GAME_LAUNCH, BACKUP, RESTORE, RECOVERY, VERIFY, EXPORT }
@Serializable enum class DiagnosticResult { STARTED, SUCCEEDED, FAILED, CANCELED, REJECTED, SKIPPED, PENDING, CONFLICT }
@Serializable enum class DiagnosticFailure { NONE, GAME_NOT_SELECTED, NOT_CHECKED, FIRST_LAUNCH_NEEDED, UNRECOGNIZED_LAYOUT, SYSTEM_REQUEST_PENDING, CAPABILITY_UNAVAILABLE, BUSY, NOT_READY, RECOVERY_REQUIRED, IDENTITY_MISMATCH, UNAVAILABLE, CANCELED, AUTHENTICATION, AUTHORIZATION, HTTP, TIMEOUT, DNS, CONNECT, TLS, EOF, CONNECTION, INTERRUPTED, NETWORK_IO, STORAGE, VALIDATION, OTHER }
@Serializable enum class DiagnosticHttpMethod { GET, PUT, PROPFIND, MKCOL, HEAD, POST, OTHER }

class DiagnosticTrace internal constructor(val id: String, val action: DiagnosticAction, val automatic: Boolean) {
 internal val timings = DiagnosticPhaseCollector()
 internal val capabilities = DiagnosticCapabilityCollector()
 internal val startedNanos = System.nanoTime()
 fun record(stage: DiagnosticStage, result: DiagnosticResult): Boolean = record(stage, result, DiagnosticFailure.NONE)
 fun record(stage: DiagnosticStage, result: DiagnosticResult, failure: DiagnosticFailure): Boolean =
  DiagnosticLog.write(this, stage, result, failure)
 fun record(stage: DiagnosticStage, result: DiagnosticResult, failure: DiagnosticFailure, details: DiagnosticDetails): Boolean =
  DiagnosticLog.write(this, stage, result, failure, details = details)
 fun failure(stage: DiagnosticStage, error: Throwable): Boolean = DiagnosticLog.writeFailure(this, stage, error)
}

/**
 * Process-local best-effort diagnostics, shared by Android and desktop. Configure with an app-owned
 * directory, never a game save directory. Seven UTC date buckets retain at most 7 x 128 KiB; the
 * oldest complete records of a full day are dropped. No account, URL, path, save ID, exception text,
 * stack trace, request/response body or headers can be supplied to the record schema.
 *
 * Every write is forced; recorder failures cannot change synchronization results. Export revalidates
 * and reserializes this schema instead of trusting on-disk bytes. Snapshots and writes use one lock.
 * The path checks reject symlinks but cannot defeat hostile concurrent ancestor replacement, as with
 * SafePaths elsewhere. Callers must bind explicitly inside each worker/coroutine's synchronous block;
 * ThreadLocal context does not propagate across a suspension or to a newly scheduled task.
 */
object DiagnosticLog {
 internal const val MAX_FILE_BYTES = 128 * 1024
 internal const val RETENTION_DAYS = 7L
 internal const val MAX_TOTAL_BYTES = MAX_FILE_BYTES * 7
 private const val MAX_LINE_BYTES = 32 * 1024
 private val lock = Any()
 private val current = ThreadLocal<DiagnosticTrace?>()
 internal fun currentTrace():DiagnosticTrace? = current.get()
 private var configuration: Configuration? = null
 private val codec = Json { encodeDefaults = true }
 private val dateName = Regex("events-(\\d{4}-\\d{2}-\\d{2})\\.jsonl")
 private val versionPattern = Regex("[0-9]{1,6}(?:\\.[0-9]{1,6}){0,3}(?:-(?:root|embedded|shared|uid|test|debug|release|dev|alpha|beta|rc)(?:[.-]?[0-9]{1,6})?){0,4}(?:\\+[0-9]{1,9})?")
 private val filenameTime = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

 private data class Configuration(val directory: Path, val version: String, val platform: String, val clock: Clock)
 @Serializable private data class Record(
  val schema: Int = 1, val timestampUtc: String, val zone: String, val offsetMinutes: Int,
  val appVersion: String, val platform: String, val operationId: String,
  val action: DiagnosticAction, val automatic: Boolean, val stage: DiagnosticStage,
  val result: DiagnosticResult, val failure: DiagnosticFailure = DiagnosticFailure.NONE,
  val elapsedMs: Long, val httpMethod: DiagnosticHttpMethod? = null, val httpStatus: Int? = null,
  val details: DiagnosticDetails? = null
 )
 @Serializable private data class Metadata(
  val schema: Int = 1, val generatedAtUtc: String, val zone: String, val offsetMinutes: Int,
  val appVersion: String, val platform: String, val retentionDays: Long = RETENTION_DAYS,
  val maxStoredBytes: Int = MAX_TOTAL_BYTES, val recordCount: Int,
  val storageAvailable: Boolean, val environment: DiagnosticEnvironment.Snapshot = DiagnosticEnvironment.snapshot(), val redaction: String = "closed-schema-no-payloads-identifiers-paths-urls-headers-messages-or-stacks"
 )

 @JvmStatic fun configure(applicationPrivateDirectory: Path, version: String, platform: String) {
  configureForTests(applicationPrivateDirectory, version, platform, Clock.systemDefaultZone())
 }
 internal fun configureForTests(directory: Path, version: String, platform: String, clock: Clock) = synchronized(lock) {
  configuration = Configuration(directory.resolve("diagnostics-v1"), safeVersion(version), safePlatform(platform), clock)
  try { val config = configuration!!; prepare(config); prune(config) } catch (_: Exception) { /* Best effort only. */ }
 }

 @JvmStatic fun begin(action: DiagnosticAction, automatic: Boolean): DiagnosticTrace =
  DiagnosticTrace(UUID.randomUUID().toString(), action, automatic).also { it.record(DiagnosticStage.QUEUED, DiagnosticResult.STARTED) }

 /** One user-observation record, without begin's QUEUED event or changing the bound operation. */
 @JvmStatic fun recordStandalone(action: DiagnosticAction, stage: DiagnosticStage, result: DiagnosticResult,
                                failure: DiagnosticFailure, details: DiagnosticDetails): Boolean =
  write(DiagnosticTrace(UUID.randomUUID().toString(), action, false), stage, result, failure, details = details)

 /** Restores nesting; closing a scope twice or from another thread cannot change another context. */
 @JvmStatic fun bind(trace: DiagnosticTrace): AutoCloseable {
  val thread = Thread.currentThread(); val previous = current.get(); current.set(trace)
  return object : AutoCloseable {
   private var closed = false
   override fun close() {
    if (!closed && Thread.currentThread() === thread) {
     closed = true
     if (current.get() === trace) { if (previous == null) current.remove() else current.set(previous) }
    }
   }
  }
 }
 @JvmStatic fun record(stage: DiagnosticStage, result: DiagnosticResult): Boolean = record(stage, result, DiagnosticFailure.NONE)
 @JvmStatic fun record(stage: DiagnosticStage, result: DiagnosticResult, failure: DiagnosticFailure): Boolean =
  current.get()?.record(stage, result, failure) ?: false
 @JvmStatic fun record(stage: DiagnosticStage, result: DiagnosticResult, failure: DiagnosticFailure, details: DiagnosticDetails): Boolean =
  current.get()?.record(stage, result, failure, details) ?: false
 @JvmStatic fun failure(stage: DiagnosticStage, error: Throwable): Boolean = current.get()?.failure(stage, error) ?: false

 /** Status is diagnostic only: a protocol-accepted 404/405 is not automatically an operation failure. */
 @JvmStatic fun http(method: DiagnosticHttpMethod, status: Int): Boolean {
  if (status !in 100..599) return false
  val trace = current.get() ?: begin(DiagnosticAction.CONNECT, false)
  return write(trace, DiagnosticStage.HTTP, DiagnosticResult.SUCCEEDED, DiagnosticFailure.NONE, method, status)
 }
 @JvmStatic fun http(method: DiagnosticHttpMethod, status: Int, details: DiagnosticDetails): Boolean {
  if (status !in 100..599) return false
  val trace = current.get() ?: begin(DiagnosticAction.CONNECT, false)
  return write(trace, DiagnosticStage.HTTP, DiagnosticResult.SUCCEEDED, DiagnosticFailure.NONE, method, status, details)
 }
 @JvmStatic fun networkFailure(error: Throwable, details: DiagnosticDetails): Boolean =
  writeFailure(current.get() ?: begin(DiagnosticAction.CONNECT, false), DiagnosticStage.HTTP, error, details)
 @JvmStatic fun networkFailure(error: Throwable): Boolean =
  (current.get() ?: begin(DiagnosticAction.CONNECT, false)).failure(DiagnosticStage.HTTP, error)

 internal fun writeFailure(trace: DiagnosticTrace, stage: DiagnosticStage, error: Throwable, details: DiagnosticDetails? = null): Boolean {
  val category = classify(error)
  return write(trace, stage, if (category == DiagnosticFailure.CANCELED) DiagnosticResult.CANCELED else DiagnosticResult.FAILED,
   category, null, httpStatus(error), details)
 }
 internal fun write(trace: DiagnosticTrace, stage: DiagnosticStage, result: DiagnosticResult, failure: DiagnosticFailure,
                    method: DiagnosticHttpMethod? = null, status: Int? = null, details: DiagnosticDetails? = null): Boolean = synchronized(lock) {
  try {
   val config = configuration ?: return@synchronized false
   prepare(config); prune(config)
   val now = config.clock.instant(); val zone = safeZone(config.clock.zone)
   val phaseTotals = if (stage == DiagnosticStage.FINISHED) trace.timings.snapshot() else emptyMap()
   var finishedDetails = details
   if (stage == DiagnosticStage.FINISHED) {
    val capabilityEvidence = trace.capabilities.snapshot()
    if (!capabilityEvidence.isEmpty()) finishedDetails = (finishedDetails ?: DiagnosticDetails.builder().build()).withCapabilityEvidence(capabilityEvidence)
    if (phaseTotals.isNotEmpty()) finishedDetails = (finishedDetails ?: DiagnosticDetails.builder().build()).withPhaseTimings(phaseTotals)
   }
   val record = Record(timestampUtc = now.toString(), zone = zone.id,
    offsetMinutes = zone.rules.getOffset(now).totalSeconds / 60, appVersion = config.version, platform = config.platform,
    operationId = trace.id, action = trace.action, automatic = trace.automatic, stage = stage, result = result, failure = failure,
    elapsedMs = ((System.nanoTime() - trace.startedNanos) / 1_000_000).coerceIn(0, 7 * 24 * 60 * 60 * 1000L),
    httpMethod = method, httpStatus = status?.takeIf { it in 100..599 }, details = finishedDetails)
   var line = (codec.encodeToString(record) + "\n").toByteArray(Charsets.UTF_8)
   require(line.size <= MAX_LINE_BYTES)
   val file = SafePaths.checked(config.directory.resolve("events-${utcDate(now)}.jsonl"))
   requireRegularOrAbsent(file)
   val size = if (Files.exists(file, NOFOLLOW_LINKS)) Files.size(file) else 0L
   require(size <= MAX_FILE_BYTES)
   if (size > 0) FileChannel.open(file, READ, NOFOLLOW_LINKS).use { channel ->
    val tail = ByteBuffer.allocate(1); channel.position(size - 1); channel.read(tail)
    // Preserve a crash-truncated record as rejected input; do not concatenate a new valid record.
    if (tail.array()[0] != '\n'.code.toByte()) line = byteArrayOf('\n'.code.toByte()) + line
   }
   if (size + line.size > MAX_FILE_BYTES) {
    val lines = readRecords(file, config).map { codec.encodeToString(it) + "\n" }
    var keptBytes = line.size
    val kept = ArrayDeque<String>()
    for (old in lines.asReversed()) {
     val length = old.toByteArray(Charsets.UTF_8).size
     if (keptBytes + length > MAX_FILE_BYTES / 2) break
     kept.addFirst(old); keptBytes += length
    }
    // A full day's buffer compacts to half its budget before appending. Temporary + stored bytes
    // still stay below 1 MiB: seven 128 KiB buckets plus at most one 64 KiB replacement.
    replaceBucket(config, file, (kept.joinToString("") + line.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8))
   } else {
    FileChannel.open(SafePaths.checked(file), CREATE, WRITE, APPEND, NOFOLLOW_LINKS).use { channel ->
     val bytes = ByteBuffer.wrap(line); while (bytes.hasRemaining()) channel.write(bytes); channel.force(true)
    }
   }
   true
  } catch (_: Exception) { false }
 }

 /** Immutable ZIP of validated records, taken under the same lock as append/rotation. No legacy files. */
 @JvmStatic fun snapshotExport(): ByteArray = synchronized(lock) {
  val config = configuration
  val now = config?.clock?.instant() ?: Instant.now()
  val zone = safeZone(config?.clock?.zone ?: ZoneOffset.UTC)
  var available = config != null
  val records = ArrayList<Record>()
  if (config != null) {
   try {
    prepare(config); prune(config)
    logFiles(config).forEach { file ->
     try { records.addAll(readRecords(file, config)) } catch (_: Exception) { available = false }
    }
   } catch (_: Exception) { available = false }
  }
  val metadata = Metadata(generatedAtUtc = now.toString(), zone = zone.id,
   offsetMinutes = zone.rules.getOffset(now).totalSeconds / 60, appVersion = config?.version ?: "unknown",
   platform = config?.platform ?: "unknown", recordCount = records.size, storageAvailable = available)
  val bytes = ByteArrayOutputStream()
  ZipOutputStream(bytes).use { zip ->
   fun entry(name: String, content: String) {
    zip.putNextEntry(ZipEntry(name).apply { time = now.toEpochMilli() })
    zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
   }
   entry("metadata.json", codec.encodeToString(metadata) + "\n")
   entry("events.jsonl", records.joinToString("") { codec.encodeToString(it) + "\n" })
   entry(CoreMessages.APPLICATION_LOG.text(), readableLog(metadata, records))
  }
  bytes.toByteArray()
 }
 /** A human-readable view of the same validated snapshot, never arbitrary exception/logcat text. */
 private fun readableLog(metadata: Metadata, records: List<Record>): String = buildString {
  appendLine(CoreMessages.PD_SYNC_APPLICATION_LOG.text())
  appendLine(CoreMessages.THIS_EXPORT_SNAPSHOT_EXCLUDES_THE_RESULT_OF_THIS_EXPORT_UNRECORDED_EVENTS_FROM_OLDER.text())
  appendLine("# version=${metadata.appVersion} platform=${metadata.platform} exportedAt=${metadata.generatedAtUtc} zone=${metadata.zone}")
  val environment = metadata.environment
  appendLine("# osVersion=${environment.osVersion ?: "unknown"} api=${environment.androidApiLevel ?: "unknown"} brand=${environment.brand ?: "unknown"} model=${environment.model ?: "unknown"} cpu=${environment.cpuArchitectures.joinToString(",")} locale=${environment.locale ?: "unknown"}")
  appendLine("# records=${metadata.recordCount} storageAvailable=${metadata.storageAvailable} retentionDays=${metadata.retentionDays}")
  if (!metadata.storageAvailable) appendLine(CoreMessages.LOG_STORAGE_IS_UNAVAILABLE_OR_COULD_NOT_BE_FULLY_READ.text())
  if (records.isEmpty()) appendLine(CoreMessages.NO_RECORDED_EVENTS_TO_EXPORT.text())
  for (record in records.sortedBy { Instant.parse(it.timestampUtc) }) {
   val level = when (record.result) {
    DiagnosticResult.FAILED -> "ERROR"
    DiagnosticResult.REJECTED, DiagnosticResult.CONFLICT -> "WARN"
    else -> "INFO"
   }
   val explanation = if (record.stage == DiagnosticStage.HTTP && record.httpStatus != null && record.result == DiagnosticResult.SUCCEEDED)
    CoreMessages.HTTP_RESPONSE_RECEIVED.text() else resultText(record.result)
   append("${record.timestampUtc} $level [${record.operationId}] ${actionText(record.action)} / ${stageText(record.stage)}：$explanation")
   append(" action=${record.action} stage=${record.stage} result=${record.result} automatic=${record.automatic} elapsedMs=${record.elapsedMs}")
   record.httpMethod?.let { append(" method=$it") }
   record.httpStatus?.let { append(" status=$it") }
   if (record.failure != DiagnosticFailure.NONE) append(" error=${record.failure} (${failureText(record.failure)})")
   record.details?.values?.toSortedMap(compareBy { it.ordinal })?.forEach { (field, value) ->
    if (field == DiagnosticDetails.Field.PREPARATION) {
     PreparationDiagnosticEvidence.decode(value)?.forEach { (key, fact) -> append(" prep.$key=$fact") }
    } else if (field == DiagnosticDetails.Field.HOME_RESTORE) {
     HomeRestoreDiagnosticEvidence.decode(value)?.forEach { (key, fact) -> append(" homeRestore.$key=$fact") }
    } else if(field == DiagnosticDetails.Field.PHASE_TIMINGS) {
     DiagnosticTimingEvidence.decode(value)?.forEach { (key,fact)->
      append(" phase.$key.inclusiveMs=${fact.durationMs} phase.$key.count=${fact.count}")
      append(" phase.$key.failed=${fact.failed} phase.$key.canceled=${fact.canceled} phase.$key.skipped=${fact.skipped}")
     }
    } else append(" ${field.name}=$value")
   }
   appendLine(" version=${record.appVersion} platform=${record.platform} zone=${record.zone} offsetMinutes=${record.offsetMinutes}")
  }
 }
 private fun actionText(value: DiagnosticAction): String = when (value) {
  DiagnosticAction.APP_START -> CoreMessages.APPLICATION_STARTUP.text(); DiagnosticAction.CONFIGURE -> CoreMessages.SETTINGS.text()
  DiagnosticAction.CONNECT -> CoreMessages.CONNECT_TO_SERVICE.text(); DiagnosticAction.INSPECT -> CoreMessages.CHECK_SAVES.text()
  DiagnosticAction.HISTORY -> CoreMessages.READ_HISTORY.text(); DiagnosticAction.PREPARE -> CoreMessages.PREPARE_GAME.text()
  DiagnosticAction.INSTALL -> CoreMessages.INSTALL_GAME.text(); DiagnosticAction.ACCESS_CHECK -> CoreMessages.CHECK_SAVE_ACCESS.text()
  DiagnosticAction.LAUNCH -> CoreMessages.LAUNCH_GAME.text(); DiagnosticAction.CAPTURE -> CoreMessages.CREATE_SNAPSHOT.text()
  DiagnosticAction.PUSH -> CoreMessages.UPLOAD_SAVES.text(); DiagnosticAction.RESTORE -> CoreMessages.RESTORE_SAVES.text()
  DiagnosticAction.RECOVERY -> CoreMessages.HANDLE_INTERRUPTED_OPERATION.text(); DiagnosticAction.RESOLVE -> CoreMessages.RESOLVE_CONFLICT.text()
  DiagnosticAction.MERGE -> CoreMessages.MERGE_SAVES.text(); DiagnosticAction.ARCHIVE -> CoreMessages.ARCHIVE_OPERATION.text()
  DiagnosticAction.EXPORT_LOGS -> CoreMessages.EXPORT_LOGS.text(); DiagnosticAction.AUTOMATIC_SYNC -> CoreMessages.AUTOMATIC_SYNC.text()
  DiagnosticAction.OTHER -> CoreMessages.OTHER_OPERATION.text()
 }
 private fun stageText(value: DiagnosticStage): String = when (value) {
  DiagnosticStage.QUEUED -> CoreMessages.QUEUED.text(); DiagnosticStage.STARTED -> CoreMessages.STARTED.text()
  DiagnosticStage.CONFIRMATION -> CoreMessages.CONFIRMATION.text(); DiagnosticStage.GUARD -> CoreMessages.PREREQUISITE_CHECK.text()
  DiagnosticStage.WORK -> CoreMessages.EXECUTION.text(); DiagnosticStage.REFRESH -> CoreMessages.REFRESH.text()
  DiagnosticStage.FINISHED -> CoreMessages.FINISHED.text(); DiagnosticStage.RUNTIME -> CoreMessages.RUNTIME_STATUS.text()
  DiagnosticStage.CONNECTION -> CoreMessages.CONNECTION_CHECK.text(); DiagnosticStage.NAMESPACE -> CoreMessages.SYNC_DIRECTORY.text()
  DiagnosticStage.HTTP -> "HTTP"; DiagnosticStage.PREPARATION -> CoreMessages.INSTALLATION_PREPARATION.text()
  DiagnosticStage.INSTALLATION -> CoreMessages.SYSTEM_INSTALLATION.text(); DiagnosticStage.BRIDGE -> CoreMessages.SAVE_ACCESS.text()
  DiagnosticStage.READINESS -> CoreMessages.SAVE_READINESS_CHECK.text(); DiagnosticStage.GAME_LAUNCH -> CoreMessages.GAME_LAUNCH.text()
  DiagnosticStage.BACKUP -> CoreMessages.BACKUP.text(); DiagnosticStage.RESTORE -> CoreMessages.RESTORE_WRITE.text()
  DiagnosticStage.RECOVERY -> CoreMessages.INTERRUPTED_RESTORE.text(); DiagnosticStage.VERIFY -> CoreMessages.INTEGRITY_CHECK.text()
  DiagnosticStage.EXPORT -> CoreMessages.EXPORT.text()
 }
 private fun resultText(value: DiagnosticResult): String = when (value) {
  DiagnosticResult.STARTED -> CoreMessages.STARTED.text(); DiagnosticResult.SUCCEEDED -> CoreMessages.SUCCESS.text()
  DiagnosticResult.FAILED -> CoreMessages.FAILED.text(); DiagnosticResult.CANCELED -> CoreMessages.CANCELED.text()
  DiagnosticResult.REJECTED -> CoreMessages.PREREQUISITES_NOT_MET.text(); DiagnosticResult.SKIPPED -> CoreMessages.SKIPPED.text()
  DiagnosticResult.PENDING -> CoreMessages.PENDING.text(); DiagnosticResult.CONFLICT -> CoreMessages.CONFLICT_DETECTED.text()
 }
 private fun failureText(value: DiagnosticFailure): String = when (value) {
  DiagnosticFailure.GAME_NOT_SELECTED -> CoreMessages.NO_GAME_SELECTED.text(); DiagnosticFailure.NOT_CHECKED -> CoreMessages.SAVE_ACCESS_HAS_NOT_BEEN_CHECKED.text()
  DiagnosticFailure.FIRST_LAUNCH_NEEDED -> CoreMessages.THE_GAME_HAS_NOT_CREATED_A_SAVE_DIRECTORY.text(); DiagnosticFailure.UNRECOGNIZED_LAYOUT -> CoreMessages.SAVE_LAYOUT_NOT_RECOGNIZED.text()
  DiagnosticFailure.SYSTEM_REQUEST_PENDING -> CoreMessages.WAITING_FOR_SYSTEM_INSTALLATION_TO_FINISH.text(); DiagnosticFailure.CAPABILITY_UNAVAILABLE -> CoreMessages.REQUIRED_FILE_OPERATIONS_ARE_UNAVAILABLE.text()
  DiagnosticFailure.NONE -> CoreMessages.NO_ERROR.text(); DiagnosticFailure.BUSY -> CoreMessages.GAME_OR_SAVES_ARE_BUSY.text()
  DiagnosticFailure.NOT_READY -> CoreMessages.SAVES_ARE_NOT_READY.text(); DiagnosticFailure.RECOVERY_REQUIRED -> CoreMessages.AN_INTERRUPTED_OPERATION_NEEDS_ATTENTION.text()
  DiagnosticFailure.IDENTITY_MISMATCH -> CoreMessages.APPLICATION_IDENTITY_MISMATCH.text(); DiagnosticFailure.UNAVAILABLE -> CoreMessages.REQUIRED_CAPABILITY_UNAVAILABLE.text()
  DiagnosticFailure.CANCELED -> CoreMessages.OPERATION_CANCELED.text(); DiagnosticFailure.AUTHENTICATION -> CoreMessages.AUTHENTICATION_FAILED.text()
  DiagnosticFailure.AUTHORIZATION -> CoreMessages.ACCESS_DENIED.text(); DiagnosticFailure.HTTP -> CoreMessages.HTTP_REQUEST_FAILED.text()
  DiagnosticFailure.TIMEOUT -> CoreMessages.NETWORK_TIMEOUT.text(); DiagnosticFailure.DNS -> CoreMessages.DNS_LOOKUP_FAILED.text()
  DiagnosticFailure.CONNECT -> CoreMessages.NETWORK_CONNECTION_FAILED.text(); DiagnosticFailure.TLS -> CoreMessages.SECURE_CONNECTION_FAILED.text()
  DiagnosticFailure.EOF -> CoreMessages.CONNECTION_ENDED_EARLY.text(); DiagnosticFailure.CONNECTION -> CoreMessages.CONNECTION_INTERRUPTED.text()
  DiagnosticFailure.INTERRUPTED -> CoreMessages.NETWORK_OPERATION_INTERRUPTED.text(); DiagnosticFailure.NETWORK_IO -> CoreMessages.NETWORK_I_O_FAILED.text()
  DiagnosticFailure.STORAGE -> CoreMessages.FILE_I_O_FAILED.text(); DiagnosticFailure.VALIDATION -> CoreMessages.DATA_OR_PREREQUISITE_VALIDATION_FAILED.text()
  DiagnosticFailure.OTHER -> CoreMessages.UNCLASSIFIED_ERROR.text()
 }
 @JvmStatic fun suggestedFilename(): String = synchronized(lock) {
  "pd-sync-diagnostics-${filenameTime.format(configuration?.clock?.instant() ?: Instant.now())}.zip"
 }

 /** Never replaces or truncates an existing selection. Export errors are safe, actionable UI errors. */
 @JvmStatic @Throws(IOException::class) fun exportToDirectory(directory: Path): Path {
  val snapshot = snapshotExport()
  try {
   val target = SafePaths.directory(directory)
   val base = suggestedFilename().removeSuffix(".zip")
   for (attempt in 0..999) {
    val name = if (attempt == 0) "$base.zip" else "$base-$attempt.zip"
    val file = SafePaths.checked(target.resolve(name))
    val channel = try { FileChannel.open(file, CREATE_NEW, WRITE, NOFOLLOW_LINKS) }
     catch (_: FileAlreadyExistsException) { continue }
    try {
     channel.use { val buffer = ByteBuffer.wrap(snapshot); while (buffer.hasRemaining()) it.write(buffer); it.force(true) }
     return file
    } catch (_: Exception) {
     try { Files.deleteIfExists(SafePaths.checked(file)) } catch (_: Exception) { /* Own partial file only. */ }
     throw IOException(CoreMessages.VALIDATION_DIAGNOSTIC_EXPORT_COULD_NOT_BE_WRITTEN.text())
    }
   }
   throw IOException(CoreMessages.VALIDATION_DIAGNOSTIC_EXPORT_NAME_LIMIT_REACHED.text())
  } catch (_: Exception) { throw IOException(CoreMessages.VALIDATION_DIAGNOSTIC_EXPORT_COULD_NOT_BE_WRITTEN_TO_THE_SELECTED_DIRECTORY.text()) }
 }

 @JvmStatic fun classify(error: Throwable): DiagnosticFailure = try {
  when (val cause = unwrap(error)) {
   is CancellationException, is InterruptedException, is ClosedByInterruptException -> DiagnosticFailure.CANCELED
   is WebDavException -> classifyStatus(cause.statusCode)
   is NamespaceProvisioningException -> when (cause.reason) {
    NamespaceFailureReason.AUTHENTICATION -> DiagnosticFailure.AUTHENTICATION
    NamespaceFailureReason.FORBIDDEN -> DiagnosticFailure.AUTHORIZATION
    NamespaceFailureReason.CANCELLED -> DiagnosticFailure.CANCELED
    NamespaceFailureReason.NETWORK -> DiagnosticFailure.NETWORK_IO
    NamespaceFailureReason.HTTP_ERROR -> DiagnosticFailure.HTTP
    else -> DiagnosticFailure.VALIDATION
   }
   is SocketTimeoutException -> DiagnosticFailure.TIMEOUT
   is UnknownHostException -> DiagnosticFailure.DNS
   is ConnectException -> DiagnosticFailure.CONNECT
   is SSLException -> DiagnosticFailure.TLS
   is EOFException -> DiagnosticFailure.EOF
   is SocketException -> DiagnosticFailure.CONNECTION
   is InterruptedIOException -> DiagnosticFailure.INTERRUPTED
   is FileSystemException, is FileNotFoundException -> DiagnosticFailure.STORAGE
   is SecurityException -> DiagnosticFailure.AUTHORIZATION
   is IllegalArgumentException, is SaveNotStableException, is RemoteIntegrityException, is UnsettledRemoteException,
   is LegacyProtocolException -> DiagnosticFailure.VALIDATION
   is IOException -> {
    // Only the exact previous transport patch's closed messages are recognized, and only for a plain
    // IOException without causes/suppressed text. Nothing from the message is stored or interpolated.
    if (cause.javaClass == IOException::class.java && cause.cause == null && cause.suppressed.isEmpty())
     sanitizedNetworkCategory(cause.message) ?: DiagnosticFailure.OTHER else DiagnosticFailure.OTHER
   }
   else -> DiagnosticFailure.OTHER
  }
 } catch (_: Exception) { DiagnosticFailure.OTHER }

 private fun sanitizedNetworkCategory(message: String?): DiagnosticFailure? = when (message) {
  "WebDAV network request failed [TIMEOUT]" -> DiagnosticFailure.TIMEOUT
  "WebDAV network request failed [DNS]" -> DiagnosticFailure.DNS
  "WebDAV network request failed [CONNECT]" -> DiagnosticFailure.CONNECT
  "WebDAV network request failed [TLS]" -> DiagnosticFailure.TLS
  "WebDAV network request failed [EOF]" -> DiagnosticFailure.EOF
  "WebDAV network request failed [CONNECTION]" -> DiagnosticFailure.CONNECTION
  "WebDAV network request failed [INTERRUPTED]" -> DiagnosticFailure.INTERRUPTED
  "WebDAV network request failed [IO]" -> DiagnosticFailure.NETWORK_IO
  else -> null
 }
 private fun classifyStatus(status: Int) = when (status) {
  401 -> DiagnosticFailure.AUTHENTICATION; 403 -> DiagnosticFailure.AUTHORIZATION; else -> DiagnosticFailure.HTTP
 }
 private fun unwrap(error: Throwable): Throwable {
  var cause = error
  repeat(16) {
   if (cause is ExecutionException || cause is CompletionException || cause is UncheckedIOException)
    cause = cause.cause ?: return cause else return cause
  }
  return cause
 }
 private fun httpStatus(error: Throwable): Int? = try {
  when (val cause = unwrap(error)) {
   is WebDavException -> cause.statusCode; is NamespaceProvisioningException -> cause.statusCode; else -> null
  }?.takeIf { it in 100..599 }
 } catch (_: Exception) { null }
 private fun safeVersion(value: String) = value.takeIf { it == "development" || it == "unknown" || versionPattern.matches(it) } ?: "unknown"
 private fun safePlatform(value: String): String = when (value.lowercase(java.util.Locale.ROOT)) {
  "android" -> "android"; "windows", "win" -> "windows"; "linux" -> "linux"; "macos", "mac", "darwin" -> "macos"; else -> "unknown"
 }
 private fun safeZone(zone: ZoneId): ZoneId = if (zone.id.length <= 64 && zone.id.matches(Regex("[A-Za-z0-9_+:/.-]+"))) zone else ZoneOffset.UTC
 private fun utcDate(instant: Instant): LocalDate = instant.atZone(ZoneOffset.UTC).toLocalDate()
 private fun prepare(config: Configuration) {
  Files.createDirectories(SafePaths.checked(config.directory))
  require(Files.isDirectory(SafePaths.checked(config.directory), NOFOLLOW_LINKS))
  // This exact diagnostics-owned temporary is the only possible interrupted compaction artifact.
  // Reject a replaced link; do not sweep arbitrary files or older/raw logs into the export.
  val temporary = SafePaths.checked(config.directory.resolve("rotation.tmp"))
  requireRegularOrAbsent(temporary)
  if (Files.exists(temporary, NOFOLLOW_LINKS)) Files.delete(temporary)
 }
 private fun replaceBucket(config: Configuration, file: Path, bytes: ByteArray) {
  require(bytes.size <= MAX_FILE_BYTES / 2)
  val temporary = SafePaths.checked(config.directory.resolve("rotation.tmp"))
  requireRegularOrAbsent(temporary)
  try {
   FileChannel.open(temporary, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
    val buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true)
   }
   Files.move(SafePaths.checked(temporary), SafePaths.checked(file), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  } finally { Files.deleteIfExists(SafePaths.checked(temporary)) }
 }
 private fun requireRegularOrAbsent(file: Path) {
  SafePaths.checked(file)
  require(!Files.exists(file, NOFOLLOW_LINKS) || Files.isRegularFile(file, NOFOLLOW_LINKS))
 }
 private fun logFiles(config: Configuration): List<Path> = Files.newDirectoryStream(SafePaths.checked(config.directory)).use { stream ->
  stream.filter { dateName.matches(it.fileName.toString()) }.sortedBy { it.fileName.toString() }
 }
 private fun prune(config: Configuration) {
  val today = utcDate(config.clock.instant()); val oldest = today.minusDays(RETENTION_DAYS - 1)
  for (file in logFiles(config)) {
   requireRegularOrAbsent(file)
   val day = LocalDate.parse(dateName.matchEntire(file.fileName.toString())!!.groupValues[1])
   // Future buckets after a wall-clock correction are excluded and removed, never trusted as recent.
   if (day < oldest || day > today) Files.deleteIfExists(SafePaths.checked(file))
  }
 }
 private fun readRecords(file: Path, config: Configuration): List<Record> {
  requireRegularOrAbsent(file)
  val bytes = Files.newInputStream(SafePaths.checked(file), READ, NOFOLLOW_LINKS).use { it.readUpTo(MAX_FILE_BYTES + 1) }
  require(bytes.size <= MAX_FILE_BYTES)
  val text = bytes.decodeToString(throwOnInvalidSequence = true)
  val lines = text.split('\n')
  val today = utcDate(config.clock.instant()); val oldest = today.minusDays(RETENTION_DAYS - 1)
  val expected = dateName.matchEntire(file.fileName.toString())!!.groupValues[1]
  // Ignore an incomplete last record left by abrupt power/process loss.
  return lines.dropLast(1).mapNotNull { line ->
   try {
    require(line.length <= MAX_LINE_BYTES)
    requireUniqueJsonKeys(line)
    val record = codec.decodeFromString<Record>(line)
    val time = Instant.parse(record.timestampUtc); val day = utcDate(time)
    require(record.schema == 1 && day.toString() == expected && day >= oldest && day <= today)
    require(UUID.fromString(record.operationId).toString() == record.operationId)
    require(record.appVersion == safeVersion(record.appVersion) && record.platform == safePlatform(record.platform))
    val zone = ZoneId.of(record.zone)
    require(safeZone(zone).id == record.zone && record.offsetMinutes == zone.rules.getOffset(time).totalSeconds / 60)
    require(record.elapsedMs in 0..7 * 24 * 60 * 60 * 1000L)
    require(record.httpStatus == null || record.httpStatus in 100..599)
    require(record.details == null || record.details.isValid())
    record
   } catch (_: Exception) { null }
  }
 }
}
