"""Source wiring checks only; session execution is covered by Java host tests, not device UI."""
import unittest
import re
from pathlib import Path
from test_embedded_install_contract import body

ROOT = Path(__file__).resolve().parents[2] / "main/java/io/github/xyz1001/pdsync/android"
MAIN = (ROOT / "MainActivity.java").read_text()
PANEL = (ROOT / "ManualPaymentPanel.java").read_text()
SESSION = (ROOT / "ManualPaymentSession.java").read_text()
COPY = (ROOT / "ManualPaymentCopy.java").read_text()
FLOW = (ROOT / "ManualPaymentFlow.java").read_text()
CLIENT = (ROOT / "ManualOrderClient.java").read_text()


class ManualPaymentUiContract(unittest.TestCase):
    def test_default_disabled_and_no_intent_or_credential_adapter(self):
        self.assertIn("private ManualPaymentSession sandboxPaymentSession;", MAIN)
        self.assertNotIn("new ManualPaymentSession(", MAIN)
        self.assertEqual(MAIN.count("showSandboxPaymentPreview("), 1)
        hook = body(MAIN, "void showSandboxPaymentPreview(")
        for guard in ("this.page != SyncPage.CONNECTION", "ConnectionEntryModel.Mode.OFFICIAL", "!session.view().sandbox"):
            self.assertIn(guard, hook)
        for forbidden in ("draftUser", "officialUser", "draftPassword", "officialPassword", "verifiedOfficial", "getIntent", "testedDraft = draftFingerprint", "authenticateOfficial"):
            self.assertNotIn(forbidden, hook)
        self.assertIn("captureDraft()", hook)
        self.assertIn("ManualPaymentSession.AuthenticatedIdentity identity", MAIN)
        self.assertIn("session.enter(identity)", hook)

    def test_only_official_child_changes(self):
        render = body(MAIN, "private void renderOfficialConnection()")
        self.assertLess(render.index("ManualPaymentPanel.render"), render.index('field("用户名"'))
        self.assertIn("return;", render[:render.index('field("用户名"')])
        for page in ("renderDiscovery", "renderPath", "renderComparison", "renderHome", "renderCustomConnection"):
            self.assertNotIn("ManualPayment", body(MAIN, "private void " + page + "()"))
        self.assertNotIn("showSandboxPaymentPreview", body(MAIN, "private void authenticateOfficial("))

    def test_exit_background_destroy_stop_and_do_not_grant_production_test_proof(self):
        self.assertIn("sandboxPaymentSession.foreground(false)", body(MAIN, "protected void onPause()"))
        self.assertIn("sandboxPaymentSession.foreground(true)", body(MAIN, "protected void onResume()"))
        self.assertIn("closeSandboxPayment()", body(MAIN, "protected void onDestroy()"))
        self.assertIn("closeSandboxPayment()", body(MAIN, "private void navigate("))
        self.assertIn("returnFromSandboxPayment()", body(MAIN, "private void backFromConnection()"))
        ret = body(MAIN, "private void returnFromSandboxPayment()")
        self.assertIn("this.testedDraft = null", ret)
        for forbidden in ("save(", "applyConnection", "refreshComparison", "authenticateOfficial", "verifiedOfficial ="):
            self.assertNotIn(forbidden, ret)

    def test_minimal_read_only_appreciation_page(self):
        self.assertIn('"沙箱"', PANEL)
        self.assertIn("session.paymentImage()", PANEL)
        self.assertIn("ManualPaymentCopy.expiry(view.entitlement)", PANEL)
        self.assertIn("ManualPaymentCopy.usernameRemark()", PANEL)
        for forbidden in ("remarkCode", "辅助编号", "amountMinor", "currency", "validity()",
                          "paymentInstructions", "paymentDisplayMatches", "cancellationConfirmation",
                          "我已赞赏", "待审核", "申报", "订单", "AlertDialog", "selectPlan",
                          "session.create", "session.submit", "session.cancel", "WebView", "https://"):
            self.assertNotIn(forbidden, PANEL)
        for source in (PANEL, COPY, FLOW, SESSION):
            for forbidden in ("我已赞赏", "待审核", "申报", "等待人工确认", "已续期"):
                self.assertNotIn(forbidden, source)
        for control in ('"测试连接"', '"刷新"', '"下一步"', "backLabel, back", "session.canContinue()"):
            self.assertIn(control, PANEL)
        self.assertNotIn("password", PANEL.lower())
        self.assertIn("new Date(entitlement.expiresMs)", COPY)
        for invented_time in ("currentTimeMillis", "plusYears", "plusDays", "calendar_year", "365", "259200"):
            self.assertNotIn(invented_time, COPY)

    def test_new_flow_has_only_get_service_calls_and_ignores_legacy_drafts(self):
        self.assertEqual(set(re.findall(r"client\.(\w+)\(", SESSION)),
                         {"plans", "paymentImage", "entitlement", "cancelPending"})
        actions = body(FLOW, "public enum Action")
        self.assertEqual({value.strip() for value in actions.strip("{} ").split(",")},
                         {"LOAD_PLANS", "LOAD_IMAGE", "LOAD_ENTITLEMENT", "DAV_TEST"})
        for forbidden in ("store.load(", "store.save(", "Action.CREATE", "Action.SUBMIT",
                          "Action.LOAD_MINE", "Action.STATUS", "Action.CANCEL", "ManualOrderClient.Order"):
            self.assertNotIn(forbidden, FLOW)
        self.assertIn('jsonRequest("/api/order/entitlement", null', body(CLIENT, "public Entitlement entitlement()"))
        self.assertIn('jsonRequest("/api/order/plans", null', body(CLIENT, "public List<Plan> plans()"))
        self.assertIn(".get().build()", body(CLIENT, "public byte[] paymentImage("))
        self.assertIn("Action.LOAD_IMAGE", body(FLOW, "public synchronized Ticket acceptPlans("))
        self.assertIn("entitlement.active", body(FLOW, "private boolean davEligible()"))

    def test_username_display_and_explicit_clipboard_use_typed_current_identity(self):
        self.assertIn("String username = session.authenticatedUsername()", PANEL)
        self.assertIn("if (username != null)", PANEL)
        self.assertIn('"用户名 " + username', PANEL)
        self.assertIn('"复制用户名", session.usernameCopyAction(', PANEL)
        self.assertIn('clipboard.setPrimaryClip(ClipData.newPlainText("用户名", currentUsername))', PANEL)
        self.assertIn("ManualPaymentCopy.usernameRemark()", PANEL)
        self.assertEqual(COPY.count('"赞赏时请备注用户名"'), 1)
        for forbidden in ("EditText", "getPrimaryClip", "setTextIsSelectable", "getIntent", "动态", "必须备注", "微信输入", "setPrimaryClip("):
            if forbidden == "setPrimaryClip(":
                self.assertEqual(PANEL.count(forbidden), 1)
            else:
                self.assertNotIn(forbidden, PANEL)
        identity = body(SESSION, "public static final class AuthenticatedIdentity")
        self.assertIn("private AuthenticatedIdentity(OfficialServiceRegistrationClient.Account account)", identity)
        self.assertIn("username = account.username", identity)
        for forbidden in ("password", "SecureConnectionStore", "String username)", "this.account ="):
            self.assertNotIn(forbidden, identity)
        entry = body(SESSION, "public void enter(")
        self.assertIn("identity == null || !view().sandbox", entry)
        self.assertNotIn("enter(String accountScope)", SESSION)
        action = body(SESSION, "public Runnable usernameCopyAction(")
        for check in ("epoch == attachment", "expected == identity", "username.equals(authenticatedUsername())"):
            self.assertIn(check, action)
        for signature in ("public void leave()", "@Override public void close()"):
            self.assertIn("identity = null", body(SESSION, signature))

    def test_lifecycle_callbacks_reject_stale_ticket_and_image_generations(self):
        for signature in ("private void accepted(", "private void failed("):
            callback = body(SESSION, signature)
            self.assertIn("!flow.isCurrent(ticket)", callback)
            self.assertIn("epoch != attachment", callback)
        self.assertIn("dispatch(flow.resume(ui.nowMillis()))", SESSION)
        self.assertIn("case LOAD_IMAGE:", body(SESSION, "private void accepted("))
        refresh = body(SESSION, "public void refresh()")
        self.assertLess(refresh.index("if (ticket == null) return"), refresh.index("clearImage()"))
        self.assertNotIn("imageTask", SESSION)
        self.assertIn("RejectedExecutionException", SESSION)
