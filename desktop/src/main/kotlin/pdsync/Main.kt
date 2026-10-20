package pdsync

import pdsync.i18n.DesktopMessages

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import java.nio.file.Path
import io.github.xyz1001.pdsync.android.ConnectionEntryModel
import io.github.xyz1001.pdsync.android.SharedConnectionConfig
import io.github.xyz1001.pdsync.android.SharedConnectionController
import io.github.xyz1001.pdsync.android.SharedConnectionHost
import io.github.xyz1001.pdsync.android.SharedGameIdentity
import io.github.xyz1001.pdsync.android.SharedConnectionScreen
import io.github.xyz1001.pdsync.android.SharedSavePathScreen
import io.github.xyz1001.pdsync.android.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.swing.SwingUtilities

private val workspace = applicationWorkspace()
private typealias Action = OperationAction
private enum class SettingsMode { FULL, UNLOCK }
private enum class Page { HOME, SETTINGS, DISCOVERY, PATH, CONNECTION, CONFLICT, CONFIRM }
private data class OperationResult(val message: String, val conflict: AppendConflict? = null, val appliedFiles: Map<String, String>? = null, val automaticSkipped: Boolean = false)
private data class LoadedProfile(val session: AppendProfileSession, val history: List<HistoryEntry>, val pending: Boolean, val saves: SaveVersions?, val target: TargetGame?, val targetError: String?, val device: DeviceIdentity, val baselineFiles: Map<String, String>?, val pendingSourceAvailable: Boolean)
private val green = Color(0xFF326650)
private val muted = Color(0xFF657069)

/** Retained modal callbacks and compatibility results belong to one observed account/session. */
internal data class DesktopConflictDialogOwner(
 val generation: Long,
 val session: AppendProfileSession?,
 val config: AppConfig?,
 val conflict: AppendConflict?,
) {
 fun matches(current: DesktopConflictDialogOwner, pageActive: Boolean): Boolean =
  pageActive && session != null && conflict != null && generation == current.generation &&
   session === current.session && config == current.config && conflict === current.conflict
}

/** A failed/canceled read can never be represented by the null that enables overwrite. */
internal fun desktopConflictCompatibilityIssue(check: () -> Unit): String? = try {
 check()
 null
} catch (failure: CancellationException) {
 throw failure
} catch (failure: Exception) {
 failure.message?.takeIf(String::isNotBlank) ?: DesktopMessages.NOT_VERIFIED.text()
}

/** Game selection carries the configured/default path forward without reading or creating it. */
internal fun desktopSetupDraftForGame(draft: AppConfig, targetGame: String, currentPath: String): AppConfig =
 draft.copy(targetGame = targetGame,
  selectedSaveRoot = draft.selectedSaveRoot?.takeIf(String::isNotBlank) ?: currentPath.takeIf(String::isNotBlank),
  onboardingProgress = OnboardingProgress.PENDING)

/** Explicitly completed configuration remains on HOME even if local files later become unavailable. */
internal fun desktopCompletedSetup(config: AppConfig?): Boolean =
 config?.onboardingProgress == OnboardingProgress.COMPLETE

/** HOME prepares only the retained target directory; this does not establish sync ancestry. */
internal fun desktopPrepareSelectedDirectory(config: AppConfig, location: DefaultProfileLocation): Path {
 val root = location.requireSelected(config.selectedSaveRoot ?: location.resolve().toString(), true)
 inspectSelectedSaveDirectory(root)
 return root
}

/** Desktop uses the JVM's system locale at startup; restart after changing the OS language. */
fun main() {
 pdsync.i18n.UiLocale.useSystemLocale()
 runSyncApplication()
}

/** Each application keeps only its selected target's process association; every check enumerates afresh. */
private fun productionProcessCheck(): (TargetGame?) -> GameProcessStatus {
 val lock = Any()
 var selected: TargetGame? = null
 var detector = GameProcessDetector(null)
 return { target -> synchronized(lock) {
  if (target != selected) { selected = target; detector = GameProcessDetector(target) }
  detector.status()
 } }
}

