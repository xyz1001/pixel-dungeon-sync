package pdsync

import java.io.FileNotFoundException
import java.io.UncheckedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.*
import java.nio.file.FileSystemException
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import javax.net.ssl.SSLException

internal enum class OperationAction { CAPTURE, PUSH, RESTORE, RESOLVE, MERGE, MERGE_CURRENT, CLOUD_HISTORY, REMOTE_TIP, ARCHIVE }
internal enum class OperationEvent {
 CONFIRMATION_ENTERED, CONFIRMATION_CANCELED, CONFIRMATION_SUBMITTED, GUARD_REJECTED,
 OPERATION_QUEUED, OPERATION_STARTED, WORK_STARTED, WORK_SUCCEEDED, WORK_FAILED,
 HISTORY_REFRESH_STARTED, HISTORY_REFRESH_SUCCEEDED, HISTORY_REFRESH_FAILED, OPERATION_CANCELED, OPERATION_FINISHED
}
internal enum class OperationGuard { BUSY, STARTUP, CLOSED, NO_SESSION, NO_SOURCE }
internal enum class OperationFailure { CANCELED, AUTHENTICATION, AUTHORIZATION, NETWORK, STORAGE, VALIDATION, OTHER }

/**
 * Local, deliberately closed-schema diagnostics. Callers cannot supply text, paths, save identifiers,
 * exception messages, accounts or network details. An operation's ID is unrelated to snapshot IDs.
 * Each successful record is flushed to disk; failures are best-effort and never escape to the UI.
 * The current file and one previous generation are each limited to 128 KiB.
 *
 * SafePaths rejects symbolic links in every existing path component and the final open uses
 * NOFOLLOW_LINKS. As with SafePaths elsewhere, hostile concurrent ancestor replacement is outside
 * this check's guarantee. The shared lock also serializes separate recorders within this process.
 */
internal class OperationEvents(private val path: Path) {
 fun begin(action: OperationAction, automatic: Boolean = false): OperationTrace = OperationTrace(this, UUID.randomUUID(), action, automatic).also {
  it.record(OperationEvent.CONFIRMATION_ENTERED)
 }

