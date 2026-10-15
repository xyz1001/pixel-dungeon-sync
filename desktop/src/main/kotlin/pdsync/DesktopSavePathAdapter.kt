package pdsync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.xyz1001.pdsync.android.SharedSavePathActions
import io.github.xyz1001.pdsync.android.SharedSavePathReadStatus
import io.github.xyz1001.pdsync.android.SharedSavePathState
import kotlinx.coroutines.CancellationException
import java.nio.file.Files
import java.nio.file.Path

/** Desktop inputs and read-only previews. Explicit Next still revalidates and persists in Main. */
internal class DesktopSavePathAdapter(
 initialPath: String,
 private val defaultPath: String?,
 private val location: DefaultProfileLocation
) {
 var state by mutableStateOf(SharedSavePathState(
  path = initialPath, editable = true, summary = null,
  canRequestRead = initialPath.isNotBlank(), canChooseDirectory = true,
  canUseDefault = defaultPath != null, canRetry = true
 ))
  private set
 var revision by mutableStateOf(0L)
  private set
 private var active = false
 private var pendingPreview: Preview? = null
 private var previewBlocked = false

 internal data class Preview(val path: String, val revision: Long)

 fun pageChanged(active: Boolean) {
  this.active = active
  invalidate()
 }

 fun editPath(path: String) = invalidate(path)
 fun chooseDirectory(selection: String?) { selection?.let(::editPath) }
 fun useDefault() { defaultPath?.let(::editPath) }
 fun retry() = invalidate()

 /** Returns true only for an already inspected target. Otherwise join/start one queued read. */
 fun requestContinue(): Boolean {
  if (!active || state.busy || state.continueAfterRead || state.path.isBlank()) return false
  return when (state.readStatus) {
   SharedSavePathReadStatus.READY, SharedSavePathReadStatus.PREPARE_REQUIRED -> state.canContinue
   SharedSavePathReadStatus.UNREAD, SharedSavePathReadStatus.READING -> {
    if (state.canRequestRead) state = state.copy(continueAfterRead = true)
    false
   }
   SharedSavePathReadStatus.FAILED -> {
    if (state.canRequestRead && state.canRetry) {
     invalidate()
     state = state.copy(continueAfterRead = true)
    }
    false
   }
  }
 }

 suspend fun chooseDirectoryUsing(
  choose: suspend (String) -> String?,
  canPublish: () -> Boolean,
  onBusyChanged: (Boolean) -> Unit
 ) {
  if (!active || state.readStatus == SharedSavePathReadStatus.READING) return
  val initialPath = state.path
  val initialRevision = revision
  var selection: String? = null
  onBusyChanged(true)
  try { selection = choose(initialPath) }
  catch (e: CancellationException) { throw e }
  catch (e: Exception) {
   if (canPublish() && active && initialRevision == revision) showError(e.message)
  } finally { onBusyChanged(false) }
  // Cancellation is a no-op. Publish a selected path after the hard operation ends.
  if (canPublish() && active && initialRevision == revision) chooseDirectory(selection)
 }

 private fun invalidate(path: String = state.path) {
  pendingPreview = null
  previewBlocked = false
  revision++
  state = state.copy(path = path, summary = null, notice = "", details = "", canContinue = false,
   readStatus = SharedSavePathReadStatus.UNREAD, continueAfterRead = false,
   canRequestRead = path.isNotBlank())
 }

 fun beginPreview(): Preview? {
  if (!active || previewBlocked || pendingPreview != null || !state.canRequestRead ||
   state.readStatus != SharedSavePathReadStatus.UNREAD) return null
  state = state.copy(summary = null, canContinue = false,
   readStatus = SharedSavePathReadStatus.READING, notice = "正在检查存档…")
  return Preview(state.path, revision).also { pendingPreview = it }
 }

 /** Never creates directories or changes game files. Missing is typed separately from unsafe. */
 fun inspect(preview: Preview): SaveSummary = inspectSelectedSaveDirectory(location.requireSelected(preview.path, false))

 /** Consume a queued continuation once, and only for the current page/path/revision/read. */
 fun completePreview(preview: Preview, result: Result<SaveSummary>): Boolean {
  if (!active || preview != pendingPreview || preview.revision != revision || preview.path != state.path) return false
  pendingPreview = null
  val safeMissing = result.exceptionOrNull() is SelectedSaveDirectoryMissing
  val success = result.isSuccess || safeMissing
  val continueRequested = state.continueAfterRead && success
  previewBlocked = !success
  state = state.copy(summary = result.getOrNull(), canContinue = success, continueAfterRead = false,
   details = if (success) "" else result.exceptionOrNull()?.message.orEmpty(),
   readStatus = when {
    safeMissing -> SharedSavePathReadStatus.PREPARE_REQUIRED
    result.isSuccess -> SharedSavePathReadStatus.READY
    else -> SharedSavePathReadStatus.FAILED
   },
   notice = when {
    safeMissing -> "下一步将创建此存档目录。"
    result.isFailure -> NoviceCopy.feedback(result.exceptionOrNull()?.message.orEmpty(), "无法读取存档，请重试。")
    else -> ""
   })
  return continueRequested
 }

 fun showError(message: String?) {
  pendingPreview = null
  previewBlocked = true
  state = state.copy(summary = null, notice = NoviceCopy.feedback(message.orEmpty(), "无法读取存档，请重试。"),
   details = message.orEmpty(), canContinue = false,
   continueAfterRead = false, readStatus = SharedSavePathReadStatus.FAILED)
 }
}

/** The same read-only content check is used by preview and the explicit final validation. */
internal fun inspectSelectedSaveDirectory(root: Path): SaveSummary {
 val summary = SaveSummary.read(root)
 require(summary.files > 0 || Files.list(root).use { children ->
  children.allMatch { isDirectoryMetadata(it.fileName.toString()) }
 }) { "此目录没有可识别存档，请选择档案根目录或空目录" }
 return summary
}

/** The actual shared-screen callbacks; retained callbacks are checked against the live host. */
internal class DesktopSavePathActions(
 private val adapter: DesktopSavePathAdapter,
 private val canAct: () -> Boolean,
 private val choose: () -> Unit,
 private val goBack: () -> Unit,
 private val continuePath: () -> Unit
) : SharedSavePathActions {
 override fun editPath(path: String) { if (canAct()) adapter.editPath(path) }
 override fun chooseDirectory() {
  if (canAct() && adapter.state.readStatus != SharedSavePathReadStatus.READING) choose()
 }
 override fun useDefault() { if (canAct()) adapter.useDefault() }
 override fun retry() { if (canAct()) adapter.retry() }
 override fun initializeGame() = Unit
 override fun back() { if (canAct()) goBack() }
 override fun next() { if (canAct() && adapter.requestContinue()) continuePath() }
}