/** Production always uses fresh process detection. Dependency injection is only used by test code. */
internal fun runSyncApplication(
 processStatus: (TargetGame?) -> GameProcessStatus = productionProcessCheck(),
 title: String = DesktopMessages.PIXEL_DUNGEON_SAVE_SYNC.text()
) = application {
 remember { configureDesktopDiagnostics(workspace.resolve(".local")) }
 var busy by remember { mutableStateOf(false) }
 var closeBlocked by remember { mutableStateOf(false) }
 var connectingAtStartup by remember { mutableStateOf(true) }
 var appClosed by remember { mutableStateOf(false) }
 Window(onCloseRequest = { if (busy || connectingAtStartup) closeBlocked = true else { appClosed = true; exitApplication() } }, title = title, icon = painterResource("icons/pdsync.png"), state = rememberWindowState(width = 1120.dp, height = 800.dp)) {
  LaunchedEffect(Unit) { window.minimumSize = java.awt.Dimension(760, 640) }
  DisposableEffect(Unit) { onDispose { appClosed = true } }
  if (System.getenv("PDSYNC_SMOKE") == "1") LaunchedEffect(Unit) {
   delay(3000); check(window.isDisplayable && window.isShowing); println("PDSYNC_UI_SMOKE_OK ${window.width}x${window.height}"); exitApplication()
  }
  val configFile = remember { ConfigFile(workspace.resolve(".local/config.json")) }
  val setupSelection = remember { SetupSelection(workspace.resolve(".local/setup-game.txt")) }
  val initialResult = remember { runCatching { configFile.load() } }
  var config by remember { mutableStateOf(initialResult.getOrNull()) }
  val location = remember { DefaultProfileLocation() }
  val pathResult = remember { runCatching { location.resolve() } }
  val pathAdapter = remember {
   DesktopSavePathAdapter(config?.selectedSaveRoot ?: pathResult.getOrNull()?.toString().orEmpty(), pathResult.getOrNull()?.toString(), location)
  }
  val pathNavigation = remember { DesktopSavePathNavigation() }
  var password by remember { mutableStateOf("") }
  var connected by remember { mutableStateOf(false) }
  val syncAccess = remember { DesktopSyncAccess() }
  var serviceExpired by remember { mutableStateOf(false) }
  var entitlementRetryAt by remember { mutableStateOf(0L) }
  var connectionNotice by remember { mutableStateOf<String?>(null) }
  var page by remember { mutableStateOf(if (config == null) Page.DISCOVERY else Page.HOME) }
  var pageGeneration by remember { mutableStateOf(0L) }
  var connectionReturnPage by remember { mutableStateOf(Page.HOME) }
  var connectionAppreciationOnly by remember { mutableStateOf(false) }
  var onboardingActive by remember { mutableStateOf(config == null || config?.onboardingProgress in setOf(OnboardingProgress.PENDING, OnboardingProgress.DEFERRED)) }
  var setupDraft by remember { mutableStateOf(config ?: newConnectionConfig().copy(targetGame = runCatching { setupSelection.load() }.getOrNull().orEmpty())) }
  var discovery by remember { mutableStateOf<InstallationDiscoveryResult?>(null) }
  var discoveredTarget by remember { mutableStateOf<TargetGame?>(null) }
  var discoveryNotice by remember { mutableStateOf<String?>(null) }
  var remoteInspectionReady by remember { mutableStateOf(false) }
  var settingsMode by remember { mutableStateOf(SettingsMode.FULL) }
  var session by remember { mutableStateOf<AppendProfileSession?>(null) }
  var saveInfo by remember { mutableStateOf<SaveVersions?>(null) }
  var targetInfo by remember { mutableStateOf<TargetGame?>(null) }
  var targetError by remember { mutableStateOf<String?>(null) }
  var history by remember { mutableStateOf(emptyList<HistoryEntry>()) }
  var graphHistory by remember { mutableStateOf<SyncHistory?>(null) }
  var renameSnapshot by remember { mutableStateOf<String?>(null) }
  var historyName by remember { mutableStateOf("") }
  var selected by remember { mutableStateOf<String?>(null) }
  var selectedByUser by remember { mutableStateOf(false) }
  var homeSelectionGeneration by remember { mutableStateOf(0L) }
  var homeHistoryPage by remember { mutableStateOf(0) }
  var homeProfile by remember { mutableStateOf<AppConfig?>(null) }
  var localHistoryVerified by remember { mutableStateOf(false) }
  val archiveReads = remember { DesktopArchiveReads() }
  val renewalReads = remember { DesktopRenewalHistoryReads() }
  var homeOperation by remember { mutableStateOf(SharedHomeOperation.NONE) }
  var remoteTips by remember { mutableStateOf(emptySet<String>()) }
  var conflictSelection by remember { mutableStateOf<String?>(null) }
  var pending by remember { mutableStateOf(false) }
  var pendingUploadSourceAvailable by remember { mutableStateOf(false) }
  var conflict by remember { mutableStateOf<AppendConflict?>(null) }
  var directoryEditor by remember { mutableStateOf<DirectorySnapshotMetadata?>(null) }
  var directoryInline by remember { mutableStateOf(false) }
  var directoryName by remember { mutableStateOf("") }
  var directoryNotice by remember { mutableStateOf<String?>(null) }
  var confirm by remember { mutableStateOf<OperationTrace?>(null) }
  var confirmationFiles by remember { mutableStateOf<Map<String,String>?>(null) }
  val operationEvents = remember { OperationEvents(workspace.resolve(".local/operation-events.log")) }
  val diagnosticExport = remember { DesktopDiagnosticExport() }
  var diagnosticExportNotice by remember { mutableStateOf<String?>(null) }
  var confirmationNotice by remember { mutableStateOf<String?>(null) }
  var restoreIssue by remember { mutableStateOf<String?>(DesktopMessages.SELECT_A_SNAPSHOT.text()) }
  var conflictRestoreIssue by remember { mutableStateOf<String?>(DesktopMessages.NOT_VERIFIED.text()) }
  var locationIssue by remember { mutableStateOf<String?>(pathResult.exceptionOrNull()?.message) }
  var status by remember { mutableStateOf(DesktopMessages.CHECKING_THE_DEFAULT_SAVE_LOCATION.text()) }
  var gameStatus by remember { mutableStateOf<GameProcessStatus>(GameProcessStatus.Unknown(DesktopMessages.SELECT_THE_OFFICIAL_GAME_JAR_IN_SETTINGS.text())) }
  val gameLauncher = remember { GameLauncher(processStatus) }
  var launchingGame by remember { mutableStateOf(false) }
  var launchNotice by remember { mutableStateOf<String?>(null) }
  var automaticStatus by remember { mutableStateOf(DesktopMessages.ESTABLISHING_A_LOCAL_OBSERVATION_BASELINE.text()) }
  var automatic by remember { mutableStateOf(AutomaticUpload()) }
  val observationDiagnostics = remember { DiagnosticObservationFailures() }
  var remoteReadAt by remember { mutableStateOf(0L) }
  var device by remember { mutableStateOf<DeviceIdentity?>(null) }
  var remoteObserved by remember { mutableStateOf(emptyMap<String, String>()) }
  val scope = rememberCoroutineScope()
  val selection = history.firstOrNull { it.manifest.id == selected } ?: graphHistory?.graph?.commits?.values?.firstOrNull { it.snapshot.id==selected }?.let { HistoryEntry(it.snapshot,"",DesktopMessages.CLOUD_HISTORY.text()) }

  val iconTarget = (if (onboardingActive) discoveredTarget ?: targetInfo else targetInfo)?.takeIf { it.trusted }
  val selectedGameIcon = rememberDesktopGameIcon(iconTarget)
  fun gameIdentity(target: TargetGame) = SharedGameIdentity(DesktopMessages.SHATTERED_PIXEL_DUNGEON.text(), target.displayVersion,
   selectedGameIcon.takeIf { target == iconTarget })

  fun homeState() = DesktopSharedHomeAdapter.state(DesktopHomeContext(
   config = config, hasSession = session != null,
   pageActive = page == Page.HOME && directoryEditor == null && renameSnapshot == null,
   busy = busy, connectingAtStartup = connectingAtStartup, appClosed = appClosed,
   connected = connected, serviceExpired = serviceExpired, gameStatus = gameStatus, launchingGame = launchingGame,
   pendingUpload = pending, hasConflict = conflict != null, remoteTips = remoteTips,
   selectedId = selected, selectedByUser = selectedByUser, restoreIssue = restoreIssue,
   history = graphHistory, historyPage = homeHistoryPage,
   game = targetInfo?.takeIf { it.trusted }?.let { gameIdentity(it) },
   operation = homeOperation, automaticStatus = automaticStatus, operationStatus = status,
   connectionNotice = connectionNotice, locationIssue = locationIssue,
   launchNotice = launchNotice, deviceLabel = device?.label,
   historyOwner = homeProfile, historyVerified = remoteInspectionReady,
   localHistoryVerified = localHistoryVerified, pendingUploadSourceAvailable = pendingUploadSourceAvailable,
   archiveLoading = archiveReads.loading,
   historyRefreshAccessKnown = syncAccess.hasKnownAccess(config) && renewalReads.allowsManualRefresh(config),
  ))
  fun conflictOwner() = DesktopConflictDialogOwner(pageGeneration, session, config, conflict)
  fun reconcileHomeSelection(retainExplicit: Boolean = true) {
   val next = desktopHomeSelection(graphHistory, selected, selectedByUser && retainExplicit)
   if (next.snapshotId != selected) restoreIssue = DesktopMessages.VERIFYING_THE_ACTUAL_VERSION.text()
   selected = next.snapshotId; selectedByUser = next.explicit
  }

  fun navigate(next: Page) {
   if (next == Page.CONNECTION && page != Page.CONNECTION) {
    connectionReturnPage = if (page == Page.SETTINGS) Page.SETTINGS else if (onboardingActive) Page.DISCOVERY else Page.HOME
   }
   if (next != Page.CONNECTION) connectionAppreciationOnly = false
   pathAdapter.pageChanged(next == Page.PATH)
   archiveReads.invalidate()
   pageGeneration++; page = next; closeBlocked = false
  }
  suspend fun reconnectSaved(saved: AppConfig) {
   val savedPassword = saved.password
   if (OfficialSyncAccess.isOfficialEndpoint(saved.endpoint) && savedPassword != null) {
    password = savedPassword
    try {
     withContext(Dispatchers.IO) { syncAccess.beginOperation(saved, savedPassword) }
     connected = true; connectionNotice = null
     return
    } catch (failure: CancellationException) { throw failure }
    catch (failure: Exception) {
     serviceExpired = syncAccess.isExpired(saved)
     connected = serviceExpired // Expiry does not discard the authenticated account or credentials.
     connectionNotice = failure.message
     return
    }
   }

   when (val restored = withContext(Dispatchers.IO) { diagnosticWork(DiagnosticAction.CONNECT, result = { if (it is SavedConnectionResult.Connected) DiagnosticResult.SUCCEEDED else DiagnosticResult.FAILED }) { restoreSavedConnection(saved) } }) {
    is SavedConnectionResult.Connected -> { password = restored.connection.password; connected = true; connectionNotice = null }
    is SavedConnectionResult.Retry -> { password = restored.connection.password; settingsMode = SettingsMode.UNLOCK; connectionNotice = restored.message }
    is SavedConnectionResult.NeedsInput -> { settingsMode = SettingsMode.UNLOCK; connectionNotice = restored.message }
   }
  }
  fun openConnection(mode: SettingsMode) {
   if (busy || connectingAtStartup || appClosed) return
   connectionAppreciationOnly = false
   settingsMode = mode; if (config?.onboardingProgress != OnboardingProgress.COMPLETE) { onboardingActive = true; setupDraft = config ?: setupDraft }; navigate(Page.CONNECTION)
  }
  fun openHomeAppreciation() {
   if (!desktopHomeAppreciationAllowed(config, homeState()) || !syncAccess.isExpired(config)) return
   connectionAppreciationOnly = true
   navigate(Page.CONNECTION)
   connectionReturnPage = Page.HOME
  }
  fun requestConfirmation(action: Action) {
   if (busy || connectingAtStartup || appClosed) return
   if (!desktopHomeTransferAllowed(action, homeState())) return
   confirmationFiles=graphHistory?.currentFiles
   confirm = operationEvents.begin(action); confirmationNotice = null; navigate(Page.CONFIRM)
  }
  suspend fun refreshProfile(value: AppConfig?, prepareSelectedDirectory: Boolean = false,
                             canPublish: () -> Boolean = { true }) {
   val sameProfile = sameDesktopHomeProfile(homeProfile, value)
   val generation = pageGeneration
   val readPage = page
   fun current() = !appClosed && page == readPage && pageGeneration == generation && config == value && canPublish()
   if (!current()) return
   val read = archiveReads.begin()
   localHistoryVerified = false; remoteInspectionReady = false; pendingUploadSourceAvailable = false
   try {
    val data = withContext(Dispatchers.IO) { diagnosticWork(DiagnosticAction.INSPECT) {
     if (value == null) location.requireDirectory()
     else if (prepareSelectedDirectory) desktopPrepareSelectedDirectory(value, location)
     else location.selected(value)
     if (value == null) null else {
      val manager = location.open(value, workspace.resolve(".local/manager"))
      val target = runCatching { manager.targetInfo() }
      LoadedProfile(manager, manager.history(), manager.hasPending(), manager.currentVersions(), target.getOrNull(), target.exceptionOrNull()?.message, manager.deviceIdentity(), manager.baselineFiles(), manager.retryablePendingSourceAvailable())
     }
    } }
    val loadedHistory = withContext(Dispatchers.IO) { diagnosticWork(DiagnosticAction.HISTORY) { data?.session?.syncHistory() } }
    if (!current()) return
    locationIssue = null; session = data?.session; history = data?.history ?: emptyList(); pending = data?.pending ?: false
    graphHistory = loadedHistory
    localHistoryVerified = loadedHistory != null; pendingUploadSourceAvailable = data?.pendingSourceAvailable == true
    saveInfo = data?.saves; targetInfo = data?.target; targetError = data?.targetError
    // Preserve an explicit still-valid target on ordinary refresh. A different account/profile
    // starts from its sole verified tip; publication order never chooses a branch.
    reconcileHomeSelection(retainExplicit = sameProfile)
    if (!sameProfile) homeHistoryPage = 0
    homeProfile = value; remoteTips = emptySet(); conflictSelection = null; conflict = null
    device = data?.device; remoteInspectionReady = false; automatic = AutomaticUpload().apply { data?.baselineFiles?.let(::establishBaseline) }; remoteReadAt = 0L
    status = if (value == null) DesktopMessages.SAVE_CONTENTS_HAVE_NOT_BEEN_CHECKED.text() else DesktopMessages.CHECKING_FOR_SAVE_CHANGES.text()
   } catch (e: CancellationException) { throw e }
   catch (e: Exception) {
    if (!current()) return
    session = null; history = emptyList(); graphHistory = null; saveInfo = null; targetInfo = null; pending = false
    localHistoryVerified = false; remoteInspectionReady = false; pendingUploadSourceAvailable = false
    if (!sameProfile) { selected = null; selectedByUser = false; homeHistoryPage = 0; homeProfile = null }
    locationIssue = e.message; status = e.message ?: DesktopMessages.CANNOT_READ_THE_DEFAULT_SAVE_FOLDER.text()
   } finally { archiveReads.end(read) }
  }
  fun loadProfile(value: AppConfig?, prepareOfficialNamespace: Boolean = false,
                  prepareSelectedDirectory: Boolean = false) {
   if (busy || connectingAtStartup || appClosed) return
   val generation = pageGeneration
   val secret = password
   fun current() = !appClosed && page == Page.HOME && pageGeneration == generation && config == value
   busy = true
   scope.launch {
    try {
     refreshProfile(value, prepareSelectedDirectory && current())
     // Service configuration has already returned HOME. Prepare only this app's empty DAV folders before
     // HOME's first remote-history read; this is neither a DAV test nor a sync decision.
     val manager = session
     if (prepareOfficialNamespace && current() && manager != null && value != null &&
         value.connectionProvider == "official" && isDesktopOfficialRoot(value.endpoint)) {
      withContext(Dispatchers.IO) {
       syncAccess.store(value, manager.adapter.id, secret)
        .ensureNamespace { current() && session === manager }
      }
     }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) {
     if (current()) status = DesktopMessages.CLOUD_TEMPORARILY_UNAVAILABLE.text()
    } finally { busy = false }
   }
  }
  fun selectTargetGame() {
   if (busy || connectingAtStartup) return
   val saved = config ?: return
   busy = true
   scope.launch {
    var updated: AppConfig? = null
    try {
     val chosen = NativeGameChooser.choose(window, saved.targetGame)
     if (chosen != null) {
      val checked = withContext(Dispatchers.IO) {
       val candidate = ShatteredInstallationDiscovery().validateJar(Path.of(chosen))
       require(candidate.trusted) { candidate.reason }; requireNotNull(candidate.target)
      }
      val next = saved.copy(targetGame = checked.path, onboardingProgress = OnboardingProgress.PENDING)
      withContext(Dispatchers.IO) { configFile.save(next) }
      config = next; setupDraft = next; discoveredTarget = checked; onboardingActive = true; updated = next
     }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { status = DesktopMessages.TARGET_GAME_NOT_SAVED_1_S.text(e.message ?: DesktopMessages.CHECK_WRITE_PERMISSIONS_FOR_THE_CONFIGURATION_FILE.text()) }
    finally { busy = false }
    // Recheck the selected game without reading saves before the service step.
    updated?.let { navigate(Page.DISCOVERY) }
   }
  }
  fun launchGame() {
   if (busy || connectingAtStartup || appClosed || launchingGame) return
   val saved = config ?: return
   busy = true; launchingGame = true; launchNotice = DesktopMessages.STARTING_GAME.text()
   scope.launch {
    try {
     val result = withContext(Dispatchers.IO) { diagnosticWork(DiagnosticAction.LAUNCH, result = {
      when (it) { is GameLaunchResult.Started, is GameLaunchResult.AlreadyRunning -> DiagnosticResult.SUCCEEDED; else -> DiagnosticResult.FAILED }
     }) { gameLauncher.launch(saved.targetGame, saved.selectedSaveRoot) } }
     if (!appClosed) {
      launchNotice = result.message
      when (result) {
       is GameLaunchResult.Started -> { targetInfo = result.target; gameStatus = GameProcessStatus.Running(setOf(result.pid)) }
       is GameLaunchResult.AlreadyRunning -> gameStatus = result.status
       else -> Unit
      }
     }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { launchNotice = DesktopMessages.LAUNCH_FAILED_PLEASE_TRY_AGAIN.text() }
    finally { launchingGame = false; busy = false }
   }
  }
  fun exportDiagnosticLogs() {
   if (busy || connectingAtStartup || appClosed) return
   busy = true; diagnosticExportNotice = null
   scope.launch {
    try {
     when (val result = diagnosticExport.export(System.getProperty("user.home"))) {
      is DiagnosticExportResult.Saved -> diagnosticExportNotice = DesktopMessages.LOGS_EXPORTED_1_S.text(result.filename)
      is DiagnosticExportResult.Failed -> diagnosticExportNotice = result.message
      DiagnosticExportResult.Canceled, DiagnosticExportResult.Ignored -> Unit
     }
    } finally { busy = false }
   }
  }
  fun authenticationFailed(e: Exception) {
   serviceExpired = syncAccess.isExpired(config)
   if (serviceExpired) {
    status = pdsync.i18n.CoreSessionMessages.SERVICE_EXPIRED.text()
    connectionNotice = status
    return
   }
   if (e is WebDavException && e.statusCode in setOf(401, 403)) {
    renewalReads.reset()
    connected = false; password = ""; settingsMode = SettingsMode.UNLOCK; navigate(Page.CONNECTION)
    connectionNotice = if (e.statusCode == 401) DesktopMessages.AUTHENTICATION_FAILED_HTTP_401_CHECK_YOUR_USERNAME_AND_PASSWORD.text() else DesktopMessages.ACCESS_DENIED_HTTP_403_CHECK_ACCOUNT_PERMISSIONS_OR_RE_ENTER_YOUR_PASSWORD.text()
    status = DesktopMessages.CLOUD_AUTHENTICATION_HAS_EXPIRED_RE_ENTER_YOUR_PASSWORD_LOCAL_SNAPSHOTS_AND.text()
   }
  }
  fun cancelConfirmation(trace: OperationTrace) {
   if (busy || connectingAtStartup) return
   trace.record(OperationEvent.CONFIRMATION_CANCELED)
   status = if (trace.action == Action.PUSH && pending) DesktopMessages.UPLOAD_CANCELLED_THE_PENDING_SNAPSHOT_HAS_BEEN_PRESERVED.text() else DesktopMessages.OPERATION_CANCELLED.text()
   confirm = null; confirmationNotice = null; navigate(Page.HOME)
  }
  fun execute(trace: OperationTrace, isAutomatic: Boolean = false) {
   val action = trace.action
   trace.record(OperationEvent.CONFIRMATION_SUBMITTED)
   val rejected = when {
    appClosed -> OperationGuard.CLOSED
    connectingAtStartup -> OperationGuard.STARTUP
    busy -> OperationGuard.BUSY
    session == null -> OperationGuard.NO_SESSION
    else -> null
   }
   if (rejected != null) {
    trace.record(OperationEvent.GUARD_REJECTED, guard = rejected)
    confirmationNotice = when (rejected) {
     OperationGuard.CLOSED -> DesktopMessages.OPERATION_NOT_STARTED_THE_WINDOW_IS_CLOSED_PLEASE_REOPEN_IT.text()
     OperationGuard.STARTUP -> DesktopMessages.OPERATION_NOT_STARTED_READING_SAVES_AND_CONNECTION_STATUS_PLEASE_TRY_AGAIN.text()
     OperationGuard.BUSY -> DesktopMessages.OPERATION_NOT_STARTED_WAIT_FOR_THE_CURRENT_OPERATION_TO_FINISH.text()
     OperationGuard.NO_SESSION, OperationGuard.NO_SOURCE -> DesktopMessages.OPERATION_NOT_STARTED_SAVES_ARE_NOT_READY_RETURN_TO_HOME_AND_CHECK_SAVES_AG.text()
    }
    return
   }
   // Automatic work owns its fresh compare-and-skip policy; this gate protects manual entry.
   if (!isAutomatic && (action == Action.PUSH && !connected || !desktopHomeTransferAllowed(action, homeState()))) {
    trace.record(OperationEvent.GUARD_REJECTED, guard = OperationGuard.NO_SOURCE)
    return
   }
   val manager = requireNotNull(session)
   val operationConfig = config
   val chosen = selected; val branchChoice = conflictSelection; val divergence = conflict; val secret = password
   val allowUniqueReplacement=confirm===trace
   val expectedBefore=if(allowUniqueReplacement)confirmationFiles else null
   busy = true; homeOperation = desktopHomeOperation(action); confirm = null; confirmationNotice = null; navigate(Page.HOME)
   localHistoryVerified = false; remoteInspectionReady = false; pendingUploadSourceAvailable = false
   if (action == Action.PUSH) { if (!isAutomatic) automatic.retryAllowed(); automatic.started() }
   status = when (action) {
    Action.RESTORE, Action.RESOLVE, Action.REMOTE_TIP -> DesktopMessages.VERIFYING_AND_BACKING_UP_THE_CURRENT_PROFILE_BEFORE_RESTORING.text()
    Action.MERGE -> DesktopMessages.CHECKING_RUN_IDENTITIES_BADGES_HISTORY_AND_THE_CATALOG.text()
    Action.PUSH -> DesktopMessages.PRESERVING_THE_SNAPSHOT_AND_VERIFYING_THE_CLOUD.text()
    else -> DesktopMessages.PROCESSING.text()
   }
   var terminalFailure: OperationFailure? = null
   trace.record(OperationEvent.OPERATION_QUEUED)
   scope.launch {
    trace.record(OperationEvent.OPERATION_STARTED)
    try {
     val result = withContext(Dispatchers.IO) { trace.withDiagnostics {
      trace.record(OperationEvent.WORK_STARTED)
      val target = manager.targetInfo()
      val stopped = {
       val fresh = gameLauncher.status(target)
       if (fresh !is GameProcessStatus.Stopped) throw SaveNotStableException(processMessage(fresh))
       fresh == GameProcessStatus.Stopped
      }
      if (action != Action.ARCHIVE) {
       val state = gameLauncher.status(target)
       if (state !is GameProcessStatus.Stopped) throw SaveNotStableException(processMessage(state))
       SaveStabilityGuard(manager.root, manager.adapter, stopped).awaitStable()
      }
      val remoteStore by lazy { syncAccess.store(manager.config, manager.adapter.id, secret) }
      fun store() = remoteStore
      // Explicit manual upload may retry failed initial app-folder preparation.
      // Background history reads and automatic upload never provision folders.
      if (action == Action.PUSH && !isAutomatic && manager.config.connectionProvider == "official" &&
          isDesktopOfficialRoot(manager.config.endpoint)) {
       store().ensureNamespace { !appClosed && session === manager && config == operationConfig }
      }
      fun pushed(result: AppendPushResult): OperationResult = when (result) {
       is AppendPushResult.Success -> OperationResult(DesktopMessages.THE_APPENDED_COMMIT_WAS_READ_BACK_AND_VERIFIED.text() + (if (result.localDirty) DesktopMessages.LOCAL_CHANGES_REMAIN_UNUPLOADED_THE_NEXT_SYNC_WILL_CREATE_A_SEPARATE_COMMIT.text() else DesktopMessages.THE_SELECTED_PROGRESS_IS_NOW_THIS_DEVICE_S_SYNC_BASELINE.text()) + (if (result.remoteAhead) DesktopMessages.ADDITIONAL_LATER_BRANCHES_HAVE_BEEN_OBSERVED_AND_CAN_BE_VIEWED.text() else ""))
       is AppendPushResult.Diverged -> OperationResult(DesktopMessages.FOUND_1_S_PROGRESS_BRANCHES_ALL_SNAPSHOTS_HAVE_BEEN_PRESERVED_PLEASE_CHOOSE.text(result.conflict.branches.size), result.conflict)
      }
      val completed = when (action) {
       Action.CAPTURE -> manager.createSnapshot(stopped).let { OperationResult(DesktopMessages.LOCAL_SNAPSHOT_SAVED_YOU_CAN_RESTORE_IT_FROM_HISTORY_AT_ANY_TIME.text()) }
       Action.PUSH -> if (!isAutomatic) pushed(manager.push(store(), stopped)) else when (val automaticResult = manager.automaticPush(store(), stopped)) {
        is AutomaticPushResult.Published -> pushed(automaticResult.result)
        is AutomaticPushResult.Skipped -> OperationResult(
         if (automaticResult.comparison.kind in setOf(OnboardingKind.BOTH_EMPTY, OnboardingKind.IDENTICAL)) DesktopMessages.THERE_ARE_NO_CHANGES_TO_UPLOAD_AUTOMATICALLY_THIS_TIME.text()
         else DesktopMessages.AUTOMATIC_UPLOAD_SKIPPED_1_S.text(automaticResult.comparison.blocker ?: DesktopMessages.CHECK_LOCAL_AND_CLOUD_PROGRESS_THEN_CHOOSE_EXPLICITLY.text()),
         automaticSkipped = true)
       }
       Action.REMOTE_TIP -> manager.restoreRemote(requireNotNull(chosen), store(), stopped).let { OperationResult(DesktopMessages.CLOUD_SAVE_RESTORED_A_PRE_RESTORE_BACKUP_WAS_ADDED_TO_HISTORY.text(), appliedFiles = it.restored.files) }
       Action.RESTORE -> manager.restoreHistory(requireNotNull(chosen), stopped).let { OperationResult(DesktopMessages.RESTORE_COMPLETE_A_BACKUP_WAS_ADDED_TO_HISTORY_THE_RESTORE_COMMIT_IS_FROZEN.text(), appliedFiles = it.restored.files) }
       Action.CLOUD_HISTORY -> manager.restoreCloudHistory(requireNotNull(chosen),store(),allowUniqueReplacement,expectedBefore,stopped).let {OperationResult(DesktopMessages.SELECTED_CLOUD_HISTORY_RESTORED_ORIGINAL_HISTORY_PRESERVED_THE_RESULT_IS_AW.text(),appliedFiles=it.restored.files)}
       Action.RESOLVE -> pushed(manager.resolve(requireNotNull(divergence), requireNotNull(branchChoice), store(), stopped)).copy(appliedFiles = divergence.branches.single { it.snapshot.manifest.id == branchChoice }.snapshot.manifest.files)
       Action.MERGE -> pushed(manager.merge(requireNotNull(divergence),store(),stopped)).let { result ->
        if(result.conflict==null) result.copy(appliedFiles=manager.observedFiles()) else result
       }
       Action.MERGE_CURRENT -> pushed(manager.mergeCurrent(store(),stopped)).let {result->if(result.conflict==null)result.copy(appliedFiles=manager.observedFiles()) else result}
       Action.ARCHIVE -> { manager.archivePending(); OperationResult(DesktopMessages.PENDING_SNAPSHOT_ARCHIVED_THE_NEXT_SYNC_WILL_CAPTURE_THE_CURRENT_SAVES_AGAI.text()) }
      }
      trace.outcome(when { completed.automaticSkipped -> DiagnosticResult.SKIPPED; completed.conflict != null -> DiagnosticResult.CONFLICT; else -> DiagnosticResult.SUCCEEDED })
      trace.record(OperationEvent.WORK_SUCCEEDED)
      completed
     } }
     status = result.message; conflict = result.conflict
     conflictSelection=if(action==Action.PUSH)result.conflict?.let {value->value.branches.firstOrNull {it.snapshot.manifest.files==value.observedLocalFiles}?.snapshot?.manifest?.id} else null
     remoteReadAt = 0L
     if(action in setOf(Action.CLOUD_HISTORY,Action.REMOTE_TIP,Action.RESOLVE,Action.MERGE,Action.MERGE_CURRENT)){selectedByUser=false;selected=null}
     val now = monotonicMillis()
     if (result.automaticSkipped) {
      automatic.skipped(); automaticStatus = result.message
     } else if (action == Action.PUSH) {
      val baseline = withContext(Dispatchers.IO) { manager.baselineFiles() }
      if (baseline != null) automatic.succeeded(baseline) else automatic.failed(now, false)
     } else if (result.appliedFiles != null) automatic.applied(result.appliedFiles, now)
     else if (action == Action.ARCHIVE) {
      val files = withContext(Dispatchers.IO) { runCatching { manager.observedFiles() }.getOrNull() }
      if (files != null) automatic.applied(files, now) else automatic.retryAllowed()
     }
     if (result.conflict != null) automaticStatus = DesktopMessages.MULTIPLE_SAVE_BRANCHES_EXIST_AUTOMATIC_UPLOAD_PAUSED_CLICK_RESTORE_TO_CHOOS.text()
     if (result.conflict != null && !isAutomatic) navigate(Page.CONFLICT)
    } catch (e: CancellationException) {
     terminalFailure = OperationFailure.CANCELED
     if (action == Action.PUSH) automatic.failed(monotonicMillis(), false)
     trace.record(OperationEvent.OPERATION_CANCELED, failure = terminalFailure)
     status = DesktopMessages.OPERATION_INTERRUPTED_SAVED_HISTORY_AND_BACKUPS_ARE_PRESERVED_CHECK_PENDING.text()
     throw e
    } catch (e: Exception) {
     terminalFailure = operationFailure(e)
     if (action == Action.PUSH) automatic.failed(monotonicMillis(), terminalFailure == OperationFailure.NETWORK || e is SaveNotStableException)
     trace.record(OperationEvent.WORK_FAILED, failure = terminalFailure)
     status = if(action==Action.MERGE && withContext(Dispatchers.IO) { manager.retryablePending() })
      DesktopMessages.LOCAL_MERGE_COMPLETE_BUT_CLOUD_UPLOAD_IS_INCOMPLETE_CLICK_UPLOAD_TO_RETRY_T.text()
     else DesktopMessages.OPERATION_INCOMPLETE_1_S_SAVED_HISTORY_AND_BACKUPS_ARE_PRESERVED.text(e.message)
     authenticationFailed(e)
    }
    finally {
     try {
      trace.record(OperationEvent.HISTORY_REFRESH_STARTED)
      val generation = pageGeneration
      val (data, loadedGraph, pendingSource) = archiveReads.reading {
       withContext(Dispatchers.IO) {
        Triple(trace.withDiagnostics { Triple(manager.history(), manager.hasPending(), runCatching { manager.currentVersions() }.getOrNull()) },
         manager.syncHistory(), manager.retryablePendingSourceAvailable())
       }
      }
      if (!appClosed && page == Page.HOME && pageGeneration == generation && config == operationConfig && session === manager) {
       history = data.first; pending = data.second; saveInfo = data.third; graphHistory = loadedGraph
       localHistoryVerified = true; pendingUploadSourceAvailable = pendingSource
       reconcileHomeSelection()
      }
      trace.record(OperationEvent.HISTORY_REFRESH_SUCCEEDED)
     } catch (e: CancellationException) {
      if (terminalFailure == null) terminalFailure = OperationFailure.CANCELED
      trace.record(OperationEvent.HISTORY_REFRESH_FAILED, failure = OperationFailure.CANCELED)
     } catch (e: Exception) {
      val refreshFailure = operationFailure(e)
      if (terminalFailure == null) terminalFailure = refreshFailure
      trace.record(OperationEvent.HISTORY_REFRESH_FAILED, failure = refreshFailure)
      status += DesktopMessages.HISTORY_REFRESH_FAILED_1_S.text(e.message)
     }
     homeOperation = SharedHomeOperation.NONE; busy = false
    }
   }.invokeOnCompletion { cause ->
    trace.record(OperationEvent.OPERATION_FINISHED, failure = terminalFailure ?: cause?.let(::operationFailure))
   }
  }
  fun editDirectory(inline: Boolean = false, change: ((AppendProfileSession, () -> Boolean) -> DirectorySnapshotMetadata)? = null) {
   if(busy || connectingAtStartup || appClosed) return
   val manager=session ?: return
   busy=true;directoryNotice=null
   if (change == null) directoryInline = inline
   scope.launch {
    try {
     val metadata=withContext(Dispatchers.IO) {
      val target=manager.targetInfo()
      val stopped={ gameLauncher.status(target) is GameProcessStatus.Stopped }
      if(change==null)manager.inspectDirectory(stopped) else change(manager,stopped)
     }
     directoryEditor=metadata;directoryName=metadata.displayName
     graphHistory=withContext(Dispatchers.IO){manager.syncHistory()}
     directoryNotice=if(change==null)null else DesktopMessages.SAVED_CLICK_UPLOAD_TO_SYNC_THE_NAME_AND_CONFIRMED_IDENTITIES.text()
     if (change != null && directoryInline) directoryEditor = null
    } catch(e:Exception) { directoryNotice=e.message;status=DesktopMessages.CANNOT_UPDATE_SAVE_INFORMATION_1_S.text(e.message) }
    finally { busy=false }
   }
  }
  fun readRemote(renewal: DesktopRenewalHistoryReads.Request? = null, refreshLocal: Boolean = false) {
   if (busy || connectingAtStartup || appClosed || !connected || serviceExpired || page != Page.HOME) return
   if (refreshLocal && !homeState().refreshHistoryEnabled) return
   val manualRefresh = if (refreshLocal) renewalReads.manualRequest(config) ?: return else null
   // An explicit read also consumes a queued recovery for this same account, exactly once.
   val recovery = renewal ?: if (refreshLocal) renewalReads.takeForHome(config, home = true, ready = true) else null
   val reopenLocal = recovery != null || refreshLocal
   val previousManager = session
   if (previousManager == null && !reopenLocal) return
   val secret = password
   val generation = pageGeneration; val readConfig = config
   val selectionGeneration = homeSelectionGeneration
   var readManager = previousManager
   fun currentPage() = !appClosed && page == Page.HOME && pageGeneration == generation &&
    config == readConfig && password == secret && homeSelectionGeneration == selectionGeneration &&
    (recovery == null || renewalReads.isCurrent(recovery, config)) &&
    (manualRefresh == null || renewalReads.isManualCurrent(manualRefresh, config))
   busy = true; remoteReadAt = monotonicMillis(); remoteInspectionReady = false; localHistoryVerified = false
   pendingUploadSourceAvailable = false; status = DesktopMessages.READING_AND_VERIFYING_CLOUD_SNAPSHOTS.text()
   if (reopenLocal) automatic.skipped()
   scope.launch {
    val read = archiveReads.begin()
    try {
     if (!currentPage()) return@launch
     if (reopenLocal) {
      // Reopen local history even if an earlier inspection failed or its session was unavailable.
      // Manual refresh and renewal recovery never prepare directories or transfer saves.
      refreshProfile(readConfig, canPublish = ::currentPage)
      if (!currentPage()) return@launch
      automatic.skipped()
      if (!localHistoryVerified) return@launch
      // refreshProfile resets this timer; a failed explicit/recovery read must not immediately replay.
      remoteReadAt = monotonicMillis()
     }
     val manager = if (reopenLocal) session else previousManager
     if (manager == null) return@launch
     readManager = manager
     fun current() = currentPage() && config == readConfig && session === manager
     if (!current() || serviceExpired) return@launch
     val entries = withContext(Dispatchers.IO) { diagnosticWork(DiagnosticAction.HISTORY) { manager.readRemote(syncAccess.store(manager.config, manager.adapter.id, secret)) } }
     val loadedHistory = withContext(Dispatchers.IO) { manager.history() }
     val loadedGraph = withContext(Dispatchers.IO) { manager.syncHistory() }
     val pendingSource = withContext(Dispatchers.IO) { manager.retryablePendingSourceAvailable() }
     if (!current()) return@launch
     remoteTips = entries.map { it.entry.manifest.id }.toSet(); remoteInspectionReady = true
     remoteObserved = entries.mapNotNull { remote -> manager.observedAt(remote.commit.id)?.let { remote.entry.manifest.id to it } }.toMap()
     history = loadedHistory; graphHistory = loadedGraph
     localHistoryVerified = true; pendingUploadSourceAvailable = pendingSource
     reconcileHomeSelection()
     status = if (entries.isEmpty()) DesktopMessages.NO_CLOUD_RECORDS_FOUND_THIS_TIME.text() else ""
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { if (currentPage() && session === readManager) { status = DesktopMessages.FAILED_TO_READ_CLOUD_1_S.text(e.message); authenticationFailed(e) } }
    finally { archiveReads.end(read); busy = false }
   }
  }
  fun hasRemoteBranches() = remoteTips.size > 1 || (graphHistory?.graph?.tips?.size ?: 0) > 1
  fun openRestore() {
   if (!homeState().restoreEnabled) return
   if (conflict != null && (selected==null || conflict!!.branches.any {it.snapshot.manifest.id==selected})) { navigate(Page.CONFLICT); return }
   if (hasRemoteBranches() && (selected==null || selected in remoteTips)) {
    val manager = session ?: return
    busy = true
    scope.launch {
     try {
      val found = withContext(Dispatchers.IO) {
       val target = manager.targetInfo()
       val stopped = { gameLauncher.status(target) is GameProcessStatus.Stopped }
       require(stopped()) { processMessage(gameLauncher.status(target)) }
       SaveStabilityGuard(manager.root, manager.adapter, stopped).awaitStable()
       manager.inspectConflict(syncAccess.store(manager.config, manager.adapter.id, password), stopped)
      }
      conflict = found; conflictSelection = null
      if (found != null) navigate(Page.CONFLICT) else status = DesktopMessages.CLOUD_BRANCHES_CHANGED_WAIT_FOR_REFRESH_THEN_SELECT_AGAIN.text()
     } catch (e: Exception) { status = DesktopMessages.CANNOT_SELECT_RESTORE_YET_1_S.text(e.message); authenticationFailed(e) }
     finally { busy = false }
    }
   } else if(graphHistory?.graph?.commits?.values?.any {it.snapshot.id==selected}==true) {
    if(graphHistory?.currentProtected==true) {
     if(selected in remoteTips && (graphHistory?.clean==true || graphHistory?.currentFiles?.isEmpty()==true))execute(operationEvents.begin(Action.REMOTE_TIP))
     else execute(operationEvents.begin(Action.CLOUD_HISTORY))
    } else requestConfirmation(Action.CLOUD_HISTORY)
   }
   else requestConfirmation(Action.RESTORE)
  }
  fun setAutomaticPaused(paused: Boolean) {
   if (busy || connectingAtStartup) return
   val value = config ?: return
   busy = true
   scope.launch {
    try {
     val next = value.copy(autoUploadPaused = paused)
     withContext(Dispatchers.IO) { configFile.save(next) }
     config = next; automatic.retryAllowed()
    } catch (e: Exception) { status = DesktopMessages.SETTINGS_NOT_SAVED_1_S.text(e.message) }
    finally { busy = false }
   }
  }
  suspend fun scanDiscovery() {
   val found = withContext(Dispatchers.IO) { ShatteredInstallationDiscovery().discover(setupDraft.targetGame) }
   if (appClosed) return
   discovery = found
   discoveredTarget = found.suggestedTarget
   discoveryNotice = null
  }
  fun rescanDiscovery() {
   if (busy || connectingAtStartup || appClosed) return
   busy = true
   scope.launch {
    try { scanDiscovery() }
    catch (e: Exception) { discoveryNotice = DesktopMessages.SCAN_INCOMPLETE_1_S.text(e.message) }
    finally { busy = false }
   }
  }
  fun chooseSetupGame() {
   if (busy || connectingAtStartup || appClosed) return
   busy = true
   scope.launch {
    try {
     val chosen = NativeGameChooser.choose(window, discoveredTarget?.path ?: setupDraft.targetGame)
     if (chosen != null) {
      val verified = withContext(Dispatchers.IO) {
       val candidate = ShatteredInstallationDiscovery().validateJar(Path.of(chosen))
       require(candidate.trusted) { candidate.reason }; requireNotNull(candidate.target)
      }
      require(verified.trusted) { DesktopMessages.SELECTED_GAME_VERSION_NOT_RECOGNIZED_1_S.text(verified.reason) }
      withContext(Dispatchers.IO) { setupSelection.save(verified.path) }
      discoveredTarget = verified; discoveryNotice = null
     }
    } catch (e: Exception) { discoveryNotice = DesktopMessages.GAME_SELECTION_UNCHANGED_1_S.text(e.message) }
    finally { busy = false }
   }
  }
  fun continueDiscovery() {
   if (busy || connectingAtStartup || appClosed) return
   val target = discoveredTarget ?: return
   busy = true
   scope.launch {
    var proceed = false
    try {
     withContext(Dispatchers.IO) {
      require(ShatteredInstallationDiscovery().validateJar(Path.of(target.path)).target == target && target.trusted) { DesktopMessages.GAME_FILE_CHANGED_PLEASE_SELECT_IT_AGAIN.text() }
     }
     val next = desktopSetupDraftForGame(setupDraft, target.path, pathAdapter.state.path)
     withContext(Dispatchers.IO) { setupSelection.save(target.path) }
     if (config != null) { withContext(Dispatchers.IO) { configFile.save(next) }; config = next }
     setupDraft = next; onboardingActive = true; proceed = true
    } catch (e: Exception) { discoveryNotice = DesktopMessages.CANNOT_CONTINUE_YET_1_S.text(e.message) }
    finally { busy = false }
    if (proceed) {
     settingsMode = SettingsMode.FULL
     navigate(Page.CONNECTION)
    }
   }
  }
  fun continuePath() {
   if(busy || connectingAtStartup || appClosed || page != Page.PATH)return
   val selectedPath = pathAdapter.state.path
   val selectedRevision = pathAdapter.revision
   val generation = pageGeneration
   fun current() = !appClosed && page == Page.PATH && pageGeneration == generation &&
    pathAdapter.revision == selectedRevision && pathAdapter.state.path == selectedPath
   if (selectedPath.isBlank() || !pathAdapter.state.canContinue) return
   busy=true
   scope.launch {
    var proceed=false
    try {
     val root=withContext(Dispatchers.IO) { location.requireSelected(selectedPath,true) }
     val next=setupDraft.copy(selectedSaveRoot=root.toString(),onboardingProgress=OnboardingProgress.PENDING)
     withContext(Dispatchers.IO) {
      // Validate the selected directory before persisting it or advancing.
      inspectSelectedSaveDirectory(root)
      if(config!=null)configFile.save(next)
     }
     if (current()) {
      setupDraft=next;if(config!=null)config=next
      onboardingActive=true;pathNavigation.beginSetup();proceed=true
     }
    } catch(e:CancellationException) { throw e }
    catch(e:Exception) { if (current()) pathAdapter.showError(e.message) }
    finally { busy=false }
    if(proceed && current()){settingsMode=SettingsMode.FULL;navigate(Page.CONNECTION)}
   }
  }
  LaunchedEffect(Unit) {
   // No observation/upload engine is released until target and onboarding progress are known.
   try {
    val saved = config
    if (saved == null) {
     onboardingActive = true; settingsMode = SettingsMode.FULL
     scanDiscovery(); page = Page.DISCOVERY
     initialResult.exceptionOrNull()?.let { discoveryNotice = DesktopMessages.OLD_SETTINGS_COULD_NOT_BE_READ_THE_ORIGINAL_FILE_IS_PRESERVED_CHECK_THE_GAM.text() }
     if (initialResult.isSuccess && System.getProperty("pdsync.workspace") == null && java.nio.file.Files.exists(Path.of(".local/config.json"))) {
      discoveryNotice = DesktopMessages.SETTINGS_FOUND_IN_THE_OLD_LAUNCH_FOLDER_THE_ORIGINAL_FILE_IS_PRESERVED_THIS.text()
     }
    } else if (desktopCompletedSetup(saved)) {
     // A missing/unreadable save directory is a HOME issue, never a reason to replay setup.
     // Keep explicit completion without deriving or manufacturing sync ancestry.
     onboardingActive = false; navigate(Page.HOME)
     refreshProfile(saved)
     reconnectSaved(saved)
    } else {
     // Resume an older unfinished PATH/connection flow using only the selected game.
     // Save-directory inspection must not block game selection or service configuration.
     val target = withContext(Dispatchers.IO) {
      runCatching { ShatteredInstallationDiscovery().validateJar(Path.of(saved.targetGame)).target }
       .getOrNull()?.takeIf { it.trusted }
     }
     if (target == null) {
      onboardingActive = true; setupDraft = saved.copy(onboardingProgress = OnboardingProgress.PENDING)
      scanDiscovery(); navigate(Page.DISCOVERY)
     } else {
      discoveredTarget = target; targetInfo = target
      var resumed = saved
      if (saved.onboardingProgress == OnboardingProgress.LEGACY) {
       // Preserve legacy migration only with a genuine existing baseline, inspected on HOME.
       navigate(Page.HOME)
       refreshProfile(saved)
       val hasBaseline = withContext(Dispatchers.IO) { session?.baselineFiles() != null }
       val progress = OnboardingGate.resumedProgress(saved.onboardingProgress, true, hasBaseline)
       resumed = saved.copy(onboardingProgress = progress)
       if (resumed != saved) withContext(Dispatchers.IO) { configFile.save(resumed) }
      }
      config = resumed; setupDraft = resumed; onboardingActive = !desktopCompletedSetup(resumed)
      reconnectSaved(resumed)
      if (onboardingActive) navigate(Page.CONNECTION) else navigate(Page.HOME)
     }
    }
   } catch (e: CancellationException) { throw e }
   catch (e: Exception) {
    if (desktopCompletedSetup(config)) {
     onboardingActive = false; navigate(Page.HOME)
     status = DesktopMessages.STARTUP_CHECK_INCOMPLETE_1_S_CHECK_AGAIN_IN_SETTINGS.text(e.message)
    } else {
     onboardingActive = true; navigate(Page.DISCOVERY)
     discoveryNotice = DesktopMessages.STARTUP_CHECK_INCOMPLETE_1_S_SCAN_AGAIN_NO_SAVES_WERE_UPLOADED_OR_OVERWRITT.text(e.message)
    }
   } finally { connectingAtStartup = false }
  }
  LaunchedEffect(page, pathAdapter.revision, busy, connectingAtStartup) {
   if (page == Page.PATH && !busy && !connectingAtStartup && !appClosed) {
    val generation = pageGeneration
    val preview = pathAdapter.beginPreview() ?: return@LaunchedEffect
    delay(350)
    val result = try {
     Result.success(withContext(Dispatchers.IO) { diagnosticWork(DiagnosticAction.INSPECT) { pathAdapter.inspect(preview) } })
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { Result.failure(e) }
    val continueRequested = pathAdapter.completePreview(preview, result)
    if (continueRequested && !appClosed && page == Page.PATH && pageGeneration == generation &&
     pathAdapter.state.path == preview.path && pathAdapter.revision == preview.revision &&
     !busy && !connectingAtStartup) continuePath()
   }
  }
  LaunchedEffect(page, pageGeneration, config, busy, connectingAtStartup, serviceExpired, renewalReads.pending) {
   val request = renewalReads.takeForHome(config, page == Page.HOME,
    !busy && !connectingAtStartup && !appClosed && connected && !serviceExpired &&
     !onboardingActive && desktopCompletedSetup(config)) ?: return@LaunchedEffect
   readRemote(request)
  }
  // Keep launch availability fresh even when disconnected or automatic upload is paused.
  LaunchedEffect(targetInfo) {
   while (isActive && !appClosed) {
    if (!busy && !connectingAtStartup) {
     val target = targetInfo
     val observed = withContext(Dispatchers.IO) { gameLauncher.status(target) }
     if (!busy && !connectingAtStartup && targetInfo == target && !appClosed) {
      if (gameStatus is GameProcessStatus.Running && observed is GameProcessStatus.Stopped) launchNotice = null
      gameStatus = observed
     }
    }
    delay(1000)
   }
  }
  LaunchedEffect(session, connected) {
   while (isActive && !appClosed) {
    delay(1000)
    serviceExpired = syncAccess.isExpired(config)
    if (serviceExpired) {
     automaticStatus = pdsync.i18n.CoreSessionMessages.SERVICE_EXPIRED.text()
     val saved = config
     val now = monotonicMillis()
     if (!busy && !connectingAtStartup && page == Page.HOME && saved != null &&
         (entitlementRetryAt == 0L || now - entitlementRetryAt >= 60000)) {
      entitlementRetryAt = now
      val secret = password; val generation = pageGeneration
      fun current() = !appClosed && page == Page.HOME && pageGeneration == generation &&
       config == saved && password == secret
      renewalReads.refreshStarted(saved, homeAvailable = false)
      try {
       withContext(Dispatchers.IO) { syncAccess.beginOperation(saved, secret) }
       if (current()) {
        serviceExpired = false; connected = true; connectionNotice = null
        renewalReads.accountAccessChanged(saved, true)
       }
      } catch (failure: CancellationException) { throw failure }
      catch (_: Exception) {
       if (current()) renewalReads.accountAccessChanged(saved, false)
       // A failed refresh never authorizes DAV or proves expiry.
      }
     }
     continue
    }
    if (busy || connectingAtStartup || page != Page.HOME || onboardingActive || config?.onboardingProgress != OnboardingProgress.COMPLETE) continue
    if (renewalReads.blocksHomeObservation) continue
    val manager = session ?: continue
    if (connected && !remoteInspectionReady) {
     automaticStatus = DesktopMessages.CLOUD_CHECK_INCOMPLETE_AUTOMATIC_UPLOAD_PAUSED.text()
     if (remoteReadAt == 0L || monotonicMillis() - remoteReadAt >= 60000) readRemote()
     continue
    }
    val observedGeneration = pageGeneration; val observedConfig = config
    try {
     val target = targetInfo
     val observation = withContext(Dispatchers.IO) {
      val state = gameLauncher.status(target)
      state to manager.observedFiles()
     }
     if (busy || connectingAtStartup || page != Page.HOME || pageGeneration != observedGeneration || config != observedConfig || onboardingActive || config?.onboardingProgress != OnboardingProgress.COMPLETE || !remoteInspectionReady || session !== manager) continue
     val state = observation.first; val files = observation.second; val now = monotonicMillis()
     if (graphHistory?.currentFiles != files) {
      val generation = pageGeneration; val readConfig = config
      val loadedGraph = archiveReads.reading { withContext(Dispatchers.IO) { manager.syncHistory() } }
      if (busy || appClosed || page != Page.HOME || pageGeneration != generation || config != readConfig || session !== manager) continue
      graphHistory = loadedGraph
     }
     localHistoryVerified = true
     gameStatus = state
     automatic.observe(files, state is GameProcessStatus.Running, state is GameProcessStatus.Stopped, now)
     val retryable = withContext(Dispatchers.IO) { manager.retryablePendingSourceAvailable() }
     if (busy || connectingAtStartup || page != Page.HOME || pageGeneration != observedGeneration || config != observedConfig || onboardingActive || config?.onboardingProgress != OnboardingProgress.COMPLETE || !remoteInspectionReady || session !== manager || appClosed) continue
     pendingUploadSourceAvailable = retryable
     automaticStatus = when {
      config?.autoUploadPaused == true -> DesktopMessages.AUTOMATIC_UPLOAD_PAUSED_YOU_CAN_STILL_UPLOAD_MANUALLY.text()
      !connected -> DesktopMessages.CONNECT_TO_THE_CLOUD_FIRST.text()
      state !is GameProcessStatus.Stopped -> processMessage(state)
      pending && !retryable -> DesktopMessages.AN_INTERRUPTED_OPERATION_NEEDS_CHECKING_HANDLE_IT_IN_SETTINGS.text()
      conflict != null || hasRemoteBranches() -> DesktopMessages.THERE_ARE_MULTIPLE_CLOUD_BRANCHES_CLICK_RESTORE_TO_CHOOSE_ONE.text()
      automatic.needsAttention -> DesktopMessages.AUTOMATIC_UPLOAD_PAUSED_RESOLVE_THE_ISSUE_BELOW_AND_TRY_AGAIN.text()
      automatic.waitingForRetry -> DesktopMessages.LAST_UPLOAD_INCOMPLETE_RETRY_LATER.text()
      retryable -> DesktopMessages.WAITING_FOR_SAVES_TO_STABILIZE_BEFORE_RETRYING_UPLOAD.text()
      else -> DesktopMessages.AUTOMATIC_UPLOAD_ENABLED.text()
     }
     if (automatic.shouldUpload(now, config?.autoUploadPaused != true && OnboardingGate.allowsAutomatic(config?.onboardingProgress, onboardingActive, remoteInspectionReady), connected, state is GameProcessStatus.Stopped, retryable, conflict != null || hasRemoteBranches(), busy)) {
      val baseline = withContext(Dispatchers.IO) { manager.baselineFiles() }
      if (busy || connectingAtStartup || page != Page.HOME || pageGeneration != observedGeneration || config != observedConfig || onboardingActive || config?.onboardingProgress != OnboardingProgress.COMPLETE || !remoteInspectionReady || session !== manager || !connected || appClosed) continue
      if (!retryable && files == baseline) automatic.succeeded(files)
      else execute(operationEvents.begin(Action.PUSH, automatic = true), isAutomatic = true)
     } else if (connected && !busy && (remoteReadAt == 0L || now - remoteReadAt >= 60000)) readRemote()
     observationDiagnostics.recovered()
    } catch (e: CancellationException) { throw e }
    catch (failure: Exception) {
     if (!appClosed && page == Page.HOME && pageGeneration == observedGeneration && config == observedConfig && session === manager) {
      localHistoryVerified = false; pendingUploadSourceAvailable = false
      observationDiagnostics.failed(failure)
      automaticStatus = DesktopMessages.SAVES_ARE_CHANGING_OR_TEMPORARILY_UNREADABLE_AUTOMATIC_UPLOAD_IS_WAITING.text()
     }
    }
   }
  }
  LaunchedEffect(session, selected, busy, graphHistory, targetInfo, config, pageGeneration) {
   restoreIssue = DesktopMessages.VERIFYING_THE_ACTUAL_VERSION.text()
   val manager = session; val id = selected
   val owner = config; val generation = pageGeneration; val target = targetInfo; val observedHistory = graphHistory
   if (manager != null && id != null && !busy) {
    val issue = withContext(Dispatchers.IO) {
     val cloud=observedHistory?.graph?.commits?.values?.firstOrNull {it.snapshot.id==id}
     runCatching { if(cloud==null)manager.checkRestore(id) else cloud.snapshot.saveVersions?.let {ShatteredVersions.requireCompatible(it,requireNotNull(target){DesktopMessages.SELECT_A_GAME.text()})} }.exceptionOrNull()?.message
    }
    if (!appClosed && !busy && session === manager && selected == id && config == owner &&
     pageGeneration == generation && targetInfo == target && graphHistory === observedHistory) restoreIssue = issue
   }
  }
  LaunchedEffect(session, conflict, conflictSelection, busy, config, pageGeneration, targetInfo) {
   conflictRestoreIssue = DesktopMessages.VERIFYING_THE_ACTUAL_VERSION.text()
   val manager = session; val value = conflict; val id = conflictSelection
   val owner = conflictOwner(); val target = targetInfo
   if (manager != null && value != null && id != null && !busy && !appClosed && page == Page.CONFLICT) {
    val issue = withContext(Dispatchers.IO) { desktopConflictCompatibilityIssue { manager.checkResolution(id) } }
    if (!appClosed && !busy && owner.matches(conflictOwner(), page == Page.CONFLICT) &&
     conflictSelection == id && targetInfo == target) conflictRestoreIssue = issue
   }
  }
  MaterialTheme(colors = lightColors(primary = green, secondary = Color(0xFF8A6B42)), shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(18.dp))) {
   directoryEditor?.takeUnless { directoryInline && page == Page.HOME }?.let { metadata ->
    SharedNamingDialog(SharedNamingState(System.identityHashCode(metadata).toLong(), directoryName,
     busy = busy, notice = directoryNotice.orEmpty()),
     onNameChange = { directoryName=it },
     onSave = { name -> editDirectory { manager,stopped -> manager.renameCurrent(name,stopped) } },
     onDismiss = { directoryEditor=null }) {
      // Identity decisions remain the existing host maintenance actions, with their original proof.
      if (metadata.runs.values.any { it.runUUID == null }) {
       Text(DesktopMessages.IF_YOU_ARE_UNSURE_WHETHER_THIS_IS_THE_SAME_RUN_LEAVE_IT_UNCONFIRMED.text(), style=MaterialTheme.typography.caption)
       metadata.runs.forEach { (slot,run) ->
        if(run.runUUID==null) {
         Text(DesktopMessages.RUN_IDENTITY_UNCONFIRMED.text(slot), fontWeight=FontWeight.Medium)
         Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
          if(run.previousRunUUID!=null) TextButton(enabled=!busy,onClick={
           editDirectory { manager,stopped ->
            val files=manager.observedFiles();require(contentSha256(files)==metadata.contentSha256){DesktopMessages.SAVES_CHANGED_CLOSE_THIS_AND_CHECK_AGAIN.text()}
            manager.confirmRunIdentity(slot,files,true,stopped)
           }
          }) { Text(DesktopMessages.CONTINUE_THE_SAME_RUN.text()) }
          TextButton(enabled=!busy,onClick={
           editDirectory { manager,stopped ->
            val files=manager.observedFiles();require(contentSha256(files)==metadata.contentSha256){DesktopMessages.SAVES_CHANGED_CLOSE_THIS_AND_CHECK_AGAIN.text()}
            manager.confirmRunIdentity(slot,files,false,stopped)
           }
          }) { Text(DesktopMessages.THIS_IS_A_NEW_RUN.text()) }
         }
        }
       }
      }
    }
   }
   Surface(Modifier.fillMaxSize(), color = Color(0xFFF3F5F1)) {
    Column(Modifier.fillMaxSize()) {
     if (closeBlocked) Surface(color = Color(0xFFFFECCD)) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
       Text(if (busy || connectingAtStartup) DesktopMessages.WAIT_FOR_THE_CURRENT_OPERATION_TO_FINISH_BEFORE_CLOSING_THE_WINDOW_BACKUP_R.text() else DesktopMessages.THE_OPERATION_HAS_FINISHED_YOU_CAN_CLOSE_THE_WINDOW_NOW.text(), Modifier.weight(1f), color = Color(0xFF75572C), style = MaterialTheme.typography.body2)
       TextButton(onClick = { closeBlocked = false }) { Text(DesktopMessages.GOT_IT.text()) }
      }
     }
     Box(Modifier.weight(1f).fillMaxWidth()) {
      when (page) {
       Page.DISCOVERY -> {
        val gamePageGeneration = pageGeneration
        val selectedGame = discoveredTarget?.takeIf { it.trusted }
        val discoveryState = SharedGameSelectionState(
         game = selectedGame?.let { gameIdentity(it) },
         selectionId = selectedGame?.path,
         location = selectedGame?.path.orEmpty(),
         rows = listOf(
          SharedGameSelectionRow(SharedGameSelectionControl.CHOOSE_FILE, if (selectedGame == null) DesktopMessages.CHOOSE_GAME_JAR.text() else DesktopMessages.CHANGE_GAME.text()),
         ),
         candidates = discovery?.trustedTargets?.takeIf { discovery?.requiresSelection == true }.orEmpty().map {
          SharedGameCandidate(it.path, gameIdentity(it), it.path)
         },
         pageActive = !appClosed && page == Page.DISCOVERY,
         busy = busy || connectingAtStartup,
         canContinue = selectedGame != null,
         notice = discoveryNotice?.let { NoviceCopy.feedback(it, DesktopMessages.CHECK_INCOMPLETE_SELECT_THE_GAME_AGAIN_OR_RETRY.text()) }
          ?: DiscoveryCopy.game(discovery, discoveredTarget),
         noticeError = discoveryNotice != null,
         details = buildList {
          add(DesktopMessages.SAVE_LOCATION_1_S.text(pathAdapter.state.path))
          discovery?.defaultSave?.reason?.let { add(it) }
          discovery?.diagnostics?.let { addAll(it) }
          discoveryNotice?.let { add(it) }
         }.joinToString("\n"),
        )
        SharedGameSelectionScreen(discoveryState, object : SharedGameSelectionActions {
         private fun canAct() = !busy && !connectingAtStartup && !appClosed && page == Page.DISCOVERY && pageGeneration == gamePageGeneration
         override fun perform(control: SharedGameSelectionControl) {
          if (!canAct()) return
          when (control) {
           SharedGameSelectionControl.CHOOSE_FILE -> chooseSetupGame()
           SharedGameSelectionControl.RESCAN -> rescanDiscovery()
           else -> Unit
          }
         }
         override fun chooseCandidate(id: String) {
          if (!canAct()) return
          val candidate = discovery?.trustedTargets?.singleOrNull { it.path == id && it.trusted } ?: return
          discoveredTarget = candidate; discoveryNotice = null
         }
         override fun next() { if (canAct()) continueDiscovery() }
        })
       }
       Page.PATH -> key(pageGeneration) {
        val generation = pageGeneration
        fun current() = !appClosed && page == Page.PATH && pageGeneration == generation
        SharedSettingsScreen(DesktopSharedSettingsAdapter.state(DesktopSettingsContext(
         config = config, game = targetInfo?.let { gameIdentity(it) }, savePath = config?.selectedSaveRoot ?: pathAdapter.state.path,
         hasSession = session != null, connected = connected, pendingUpload = pending,
         currentName = graphHistory?.currentName.orEmpty(), pageActive = false, busy = false,
         connectingAtStartup = false, appClosed = appClosed)), object : SharedSettingsActions {
          override fun perform(key: SharedSettingsKey) = Unit
          override fun back() = Unit
        })
        SharedSavePathDialog(pathAdapter.state.copy(busy = busy || connectingAtStartup), DesktopSavePathActions(
        adapter = pathAdapter,
        canAct = { !busy && !connectingAtStartup && current() },
        choose = {
         busy = true
         scope.launch {
          pathAdapter.chooseDirectoryUsing(
           choose = { NativeFolderChooser.choose(it) },
           canPublish = ::current,
           onBusyChanged = { busy = it }
          )
         }
        },
        goBack = {
         val previous = pathNavigation.cancel(pathAdapter)
         if (previous == null) navigate(Page.DISCOVERY) else {
          setupDraft = previous.setupDraft; onboardingActive = previous.onboardingActive
          navigate(Page.SETTINGS)
         }
        },
        continuePath = { continuePath() }
       ))
       }
       Page.HOME -> SharedHomeScreen(homeState(), object : SharedHomeActions {
        override fun settings() { if (homeState().canInteract) navigate(Page.SETTINGS) }
        override fun appreciate() = openHomeAppreciation()
        override fun refreshHistory() {
         if (!homeState().refreshHistoryEnabled) return
         readRemote(refreshLocal = true)
        }
        override fun upload() {
         if (!homeState().uploadEnabled) return
         if (!connected) openConnection(if (config == null) SettingsMode.FULL else SettingsMode.UNLOCK)
         else execute(operationEvents.begin(Action.PUSH))
        }
        override fun restore() {
         if (!homeState().restoreEnabled) return
         openRestore()
        }
        override fun launch() { if (homeState().launchEnabled) launchGame() }
        override fun selectSnapshot(snapshotId: String) {
         val current = homeState()
         if (current.canInteract && current.history?.nodes?.any { it.selectable && it.snapshotId == snapshotId } == true) {
          if (selected != snapshotId) restoreIssue = DesktopMessages.VERIFYING_THE_ACTUAL_VERSION.text()
          homeSelectionGeneration++
          selected = snapshotId; selectedByUser = true
         }
        }
        override fun rename(snapshotId: String?) {
         val current = homeState()
         if (!current.canInteract || session == null || current.history?.nodes?.any {
           if (snapshotId == null) it.id == "local-current" else it.snapshotId == snapshotId
          } != true) return
         if (snapshotId == null) editDirectory(inline = true)
         else { renameSnapshot = snapshotId; historyName = graphHistory?.aliases?.get(snapshotId).orEmpty() }
        }
        override fun changeHistoryPage(page: Int) {
         val current = homeState()
         val presentation = current.history ?: return
         if (current.canInteract && page in 0 until presentation.pages) homeHistoryPage = page
        }
       }, naming = renameSnapshot?.let { id ->
        val manager = session
        val ownerConfig = config
        SharedHistoryNaming(id, SharedNamingState(id.hashCode().toLong(), historyName,
         busy = busy || connectingAtStartup || appClosed, notice = directoryNotice.orEmpty()),
         onNameChange = { if (!busy && renameSnapshot == id) historyName = it },
         onSave = { name ->
          if (manager != null && !busy && !appClosed && page == Page.HOME && renameSnapshot == id && session === manager && config == ownerConfig) {
           busy = true; directoryNotice = null
           scope.launch { try {
            withContext(Dispatchers.IO) { manager.renameHistory(id, name) }
            if (session === manager && config == ownerConfig) {
             graphHistory = withContext(Dispatchers.IO) { manager.syncHistory() }; renameSnapshot = null
            }
           } catch (e: Exception) { directoryNotice = NoviceCopy.feedback(e.message.orEmpty(), DesktopMessages.NAME_NOT_SAVED_PLEASE_TRY_AGAIN.text()) }
           finally { busy = false } }
          }
         },
         onDismiss = { if (!busy) { renameSnapshot = null; directoryNotice = null } })
       } ?: directoryEditor?.takeIf { directoryInline }?.let { metadata ->
        SharedHistoryNaming(null, SharedNamingState(System.identityHashCode(metadata).toLong(), directoryName,
         busy = busy || connectingAtStartup || appClosed, notice = directoryNotice.orEmpty()),
         onNameChange = { if (!busy) directoryName = it },
         onSave = { name -> editDirectory { manager, stopped -> manager.renameCurrent(name, stopped) } },
         onDismiss = { if (!busy) { directoryEditor = null; directoryInline = false } },
         onMaintenance = if (metadata.runs.values.any { it.runUUID == null }) ({ directoryInline = false }) else null)
       })
       Page.SETTINGS -> key(pageGeneration) {
        val generation = pageGeneration
        val initialStatus = remember { status }
        var managementOpen by remember { mutableStateOf(false) }
        var managementGeneration by remember { mutableStateOf(0L) }
        fun current() = !appClosed && page == Page.SETTINGS && pageGeneration == generation
        fun settingsContext() = DesktopSettingsContext(
         config = config,
         game = targetInfo?.let { gameIdentity(it) },
         savePath = pathAdapter.state.path, hasSession = session != null, connected = connected,
         pendingUpload = pending, currentName = graphHistory?.currentName.orEmpty(),
         pageActive = current(), busy = busy, connectingAtStartup = connectingAtStartup, appClosed = appClosed,
         managementOpen = managementOpen, editorOpen = directoryEditor != null || renameSnapshot != null,
         targetError = targetError, locationIssue = locationIssue,
         operationNotice = status.takeIf { it != initialStatus }, diagnosticExportNotice = diagnosticExportNotice,
        )
        fun settingsState() = DesktopSharedSettingsAdapter.state(settingsContext())
        SharedSettingsScreen(settingsState(), DesktopSharedSettingsActions(
         state = ::settingsState, pageCurrent = ::current,
         performAction = { action ->
          diagnosticExportNotice = null
          when (action) {
           SharedSettingsKey.GAME -> selectTargetGame()
           SharedSettingsKey.SAVE_PATH -> {
            val savedPath = config?.selectedSaveRoot?.takeIf { it.isNotBlank() } ?: pathAdapter.state.path
            pathNavigation.beginSettings(pathAdapter, savedPath, setupDraft, onboardingActive)
            setupDraft = config ?: newConnectionConfig(); navigate(Page.PATH)
           }
           SharedSettingsKey.CLOUD_CONNECTION -> openConnection(SettingsMode.FULL)
           SharedSettingsKey.AUTOMATIC_UPLOAD -> setAutomaticPaused(config?.autoUploadPaused != true)
           SharedSettingsKey.RECHECK_SAVES -> loadProfile(config)
           SharedSettingsKey.REVIEW_SETUP -> {
            onboardingActive = true; setupDraft = config ?: newConnectionConfig(); navigate(Page.DISCOVERY); rescanDiscovery()
           }
           SharedSettingsKey.EXPORT_LOGS -> exportDiagnosticLogs()
           SharedSettingsKey.MAINTENANCE -> { managementGeneration++; managementOpen = true }
           else -> Unit
          }
         },
         goBack = { navigate(Page.HOME) },
        ))
        if (managementOpen) {
         val dialogGeneration = managementGeneration
         fun dialogCurrent() = current() && managementOpen && managementGeneration == dialogGeneration
         fun dismissManagement() { if (dialogCurrent() && !busy && !connectingAtStartup) managementOpen = false }
         val maintenance = DesktopSettingsMaintenanceActions(::settingsContext, ::dialogCurrent) { action ->
          managementOpen = false
          when (action) {
           DesktopSettingsMaintenance.EDIT_DIRECTORY -> editDirectory()
           DesktopSettingsMaintenance.ARCHIVE_PENDING -> requestConfirmation(Action.ARCHIVE)
          }
         }
         AlertDialog(onDismissRequest = ::dismissManagement, title = { Text(DesktopMessages.MANAGE_SAVES.text()) },
          text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
           verticalArrangement = Arrangement.spacedBy(8.dp)) {
           TextButton(enabled = settingsContext().maintenanceEnabled(DesktopSettingsMaintenance.EDIT_DIRECTORY),
            onClick = { maintenance.perform(DesktopSettingsMaintenance.EDIT_DIRECTORY) }) { Text(DesktopMessages.SAVE_NAME_AND_RUN_IDENTITIES.text()) }
           if (pending) TextButton(enabled = settingsContext().maintenanceEnabled(DesktopSettingsMaintenance.ARCHIVE_PENDING),
            onClick = { maintenance.perform(DesktopSettingsMaintenance.ARCHIVE_PENDING) }) { Text(DesktopMessages.REVIEW_INCOMPLETE_UPLOAD.text()) }
           Details {
            SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
             Text(processMessage(gameStatus))
             listOfNotNull(targetError, locationIssue, directoryNotice, diagnosticExportNotice,
              status.takeIf { it != initialStatus }).filter(String::isNotBlank).distinct().forEach { Text(it) }
            } }
           }
          } },
          confirmButton = { TextButton(enabled = !busy && !connectingAtStartup,
           onClick = ::dismissManagement) { Text(DesktopMessages.CLOSE.text()) } })
        }
       }
       Page.CONNECTION -> key(pageGeneration) {
        val generation = pageGeneration
        fun current() = !appClosed && page == Page.CONNECTION && pageGeneration == generation
        DesktopSharedConnectionPage(configFile, if (onboardingActive && !connectionAppreciationOnly) setupDraft else config, password,
         if (connectionReturnPage == Page.DISCOVERY) ConnectionEntryModel.Origin.SETUP else ConnectionEntryModel.Origin.SETTINGS,
         game = (discoveredTarget ?: targetInfo)?.takeIf { it.trusted }?.let {
          gameIdentity(it)
         },
         appreciationOnly = connectionAppreciationOnly,
         onAccountStatus = { source, expiry, serverTime ->
          val saved = config
          if (current() && saved != null && source == desktopConnectionConfig(saved, password)) {
           // The saved root omits "/", while the shared official account uses its canonical root.
           // Display facts stay keyed to the same saved identity used by operation authorization.
           syncAccess.observeAccountStatus(saved.endpoint, saved.username, expiry, serverTime)
           serviceExpired = syncAccess.isExpired(saved)
          }
         },
         onAccountRefreshStarted = { source ->
          val saved = config
          if (current() && saved != null && source == desktopConnectionConfig(saved, password)) {
           renewalReads.refreshStarted(saved, connected && !serviceExpired && session != null &&
            localHistoryVerified && remoteInspectionReady && sameDesktopHomeProfile(homeProfile, saved))
          }
         },
         onAccountAccessChanged = { source, available ->
          val saved = config
          if (current() && saved != null && source == desktopConnectionConfig(saved, password)) {
           renewalReads.accountAccessChanged(saved, available)
           if (available) { connected = true; connectionNotice = null }
          }
         },
         onBusy = { if (current()) busy = it },
         onDismiss = { if (current() && !busy && !connectingAtStartup) {
          if (connectionAppreciationOnly) entitlementRetryAt = 0L
          navigate(connectionReturnPage)
         } },
         onApplied = { applied, note ->
          if (current()) {
           val prepareNamespace = onboardingActive || !sameDesktopHomeProfile(config, applied.config)
           config = applied.config; setupDraft = applied.config; password = applied.password; connected = true
           renewalReads.reset()
           syncAccess.reset(); serviceExpired = false; entitlementRetryAt = 0L
           connectionNotice = note
           // Connection completion only changes configuration. HOME owns history and save actions.
           onboardingActive = false
           status = DesktopMessages.SETUP_COMPLETE.text()
           automaticStatus = DesktopMessages.READING_SAVE_HISTORY_MANUAL_UPLOAD_AND_RESTORE_ARE_AVAILABLE_ON_HOME.text()
           navigate(Page.HOME)
           loadProfile(applied.config, prepareOfficialNamespace = prepareNamespace, prepareSelectedDirectory = true)
          }
         })
       }
       Page.CONFLICT -> key(pageGeneration) { conflict?.let { value ->
        val owner = conflictOwner()
        fun current() = !appClosed && !busy && !connectingAtStartup &&
         owner.matches(conflictOwner(), page == Page.CONFLICT)
        fun canResolve() = current() && connected && !serviceExpired && !pending
        SharedHomeScreen(homeState().copy(pageActive = false), object : SharedHomeActions {
         override fun settings() = Unit; override fun upload() = Unit; override fun restore() = Unit; override fun launch() = Unit
         override fun selectSnapshot(snapshotId: String) = Unit; override fun rename(snapshotId: String?) = Unit
         override fun changeHistoryPage(page: Int) = Unit
        })
        ConflictDialog(value, conflictSelection, conflictRestoreIssue, busy || connectingAtStartup, connected && !serviceExpired,
         game = (discoveredTarget ?: targetInfo)?.takeIf { it.trusted }?.let {
          gameIdentity(it)
         }, pending = pending,
         onSelect = { id ->
          if (current() && connected && !serviceExpired && value.branches.any { it.snapshot.manifest.id == id }) {
           if (conflictSelection != id) conflictRestoreIssue = DesktopMessages.VERIFYING_THE_ACTUAL_VERSION.text()
           conflictSelection = id
          }
         },
         onBack = { if (current()) navigate(Page.HOME) },
         onConfirm = { id ->
          if (canResolve() && conflictSelection == id && conflictRestoreIssue == null &&
           value.branches.any { it.snapshot.manifest.id == id }) execute(operationEvents.begin(Action.RESOLVE))
         },
         onMerge = { if (canResolve()) execute(operationEvents.begin(Action.MERGE)) })
       } }
       Page.CONFIRM -> confirm?.let { trace ->
        SharedHomeScreen(homeState().copy(pageActive = false), object : SharedHomeActions {
         override fun settings() = Unit; override fun upload() = Unit; override fun restore() = Unit; override fun launch() = Unit
         override fun selectSnapshot(snapshotId: String) = Unit; override fun rename(snapshotId: String?) = Unit
         override fun changeHistoryPage(page: Int) = Unit
        })
        ConfirmationPage(trace.id.hashCode().toLong(), trace.action, session?.root?.toString(), pending, conflictSelection, if (trace.action == Action.RESOLVE) conflict?.branches?.firstOrNull { it.snapshot.manifest.id == conflictSelection }?.snapshot?.manifest else selection?.manifest, busy || connectingAtStartup, confirmationNotice,
         onBack = { cancelConfirmation(trace) },
         onConfirm = { execute(trace) },
         onMerge = if(trace.action==Action.CLOUD_HISTORY)({execute(operationEvents.begin(Action.MERGE_CURRENT))}) else null)
       }
      }
     }
    }
   }
  }
 }
}

