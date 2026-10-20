package pdsync

import pdsync.i18n.DesktopMessages

import io.github.xyz1001.pdsync.android.SharedGameIdentity
import io.github.xyz1001.pdsync.android.SharedSettingsActions
import io.github.xyz1001.pdsync.android.SharedSettingsKey
import io.github.xyz1001.pdsync.android.SharedSettingsRow
import io.github.xyz1001.pdsync.android.SharedSettingsState
import io.github.xyz1001.pdsync.android.actionEnabled
import io.github.xyz1001.pdsync.android.backEnabled

/** Settings are a projection of real host facts. Rendering never reads or writes configuration. */
internal data class DesktopSettingsContext(
 val config: AppConfig?,
 val game: SharedGameIdentity?,
 val savePath: String,
 val hasSession: Boolean,
 val connected: Boolean,
 val pendingUpload: Boolean,
 val currentName: String,
 val pageActive: Boolean,
 val busy: Boolean,
 val connectingAtStartup: Boolean,
 val appClosed: Boolean,
 val managementOpen: Boolean = false,
 val editorOpen: Boolean = false,
 val targetError: String? = null,
 val locationIssue: String? = null,
 val operationNotice: String? = null,
 val diagnosticExportNotice: String? = null,
)

internal enum class DesktopSettingsMaintenance { EDIT_DIRECTORY, ARCHIVE_PENDING }

internal fun DesktopSettingsContext.maintenanceEnabled(action: DesktopSettingsMaintenance): Boolean =
 pageActive && managementOpen && !editorOpen && !busy && !connectingAtStartup && !appClosed &&
  when (action) {
   DesktopSettingsMaintenance.EDIT_DIRECTORY -> hasSession
   DesktopSettingsMaintenance.ARCHIVE_PENDING -> hasSession && pendingUpload
  }

internal object DesktopSharedSettingsAdapter {
 fun state(current: DesktopSettingsContext): SharedSettingsState {
  val config = current.config
  val notice = notice(current)
  val rows = buildList {
   add(SharedSettingsRow(SharedSettingsKey.GAME, DesktopMessages.GAME.text(), current.game?.summary ?: DesktopMessages.NO_GAME_SELECTED.text(),
    DesktopMessages.CHANGE.text(), config != null))
   add(SharedSettingsRow(SharedSettingsKey.SAVE_PATH, DesktopMessages.SAVE_LOCATION.text(), current.savePath.ifBlank { DesktopMessages.NOT_SELECTED.text() },
    DesktopMessages.EDIT.text(), true))
   add(SharedSettingsRow(SharedSettingsKey.CLOUD_CONNECTION, DesktopMessages.CLOUD_CONNECTION.text(), if (config == null) DesktopMessages.NOT_CONFIGURED.text() else
    listOf(if (config.connectionProvider == "official") DesktopMessages.BUILT_IN_SERVICE.text() else DesktopMessages.CUSTOM_SERVICE.text(),
     config.username, if (current.connected) DesktopMessages.CONNECTED.text() else DesktopMessages.DISCONNECTED.text()).filter(String::isNotBlank).joinToString(" · "),
    DesktopMessages.EDIT.text(), true))
   add(SharedSettingsRow(SharedSettingsKey.AUTOMATIC_UPLOAD, DesktopMessages.AUTOMATIC_UPLOAD.text(), when {
    config == null -> DesktopMessages.NOT_CONFIGURED.text()
    config.autoUploadPaused -> DesktopMessages.PAUSED.text()
    else -> DesktopMessages.ENABLED.text()
   }, if (config?.autoUploadPaused == true) DesktopMessages.ENABLE.text() else DesktopMessages.PAUSE.text(), config != null, toggleEnabled = config != null && !config.autoUploadPaused))
   add(SharedSettingsRow(SharedSettingsKey.RECHECK_SAVES, DesktopMessages.SAVE_CHECK.text(),
    if (current.hasSession) DesktopMessages.READ.text() else DesktopMessages.NOT_READY.text(), DesktopMessages.CHECK_AGAIN.text(), true))
   add(SharedSettingsRow(SharedSettingsKey.REVIEW_SETUP, DesktopMessages.SETUP_GUIDE.text(),
    if (config?.onboardingProgress == OnboardingProgress.COMPLETE) DesktopMessages.COMPLETE.text() else DesktopMessages.INCOMPLETE.text(), DesktopMessages.CHECK_AGAIN.text(), true, setupComplete = config?.onboardingProgress == OnboardingProgress.COMPLETE))
   if (current.hasSession || current.pendingUpload) {
    add(SharedSettingsRow(SharedSettingsKey.MAINTENANCE, DesktopMessages.MANAGE_SAVES.text(),
     if (current.pendingUpload) DesktopMessages.AN_UPLOAD_IS_INCOMPLETE.text() else current.currentName.ifBlank { DesktopMessages.NAMES_AND_RUN_IDENTITIES.text() },
     DesktopMessages.MANAGE.text(), true))
   }
   add(SharedSettingsRow(SharedSettingsKey.EXPORT_LOGS, DesktopMessages.LOGS.text(), "", DesktopMessages.EXPORT.text(), true))
  }
  return SharedSettingsState(current.game, rows,
   pageActive = current.pageActive && !current.appClosed && !current.managementOpen && !current.editorOpen,
   busy = current.busy || current.connectingAtStartup || current.appClosed,
   notice = notice?.first.orEmpty(), noticeError = notice?.second == true)
 }