 internal fun record(trace: OperationTrace, event: OperationEvent, guard: OperationGuard?, failure: OperationFailure?): Boolean =
  synchronized(writeLock) {
   try {
    val line = buildString {
     append("timestamp=").append(Instant.now())
     append(" id=").append(trace.id)
     append(" action=").append(trace.action.name)
     append(" event=").append(event.name)
     if (guard != null) append(" guard=").append(guard.name)
     if (failure != null) append(" failure=").append(failure.name)
     append('\n')
    }.toByteArray(Charsets.UTF_8)
    val current = SafePaths.checked(path)
    val previous = SafePaths.checked(current.resolveSibling(current.fileName.toString() + ".1"))
    Files.createDirectories(SafePaths.checked(current.parent))
    SafePaths.checked(current)
    requireRegularOrAbsent(current)
    requireRegularOrAbsent(previous)
    if (Files.exists(current, NOFOLLOW_LINKS)) {
     val attributes = Files.readAttributes(current, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
     require(attributes.isRegularFile)
     val size = attributes.size()
     // Do not carry an externally created oversized file into the bounded rotation.
     require(size <= MAX_FILE_BYTES)
     if (size + line.size > MAX_FILE_BYTES) {
      Files.move(SafePaths.checked(current), SafePaths.checked(previous), REPLACE_EXISTING)
     }
    }
    FileChannel.open(SafePaths.checked(current), CREATE, WRITE, APPEND, NOFOLLOW_LINKS).use { channel ->
     val bytes = ByteBuffer.wrap(line)
     while (bytes.hasRemaining()) channel.write(bytes)
     channel.force(true)
    }
    true
   } catch (_: Exception) {
    // Never print the failure: even its message or path may contain private information.
    false
   }
  }

 private fun requireRegularOrAbsent(file: Path) {
  SafePaths.checked(file)
  require(!Files.exists(file, NOFOLLOW_LINKS) || Files.isRegularFile(file, NOFOLLOW_LINKS))
 }

 companion object {
  internal const val MAX_FILE_BYTES = 128 * 1024
  private val writeLock = Any()
 }
}

internal class OperationTrace internal constructor(
 private val recorder: OperationEvents, private val uuid: UUID, val action: OperationAction, automatic: Boolean = false
) {
 private val diagnostics = DiagnosticLog.begin(when (action) {
  OperationAction.CAPTURE -> DiagnosticAction.CAPTURE
  OperationAction.PUSH -> DiagnosticAction.PUSH
  OperationAction.RESTORE, OperationAction.CLOUD_HISTORY, OperationAction.REMOTE_TIP -> DiagnosticAction.RESTORE
  OperationAction.RESOLVE -> DiagnosticAction.RESOLVE
  OperationAction.MERGE, OperationAction.MERGE_CURRENT -> DiagnosticAction.MERGE
  OperationAction.ARCHIVE -> DiagnosticAction.ARCHIVE
 }, automatic)
 private var diagnosticOutcome = DiagnosticResult.SUCCEEDED
 val id: String get() = uuid.toString()
 fun outcome(result: DiagnosticResult) {
  diagnosticOutcome = result
  diagnostics.record(DiagnosticStage.VERIFY, result)
 }
 fun <T> withDiagnostics(block: () -> T): T = DiagnosticLog.bind(diagnostics).use { block() }
 fun record(event: OperationEvent, guard: OperationGuard? = null, failure: OperationFailure? = null): Boolean {
  val (stage, result) = when (event) {
   OperationEvent.CONFIRMATION_ENTERED -> DiagnosticStage.CONFIRMATION to DiagnosticResult.STARTED
   OperationEvent.CONFIRMATION_CANCELED -> DiagnosticStage.CONFIRMATION to DiagnosticResult.CANCELED
   OperationEvent.CONFIRMATION_SUBMITTED -> DiagnosticStage.CONFIRMATION to DiagnosticResult.SUCCEEDED
   OperationEvent.GUARD_REJECTED -> DiagnosticStage.GUARD to DiagnosticResult.REJECTED
   OperationEvent.OPERATION_QUEUED -> DiagnosticStage.QUEUED to DiagnosticResult.PENDING
   OperationEvent.OPERATION_STARTED -> DiagnosticStage.STARTED to DiagnosticResult.STARTED
   OperationEvent.WORK_STARTED -> DiagnosticStage.WORK to DiagnosticResult.STARTED
   OperationEvent.WORK_SUCCEEDED -> DiagnosticStage.WORK to diagnosticOutcome
   OperationEvent.WORK_FAILED -> DiagnosticStage.WORK to DiagnosticResult.FAILED
   OperationEvent.HISTORY_REFRESH_STARTED -> DiagnosticStage.REFRESH to DiagnosticResult.STARTED
   OperationEvent.HISTORY_REFRESH_SUCCEEDED -> DiagnosticStage.REFRESH to DiagnosticResult.SUCCEEDED
   OperationEvent.HISTORY_REFRESH_FAILED -> DiagnosticStage.REFRESH to DiagnosticResult.FAILED
   OperationEvent.OPERATION_CANCELED -> DiagnosticStage.FINISHED to DiagnosticResult.CANCELED
   OperationEvent.OPERATION_FINISHED -> DiagnosticStage.FINISHED to when (failure) {
    null -> diagnosticOutcome
    OperationFailure.CANCELED -> DiagnosticResult.CANCELED
    else -> DiagnosticResult.FAILED
   }
  }
  diagnostics.record(stage, result, when (failure) {
   null -> DiagnosticFailure.NONE
   OperationFailure.CANCELED -> DiagnosticFailure.CANCELED
   OperationFailure.AUTHENTICATION -> DiagnosticFailure.AUTHENTICATION
   OperationFailure.AUTHORIZATION -> DiagnosticFailure.AUTHORIZATION
   OperationFailure.NETWORK -> DiagnosticFailure.NETWORK_IO
   OperationFailure.STORAGE -> DiagnosticFailure.STORAGE
   OperationFailure.VALIDATION -> DiagnosticFailure.VALIDATION
   OperationFailure.OTHER -> DiagnosticFailure.OTHER
  })
  if (event == OperationEvent.CONFIRMATION_CANCELED || event == OperationEvent.GUARD_REJECTED) {
   diagnostics.record(DiagnosticStage.FINISHED, result)
  }
  return recorder.record(this, event, guard, failure)
 }
}

/** Classify only typed failures and protocol status enums, never message text or stack traces. */
internal fun operationFailure(failure: Throwable): OperationFailure {
 var cause = failure
 // Only unwrap known transport wrappers. Bound traversal even for a malicious/cyclic cause chain.
 repeat(16) {
  if (cause is ExecutionException || cause is CompletionException || cause is UncheckedIOException) {
   cause = cause.cause ?: return OperationFailure.OTHER
  } else return classifiedFailure(cause)
 }
 return OperationFailure.OTHER
}

private fun classifiedFailure(failure: Throwable): OperationFailure = when (failure) {
 is CancellationException, is InterruptedException, is ClosedByInterruptException -> OperationFailure.CANCELED
 is WebDavException -> when (failure.statusCode) {
  401 -> OperationFailure.AUTHENTICATION
  403 -> OperationFailure.AUTHORIZATION
  else -> OperationFailure.NETWORK
 }
 is NamespaceProvisioningException -> when (failure.reason) {
  NamespaceFailureReason.AUTHENTICATION -> OperationFailure.AUTHENTICATION
  NamespaceFailureReason.FORBIDDEN -> OperationFailure.AUTHORIZATION
  NamespaceFailureReason.CANCELLED -> OperationFailure.CANCELED
  NamespaceFailureReason.NETWORK, NamespaceFailureReason.HTTP_ERROR -> OperationFailure.NETWORK
  else -> OperationFailure.VALIDATION
 }
 is SocketException, is SocketTimeoutException, is UnknownHostException, is SSLException -> OperationFailure.NETWORK
 is FileSystemException, is FileNotFoundException -> OperationFailure.STORAGE
 is SecurityException -> OperationFailure.AUTHORIZATION
 is SaveNotStableException, is IllegalArgumentException, is RemoteIntegrityException, is UnsettledRemoteException,
 is LegacyProtocolException -> OperationFailure.VALIDATION
 else -> OperationFailure.OTHER
}