/** A normal page in the root window: content may scroll; navigation and actions never do. */
@Composable
private fun GuidedPage(title: String, subtitle: String, footer: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
 Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
   Text(DesktopMessages.PIXEL_DUNGEON_SAVE_SYNC_220.text(), color = muted, style = MaterialTheme.typography.caption)
   Text(title, style = MaterialTheme.typography.h5, fontWeight = FontWeight.SemiBold)
   if (subtitle.isNotBlank()) Text(subtitle, color = muted, style = MaterialTheme.typography.body2)
  }
  Divider()
  Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
   Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight(), content = content)
  }
  Divider()
  Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().align(Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(10.dp), content = footer)
 }
}

@Composable
private fun Details(label: String = DesktopMessages.DETAILS.text(), resetKey: Any? = Unit, content: @Composable ColumnScope.() -> Unit) {
 var state by remember(resetKey) { mutableStateOf(DetailsState()) }
 Column {
  TextButton(onClick = { state = state.toggle() }) { Text(if (state.expanded) DesktopMessages.COLLAPSE_1_S.text(label) else label) }
  if (state.expanded) Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
 }
}

@Composable
private fun CompactFeedback(raw: String, fallback: String) {
 val summary = NoviceCopy.feedback(raw, fallback)
 if (summary.isNotBlank()) Text(summary, color = muted, style = MaterialTheme.typography.body2)
 if (summary != raw) Details(DesktopMessages.PROBLEM_DETAILS.text(), raw) { SelectionContainer { Text(raw, style = MaterialTheme.typography.caption) } }
}