 /** Only short, actionable results belong on the page. Raw diagnostics remain available on demand. */
 private fun notice(current: DesktopSettingsContext): Pair<String, Boolean>? = when {
  !current.diagnosticExportNotice.isNullOrBlank() ->
   if (current.diagnosticExportNotice.startsWith(DesktopMessages.LOGS_EXPORTED.text())) DesktopMessages.LOGS_EXPORTED_79.text() to false
   else concise(current.diagnosticExportNotice, DesktopMessages.LOG_EXPORT_FAILED_PLEASE_TRY_AGAIN.text()) to true
  current.operationNotice?.startsWith(DesktopMessages.SETTINGS_NOT_SAVED.text()) == true -> DesktopMessages.SETTINGS_NOT_SAVED_PLEASE_TRY_AGAIN.text() to true
  current.operationNotice?.startsWith(DesktopMessages.TARGET_GAME_NOT_SAVED.text()) == true -> DesktopMessages.GAME_NOT_CHANGED_PLEASE_SELECT_IT_AGAIN.text() to true
  current.operationNotice?.startsWith(DesktopMessages.CANNOT_UPDATE_SAVE_INFORMATION.text()) == true ->
   concise(current.operationNotice, DesktopMessages.SAVE_INFORMATION_NOT_UPDATED_PLEASE_TRY_AGAIN.text()) to true
  !current.targetError.isNullOrBlank() -> DesktopMessages.GAME_FILE_UNAVAILABLE_PLEASE_SELECT_IT_AGAIN.text() to true
  !current.locationIssue.isNullOrBlank() -> DesktopMessages.CANNOT_READ_SAVES_PLEASE_CHECK_AGAIN.text() to true
  else -> null
 }

 private fun concise(raw: String, fallback: String): String {
  val translated = NoviceCopy.feedback(raw, fallback)
  return if (translated == raw && (raw.startsWith(DesktopMessages.CANNOT_UPDATE_SAVE_INFORMATION.text()) ||
    raw.contains(Regex("https?://|[A-Za-z]:[\\\\/]|(^|\\s)/|Exception|Error|failed|denied", RegexOption.IGNORE_CASE)))) fallback
   else translated
 }
}

/** Each callback rechecks current capabilities and its original page lifetime before dispatching. */
internal class DesktopSharedSettingsActions(
 private val state: () -> SharedSettingsState,
 private val pageCurrent: () -> Boolean,
 private val performAction: (SharedSettingsKey) -> Unit,
 private val goBack: () -> Unit,
) : SharedSettingsActions {
 override fun perform(key: SharedSettingsKey) {
  if (pageCurrent() && state().actionEnabled(key)) performAction(key)
 }
 override fun back() {
  if (pageCurrent() && state().backEnabled) goBack()
 }
}

/** Opening management does not authorize a later action after the dialog or session changes. */
internal class DesktopSettingsMaintenanceActions(
 private val context: () -> DesktopSettingsContext,
 private val dialogCurrent: () -> Boolean,
 private val performAction: (DesktopSettingsMaintenance) -> Unit,
) {
 fun perform(action: DesktopSettingsMaintenance) {
  if (dialogCurrent() && context().maintenanceEnabled(action)) performAction(action)
 }
}
