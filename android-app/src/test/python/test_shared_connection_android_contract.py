"""Android host/source proof. Shared runtime tests execute state transitions separately.

This does not claim phone, network, pixel/screenshot, or Android lifecycle execution.
"""
import re
import unittest
from pathlib import Path
from test_embedded_install_contract import body

REPO = Path(__file__).resolve().parents[4]
MAIN = (REPO / "android-app/src/main/java/io/github/xyz1001/pdsync/android/MainActivity.java").read_text()
ADAPTER = (REPO / "android-app/src/main/kotlin/io/github/xyz1001/pdsync/android/AndroidConnectionComposition.kt").read_text()
COMMON = (REPO / "shared-ui/src/main/kotlin/io/github/xyz1001/pdsync/android/SharedConnectionController.kt").read_text()
SCREEN = (REPO / "shared-ui/src/main/kotlin/io/github/xyz1001/pdsync/android/SharedConnectionScreen.kt").read_text()


class SharedConnectionAndroidContract(unittest.TestCase):
    def test_component_activity_mounts_the_actual_shared_composable(self):
        self.assertIn("extends ComponentActivity implements SharedConnectionHost", MAIN)
        self.assertIn("activity.setContent", ADAPTER)
        self.assertIn("SharedConnectionScreen(session.controller, game)", ADAPTER)
        route = body(MAIN, "public void render()")
        self.assertLess(route.index("renderSharedConnection()"), route.index("ScrollView scrollView"))
        self.assertIn("this.page == SyncPage.CONNECTION || this.sharedAppreciationOnly", route)
        for renderer in ("renderConnection()", "renderOfficialConnection()", "renderCustomConnection()",
                         "ManagedAccountPanel.render", "ManualPaymentPanel.render"):
            self.assertNotIn(renderer, MAIN)

    def test_unmigrated_native_pages_are_explicit_and_unchanged_routes(self):
        route = body(MAIN, "public void render()")
        for page in ("Home", "Settings", "Discovery", "Conflict", "Confirmation"):
            self.assertIn("render" + page + "()", route)
        self.assertIn("renderSharedSavePath()", route)
        self.assertIn("renderSharedComparison()", route)

    def test_platform_adapter_is_typed_and_keeps_only_application_context(self):
        ports = ADAPTER[ADAPTER.index("internal class AndroidConnectionPorts"):]
        self.assertIn(": SharedConnectionPorts", ports)
        self.assertIn("private val app = context.applicationContext", ports)
        for forbidden in ("MainActivity", "ComponentActivity", "private val context", "private var host"):
            self.assertNotIn(forbidden, ports)
        self.assertIn("AndroidConnectionPorts(context.applicationContext)", ADAPTER)
        self.assertIn("Handler(Looper.getMainLooper())", ADAPTER)
        self.assertNotIn("activity.runOnUiThread", ADAPTER)

    def test_existing_connection_in_wizard_preserves_setup_origin(self):
        route = body(MAIN, "private void renderSharedConnection()")
        self.assertIn("this.onboardingActive ? ConnectionEntryModel.Origin.SETUP : ConnectionEntryModel.Origin.SETTINGS", route)
        creation = body(ADAPTER, "fun create(")
        self.assertNotIn("existing == null", creation)
        self.assertIn("SharedConnectionController(origin, fromStored(existing), profile", creation)

    def test_configuration_mapping_preserves_profile_provider_and_password(self):
        self.assertIn("SharedConnectionConfig(it.endpoint, it.username, it.password, it.profile", ADAPTER)
        self.assertIn("it.provider == SecureConnectionStore.Connection.PROVIDER_OFFICIAL", ADAPTER)
        self.assertIn("SecureConnectionStore.Connection(value.endpoint, value.username, value.password, value.profile", ADAPTER)
        self.assertIn("if (value.official) SecureConnectionStore.Connection.PROVIDER_OFFICIAL", ADAPTER)
        self.assertIn("else SecureConnectionStore.Connection.PROVIDER_CUSTOM", ADAPTER)
        self.assertIn('this.connection == null ? "default" : this.connection.profile', body(MAIN, "private void loadDraft()"))

    def test_test_and_save_are_separate_and_use_existing_backend_and_store(self):
        probe = body(ADAPTER, "override fun testConnection(")
        self.assertIn("synchronized(SyncRuntime.OPERATION_LOCK)", ADAPTER)
        self.assertIn("candidate.validate()", probe)
        self.assertIn("SyncRuntime.facade(app, candidate).connect().isSuccess", probe)
        for mutation in (".save(", "setConnection(", "setOnboardingProgress(", "forget("):
            self.assertNotIn(mutation, probe)
        save = body(ADAPTER, "override fun saveAndActivate(")
        self.assertIn("if (!candidate.isTemporary) SecureConnectionStore(app).save(candidate)", save)
        self.assertLess(save.index(".save(candidate)"), save.index("SyncRuntime.setConnection(candidate)"))
        self.assertIn("AutoSyncService.cancelForConnectionChange(app)", save)
        self.assertNotIn("forget(", ADAPTER)

    def test_adapter_cannot_change_game_source_namespace_or_baseline(self):
        for forbidden in ("setPreparedGame", "setRootGame", "useEmbeddedBackend", "setNamespace",
                          "RootSelectionStore", "GamePreparationService", "clearBaseline", "delete", ".restore", ".upload"):
            self.assertNotIn(forbidden, ADAPTER)
        committed = body(MAIN, "private boolean drainSharedCommit()")
        self.assertIn("refreshComparison(SyncPage.COMPARISON)", committed)
        for forbidden in ("setNamespace", "setPreparedGame", "setRootGame", "delete", "clearBaseline"):
            self.assertNotIn(forbidden, committed)

    def test_rotation_retains_controller_without_replaying_registration(self):
        self.assertIn("getLastCustomNonConfigurationInstance()", MAIN)
        retained = body(MAIN, "private static final class RetainedConnectionUi")
        self.assertIn("session = activity.sharedConnectionSession", retained)
        self.assertIn("automaticHistory = activity.automaticHistoryState", retained)
        self.assertNotRegex(retained, r"(?:final|private|public)\s+(?:MainActivity|Activity|Context|View)\s+\w+\s*[;=]")
        restore = body(MAIN, "private void restoreConnectionUi(")
        self.assertIn("this.sharedConnectionSession = retained.session", restore)
        self.assertIn("getController().attach(this)", restore)
        retain = body(MAIN, "public Object onRetainCustomNonConfigurationInstance()")
        self.assertIn("getController().detach()", retain)
        for text in (restore, retain):
            for action in (".register(", ".login(", "new SharedConnectionController", "AndroidConnectionComposition.create"):
                self.assertNotIn(action, text)

    def test_lifecycle_detaches_host_but_only_real_leave_closes_controller(self):
        self.assertIn("getController().foreground(false)", body(MAIN, "protected void onPause()"))
        self.assertIn("getController().foreground(true)", body(MAIN, "protected void onResume()"))
        destroy = body(MAIN, "protected void onDestroy()")
        self.assertIn("getController().detach()", destroy)
        self.assertIn("if (!isChangingConfigurations()) closeSharedConnection()", destroy)
        navigation = body(MAIN, "private void navigate(")
        self.assertLess(navigation.index("closeSharedConnection()"), navigation.index("this.page = syncPage"))
        close = body(ADAPTER, "override fun close()")
        self.assertIn("controller.detach()", close)
        self.assertIn("controller.close()", close)
        self.assertIn("io.shutdownNow()", close)
        self.assertIn("host = null", body(COMMON, "fun detach()"))
        foreground = body(COMMON, "fun foreground(")
        self.assertIn("proof = null", foreground)
        self.assertIn("cancelAppreciationRequest()", foreground)

    def test_saved_instance_state_contains_no_credentials_or_controller(self):
        saved = body(MAIN, "protected void onSaveInstanceState(")
        for secret in ("password", "official", "credential", "session", "proof", "attempt", "draft"):
            self.assertNotIn(secret, saved.lower())
        self.assertNotIn("rememberSaveable", SCREEN)

    def test_settings_appreciation_uses_same_controller_and_screen(self):
        settings = body(MAIN, "private void renderSettings()")
        self.assertIn("isOfficialAccount(this.connection)", settings)
        self.assertIn("showManagedAccount(MainActivity.this.connection)", settings)
        route = body(MAIN, "private void showManagedAccount(")
        self.assertIn("AndroidConnectionComposition.create(getApplicationContext(), source, source.profile,", route)
        self.assertIn("ConnectionEntryModel.Origin.SETTINGS", route)
        self.assertIn("getController().openAppreciationOnly()", route)
        self.assertIn("this.sharedAppreciationOnly = true", route)
        self.assertIn('resolve("managed-account-expiry-v1")', ADAPTER)

    def test_busy_native_completion_cannot_drop_a_successful_shared_commit(self):
        committed = body(MAIN, "public void onCommitted(")
        self.assertIn("this.pendingSharedCommit = config", committed)
        drain = body(MAIN, "private boolean drainSharedCommit()")
        self.assertLess(drain.index("this.busy"), drain.index("this.pendingSharedCommit = null"))
        self.assertIn("if (drainSharedCommit()) return", body(MAIN, "public void render()"))

    def test_pending_commit_waits_for_resume_and_survives_readiness_rotation_restore(self):
        drain = body(MAIN, "private boolean drainSharedCommit()")
        self.assertIn("!this.resumed", drain)
        self.assertIn("drainSharedCommit()", body(MAIN, "protected void onResume()"))
        startup = body(MAIN, "void m69xebc6eee5(")
        restored = body(startup, "if (this.retainedConnectionUi != null")
        self.assertNotIn("restoreConnectionUi(retained)", restored)
        self.assertNotIn("this.pendingSharedCommit =", restored)
        self.assertNotIn("this.sharedConnectionSession =", restored)

    def test_resume_commit_does_not_queue_a_second_readiness_refresh(self):
        resume = body(MAIN, "protected void onResume()")
        self.assertLess(resume.index("commitBeforeResume = this.sharedCommitSerial"),
                        resume.index("getController().foreground(true)"))
        self.assertLess(resume.index("commitBeforeResume != this.sharedCommitSerial || drainSharedCommit()"),
                        resume.index("requestReadinessRefresh()"))
        self.assertIn("this.sharedCommitSerial++", body(MAIN, "private boolean drainSharedCommit()"))

    def test_saved_recovery_error_updates_existing_controller_and_preserves_draft(self):
        render = body(MAIN, "private void renderSharedConnection()")
        creation = body(render, "if (this.sharedConnectionSession == null)")
        self.assertNotIn("setSavedConnectionRecovery", creation)
        self.assertIn("setSavedConnectionRecovery", render)
        self.assertIn("this.connection.isTemporary() && this.secrets.exists()", render)
        reload = body(MAIN, "public void onReloadSavedConnection()")
        self.assertIn("this.sharedReloadPending = true", reload)
        failed = body(MAIN, "void m149lambda$work$1$iogithubxyz1001pdsyncandroidMainActivity(")
        failure = body(failed, "if (this.sharedReloadPending)")
        self.assertIn('this.connectionLoadError = "连接设置读取失败，请重试。"', failure)
        for destructive in ("closeSharedConnection", "forget(", "this.connection =", ".save("):
            self.assertNotIn(destructive, failure)
        success = body(MAIN, "void m71x6d7dc9b8(")
        self.assertIn("closeSharedConnection()", success)

    def test_deferred_commit_cannot_remount_a_closed_session_during_render(self):
        render = body(MAIN, "private void renderSharedConnection()")
        self.assertLess(render.index("visibleSession.getController().foreground(this.resumed)"),
                        render.index("this.sharedConnectionSession != visibleSession"))
        self.assertLess(render.index("this.sharedConnectionSession != visibleSession"),
                        render.index("AndroidConnectionComposition.show(this, visibleSession, selectedGameIdentity())"))

    def test_commit_preserves_runtime_connection_identity_for_history_guards(self):
        commit = body(MAIN, "private boolean drainSharedCommit()")
        self.assertLess(commit.index("this.connection = AndroidConnectionComposition.toStored(config)"),
                        commit.index("SyncRuntime.setConnection(this.connection)"))
        self.assertLess(commit.index("SyncRuntime.setConnection(this.connection)"),
                        commit.index("refreshComparison(SyncPage.COMPARISON)"))

    def test_system_back_dispatches_once_to_existing_navigation_and_shared_controller(self):
        create = body(MAIN, "public void onCreate(")
        self.assertEqual(create.count("getOnBackPressedDispatcher().addCallback(this, this.navigationBack)"), 1)
        self.assertIn("MainActivity.this.onBackPressed()", create)
        back = body(MAIN, "public void onBackPressed()")
        self.assertIn("getController().back()", back)
        self.assertLess(back.index("this.navigationBack.setEnabled(false)"), back.index("super.onBackPressed()"))
        self.assertIn("finally { this.navigationBack.setEnabled(true); }", back)

    def test_android_only_insets_keep_status_navigation_bars_and_keyboard_clear(self):
        self.assertIn("Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding())", ADAPTER)
        self.assertNotIn("safeDrawingPadding", SCREEN)
        self.assertNotIn("imePadding", SCREEN)

    def test_saved_reentry_is_a_durable_match_without_authentication_or_runtime_activation(self):
        saved = body(ADAPTER, "override fun isSavedConnection(")
        self.assertIn("candidate.validate()", saved)
        self.assertIn("candidate.isTemporary", saved)
        self.assertIn("SecureConnectionStore(app).load()", saved)
        for field in ("endpoint", "username", "password", "profile", "provider"):
            self.assertIn("stored." + field + " == candidate." + field, saved)
        for action in (".save(", "setConnection(", "setOnboardingProgress(", ".connect(", ".login("):
            self.assertNotIn(action, saved)
        self.assertIn(".getOrDefault(false)", ADAPTER)

    def test_fresh_verification_remains_mandatory_before_android_persistence(self):
        commit = body(COMMON, "fun continueConnection()")
        self.assertIn("if (hasProof()) proof!!.config else savedDraftForRevalidation()", commit)
        self.assertLess(commit.index("if (candidate == null)"), commit.index("ports.saveAndActivate(candidate)"))
        self.assertLess(commit.index("savedReentry = null"), commit.index("verify(candidate, pending)"))
        self.assertLess(commit.index("verify(candidate, pending)"), commit.index("ports.saveAndActivate(candidate)"))
        self.assertLess(commit.index("checkWork(pending)"), commit.index("ports.saveAndActivate(candidate)"))
        verify = body(COMMON, "private fun verify(")
        self.assertLess(verify.index("entitlement.requireSyncAccess()"), verify.index("ports.testConnection(candidate)"))
        self.assertIn("if (!ports.testConnection(candidate)) throw ProbeRejected()", verify)


if __name__ == "__main__":
    unittest.main()