/** The same controller and composable are used by Android; this host only supplies desktop I/O. */
@Composable
private fun DesktopSharedConnectionPage(
 configFile: ConfigFile,
 initial: AppConfig?,
 currentPassword: String,
 origin: ConnectionEntryModel.Origin,
 game: SharedGameIdentity?,
 appreciationOnly: Boolean = false,
 onAccountStatus: (SharedConnectionConfig, Long?, Long) -> Unit,
 onAccountRefreshStarted: (SharedConnectionConfig) -> Unit,
 onAccountAccessChanged: (SharedConnectionConfig, Boolean) -> Unit,
 onBusy: (Boolean) -> Unit,
 onDismiss: () -> Unit,
 onApplied: (AppliedConnection, String?) -> Unit
) {
 val adapter = remember { DesktopConnectionAdapter(initial, currentPassword, configFile, workspace.resolve(".local/official-account-cache")) }
 val io = remember { Executors.newSingleThreadExecutor { task -> Thread(task, "pdsync-connection").apply { isDaemon = true } } }
 val ui = remember { Executor { task -> if (SwingUtilities.isEventDispatchThread()) task.run() else SwingUtilities.invokeLater(task) } }
 val controller = remember { SharedConnectionController(origin, adapter.initialConnection, adapter.profile, adapter, io, ui) }
 val latestDismiss by rememberUpdatedState(onDismiss)
 val latestApplied by rememberUpdatedState(onApplied)
 val latestBusy by rememberUpdatedState(onBusy)
 val latestAccountStatus by rememberUpdatedState(onAccountStatus)
 val latestAccountRefreshStarted by rememberUpdatedState(onAccountRefreshStarted)
 val latestAccountAccessChanged by rememberUpdatedState(onAccountAccessChanged)
 DisposableEffect(controller) {
  controller.attach(object : SharedConnectionHost {
   override fun onAccountStatus(config: SharedConnectionConfig, expiresMs: Long?, serverTimeMs: Long) {
    latestAccountStatus(config, expiresMs, serverTimeMs)
   }
   override fun onAccountRefreshStarted(config: SharedConnectionConfig) {
    latestAccountRefreshStarted(config)
   }
   override fun onAccountAccessChanged(config: SharedConnectionConfig, available: Boolean) {
    latestAccountAccessChanged(config, available)
   }
   override fun onBusyChanged(busy: Boolean) { latestBusy(busy) }
   override fun onBack(destination: ConnectionEntryModel.Back) { latestBusy(false); latestDismiss() }
   override fun onCommitted(config: SharedConnectionConfig) {
    latestBusy(false)
    adapter.appliedConnection?.let { latestApplied(it, null) }
   }
  })
  // Account/appreciation state belongs to this page, independent of native window focus.
  controller.foreground(true)
  if (appreciationOnly) controller.openAppreciationOnly()
  onDispose {
   controller.foreground(false)
   controller.detach()
   controller.close()
   adapter.close()
   io.shutdownNow()
   latestBusy(false)
  }
 }
 SharedConnectionScreen(controller, game)
}

