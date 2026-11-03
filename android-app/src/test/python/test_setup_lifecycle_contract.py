"""New Android setup wiring; complements executed flow and real preparation tests, not device QA."""
from pathlib import Path
import unittest
from test_embedded_install_contract import body
ROOT = Path(__file__).resolve().parents[2] / 'main/java/io/github/xyz1001/pdsync/android'
MAIN = (ROOT / 'MainActivity.java').read_text()
INSTALL = (ROOT / 'InstallCoordinator.java').read_text()

class SetupLifecycleContract(unittest.TestCase):
    def test_both_selection_entries_require_explicit_inline_access_choice(self):
        choose = body(MAIN, 'public void chooseInstalled()')
        select = body(MAIN, 'private void selectInstalledForSetup(')
        self.assertIn('owner::selectInstalledForSetup', choose)
        self.assertIn('offerGameModeChoice(GameModeChoice.Source.installed(packageName))', select)
        for forbidden in ('prepareInstalledGame', 'prepareSelectedApks', 'RootTargetResolver', 'inspectReady', 'exportTo', 'sha256',
                          'InstalledBridgeResolver', 'authenticateInstalledBridge', 'SyncRuntime', 'flow.select'):
            self.assertNotIn(forbidden, select)
        result = body(MAIN, 'protected void onActivityResult(')
        self.assertIn('offerGameModeChoice(GameModeChoice.Source.files(', result)
        self.assertNotIn('requestPrepare(', result)
        offer = body(MAIN, 'private void offerGameModeChoice(')
        for forbidden in ('prepare(', 'requestRootAuthorization(', 'SyncRuntime.', 'flow.select(', 'inspectSelectedApkPackage'):
            self.assertNotIn(forbidden, offer)
        modification = body(MAIN, 'private void beginModification()')
        for expected in ('InstalledBridgeResolver.resolve', 'authenticateInstalledBridge(binding)', 'requestPrepare(packageName, null)'):
            self.assertIn(expected, modification)

    def test_root_file_resolves_installed_package_before_permission_without_preparation(self):
        resolve = body(MAIN, 'private void resolveRootSetupSource(')
        self.assertLess(resolve.index('inspectSelectedApkPackage(source.apks)'), resolve.index('inspectInstalledGame(packageName)'))
        self.assertLess(resolve.index('if (candidate == null)'), resolve.index('owner.requestRootAuthorization()'))
        self.assertIn('INSTALL_THIS_GAME_BEFORE_USING_ROOT_81.text()', resolve)
        self.assertIn('owner.retryGameModeChoice(request, result.failure)', resolve)
        for forbidden in ('requestPrepare(', 'RootTargetResolver', 'SyncRuntime.', '.versionCode', 'inspectReady', 'exportTo'):
            self.assertNotIn(forbidden, resolve)
        selected = body(MAIN, 'private void selectSetupSource(')
        self.assertIn('this.setupSession.selectedSource = source', selected)
        self.assertNotIn('SyncRuntime.', selected)

    def test_inline_choice_survives_rotation_without_auto_dispatch_or_old_result(self):
        dispatch = body(MAIN, 'private void dispatchInlineAccess(')
        for guard in ('this.setupSession.modeChoice.awaiting(request)', 'this.setupSession.owner != this',
                      'this.installer.hasActiveRequest()', '!this.resumed', 'this.busy'):
            self.assertIn(guard, dispatch)
        resume = body(MAIN, 'protected void onResume()')
        self.assertIn('this.setupSession.modeChoice.pending() != null) { this.homeEntryReadPending = false; render(); return;', resume)
        self.assertNotIn('dispatchInlineAccess(', resume)
        self.assertIn('dismissGameModeDialog()', body(MAIN, 'protected void onPause()'))
        dismiss = body(MAIN, 'private void dismissGameModeDialog()')
        self.assertIn('++this.gameModeDialogToken', dismiss)
        self.assertNotIn('cancel(', dismiss)
        self.assertNotIn('dispatch(', dismiss)
        session = body(MAIN, 'private static final class SetupSession')
        self.assertIn('final GameModeChoice<Uri> modeChoice', session)
        self.assertIn('GameModeChoice.Mode accessMode, previousAccessMode', session)

    def test_first_step_root_only_authorizes_and_never_advances(self):
        auth = body(MAIN, 'private void requestRootAuthorization()')
        self.assertIn('RootSetupAuthorization.probe', auth)
        self.assertIn('authorizeRoot(generation, packageName)', auth)
        self.assertIn('BridgeDiagnostics.Stage.SAVE_READINESS, code', auth)
        self.assertIn('case "ROOT_DENIED_OR_EXITED"', auth)
        self.assertIn('default: code = "ROOT_ACCESS_FAILED"', auth)
        for forbidden in ('SyncPage.PATH', 'RootTargetResolver', 'inspectReady', 'exportTo', 'hash', 'checkRootAccess'):
            self.assertNotIn(forbidden, auth)
        next_step = body(MAIN, 'void m103x3c55a874()')
        self.assertLess(next_step.index('canAdvanceGameSelection()'), next_step.index('RootTargetResolver'))
        self.assertLess(next_step.index('SyncRuntime.setRootGame'), next_step.index('navigate(SyncPage.CONNECTION)'))
        self.assertIn('InstallationReadiness.AccessStatus.NOT_CHECKED', next_step)
        for forbidden in ('SyncPage.PATH', 'AccessStatus.READY', 'previewPath(', 'checkRootAccess(',
                          '.inspectReady(', '.exportTo(', '.targetGame(', 'readComparison(',
                          'refreshComparison(', 'SaveSummary', 'sha256', 'freshPair(',
                          'readInstallationReadiness(', 'authenticateInstalledBridge('):
            self.assertNotIn(forbidden, next_step)

    def test_failed_saf_preparation_reoffers_same_source_without_auto_dispatch(self):
        prepare = body(MAIN, 'private void requestPrepare(')
        self.assertIn('this.setupSession.selectedSource = list == null ? GameModeChoice.Source.installed(str) : GameModeChoice.Source.files(list)', prepare)
        work = body(MAIN, 'private <T> void setupWork(')
        failure = body(work, 'if (action == DiagnosticAction.PREPARE && session.selectedSource != null)')
        self.assertIn('session.modeChoice.offer(session.selectedSource)', failure)
        self.assertIn('session.modeChoiceError = owner.safeError(error)', failure)
        self.assertNotIn('dispatch(', failure)
        self.assertNotIn('requestPrepare(', failure)
        self.assertIn('owner.render(); owner.drainReadinessRefresh()', work)

    def test_legacy_handshake_explicit_modification_reaches_preparation(self):
        modification = body(MAIN, 'private void beginModification()')
        reuse = body(modification, 'if (binding != null && !"PURE_HANDSHAKE_REQUIRED".equals(owner.bridgeAccessCode))')
        self.assertIn('owner.readyForSaves()', reuse)
        self.assertIn('owner.maybeActivateSetupComponent()', reuse)
        self.assertIn('return;', reuse)
        self.assertLess(modification.index(reuse), modification.index('owner.requestPrepare(packageName, null)'))
        self.assertIn('if (source != null && source.apks != null)', modification)
        self.assertIn('requestPrepare(null, source.apks)', modification)

    def test_bridge_step_one_is_only_handshake(self):
        handshake = body(MAIN, 'private void authenticateInstalledBridge(')
        self.assertIn('.authenticateAccess()', handshake)
        for forbidden in ('.inspectReady()', '.targetGame()', '.exportTo(', 'freshPair(', 'reloadPrepared('):
            self.assertNotIn(forbidden, handshake)
        cold = body(MAIN, 'private void readInstallationReadiness()')
        self.assertLess(cold.index('keepSelectedFileInstallationPending()'), cold.index('SyncRuntime.installedBridge'))
        self.assertNotIn('installedMatches', cold)
        self.assertNotIn('reloadPrepared()', cold)

    def test_first_home_inspection_can_activate_stopped_component_once_after_work_releases_busy(self):
        completion = body(MAIN, 'void m67xf48d062(')
        rejected = body(completion, 'if (!bool.booleanValue())')
        self.assertLess(completion.index('this.page = syncPage'), completion.index('if (!bool.booleanValue())'))
        self.assertIn('if (syncPage == SyncPage.HOME) maybeActivateSetupComponent()', rejected)
        self.assertEqual(1, completion.count('maybeActivateSetupComponent()'))
        work_done = body(MAIN, 'void m148lambda$work$0')
        self.assertLess(work_done.index('this.busy = false'), work_done.index('done.accept(obj)'))
        activation = body(MAIN, 'private void maybeActivateSetupComponent()')
        for guard in ('this.busy', 'this.installedBridge == null', 'this.readyForSaves()',
                      'this.page == SyncPage.DISCOVERY && this.installer.acceptedInstalledComponent',
                      'this.setupSession.modeChoice.pending() != null', '!this.resumed',
                      'HelperActivationPolicy.canOffer', 'this.setupSession.flow.beginActivation()'):
            self.assertIn(guard, activation)
        self.assertLess(activation.index('HelperActivationPolicy.canOffer'), activation.index('beginActivation()'))
        self.assertLess(activation.index('beginActivation()'), activation.index('HelperActivation.installedIntent'))
        for render in ('private void renderDiscovery()', 'private void renderHome()', 'protected void onResume()'):
            self.assertNotIn('maybeActivateSetupComponent()', body(MAIN, render))
        for read in ('previewPath(', 'readComparison(', '.inspectReady(', '.exportTo('):
            self.assertNotIn(read, activation)

    def test_ordinary_resume_does_not_scan(self):
        resume = body(MAIN, 'protected void onResume()')
        self.assertIn('if (this.initialLoad && !this.setupSession.running) refreshReadiness()', resume)
        for drain in ('drainHomeEntryRead()', 'drainRenewalArchiveRead()', 'drainExternalInstallation()', 'drainPendingSetup()'):
            self.assertIn(drain, resume)
        self.assertIn('if (this.installer.externalInstallObservation())', resume)
        self.assertNotIn('externalActivityReturned(', resume)
        for forbidden in ('requestReadinessRefresh()', 'inspectReady', 'probeRoot', 'sha256', 'readComparison'):
            self.assertNotIn(forbidden, resume)
        self.assertIn('AutoSyncService.knownReturn(this)', resume)
        self.assertIn('AutoSyncService.recordDeparture(this)', body(MAIN, 'protected void onPause()'))

    def test_system_flows_bounded_owned_and_no_internal_controls(self):
        state = body(MAIN, 'private SharedGameSelectionState gameSelectionState()')
        for forbidden in ('OPEN_INSTALL_SETTINGS', 'RESCAN', 'ACTIVATE_BRIDGE', 'COPY_DIAGNOSTICS',
                          'CHECK_ACCESS'):
            self.assertNotIn('SharedGameSelectionControl.' + forbidden, state)
        for label in ('SELECT_AN_INSTALLED_APP_41.text()', 'SELECT_AN_APK_FILE_42.text()'):
            self.assertIn(label, state)
        drain = body(MAIN, 'private void drainPendingSetup()')
        self.assertIn('pendingSystemChecks >= 12', drain)
        self.assertIn('postDelayed(owner::drainPendingSetup, 500L)', drain)
        self.assertIn('mayContinueAfterUninstall()', drain)
        result = body(MAIN, 'protected void onActivityResult(')
        for kind in ('ACTIVATION', 'UNKNOWN_SOURCES', 'SYSTEM_CONFIRMATION'):
            self.assertIn('consumePending(GameSetupFlow.Pending.' + kind + ')', result)
        self.assertIn('nonce.equals(this.openedConfirmationNonce)', body(MAIN, 'private void openInstallConfirmation(boolean explicitRetry)'))

    def test_session_rotates_and_old_callback_cannot_clear_new_work(self):
        setup = body(MAIN, 'private <T> void setupWork(')
        self.assertIn('session.flow.finishWork(token)', setup)
        self.assertIn('session.flow.matches(generation, target)', setup)
        self.assertIn('MainActivity owner = session.owner', setup)
        retained = body(MAIN, 'private static final class RetainedConnectionUi')
        self.assertIn('final SetupSession setup', retained)
        self.assertNotIn('final MainActivity', retained)
        self.assertIn('this.setupSession = retained.setup', body(MAIN, 'private void restoreConnectionUi('))

    def test_global_log_export_independent_and_details_empty(self):
        shell = body(MAIN, 'public SharedDiagnosticAction wizardDiagnosticAction()')
        self.assertIn('wizardDiagnosticExport.setEnabled(!diagnosticExport.busy())', shell)
        self.assertNotIn('this.busy', shell)
        self.assertIn('new SharedDiagnosticAction(this::chooseDiagnosticDirectory)', MAIN)
        self.assertNotIn('详细信息', body(MAIN, 'public void render()'))
        self.assertNotIn('showSettingsDiagnostics', MAIN)
        self.assertIn('pathNotice, ""', body(MAIN, 'private void renderSharedSavePath()'))
        self.assertIn('feedbackError, ""', body(MAIN, 'private void renderHome()'))

    def test_install_phase_diagnostics_reach_structured_export_logger(self):
        phase = body(INSTALL, 'private static void phase(')
        for builder in ('installationPhase(phase)', 'installationCache(cache)', 'installationElapsedMs('):
            self.assertIn(builder, phase)
        self.assertIn('DiagnosticLog.record(', phase)
        for event in ('PREPARE_VALIDATION', 'UNINSTALL_REQUEST', 'INSTALL_SESSION', 'SESSION_WRITE', 'INSTALL_COMMIT', 'INSTALL_RESULT'):
            self.assertIn('phase("' + event + '"', INSTALL)
        worker = body(MAIN, 'private <T> void setupWork(')
        self.assertIn('AndroidDiagnosticLogging.failure(trace, DiagnosticStage.FINISHED, source.setupProbeFailure)', worker)

    def test_hot_uninstall_has_only_cached_proof_and_cheap_target_check(self):
        consent = body(INSTALL, 'public String uninstallConsentReceipt(')
        self.assertIn('identity(packageName)', consent)
        request = body(INSTALL, 'public void requestUninstall(')
        self.assertIn('ValidatedPreparationCache.requireUnchanged', request)
        self.assertIn('targetStamp(str).equals(approvedTargetStamp)', request)
        self.assertIn('approvedUninstallReceipt.equals(str2)', request)
        for forbidden in ('freshPair()', 'validatePrepared(', 'apkSetSha256(', 'identity(str)'):
            self.assertNotIn(forbidden, request)
        continuation = body(INSTALL, 'public boolean mayContinueAfterUninstall()')
        self.assertIn('PackageTrust.installed(context, pendingPackage())', continuation)
        self.assertIn('catch (PackageManager.NameNotFoundException absent) { return true; }', continuation)
