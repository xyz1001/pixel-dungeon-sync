"""Real queue + real ledger + real entitlement GET; external account/I/O are fake."""
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "manual-orders"))
from feishu_renewals import AccountIdentity, FeishuRenewals
from orders import Orders, Plan, extend_ms
from test_feishu_renewals import FakeAccounts, AccountEntitlements
from test_orders import TestAuth

from bot import DurableQueue, RenewalBot, LEASE_MS
from test_bot import FakeClock, FakeReplies, binding, event


class RealLedgerIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "shared-ledger.sqlite3"
        self.clock = FakeClock()
        self.accounts = FakeAccounts()
        self.entitlements = AccountEntitlements(self.accounts)
        self.replies = FakeReplies()
        self.restart()
        self.api = Orders(self.path, (Plan("sandbox", quota_bytes=100, upload_kbps=100, download_kbps=100),),
                          mode="sandbox", authenticator=TestAuth(), clock=self.clock)

    def restart(self):
        self.ledger = FeishuRenewals(self.path, enabled=True, accounts=self.accounts,
                                    entitlements=self.entitlements, clock=self.clock)
        self.queue = DurableQueue(self.path, clock=self.clock)
        self.bot = RenewalBot(binding(), self.queue, self.ledger, self.replies)

    def get_entitlement(self):
        code, body = self.api.dispatch("GET", "/api/order/entitlement", {"test-identity": "alice"})
        self.assertEqual(200, code)
        return body["data"]

    def rows(self):
        with self.ledger.ledger.connect() as db:
            return [dict(r) for r in db.execute("SELECT * FROM feishu_renewals ORDER BY message_id")]

    def test_real_ledger_duplicate_restart_reply_retry_and_second_message(self):
        self.assertIsNone(self.get_entitlement()["expires_ms"])
        self.bot.accept_sdk_event(event()); self.bot.process_one()
        target = self.rows()[0]["target_ms"]
        operation = self.rows()[0]["operation_id"]
        self.assertEqual("confirmed", self.rows()[0]["state"])
        self.assertEqual(extend_ms(self.clock(), "calendar_year", 0), target)
        self.assertEqual(target, self.get_entitlement()["expires_ms"])
        self.assertEqual(target, self.entitlements.observed_account_expiry(self.accounts.identities["alice"]))
        self.replies.fail = True; self.bot.reply_one()
        self.restart(); self.clock.advance()
        self.bot.accept_sdk_event(event()); self.assertFalse(self.bot.process_one())
        self.replies.fail = False; self.bot.reply_one()
        self.assertEqual(1, len(self.entitlements.operations))
        self.assertEqual(operation, self.rows()[0]["operation_id"])
        self.assertEqual(target, self.get_entitlement()["expires_ms"])
        self.assertEqual(self.replies.calls[0][2], self.replies.calls[1][2])
        self.bot.accept_sdk_event(event("om_two")); self.bot.process_one()
        self.assertEqual(extend_ms(target, "calendar_year", 0), self.get_entitlement()["expires_ms"])
        self.assertEqual(2, len(self.entitlements.operations))

    def test_unknown_user_stays_unknown_on_replay_after_later_account_creation(self):
        self.bot.accept_sdk_event(event(username="missing")); self.bot.process_one(); self.bot.reply_one()
        self.accounts.identities["missing"] = AccountIdentity("missing", "later-account")
        self.restart(); self.bot.accept_sdk_event(event(username="missing")); self.bot.process_one()
        self.assertEqual("unknown_user", self.rows()[0]["state"])
        self.assertEqual({}, self.entitlements.operations)
        self.assertIsNone(self.get_entitlement()["expires_ms"])
        self.assertEqual("未知用户。", self.replies.calls[0][1])

    def test_unconfirmed_remote_write_never_grants_get_until_same_id_recovery(self):
        self.entitlements.fail_after = True
        self.bot.accept_sdk_event(event()); self.bot.process_one(); self.bot.reply_one()
        target = self.rows()[0]["target_ms"]
        self.assertEqual("applying", self.rows()[0]["state"])
        self.assertIsNone(self.get_entitlement()["expires_ms"])
        self.assertEqual("续期已记录，待执行。", self.replies.calls[-1][1])
        self.restart(); self.clock.advance(); self.bot.process_one(); self.bot.reply_one()
        self.assertEqual("confirmed", self.rows()[0]["state"])
        self.assertEqual(target, self.get_entitlement()["expires_ms"])
        self.assertEqual(1, len(self.entitlements.operations))

    def test_crash_between_real_grant_commit_and_outbox_commit(self):
        self.bot.accept_sdk_event(event())
        with patch.object(self.queue, "finish_job", side_effect=SystemExit("crash")):
            with self.assertRaises(SystemExit):
                self.bot.process_one()
        target = self.get_entitlement()["expires_ms"]
        self.restart(); self.clock.advance(LEASE_MS + 1); self.bot.process_one(); self.bot.reply_one()
        self.assertEqual(target, self.get_entitlement()["expires_ms"])
        self.assertEqual(1, len(self.entitlements.operations))
        self.assertEqual(1, len(self.rows()))

    def test_missing_account_adapter_does_not_claim_reserved_or_confirmed(self):
        self.bot.ledger = FeishuRenewals(self.path, enabled=True, entitlements=self.entitlements, clock=self.clock)
        self.bot.accept_sdk_event(event()); self.bot.process_one(); self.bot.reply_one()
        self.assertEqual([], self.rows())
        self.assertIsNone(self.get_entitlement()["expires_ms"])
        self.assertEqual("未完成续期，请检查。", self.replies.calls[0][1])


if __name__ == "__main__":
    unittest.main()