@Composable
private fun ConflictDialog(value: AppendConflict, selected: String?, restoreIssue: String?, busy: Boolean, connected: Boolean,
 game: SharedGameIdentity?, pending: Boolean, onSelect: (String) -> Unit, onBack: () -> Unit,
 onConfirm: (String) -> Unit, onMerge: () -> Unit) {
 val state = SharedBranchChoiceState(
  observation = value,
  branches = value.branches.sortedBy { it.commit.id }.distinctBy { it.snapshot.manifest.id }.map { branch ->
   val manifest = branch.snapshot.manifest
   SharedBranchChoice(manifest.id,
    manifest.directoryMetadata?.displayName?.takeIf(String::isNotBlank) ?: manifest.metadata?.device?.label ?: DesktopMessages.UNKNOWN_DEVICE.text(),
    listOfNotNull(manifest.metadata?.device?.label,
     pdsync.presentation.HistoryPresentation.time(manifest.metadata?.latestFileModifiedAt),
     DesktopMessages.FILE_COUNT.text(manifest.files.size), DesktopMessages.IDENTICAL_TO_CURRENT_LOCAL_PROGRESS.text().takeIf { manifest.files == value.observedLocalFiles })
     .joinToString(" · "))
  },
  selectedId = selected, game = game, pageActive = true, busy = busy, connected = connected,
  observationCurrent = true, resolutionBlocked = pending,
  overwriteSupported = restoreIssue == null, mergeSupported = true,
  notice = if (pending) DesktopMessages.AN_OPERATION_IS_INCOMPLETE_RETURN_TO_HOME_TO_HANDLE_IT.text() else if (selected != null) restoreIssue.orEmpty() else "",
 )
 SharedBranchChoiceDialog(state, object : SharedBranchChoiceActions {
  override fun select(snapshotId: String) = onSelect(snapshotId)
  override fun back() = onBack()
  override fun refresh() = onBack()
  override fun overwrite(snapshotId: String) { if (snapshotId == selected) onConfirm(snapshotId) }
  override fun merge() = onMerge()
  override fun preserveLocal() = Unit
 })
}

