"""Account-free host tests. No real SDK connection or remote grant is made."""
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace as NS
from unittest.mock import patch
import copy
import json
import sqlite3
import tempfile
import threading
import unittest
import uuid

from bot import (Binding, Command, DurableQueue, InboxConflict, RenewalBot,
                 LEASE_MS, MAX_ATTEMPTS, REPLY_WINDOW_MS, parse_sdk_event)


def binding():
    return Binding(True, "cli_app", "tenant_one", "oc_group", ("ou_admin",), "ou_bot")


def event(message_id="om_one", username="alice"):
    # This structure mirrors the official SDK; a Python object is not event authentication.
    return NS(schema="2.0", header=NS(app_id="cli_app", tenant_key="tenant_one",
              event_type="im.message.receive_v1", event_id="header_event_not_a_command_key"),
              event=NS(sender=NS(sender_type="user", tenant_key="tenant_one", sender_id=NS(open_id="ou_admin")),
                       message=NS(message_id=message_id, chat_id="oc_group", chat_type="group",
                                  message_type="text", content=json.dumps({"text": username}), mentions=[])))


class FakeClock:
    def __init__(self):
        self.now = 1_800_000_000_000
    def __call__(self):
        return self.now
    def advance(self, ms=300_001):
        self.now += ms


class FakeLedger:
    """Only for queue tests. Actual ledger integration is covered separately."""
    enabled = True
    def __init__(self):
        self.calls = []
        self.saved = {}
        self.grants = 0
        self.lock = threading.Lock()
        self.status = "confirmed"
    def renew(self, **kw):
        with self.lock:
            self.calls.append(kw)
            key = (kw["app_id"], kw["tenant_key"], kw["message_id"])
            if key in self.saved:
                return self.saved[key]
            if kw["username"] == "missing":
                result = dict(status="unknown_user", username="missing")
                self.saved[key] = result
                return result
            if self.status != "confirmed":
                return dict(status=self.status, username=kw["username"], operation_id="feishu_reserved",
                            target_ms=1_831_536_000_000)
            self.grants += 1
            result = dict(status="confirmed", username=kw["username"],
                          operation_id=kw["message_id"], target_ms=1_800_000_000_000 + self.grants * 31_536_000_000)
            self.saved[key] = result
            return result


class FakeReplies:
    def __init__(self):
        self.calls = []
        self.fail = False
    def send_reply(self, message_id, text, request_uuid):
        uuid.UUID(request_uuid)
        self.calls.append((message_id, text, request_uuid))
        if self.fail:
            raise TimeoutError("possibly sent; don't assume failure")
        return "om_reply"


