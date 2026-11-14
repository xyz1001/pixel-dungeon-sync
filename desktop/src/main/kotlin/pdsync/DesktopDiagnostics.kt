package pdsync

import pdsync.i18n.DesktopMessages

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Log only closed categories, never the selected path or the inspector's free-form reason. */
internal fun validateSelectedGame(path: Path): TargetGame {
 val trace = DiagnosticLog.begin(DiagnosticAction.INSPECT, false)
 try {
  val candidate = ShatteredInstallationDiscovery().validateJar(path)
  val target = candidate.target ?: throw IllegalArgumentException(candidate.reason)
  trace.record(DiagnosticStage.FINISHED, DiagnosticResult.SUCCEEDED)
  return target
 } catch (e: Exception) {
  trace.failure(DiagnosticStage.FINISHED, e)
  throw e
 }
}

/** A separate private log directory; the older operation-events log and all history remain intact. */
internal fun configureDesktopDiagnostics(directory: Path) {
 val platform = desktopDiagnosticPlatform(System.getProperty("os.name")) ?: return
 val version = DesktopDiagnosticExport::class.java.`package`?.implementationVersion ?: "development"
 runCatching {
  configureDesktopDiagnosticEnvironment()
  DiagnosticLog.configure(directory, version, platform)
  DiagnosticLog.begin(DiagnosticAction.APP_START, false).record(DiagnosticStage.FINISHED, DiagnosticResult.SUCCEEDED)
 }
}

internal fun desktopDiagnosticPlatform(osName: String): String? = when {
 osName.lowercase(Locale.ROOT).startsWith("windows") -> "windows"
 osName.lowercase(Locale.ROOT).startsWith("mac") || osName.lowercase(Locale.ROOT) == "darwin" -> "macos"
 osName.lowercase(Locale.ROOT).startsWith("linux") -> "linux"
 else -> null
}

/** Call inside the actual IO dispatcher: a ThreadLocal scope must never span a suspension. */
internal fun <T> diagnosticWork(action: DiagnosticAction, result: (T) -> DiagnosticResult = { DiagnosticResult.SUCCEEDED }, block: () -> T): T {
 val trace = DiagnosticLog.begin(action, false)
 return DiagnosticLog.bind(trace).use {
  try {
   trace.record(DiagnosticStage.WORK, DiagnosticResult.STARTED)
   val value = block()
   val outcome = result(value)
   trace.record(DiagnosticStage.WORK, outcome)
   trace.record(DiagnosticStage.FINISHED, outcome)
   value
  } catch (failure: Exception) {
   trace.failure(DiagnosticStage.WORK, failure)
   trace.failure(DiagnosticStage.FINISHED, failure)
   throw failure
  }
 }
}

/** Completed observation failures only; repeated identical polling failures are not log spam. */
internal class DiagnosticObservationFailures {
 private var last: DiagnosticFailure? = null
 fun recovered() { last = null }
 fun failed(error: Throwable) {
  val category = DiagnosticLog.classify(error)
  if (last == category) return
  last = category
  val trace = DiagnosticLog.begin(DiagnosticAction.AUTOMATIC_SYNC, true)
  trace.failure(DiagnosticStage.WORK, error)
  trace.failure(DiagnosticStage.FINISHED, error)
 }
}

internal fun diagnosticPublishedResult(value: Any?): DiagnosticResult = when (value) {
 is AppendPushResult.Diverged -> DiagnosticResult.CONFLICT
 is OnboardingRestoreResult.Published -> diagnosticPublishedResult(value.result)
 else -> DiagnosticResult.SUCCEEDED
}

internal sealed interface DiagnosticExportResult {
 data object Canceled : DiagnosticExportResult
 data object Ignored : DiagnosticExportResult
 data class Saved(val filename: String) : DiagnosticExportResult
 data class Failed(val message: String) : DiagnosticExportResult
}

/** Local-only export with no sync/session dependency; repeated clicks cannot open another chooser. */
internal class DesktopDiagnosticExport(
 private val choose: suspend (String, String) -> String? = { initial, title -> NativeFolderChooser.choose(initial, title) },
 private val write: (Path) -> Path = DiagnosticLog::exportToDirectory
) {
 private val active = AtomicBoolean(false)

 suspend fun export(initial: String): DiagnosticExportResult {
  if (!active.compareAndSet(false, true)) return DiagnosticExportResult.Ignored
  val trace = DiagnosticLog.begin(DiagnosticAction.EXPORT_LOGS, false)
  try {
   val destination = try { choose(initial, DesktopMessages.EXPORT_LOGS.text()) }
   catch (failure: CancellationException) { throw failure }
   catch (failure: Exception) {
    trace.failure(DiagnosticStage.EXPORT, failure)
    trace.record(DiagnosticStage.FINISHED, DiagnosticResult.FAILED)
    return DiagnosticExportResult.Failed(DesktopMessages.CANNOT_OPEN_THE_SYSTEM_FOLDER_PICKER_PLEASE_TRY_AGAIN_LATER.text())
   }
   if (destination == null) {
    trace.record(DiagnosticStage.EXPORT, DiagnosticResult.CANCELED)
    trace.record(DiagnosticStage.FINISHED, DiagnosticResult.CANCELED)
    return DiagnosticExportResult.Canceled
   }
   return try {
    val saved = withContext(Dispatchers.IO) {
     DiagnosticLog.bind(trace).use {
      trace.record(DiagnosticStage.EXPORT, DiagnosticResult.STARTED)
      write(Path.of(destination))
     }
    }
    trace.record(DiagnosticStage.EXPORT, DiagnosticResult.SUCCEEDED)
    trace.record(DiagnosticStage.FINISHED, DiagnosticResult.SUCCEEDED)
    DiagnosticExportResult.Saved(saved.fileName.toString())
   } catch (failure: CancellationException) { throw failure }
   catch (failure: Exception) {
    trace.failure(DiagnosticStage.EXPORT, failure)
    trace.record(DiagnosticStage.FINISHED, DiagnosticResult.FAILED)
    DiagnosticExportResult.Failed(DesktopMessages.EXPORT_FAILED_CHECK_FOLDER_PERMISSIONS_AND_AVAILABLE_SPACE.text())
   }
  } catch (failure: CancellationException) {
   trace.record(DiagnosticStage.EXPORT, DiagnosticResult.CANCELED)
   trace.record(DiagnosticStage.FINISHED, DiagnosticResult.CANCELED)
   throw failure
  } finally { active.set(false) }
 }
}
