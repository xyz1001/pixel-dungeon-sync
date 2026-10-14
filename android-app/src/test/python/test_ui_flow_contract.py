"""Activity wiring checks paired with executed production policy/core tests.

These checks cover render structure and Android callbacks that cannot run on the host JVM.
They do not claim device or visual UI coverage.
"""
import re
import unittest
from pathlib import Path

from test_embedded_install_contract import body

ROOT = Path(__file__).resolve().parents[2] / "main/java/io/github/xyz1001/pdsync/android"
MAIN = (ROOT / "MainActivity.java").read_text()
STORE = (ROOT / "SecureConnectionStore.java").read_text()
CONNECTION_FILE = (ROOT / "WebDavConnectionFile.java").read_text()


class AndroidUiFlowContract(unittest.TestCase):
    def test_four_steps_keep_existing_forward_and_back_navigation(self):
        for method, title in (
            ("renderDiscovery", "1 / 4  选择游戏"),
            ("renderPath", "2 / 4  设置存档路径"),
            ("renderConnection", "3 / 4  连接云端"),
            ("renderComparison", "4 / 4  选择存档"),
        ):
            self.assertIn(title, body(MAIN, "private void " + method + "()"))
        self.assertIn("navigate(SyncPage.PATH)", body(MAIN, "void m103x3c55a874("))
        self.assertIn("navigate(SyncPage.DISCOVERY)", body(MAIN, "void m109x58567797("))
        self.assertIn("navigate(SyncPage.CONNECTION)", body(MAIN, "void m110xcdd09dd8("))
        self.assertIn("refreshComparison(SyncPage.COMPARISON)", body(MAIN, "void m18x35802ca1("))
        self.assertIn("navigate(SyncPage.CONNECTION)", body(MAIN, "void m80x76ddc5af("))

    def test_first_step_keeps_installed_game_and_apk_pickers(self):
        discovery = body(MAIN, "private void renderDiscovery()")
        self.assertIn("chooseInstalled()", discovery)
        self.assertIn("pickApks()", discovery)
        self.assertIn("InstalledAppPickerDialog", body(MAIN, "public void chooseInstalled()"))
        picker = body(MAIN, "public void pickApks()")
        self.assertIn("android.intent.action.OPEN_DOCUMENT", picker)
        self.assertIn("android.intent.extra.ALLOW_MULTIPLE", picker)
        self.assertIn("application/vnd.android.package-archive", picker)
        self.assertIn("PICK_APK", MAIN)

    def test_path_preview_uses_resolved_path_and_actual_slot_play_summary(self):
        path = body(MAIN, "private void renderPath()")
        self.assertIn("text(savePathLabel())", path)
        self.assertIn("this.localPreview.getSlots().size()", path)
        self.assertIn("this.localPreview.getSlots().entrySet()", path)
        self.assertIn("this.localPreview.getPlays()", path)
        self.assertIn("this.localPreview.getFiles()", path)
        self.assertIn("readyForSaves() && this.localPreview != null", path)
        label = body(MAIN, "private String savePathLabel()")
        self.assertIn("this.rootGame.dataDir", label)
        self.assertIn("getApplicationInfo(gamePackage, 0).dataDir", label)
        self.assertIn('"files"', label)

    def test_connection_password_can_start_empty_and_is_not_required_by_ui(self):
        self.assertIn('private String draftPassword = ""', MAIN)
        render = body(MAIN, "private void renderCustomConnection()")
        self.assertIn('field("密码", this.draftPassword, true)', render)
        self.assertNotIn("密码可留空", render)
        self.assertNotIn("准备本应用的同步目录", render)
        self.assertNotIn("draftPassword.isEmpty()", render)
        self.assertNotIn("passwordInput.getText().length()", render)
        self.assertIn("setSaveEnabled(false)", body(MAIN, "private EditText field("))

    def test_connection_has_no_static_explanation_and_preserves_settings_back(self):
        render = body(MAIN, "private void renderConnection()")
        custom = body(MAIN, "private void renderCustomConnection()")
        official = body(MAIN, "private void renderOfficialConnection()")
        footer = body(MAIN, "private Button renderConnectionFooter()")
        self.assertIn('this.onboardingActive ? "上一步" : "返回"', footer)
        self.assertIn('this.onboardingActive ? "下一步" : "保存"', footer)
        for section in (render, custom, official, footer):
            self.assertFalse(re.search(r'^        text\("', section, re.MULTILINE))
        self.assertIn("backFromConnection()", body(MAIN, "void m89x4f84e35a("))
        self.assertIn('navigate(this.onboardingActive ? SyncPage.PATH : SyncPage.SETTINGS)',
                      body(MAIN, "private void backFromConnection()"))
        for token in ('field("WebDAV 地址"', 'field("用户名"', 'field("密码"', 'button("测试连接"'):
            self.assertIn(token, custom)

    def test_all_editable_connection_fields_use_executed_proof_policy(self):
        watcher = body(MAIN, "private void watchConnectionFields(")
        footer = body(MAIN, "private Button renderConnectionFooter()")
        changed = body(watcher, "public void onTextChanged(")
        before = changed.index("String before = MainActivity.this.draftFingerprint()")
        capture = changed.index("MainActivity.this.captureDraft()")
        invalidate = changed.index("AndroidUiFlowPolicy.invalidateProofOnEdit(")
        continuation = changed.index("AndroidUiFlowPolicy.canContinue(")
        self.assertLess(before, capture)
        self.assertLess(capture, invalidate)
        self.assertLess(invalidate, continuation)
        self.assertIn("MainActivity.this.testedDraft, before, MainActivity.this.draftFingerprint()", changed)
        self.assertIn("MainActivity.this.busy", changed)
        for field in ("endpointInput", "userInput", "passwordInput"):
            self.assertIn("this." + field + ".addTextChangedListener(textWatcher)", watcher)
        self.assertIn("if (this.endpointInput != null)", watcher)
        self.assertGreaterEqual((watcher + footer).count("AndroidUiFlowPolicy.canContinue("), 2)
        for render in ("renderCustomConnection", "renderOfficialConnection"):
            self.assertIn("watchConnectionFields(renderConnectionFooter())", body(MAIN, "private void " + render + "()"))

    def test_leaving_connection_invalidates_test_before_changing_page(self):
        navigate = body(MAIN, "private void navigate(")
        guard = "if (this.page == SyncPage.CONNECTION && syncPage != SyncPage.CONNECTION) this.testedDraft = null;"
        self.assertIn(guard, navigate)
        self.assertLess(navigate.index(guard), navigate.index("this.page = syncPage"))
        self.assertIn("backFromConnection()", body(MAIN, "void m89x4f84e35a("))
        back = body(MAIN, "private void backFromConnection()")
        self.assertIn("navigate(this.onboardingActive ? SyncPage.PATH : SyncPage.SETTINGS)", back)
        self.assertLess(back.index("this.testedDraft = null"), back.index("navigate("))
        self.assertIn("this.testedDraft = null", body(MAIN, "private void loadDraft()"))
        applied = body(MAIN, "void m18x35802ca1(")
        tested = body(applied, "if (!z)")
        saved = body(applied, "else")
        self.assertIn("this.testedDraft = str", tested)
        self.assertIn("this.testedDraft = null", saved)
        self.assertLess(saved.index("this.testedDraft = null"), saved.index("navigate(SyncPage.COMPARISON)"))
        self.assertLess(saved.index("navigate(SyncPage.COMPARISON)"), saved.index("refreshComparison("))

    def test_apply_requires_current_success_and_failed_dav_cannot_create_proof(self):
        apply = body(MAIN, "private void applyConnection(")
        self.assertIn("strDraftFingerprint.equals(this.testedDraft)", apply)
        self.assertLess(apply.index("this.testedDraft = null"), apply.index("work("))
        connect = body(MAIN, "void lambda$applyConnection$55(")
        self.assertIn(".connect()", connect)
        self.assertIn("!connectionStatusConnect.isSuccess()", connect)
        self.assertIn("throw new IOException", connect)
        worker = body(MAIN, "Object m17xc0060660(")
        self.assertIn("ConnectionUpdatePolicy.apply(", worker)
        self.assertNotIn("this.testedDraft =", worker)
        success = body(MAIN, "void m18x35802ca1(")
        self.assertIn("this.testedDraft =", success)

    def test_first_connection_offers_only_explicit_official_and_custom_entries(self):
        render = body(MAIN, "private void renderConnection()")
        choice = body(render, "if (this.connectionEntry.mode() == ConnectionEntryModel.Mode.CHOICE)")
        self.assertEqual(["使用官方服务", "自定义 WebDAV"], re.findall(r'button\("([^"]+)"', choice))
        self.assertIn("chooseConnectionService(ConnectionEntryModel.Mode.OFFICIAL)", choice)
        self.assertIn("chooseConnectionService(ConnectionEntryModel.Mode.CUSTOM)", choice)
        self.assertIn("this::backFromConnection", choice)
        self.assertRegex(choice, r"return;\s*}$")
        for operation in ("field(", "authenticateOfficial(", "applyConnection(", ".register(", ".login("):
            self.assertNotIn(operation, choice)
        self.assertIn("renderOfficialConnection()", render)
        self.assertIn("renderCustomConnection()", render)
        self.assertIn('button("更换服务", this::showConnectionChoice)', render)
        self.assertIn("connectionEntry.origin() == ConnectionEntryModel.Origin.SETTINGS", render)

    def test_official_form_has_username_password_and_separate_login_registration(self):
        render = body(MAIN, "private void renderOfficialConnection()")
        self.assertIn('field("用户名", this.officialUser, false)', render)
        self.assertIn('field("密码", this.officialPassword, true)', render)
        self.assertIn('addButton(actions, "登录", () -> authenticateOfficial(false), true)', render)
        self.assertIn('addButton(actions, "注册", () -> authenticateOfficial(true), true)', render)
        self.assertEqual(["用户名", "密码"], re.findall(r'field\("([^"]+)"', render))
        self.assertNotIn("WebDAV 地址", render)
        authenticate = body(MAIN, "private void authenticateOfficial(")
        self.assertIn("client.register(username, password) : client.login(username, password)", authenticate)
        self.assertIn("final String username = this.officialUser, password = this.officialPassword", authenticate)
        for removed in ("UUID", "random", "register()", "displayName", "deviceName"):
            self.assertNotIn(removed, authenticate)
        for removed_label in ("一键注册", "随机注册", "自动注册"):
            self.assertNotIn(removed_label, MAIN)

    def test_existing_settings_source_is_loaded_explicitly_and_missing_source_is_custom(self):
        load = body(MAIN, "private void loadDraft()")
        self.assertIn("SecureConnectionStore.Connection.PROVIDER_OFFICIAL.equals(connection.provider)", load)
        self.assertIn("connection == null ? ConnectionEntryModel.Origin.SETUP : ConnectionEntryModel.Origin.SETTINGS", load)
        self.assertIn("connection == null ? null : official ? ConnectionEntryModel.Mode.OFFICIAL : ConnectionEntryModel.Mode.CUSTOM", load)
        self.assertIn('this.officialUser = official ? connection.username : ""', load)
        self.assertIn('this.officialPassword = official ? connection.password : ""', load)
        for inference in ("contains(", "startsWith(", "REGISTER_URL", "webdav.xyz1001.top"):
            self.assertNotIn(inference, load)
        settings = body(MAIN, "void m131xb9d46b71()")
        self.assertLess(settings.index("this.draftLoaded = false"), settings.index("navigate(SyncPage.CONNECTION)"))
        default = body(CONNECTION_FILE, "Value(String str, String str2, String str3, String str4)")
        self.assertIn("this(str, str2, str3, str4, PROVIDER_CUSTOM)", default)
        explicit = body(CONNECTION_FILE, "Value(String str, String str2, String str3, String str4, String provider)")
        self.assertIn("PROVIDER_OFFICIAL.equals(provider) ? PROVIDER_OFFICIAL : PROVIDER_CUSTOM", explicit)

    def test_saved_connection_round_trips_source_without_saving_during_authentication(self):
        self.assertIn('properties.setProperty("provider", value.provider)', body(CONNECTION_FILE, "void save(Value value)"))
        self.assertIn('properties.getProperty("provider")', CONNECTION_FILE)
        self.assertIn("valueLoadOrMigrate.profile, valueLoadOrMigrate.provider", body(STORE, "public Connection load()"))
        authenticate = body(MAIN, "private void authenticateOfficial(")
        self.assertIn("account.password, profile, SecureConnectionStore.Connection.PROVIDER_OFFICIAL", authenticate)
        for method in ("private void authenticateOfficial(", "private void resumeOfficialAttempt()", "private void chooseConnectionService(", "private void showConnectionChoice()"):
            section = body(MAIN, method)
            for mutation in (".save(", "SyncRuntime.setConnection(", "setOnboardingProgress("):
                self.assertNotIn(mutation, section)
        apply = body(MAIN, "private void applyConnection(")
        self.assertIn("? this.verifiedOfficial : new SecureConnectionStore.Connection(this.draftEndpoint, this.draftUser, this.draftPassword, this.draftProfile)", apply)
        self.assertIn("this.secrets.save(connection)", body(MAIN, "void m16x4a8be01f("))
        self.assertIn("ConnectionUpdatePolicy.apply(z, connection.isTemporary()", body(MAIN, "Object m17xc0060660("))

    def test_proof_is_bound_to_source_and_all_visible_credentials(self):
        fingerprint = body(MAIN, "public String draftFingerprint()")
        self.assertIn('return "official\\u0000" + this.officialUser + "\\u0000" + this.officialPassword + "\\u0000" + this.draftProfile', fingerprint)
        self.assertIn('return "custom\\u0000" + this.draftEndpoint + "\\u0000" + this.draftUser + "\\u0000" + this.draftPassword + "\\u0000" + this.draftProfile', fingerprint)
        self.assertIn("connectionEntry.mode() == ConnectionEntryModel.Mode.OFFICIAL", fingerprint)
        watcher = body(MAIN, "private void watchConnectionFields(")
        self.assertIn("if (!before.equals(MainActivity.this.draftFingerprint())) MainActivity.this.verifiedOfficial = null", watcher)
        capture = body(MAIN, "public void captureDraft()")
        self.assertIn("ConnectionEntryModel.Mode.OFFICIAL", capture)
        self.assertIn("ConnectionEntryModel.Mode.CUSTOM && this.endpointInput != null", capture)
        for binding in ("this.officialUser = this.userInput", "this.officialPassword = this.passwordInput", "this.draftEndpoint = this.endpointInput", "this.draftUser = this.userInput", "this.draftPassword = this.passwordInput"):
            self.assertIn(binding, capture)
        render = body(MAIN, "public void render()")
        for field in ("endpointInput", "userInput", "passwordInput"):
            self.assertLess(render.index("this." + field + " = null"), render.index("renderConnection()"))

    def test_source_switch_and_back_share_busy_guard_and_invalidate_proof(self):
        for signature, transition in (("private void chooseConnectionService(", ".choose(service, false)"), ("private void showConnectionChoice()", ".showChooser(false)")):
            section = body(MAIN, signature)
            self.assertLess(section.index("if (this.busy"), section.index("captureDraft()"))
            self.assertLess(section.index("captureDraft()"), section.index(transition))
            self.assertLess(section.index(transition), section.index("this.testedDraft = null"))
            self.assertLess(section.index("this.testedDraft = null"), section.index("render()"))
        back = body(MAIN, "private void backFromConnection()")
        self.assertLess(back.index("if (this.busy) return"), back.index("captureDraft()"))
        self.assertIn("this.connectionEntry.back(false)", back)
        self.assertIn("destination == ConnectionEntryModel.Back.CHOICE", back)
        self.assertIn("destination != ConnectionEntryModel.Back.STAY", back)
        self.assertLess(back.index("this.testedDraft = null"), back.index("render()"))
        system_back = body(MAIN, "public void onBackPressed()")
        self.assertIn("backFromConnection()", body(system_back, "if (this.page == SyncPage.CONNECTION)"))
        self.assertIn("backFromConnection()", body(MAIN, "void m89x4f84e35a("))

    def test_existing_form_back_keeps_wizard_path_and_standalone_settings(self):
        # An existing provider opens directly in both the four-step recheck and
        # standalone Settings. The model's direct-form origin does not replace
        # the Activity's actual parent; only new setup forms back into CHOICE.
        back = body(MAIN, "private void backFromConnection()")
        chooser = body(back, "if (destination == ConnectionEntryModel.Back.CHOICE)")
        self.assertIn("render()", chooser)
        self.assertNotIn("navigate(", chooser)
        leave = body(back, "else if (destination != ConnectionEntryModel.Back.STAY)")
        destinations = re.search(r"navigate\(this\.onboardingActive\s*\?\s*SyncPage\.(\w+)\s*:\s*SyncPage\.(\w+)\)", leave)
        self.assertIsNotNone(destinations)
        self.assertEqual("PATH", destinations.group(1), "Existing connection in the wizard returns to its path step")
        self.assertEqual("SETTINGS", destinations.group(2), "Standalone connection editing returns to Settings")
        self.assertNotIn("navigate(SyncPage.SETTINGS)", leave)

    def test_official_authentication_proves_dav_before_creating_source_bound_success(self):
        authenticate = body(MAIN, "private void authenticateOfficial(")
        self.assertLess(authenticate.index("if (this.busy"), authenticate.index("new ConnectionAuthAttempt"))
        self.assertIn("connectionEntry.mode() != ConnectionEntryModel.Mode.OFFICIAL", authenticate)
        self.assertLess(authenticate.index("this.testedDraft = null"), authenticate.index("new ConnectionAuthAttempt"))
        self.assertLess(authenticate.index("this.verifiedOfficial = null"), authenticate.index("new ConnectionAuthAttempt"))
        request = body(authenticate, "() ->")
        self.assertLess(request.index("candidate.validate()"), request.index("MainActivity.lambda$applyConnection$55"))
        self.assertLess(request.index("MainActivity.lambda$applyConnection$55"), request.index("return candidate"))
        self.assertIn("SyncRuntime.facade(app, candidate)", request)
        self.assertNotIn("this.", request)
        self.assertIn("final android.content.Context app = getApplicationContext()", authenticate)
        resume = body(MAIN, "private void resumeOfficialAttempt()")
        self.assertIn("work(\"正在连接…\", attempt::await", resume)
        for guard in ("this.officialAttempt != attempt", "connectionEntry.mode() != ConnectionEntryModel.Mode.OFFICIAL", "!attempt.proof.equals(draftFingerprint())"):
            self.assertLess(resume.index(guard), resume.index("this.verifiedOfficial = candidate"))
        self.assertLess(resume.index("this.verifiedOfficial = candidate"), resume.index("this.testedDraft = attempt.proof"))
        apply = body(MAIN, "private void applyConnection(")
        self.assertIn("ConnectionEntryModel.Mode.CHOICE) return", apply)
        self.assertIn("if (!z && this.connectionEntry.mode() != ConnectionEntryModel.Mode.CUSTOM) return", apply)

    def test_rotation_reattaches_one_memory_only_attempt_without_automatic_registration(self):
        retain = body(MAIN, "public Object onRetainNonConfigurationInstance()")
        self.assertIn("this.page != SyncPage.CONNECTION || this.connectionEntry == null", retain)
        self.assertLess(retain.index("captureDraft()"), retain.index("new RetainedConnectionUi(this)"))
        state = body(MAIN, "private static final class RetainedConnectionUi")
        self.assertIn("final ConnectionAuthAttempt<SecureConnectionStore.Connection> attempt", state)
        self.assertIn("attempt = activity.officialAttempt", state)
        self.assertIn("testedProof = activity.testedDraft", state)
        self.assertNotRegex(state, r"(?:final|private|public)\s+(?:MainActivity|Activity|Context|View)\s+\w+\s*[;=]")
        restore = body(MAIN, "private void restoreConnectionUi(")
        self.assertIn("this.officialAttempt = retained.attempt", restore)
        self.assertIn("this.testedDraft = retained.testedProof", restore)
        self.assertIn("this.page = SyncPage.CONNECTION", restore)
        create = body(MAIN, "public void onCreate(")
        self.assertLess(create.index("restoreConnectionUi(this.retainedConnectionUi)"), create.index("render()"))
        startup = body(MAIN, "void m69xebc6eee5(")
        reattach = body(startup, "if (this.retainedConnectionUi != null && canRequestSaveAccess())")
        self.assertLess(reattach.index("restoreConnectionUi(retained)"), reattach.index("resumeOfficialAttempt()"))
        for section in (retain, restore, reattach, body(MAIN, "private void resumeOfficialAttempt()")):
            for operation in ("new ConnectionAuthAttempt", "new OfficialServiceRegistrationClient", ".register(", ".login("):
                self.assertNotIn(operation, section)
        saved = body(MAIN, "protected void onSaveInstanceState(")
        for secret in ("draft", "official", "password", "tested", "proof", "attempt", "verified"):
            self.assertNotIn(secret, saved.lower())
        self.assertIn("setSaveEnabled(false)", body(MAIN, "private EditText field("))
        self.assertEqual(1, MAIN.count("new ConnectionAuthAttempt<>("))

    def test_failed_official_attempt_is_terminal_and_cleared_before_error_display(self):
        report = body(MAIN, "private void reportError(")
        clear = "if (this.officialAttempt != null && this.officialAttempt.isDone()) this.officialAttempt = null"
        self.assertIn(clear, report)
        self.assertLess(report.index(clear), report.index("this.feedbackError = true"))
        failure = body(MAIN, "void m149lambda$work$1$iogithubxyz1001pdsyncandroidMainActivity(")
        self.assertLess(failure.index("if (isDestroyed())"), failure.index("reportError(exc)"))
        self.assertIn("this.busy = false", failure)
        for operation in ("authenticateOfficial(", "resumeOfficialAttempt(", ".register(", ".login("):
            self.assertNotIn(operation, report + failure)

    def test_empty_layout_returns_before_all_normal_comparison_and_transfer_controls(self):
        render = body(MAIN, "private void renderComparison()")
        empty = body(render, "if (showEmptyCompletion())")
        self.assertIn('heading(this.feedbackError ? "4 / 4  配置尚未完成" : "4 / 4  配置完成")', empty)
        self.assertIn('if (!this.feedbackError) text("配置完成")', empty)
        self.assertEqual(["上一步", "完成"], re.findall(r'foot\("([^"]+)"', empty))
        self.assertIn("this::completeEmpty", empty)
        self.assertIn("navigate(SyncPage.CONNECTION)", empty)
        self.assertRegex(empty, r"return;\s*}$")
        self.assertLess(render.index(empty), render.index("text(comparisonCopy())"))
        for operation in ("versionChoice", "getLocalFileCount", "重新比较", "稍后处理", "上传", "恢复", ".upload(", ".restore", "sync()", "readComparison()"):
            self.assertNotIn(operation, empty)

    def test_empty_layout_and_completion_use_current_observation_guard(self):
        empty = body(MAIN, "private boolean showEmptyCompletion()")
        self.assertIn("AndroidUiFlowPolicy.showEmptyCompletion(", empty)
        for evidence in ("kind().name()", "this.cloudVerified", "getNeedsRestoreRecovery()", "isRetryablePending()"):
            self.assertIn(evidence, empty)
        complete = body(MAIN, "public void completeEmpty()")
        self.assertIn("AndroidUiFlowPolicy.canFinishEmpty(", complete)
        self.assertIn("this.page == SyncPage.COMPARISON && this.onboardingActive", complete)
        self.assertIn("this.busy", complete)
        self.assertLess(complete.index("AndroidUiFlowPolicy.canFinishEmpty("), complete.index("work("))

    def test_comparison_completion_is_exclusive_and_preserves_unavailable_actions(self):
        render = body(MAIN, "private void renderComparison()")
        empty = body(render, "if (showEmptyCompletion())")
        identical = body(render, "if (identical != AndroidUiFlowPolicy.IdenticalCompletion.NONE)")
        keep_guard = "if (inspection != null && inspection.getCanKeepLocalProgress())"
        ready_guard = "else if (inspection != null && inspection.getCanCompleteWithoutTransfer())"
        adopt_guard = "else if (!inspection2.getCanKeepLocalProgress() && !inspection2.getCanCompleteWithoutTransfer())"
        keep = body(render, keep_guard)
        ready = body(render, ready_guard)
        adopt = body(render, adopt_guard)

        # BOTH_EMPTY returns early; local-progress and SAME_TIP completion are
        # mutually exclusive. Adoption must not add a second completion button
        # when either earlier branch already supplied one.
        self.assertRegex(empty, r"return;\s*}$")
        self.assertIn('foot("完成", this::completeLocalProgress)', keep)
        self.assertIn('foot("完成", this::completeReadyComparison)', ready)
        self.assertRegex(render, re.escape(keep) + r"\s*" + re.escape(ready_guard))
        self.assertIn('foot("完成", new Runnable()', adopt)
        self.assertIn("MainActivity.this.adopt()", adopt)
        self.assertEqual(1, adopt.count('foot("完成"'))
        remainder = render
        for section in (empty, identical, keep, ready, adopt):
            remainder = remainder.replace(section, "", 1)
        self.assertNotIn('foot("完成"', remainder)

        # NONE/ANCESTOR adoption still exists, but null or non-adoptable
        # inspections take the original restore branch without dereferencing
        # inspection2. Missing cloud proof still returns before any choices.
        unavailable = body(render, "if (inspection2 == null || !inspection2.getCanAdoptRemote())")
        self.assertRegex(render, re.escape(unavailable) + r"\s*" + re.escape(adopt_guard))
        restore = body(unavailable, "if (inspection3 != null && !inspection3.getRemoteVersions().isEmpty())")
        self.assertIn('foot("恢复所选云端存档", this::requestRestore).setEnabled(restoreSupported() && this.selected != null && kind() != OnboardingKind.BLOCKED)', restore)
        unverified = body(render, "if (!this.cloudVerified && kind() != OnboardingKind.BLOCKED)")
        self.assertEqual(["上一步", "重新比较"], re.findall(r'foot\("([^"]+)"', unverified))
        self.assertRegex(unverified, r"return;\s*}$")
        self.assertLess(render.index(unverified), render.index(keep_guard))
        self.assertIn('foot("完成", this::completeEmpty).setEnabled(!this.busy && this.onboardingActive && this.connection != null)', empty)
        self.assertIn("this::upload).setEnabled(kind() != OnboardingKind.BLOCKED || this.inspection.isRetryablePending())", render)

    def test_exact_identical_state_renders_only_one_completion_before_any_choice(self):
        render = body(MAIN, "private void renderComparison()")
        identical = body(render, "if (identical != AndroidUiFlowPolicy.IdenticalCompletion.NONE)")
        self.assertEqual(["完成"], re.findall(r'foot\("([^\"]+)"', identical))
        self.assertIn("identical == AndroidUiFlowPolicy.IdenticalCompletion.CONFIRM", identical)
        self.assertIn("? this::completeReadyComparison : this::adopt", identical)
        self.assertRegex(identical, r"return;\s*}$")
        before = render[:render.index(identical)]
        for evidence in ("AndroidUiFlowPolicy.identicalCompletion(",
                         "kind() == null ? null : kind().name(), this.cloudVerified",
                         "observed.getNeedsRestoreRecovery()", "observed.isRetryablePending()",
                         "observed.getCanCompleteWithoutTransfer()", "observed.getCanAdoptRemote()"):
            self.assertIn(evidence, before)
        for remaining in ('text("本机 · "', "versionChoice(", "LinearLayout linearLayoutRow = row()",
                          '"上传本机，保留两份"', 'foot("恢复所选云端存档"', 'foot("稍后处理"'):
            self.assertLess(render.index(identical), render.index(remaining))
        self.assertNotIn("getLocalFileCount()", before[before.index("AndroidSyncFacade.Inspection observed"):])
        # Completion callbacks keep their original locked core revalidation;
        # rendering a single button cannot fabricate or advance a baseline.
        confirm = body(MAIN, "private void completeReadyComparison()")
        self.assertIn("owner.completeOnboarding(observed)", confirm)
        self.assertLess(confirm.index("owner.completeOnboarding(observed)"),
                        confirm.index("SyncRuntime.setOnboardingProgress"))
        self.assertIn("sync().adoptIdentical(inspection)",
                      body(MAIN, "AndroidSyncFacade.OperationResult m15lambda$adopt$72$"))

    def test_empty_finish_revalidates_and_records_only_a_real_no_transfer_decision(self):
        finish = body(MAIN, "Object m29xb53dc5d7()")
        self.assertLess(finish.index("readComparison()"), finish.index("showEmptyCompletion()"))
        self.assertLess(finish.index("EmptySetupCompletion.canFinish"), finish.index(".completeOnboarding(this.inspection)"))
        self.assertLess(finish.index(".completeOnboarding(this.inspection)"), finish.index("SyncRuntime.setOnboardingProgress"))
        self.assertIn("OnboardingProgress.COMPLETE", finish)
        for operation in (".upload(", ".restore", "createSnapshot", "createCommit"):
            self.assertNotIn(operation, finish)
        completed = body(MAIN, "void m30x2ab7ec18(")
        self.assertIn("this.onboardingActive = false", completed)
        self.assertIn("this.page = SyncPage.HOME", completed)

    def test_startup_keeps_pc_core_baseline_gate_before_home_selection(self):
        startup = body(MAIN, "Object m68x764cc8a4(")
        self.assertLess(startup.index("readComparison()"), startup.index("OnboardingGate.resumedProgress"))
        self.assertIn("syncHistory.getBaselineCommit() == null", startup)
        self.assertIn("this.progress = onboardingProgressResumedProgress", startup)
        route = body(MAIN, "void m69xebc6eee5(")
        self.assertIn("this.progress != OnboardingProgress.COMPLETE", route)
        self.assertIn("this.page = this.onboardingActive ? SyncPage.COMPARISON : SyncPage.HOME", route)

    def test_diagnostic_copy_is_created_only_inside_expanded_details(self):
        render = body(MAIN, "public void render()")
        expanded = body(render, "if (this.detailsExpanded)")
        self.assertIn("text(this.details)", expanded)
        self.assertIn("this::copyDiagnostic", expanded)
        self.assertNotIn("copyDiagnostic", render.replace(expanded, ""))
        self.assertNotIn("复制脱敏诊断", render)
        self.assertIn("!(this.page == SyncPage.COMPARISON && showEmptyCompletion() && !this.feedbackError)", render)
        self.assertIn("this.detailsExpanded = false", body(MAIN, "private void navigate("))

    def test_short_home_automatic_status_preserves_real_service_and_conflict_result(self):
        home = body(MAIN, "private void renderHome()")
        self.assertIn("automaticStatusBrief()", home)
        self.assertIn("if (!automaticStatus.isEmpty()) text(automaticStatus)", home)
        brief = body(MAIN, "private String automaticStatusBrief()")
        self.assertIn("AutoSyncService.status(this)", brief)
        self.assertIn('result.contains("HISTORY_CONFLICT")', brief)
        self.assertIn("有存档冲突，自动上传已跳过。", brief)
        self.assertIn("this.details = safeText(result)", brief)
        self.assertIn("return result;", brief)
        self.assertIn("SyncRuntime.isAutoUploadPaused(this)", brief)
        self.assertIn("this.progress != OnboardingProgress.COMPLETE", brief)
        for operation in (".upload(", "startService(", ".connect()", "refreshComparison("):
            self.assertNotIn(operation, brief)


if __name__ == "__main__":
    unittest.main()