@Composable
private fun ConfirmationPage(token: Long, action: Action, root: String?, pending: Boolean, branch: String?, snapshot: Manifest?, busy: Boolean, notice: String?, onBack: () -> Unit, onConfirm: () -> Unit,onMerge:(()->Unit)?=null) {
 val restoring = action in setOf(Action.RESTORE, Action.RESOLVE, Action.REMOTE_TIP,Action.CLOUD_HISTORY)
 val message = when {
  action == Action.ARCHIVE -> DesktopMessages.ONLY_THE_PENDING_UPLOAD_MARKER_IS_REMOVED_HISTORY_IS_PRESERVED_AND_CLOUD_CO.text()
  restoring -> listOfNotNull(snapshot?.let { DesktopMessages.SELECTED_SAVE_1_S_2_S.text(it.metadata?.device?.label ?: DesktopMessages.UNKNOWN_DEVICE.text(), snapshotBriefTime(it)) },
   NoviceCopy.restoreWarning, DesktopMessages.EXIT_THE_GAME_AND_PAUSE_OTHER_SAVE_SYNC_TOOLS.text()).joinToString("\n\n")
  action == Action.PUSH && pending -> DesktopMessages.THIS_RETRIES_THE_PREVIOUSLY_SAVED_CONTENT_WHICH_MAY_NOT_BE_THE_LATEST_PROGR.text()
  else -> DesktopMessages.CONTINUE_WITH_THIS_OPERATION.text()
 }
 SharedConnectionTheme {
  SharedConfirmationDialog(SharedConfirmationState(token,
   title = if (onMerge != null) DesktopMessages.HOW_SHOULD_THE_PROGRESS_CONFLICT_BE_RESOLVED.text() else if (restoring) DesktopMessages.RESTORE_SAVE.text() else if (action == Action.ARCHIVE) DesktopMessages.END_PENDING_UPLOAD.text() else DesktopMessages.CONFIRM_OPERATION.text(),
   message = message, confirmLabel = if (restoring) DesktopMessages.OVERWRITE_LOCAL.text() else DesktopMessages.CONTINUE.text(),
   confirmEnabled = true, secondaryLabel = if (onMerge != null) DesktopMessages.SMART_MERGE_BOTH_SIDES.text() else null,
   secondaryEnabled = onMerge != null, busy = busy, details = notice.orEmpty(), destructive = restoring),
   onConfirm = { onConfirm() }, onSecondary = { onMerge?.invoke() }, onDismiss = { onBack() })
 }
}