class ParserTests(unittest.TestCase):
    def test_exact_username_and_real_message_id(self):
        command = parse_sdk_event(event(username="Alice.foo-1_"), binding())
        self.assertEqual("Alice.foo-1_", command.username)
        self.assertEqual("om_one", command.message_id)

    def test_missing_configuration_disables_by_default(self):
        self.assertIsNone(parse_sdk_event(event(), Binding()))
        for key, value in (("app_id", ""), ("tenant_key", ""), ("chat_id", ""),
                           ("admin_open_ids", ()), ("bot_open_id", ""),
                           ("bot_open_id", "ou_admin"), ("admin_open_ids", ("ou_admin", "ou_admin"))):
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                replace(binding(), **{key: value})
        with self.assertRaises(ValueError):
            replace(binding(), enabled="true")

    def test_untrusted_or_other_event_fields_are_ignored(self):
        mutations = [("schema", "1.0"), ("header.app_id", "cli_attacker"),
                     ("header.tenant_key", "other"), ("header.event_type", "other.event"),
                     ("event.sender.tenant_key", "other"), ("event.sender.sender_type", "app"),
                     ("event.sender.sender_id.open_id", "ou_stranger"),
                     ("event.sender.sender_id.open_id", "ou_bot"),
                     ("event.message.chat_id", "oc_other"), ("event.message.chat_type", "p2p"),
                     ("event.message.message_type", "post"), ("event.message.message_id", ""),
                     ("event.message.message_id", None), ("event.message.mentions", [NS(id="ou_bot")])]
        for path, value in mutations:
            with self.subTest(path=path, value=value):
                data = event()
                node, *parts = path.split(".")
                obj = data
                for part in path.split(".")[:-1]:
                    obj = getattr(obj, part)
                setattr(obj, path.split(".")[-1], value)
                self.assertIsNone(parse_sdk_event(data, binding()))
        self.assertIsNone(parse_sdk_event({"event": "forged HTTP JSON"}, binding()))
        self.assertIsNone(parse_sdk_event(NS(), binding()))

    def test_strict_text_json_no_trim_no_multiple_commands_no_identity_claims(self):
        bad = ['{"text":"alice","text":"bob"}', '{"text":"alice","open_id":"ou_admin"}',
               '{"text":true}', '{"text":null}', '{"text":["alice"]}', '["alice"]', '{}',
               '{"text":"alice",}', '{"text":NaN}', 'not-json', '{"text":"alice"} {}']
        bad += [json.dumps({"text": x}) for x in (" alice", "alice ", "alice\n", "alice bob",
                "alice,bob", "/renew alice", "@bot alice", "我是管理员 alice", "", "a" * 65, "ali\x00ce", "ali\u200bce")]
        bad.append('{"text":"' + 'a' * 2000 + '"}')
        for content in bad:
            with self.subTest(content=content[:80]):
                data = event(); data.event.message.content = content
                self.assertIsNone(parse_sdk_event(data, binding()))

    def test_example_config_is_disabled_and_has_no_secret(self):
        result = Binding.load(Path(__file__).with_name("binding.example.json"))
        self.assertFalse(result.enabled)
        self.assertFalse(result.complete())
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "binding.json"
            for raw in ('{"enabled":false,"enabled":true}', '{"app_secret":"notallowed"}'):
                path.write_text(raw)
                with self.assertRaises(ValueError):
                    Binding.load(path)


class QueueTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "jobs.sqlite3"
        self.clock = FakeClock()
        self.queue = DurableQueue(self.path, clock=self.clock)
        self.ledger, self.replies = FakeLedger(), FakeReplies()
        self.bot = RenewalBot(binding(), self.queue, self.ledger, self.replies)

    def restart(self):
        self.queue = DurableQueue(self.path, clock=self.clock)
        self.bot = RenewalBot(binding(), self.queue, self.ledger, self.replies)

    def inbox(self):
        with self.queue.connect() as db:
            return [dict(r) for r in db.execute("SELECT * FROM feishu_bot_inbox")]

    def outbox(self):
        with self.queue.connect() as db:
            return [dict(r) for r in db.execute("SELECT * FROM feishu_bot_outbox ORDER BY reply_key")]

    def test_callback_only_commits_and_never_runs_slow_io(self):
        self.assertTrue(self.bot.accept_sdk_event(event()))
        self.assertEqual([], self.ledger.calls)
        self.assertEqual([], self.replies.calls)
        self.assertEqual("pending", self.inbox()[0]["state"])

    def test_persistence_failure_propagates_for_sdk_error_ack(self):
        with patch.object(self.queue, "record", side_effect=sqlite3.OperationalError("database busy")):
            with self.assertRaises(sqlite3.OperationalError):
                self.bot.accept_sdk_event(event())
        self.assertEqual([], self.ledger.calls)

    def test_duplicate_event_restart_and_reply_failure_do_not_grant_again(self):
        self.bot.accept_sdk_event(event())
        self.bot.accept_sdk_event(event())
        self.bot.process_one()
        self.replies.fail = True
        self.bot.reply_one()
        initial_uuid = self.replies.calls[0][2]
        self.restart(); self.clock.advance()
        self.bot.accept_sdk_event(event())
        self.assertFalse(self.bot.process_one())
        self.replies.fail = False
        self.bot.reply_one()
        self.assertEqual(1, self.ledger.grants)
        self.assertEqual(1, len(self.ledger.calls))
        self.assertEqual(initial_uuid, self.replies.calls[-1][2])
        self.assertEqual("sent", self.outbox()[0]["state"])
        self.assertIn("UTC", self.replies.calls[-1][1])

    def test_new_message_same_username_is_separate_renewal(self):
        for mid in ("om_one", "om_two"):
            self.bot.accept_sdk_event(event(mid)); self.bot.process_one()
        self.assertEqual(2, self.ledger.grants)
        self.assertEqual(2, len(self.inbox()))

    def test_conflicting_message_id_is_not_rebound(self):
        self.bot.accept_sdk_event(event())
        with self.assertRaises(InboxConflict):
            self.bot.accept_sdk_event(event(username="bob"))
        self.bot.process_one()
        self.assertEqual("alice", self.ledger.calls[0]["username"])

    def test_unknown_user_is_persistent_and_never_creates_account(self):
        self.bot.accept_sdk_event(event(username="missing")); self.bot.process_one(); self.bot.reply_one()
        self.restart(); self.bot.accept_sdk_event(event(username="missing"))
        self.assertFalse(self.bot.process_one())
        self.assertEqual(0, self.ledger.grants)
        self.assertEqual("未知用户。", self.replies.calls[0][1])

    def test_crash_after_ledger_commit_resumes_same_operation(self):
        self.bot.accept_sdk_event(event())
        with patch.object(self.queue, "finish_job", side_effect=SystemExit("process died")):
            with self.assertRaises(SystemExit):
                self.bot.process_one()
        self.assertEqual(1, self.ledger.grants)
        self.restart(); self.clock.advance(LEASE_MS + 1)
        self.bot.process_one(); self.bot.reply_one()
        self.assertEqual(1, self.ledger.grants)
        self.assertEqual(self.ledger.calls[0], self.ledger.calls[1])
        self.assertEqual("done", self.inbox()[0]["state"])

    def test_reserved_never_reports_confirmed_and_progresses_without_new_message(self):
        self.ledger.status = "reserved"
        self.bot.accept_sdk_event(event()); self.bot.process_one(); self.bot.reply_one()
        self.assertEqual("续期已记录，待执行。", self.replies.calls[0][1])
        self.assertNotIn("到期", self.replies.calls[0][1])
        self.restart(); self.clock.advance()
        self.ledger.status = "confirmed"
        self.bot.process_one(); self.bot.reply_one()
        self.assertIn("已续期", self.replies.calls[-1][1])
        self.assertEqual(1, self.ledger.grants)

    def test_startup_background_loop_drains_persisted_job(self):
        self.bot.accept_sdk_event(event()); self.restart()
        completed = threading.Event()
        def sent(*args):
            completed.set()
            return "om_reply"
        self.replies.send_reply = sent
        worker = threading.Thread(target=self.bot.run)
        worker.start()
        try:
            self.assertTrue(completed.wait(2))
        finally:
            self.bot.stop(); worker.join(2)
        self.assertFalse(worker.is_alive())
        self.assertEqual(1, self.ledger.grants)

    def test_reply_ambiguous_beyond_uuid_window_requires_review(self):
        self.bot.accept_sdk_event(event()); self.bot.process_one()
        self.replies.fail = True; self.bot.reply_one()
        self.restart(); self.clock.advance(REPLY_WINDOW_MS + 1)
        self.bot.reply_one()
        self.assertEqual(1, len(self.replies.calls))
        self.assertEqual("review", self.outbox()[0]["state"])
        self.assertEqual(1, self.ledger.grants)

    def test_reply_crash_after_send_reuses_uuid_within_window(self):
        self.bot.accept_sdk_event(event()); self.bot.process_one()
        with patch.object(self.queue, "finish_reply", side_effect=SystemExit("died after send")):
            with self.assertRaises(SystemExit):
                self.bot.reply_one()
        self.restart(); self.clock.advance(LEASE_MS + 1); self.bot.reply_one()
        self.assertEqual(2, len(self.replies.calls))
        self.assertEqual(self.replies.calls[0], self.replies.calls[1])
        self.assertEqual(1, self.ledger.grants)

    def test_failed_intermediate_reply_never_retries_after_final_confirmation(self):
        self.ledger.status = "reserved"
        self.bot.accept_sdk_event(event()); self.bot.process_one()
        self.replies.fail = True; self.bot.reply_one()
        self.assertEqual("续期已记录，待执行。", self.replies.calls[-1][1])
        self.clock.advance(); self.ledger.status = "confirmed"
        self.bot.process_one()
        self.replies.fail = False; self.bot.reply_one()
        self.assertIn("已续期", self.replies.calls[-1][1])
        self.clock.advance(); self.assertFalse(self.bot.reply_one())
        self.assertEqual(2, len(self.replies.calls))
        intermediate = next(row for row in self.outbox() if row["phase"] == "pending")
        self.assertEqual("review", intermediate["state"])
        self.assertEqual("SUPERSEDED_REPLY_OUTCOME_UNKNOWN", intermediate["last_code"])

    def test_bounded_retry_exposes_review_without_resubmitting_message(self):
        self.ledger.status = "applying"
        self.bot.accept_sdk_event(event())
        for _ in range(MAX_ATTEMPTS + 2):
            self.bot.process_one(); self.clock.advance()
        self.assertEqual(MAX_ATTEMPTS, len(self.ledger.calls))
        self.assertEqual("review", self.inbox()[0]["state"])
        self.assertEqual(1, len(self.outbox()))
        self.assertEqual(0, self.ledger.grants)

    def test_unreserved_error_never_claims_renewal_recorded(self):
        for status in ("disabled", "conflict", "rejected", "binding_changed", "unexpected", "retry_required"):
            with self.subTest(status=status):
                self.bot.accept_sdk_event(event("om_" + status))
                job = self.queue.claim_job()
                self.queue.finish_job(job, {"status": status, "username": "alice"})
                self.bot.reply_one()
                self.assertEqual("未完成续期，请检查。", self.replies.calls[-1][1])

    def test_mismatched_unknown_or_missing_operation_cannot_report_success(self):
        self.bot.accept_sdk_event(event())
        job = self.queue.claim_job()
        for result in ({"status": "unknown_user", "username": "bob"},
                       {"status": "confirmed", "username": "alice", "target_ms": self.clock()}):
            with self.assertRaises(ValueError):
                self.queue.finish_job(job, result)
        self.assertEqual([], self.outbox())

    def test_changed_or_disabled_binding_blocks_persisted_jobs_and_replies(self):
        self.bot.accept_sdk_event(event())
        self.bot.binding = replace(binding(), enabled=False)
        self.assertFalse(self.bot.process_one())
        self.bot.binding = replace(binding(), admin_open_ids=("ou_other",))
        self.bot.process_one(); self.bot.reply_one()
        self.assertEqual([], self.ledger.calls)
        self.assertEqual([], self.replies.calls)
        self.assertEqual("review", self.inbox()[0]["state"])

    def test_concurrent_connections_claim_one_durable_message(self):
        def insert(_):
            queue = DurableQueue(self.path, clock=self.clock)
            queue.record(parse_sdk_event(event(), binding()))
        with ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(insert, range(16)))
        def process(_):
            other = RenewalBot(binding(), DurableQueue(self.path, clock=self.clock), self.ledger, self.replies)
            return other.process_one()
        with ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(process, range(16)))
        self.assertEqual(1, sum(results))
        self.assertEqual(1, self.ledger.grants)
        self.assertEqual(1, len(self.inbox()))

    def test_stale_worker_cannot_ack_newer_lease(self):
        self.bot.accept_sdk_event(event())
        old = self.queue.claim_job(); self.clock.advance(LEASE_MS + 1)
        fresh = self.queue.claim_job()
        self.assertFalse(self.queue.finish_job(old, {"status": "unknown_user", "username": "alice"}))
        self.assertTrue(self.queue.finish_job(fresh, {"status": "unknown_user", "username": "alice"}))
        self.assertEqual("done", self.inbox()[0]["state"])


if __name__ == "__main__":
    unittest.main()