private fun monotonicMillis() = System.nanoTime() / 1_000_000
private fun processMessage(status: GameProcessStatus): String = when (status) {
 is GameProcessStatus.Stopped -> DesktopMessages.NO_GAME_DETECTED_FOR_THE_CURRENT_ACCOUNT_WAITING_FOR_SAVES_TO_STABILIZE.text()
 is GameProcessStatus.Running -> DesktopMessages.THE_GAME_IS_RUNNING_AUTOMATIC_UPLOAD_WILL_START_AFTER_IT_EXITS.text()
 is GameProcessStatus.Unknown -> status.reason.ifBlank { DesktopMessages.CANNOT_CONFIRM_THE_GAME_HAS_EXITED.text() }
}
private fun DeviceIdentity.displayLabel() = "$label · ${id.take(8)}"
private fun localTime(value: String): String = runCatching {
 java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM).withLocale(pdsync.i18n.UiLocale.formattingLocale()).withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(value))
}.getOrElse { DesktopMessages.UNKNOWN_TIME.text() }
private fun snapshotBriefTime(manifest: Manifest): String =
 manifest.metadata?.latestFileModifiedAt?.let { DesktopMessages.SAVE_MODIFIED_1_S.text(localTime(it)) }
  ?: manifest.metadata?.capturedAt?.let { DesktopMessages.SAVED_AT_1_S.text(localTime(it)) } ?: DesktopMessages.UNKNOWN_TIME.text()
