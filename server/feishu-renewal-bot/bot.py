"""Strict Feishu event adapter and durable worker. No HTTP ingress or live defaults.

Only sdk_transport.build_long_connection registers accept_sdk_event in production.
Calling Python methods is a trusted in-process integration seam, not authentication.
"""
from contextlib import contextmanager
from dataclasses import dataclass, asdict
from datetime import datetime, timezone
import hashlib
import json
import re
import secrets
import sqlite3
import threading
import time
import uuid


USERNAME = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}", re.ASCII)
IDENTIFIER = re.compile(r"[A-Za-z0-9_-]{1,200}", re.ASCII)
LEASE_MS = 30_000
REPLY_WINDOW_MS = 55 * 60_000  # Safety margin below Feishu's one-hour UUID window.
MAX_ATTEMPTS = 8


def strict_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("duplicate JSON key")
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=pairs,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError("non-JSON number")))


def identifier(value):
    return type(value) is str and IDENTIFIER.fullmatch(value) is not None


@dataclass(frozen=True)
class Binding:
    enabled: bool = False
    app_id: str = ""
    tenant_key: str = ""
    chat_id: str = ""
    admin_open_ids: tuple = ()
    bot_open_id: str = ""

    def __post_init__(self):
        if type(self.enabled) is not bool:
            raise ValueError("enabled must be a boolean")
        if type(self.admin_open_ids) not in (list, tuple):
            raise ValueError("admin_open_ids must be a list")
        object.__setattr__(self, "admin_open_ids", tuple(self.admin_open_ids))
        if self.enabled and not self.complete():
            raise ValueError("enabled binding requires exact app, tenant, group, bot and admin open IDs")

    def complete(self):
        values = (self.app_id, self.tenant_key, self.chat_id, self.bot_open_id, *self.admin_open_ids)
        return (bool(self.admin_open_ids) and len(self.admin_open_ids) <= 32
                and all(identifier(x) and "REPLACE" not in x.upper() for x in values)
                and len(set(self.admin_open_ids)) == len(self.admin_open_ids)
                and self.bot_open_id not in self.admin_open_ids)

    @classmethod
    def load(cls, path):
        with open(path, encoding="utf-8") as handle:
            raw = handle.read(16_385)
        if len(raw) > 16_384:
            raise ValueError("binding file too large")
        data = strict_json(raw)
        if type(data) is not dict or set(data) - set(cls.__dataclass_fields__):
            raise ValueError("unknown binding fields; do not place credentials here")
        return cls(**data)

    def permits(self, job):
        return (self.enabled and self.complete()
                and job["app_id"] == self.app_id and job["tenant_key"] == self.tenant_key
                and job["chat_id"] == self.chat_id and job["sender_id"] in self.admin_open_ids
                and job["sender_id"] != self.bot_open_id)


@dataclass(frozen=True)
class Command:
    app_id: str
    tenant_key: str
    message_id: str
    chat_id: str
    sender_id: str
    username: str

    @property
    def key(self):
        raw = json.dumps([self.app_id, self.tenant_key, self.message_id], separators=(",", ":"))
        return hashlib.sha256(raw.encode()).hexdigest()


def parse_sdk_event(data, binding):
    """Parse a typed SDK event; never accept dict/JSON from an HTTP request.

    SDK-object shape alone is NOT proof of origin. Only the authenticated official
    WebSocket client may call this function in a deployed service.
    """
    if not binding.enabled or not binding.complete() or isinstance(data, dict):
        return None
    try:
        header, sender, message = data.header, data.event.sender, data.event.message
        open_id = sender.sender_id.open_id
        if (getattr(data, "schema", None) != "2.0"
                or header.event_type != "im.message.receive_v1"
                or header.app_id != binding.app_id or header.tenant_key != binding.tenant_key
                or sender.tenant_key != binding.tenant_key or sender.sender_type != "user"
                or open_id == binding.bot_open_id or open_id not in binding.admin_open_ids
                or message.chat_id != binding.chat_id or message.chat_type != "group"
                or message.message_type != "text" or getattr(message, "mentions", None)
                or not identifier(message.message_id)):
            return None
        content = message.content
        if type(content) is not str or len(content.encode("utf-8")) > 1024:
            return None
        decoded = strict_json(content)
        if (type(decoded) is not dict or set(decoded) != {"text"}
                or type(decoded["text"]) is not str or not USERNAME.fullmatch(decoded["text"])):
            return None
        return Command(header.app_id, header.tenant_key, message.message_id,
                       message.chat_id, open_id, decoded["text"])
    except (AttributeError, TypeError, ValueError, UnicodeError, RecursionError):
        return None


SCHEMA = """
CREATE TABLE IF NOT EXISTS feishu_bot_inbox (
 job_key TEXT PRIMARY KEY, app_id TEXT NOT NULL, tenant_key TEXT NOT NULL,
 message_id TEXT NOT NULL, chat_id TEXT NOT NULL, sender_id TEXT NOT NULL,
 username TEXT NOT NULL, created_ms INTEGER NOT NULL,
 state TEXT NOT NULL DEFAULT 'pending' CHECK(state IN ('pending','done','review')),
 attempts INTEGER NOT NULL DEFAULT 0, next_ms INTEGER NOT NULL,
 lease_token TEXT, lease_until_ms INTEGER, result_status TEXT, last_code TEXT,
 UNIQUE(app_id,tenant_key,message_id)
);
CREATE TABLE IF NOT EXISTS feishu_bot_outbox (
 reply_key TEXT PRIMARY KEY, job_key TEXT NOT NULL REFERENCES feishu_bot_inbox(job_key),
 phase TEXT NOT NULL, message_id TEXT NOT NULL, text TEXT NOT NULL, uuid TEXT NOT NULL UNIQUE,
 state TEXT NOT NULL DEFAULT 'pending' CHECK(state IN ('pending','sent','review','superseded')),
 attempts INTEGER NOT NULL DEFAULT 0, next_ms INTEGER NOT NULL,
 first_attempt_ms INTEGER, lease_token TEXT, lease_until_ms INTEGER,
 response_message_id TEXT, last_code TEXT, UNIQUE(job_key,phase)
);
"""


class InboxConflict(ValueError):
    pass


class DurableQueue:
    def __init__(self, path, *, clock=None):
        self.path = str(path)
        if self.path == ":memory:":
            raise ValueError("a durable database path is required")
        self.clock = clock or (lambda: time.time_ns() // 1_000_000)
        with self.connect() as db:
            db.executescript(SCHEMA)

    @contextmanager
    def connect(self):
        # Keep callback contention bounded well below the platform's response deadline.
        db = sqlite3.connect(self.path, timeout=0.5, isolation_level=None)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        try:
            yield db
        finally:
            db.close()

    def record(self, command):
        values = asdict(command)
        now = self.clock()
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            old = db.execute("SELECT * FROM feishu_bot_inbox WHERE job_key=?", (command.key,)).fetchone()
            if old:
                if any(old[name] != value for name, value in values.items()):
                    raise InboxConflict("MESSAGE_ID_BINDING_CONFLICT")
                return False
            db.execute("""INSERT INTO feishu_bot_inbox
                (job_key,app_id,tenant_key,message_id,chat_id,sender_id,username,created_ms,next_ms)
                VALUES(?,?,?,?,?,?,?,?,?)""", (command.key, *values.values(), now, now))
            db.commit()
        return True

    def _claim(self, table, key):
        assert (table, key) in (("feishu_bot_inbox", "job_key"), ("feishu_bot_outbox", "reply_key"))
        now, token = self.clock(), secrets.token_hex(16)
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute(f"""SELECT * FROM {table} WHERE state='pending' AND next_ms<=?
                AND (lease_until_ms IS NULL OR lease_until_ms<=?) ORDER BY next_ms,{key} LIMIT 1""",
                             (now, now)).fetchone()
            if not row:
                return None
            extra = ",first_attempt_ms=COALESCE(first_attempt_ms,?)" if key == "reply_key" else ""
            params = [token, now + LEASE_MS]
            if extra:
                params.append(now)
            db.execute(f"UPDATE {table} SET lease_token=?,lease_until_ms=?,attempts=attempts+1{extra} WHERE {key}=?",
                       (*params, row[key]))
            updated = dict(db.execute(f"SELECT * FROM {table} WHERE {key}=?", (row[key],)).fetchone())
            db.commit()
            return updated

    def claim_job(self):
        return self._claim("feishu_bot_inbox", "job_key")

    def claim_reply(self):
        return self._claim("feishu_bot_outbox", "reply_key")

    @staticmethod
    def _backoff(attempts):
        return min(300_000, 5_000 * 2 ** min(attempts - 1, 8))

    def finish_job(self, job, result):
        status, now = result.get("status"), self.clock()
        terminal = status in ("confirmed", "unknown_user")
        retryable = status in ("reserved", "applying", "retry_required")
        same_user = result.get("username") == job["username"]
        has_reservation = (same_user and identifier(result.get("operation_id"))
                           and type(result.get("target_ms")) is int and result["target_ms"] > 0)
        if status == "confirmed":
            target = result.get("target_ms")
            if not has_reservation:
                raise ValueError("INVALID_CONFIRMED_RESULT")
            end = datetime.fromtimestamp(target / 1000, timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
            text, phase = f"已续期，到期时间：{end}。", "final"
        elif status == "unknown_user":
            if not same_user:
                raise ValueError("INVALID_UNKNOWN_USER_RESULT")
            text, phase = "未知用户。", "final"
        elif retryable and has_reservation:
            text, phase = "续期已记录，待执行。", "pending"
        else:
            text, phase = "未完成续期，请检查。", "attention"
        state = "done" if terminal else "pending" if retryable and job["attempts"] < MAX_ATTEMPTS else "review"
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            changed = db.execute("""UPDATE feishu_bot_inbox SET state=?,result_status=?,last_code=?,
                next_ms=?,lease_token=NULL,lease_until_ms=NULL WHERE job_key=? AND lease_token=?""",
                (state, status if isinstance(status, str) else "invalid", "REVIEW_REQUIRED" if state == "review" else None,
                 now + self._backoff(job["attempts"]), job["job_key"], job["lease_token"])).rowcount
            if not changed:
                return False
            reply_key = job["job_key"] + ":" + phase
            reply_uuid = str(uuid.uuid5(uuid.NAMESPACE_URL, "feishu-renewal-reply:" + reply_key))
            db.execute("""INSERT OR IGNORE INTO feishu_bot_outbox
                (reply_key,job_key,phase,message_id,text,uuid,next_ms) VALUES(?,?,?,?,?,?,?)""",
                (reply_key, job["job_key"], phase, job["message_id"], text, reply_uuid, now))
            if terminal:
                # Never deliver an old "pending" update after a final result.
                # Keep ambiguous earlier attempts reviewable instead of erasing them.
                db.execute("""UPDATE feishu_bot_outbox
                    SET state=CASE WHEN first_attempt_ms IS NULL THEN 'superseded' ELSE 'review' END,
                    last_code=CASE WHEN first_attempt_ms IS NULL THEN NULL ELSE 'SUPERSEDED_REPLY_OUTCOME_UNKNOWN' END,
                    lease_token=NULL,lease_until_ms=NULL
                    WHERE job_key=? AND phase IN ('pending','attention') AND state='pending'""",
                    (job["job_key"],))
            db.commit()
            return True

    def defer_job(self, job):
        return self.finish_job(job, {"status": "retry_required"})

    def finish_reply(self, reply, *, response_id=None, review=False):
        now = self.clock()
        state = "sent" if response_id is not None else "review" if review or reply["attempts"] >= MAX_ATTEMPTS else "pending"
        with self.connect() as db:
            return db.execute("""UPDATE feishu_bot_outbox SET state=?,response_message_id=?,
                last_code=?,next_ms=?,lease_token=NULL,lease_until_ms=NULL WHERE reply_key=? AND lease_token=?""",
                (state, response_id, "REPLY_REVIEW_REQUIRED" if state == "review" else None,
                 now + self._backoff(reply["attempts"]), reply["reply_key"], reply["lease_token"])).rowcount == 1

    def job_for_reply(self, reply):
        with self.connect() as db:
            return dict(db.execute("SELECT * FROM feishu_bot_inbox WHERE job_key=?", (reply["job_key"],)).fetchone())

    def counts(self):
        with self.connect() as db:
            return {table: {r[0]: r[1] for r in db.execute(f"SELECT state,COUNT(*) FROM {table} GROUP BY state")}
                    for table in ("feishu_bot_inbox", "feishu_bot_outbox")}


class RenewalBot:
    def __init__(self, binding, queue, ledger, reply_transport):
        self.binding, self.queue = binding, queue
        self.ledger, self.reply_transport = ledger, reply_transport
        self.wakeup = threading.Event()
        self.stopped = threading.Event()

    def accept_sdk_event(self, event):
        command = parse_sdk_event(event, self.binding)
        if command is None:
            return False
        self.queue.record(command)  # Commit BEFORE callback returns/SDK acknowledges receipt.
        self.wakeup.set()
        return True

    def process_one(self):
        if not self.binding.enabled:
            return False
        job = self.queue.claim_job()
        if not job:
            return False
        if not self.binding.permits(job):
            self.queue.finish_job(job, {"status": "binding_changed"})
            return True
        if job["attempts"] > MAX_ATTEMPTS:
            self.queue.defer_job(job)
            return True
        try:
            result = self.ledger.renew(**{name: job[name] for name in Command.__dataclass_fields__})
            if type(result) is not dict:
                raise ValueError("INVALID_LEDGER_RESULT")
            self.queue.finish_job(job, result)
        except Exception:
            # Do not log remote response bodies, account data, tokens or raw messages.
            self.queue.defer_job(job)
        return True

    def reply_one(self):
        if not self.binding.enabled:
            return False
        reply = self.queue.claim_reply()
        if not reply:
            return False
        job = self.queue.job_for_reply(reply)
        if (not self.binding.permits(job)
                or (job["state"] == "done" and reply["phase"] != "final")
                or reply["attempts"] > MAX_ATTEMPTS
                or not 0 <= self.queue.clock() - reply["first_attempt_ms"] < REPLY_WINDOW_MS):
            self.queue.finish_reply(reply, review=True)
            return True
        try:
            response_id = self.reply_transport.send_reply(reply["message_id"], reply["text"], reply["uuid"])
            if not identifier(response_id):
                raise ValueError("UNKNOWN_REPLY_OUTCOME")
            self.queue.finish_reply(reply, response_id=response_id)
        except Exception:
            self.queue.finish_reply(reply)
        return True

    def run(self):
        """Drain existing jobs on startup, then bounded work per tick; no new event needed."""
        while not self.stopped.is_set():
            self.wakeup.clear()
            # The network-facing callback never executes ledger or reply I/O.
            try:
                did_work = self.process_one()
                did_work = self.reply_one() or did_work
            except sqlite3.Error:
                did_work = False  # Database failure is fail-closed; preserve persisted leases.
            if not did_work:
                self.wakeup.wait(1.0)

    def stop(self):
        self.stopped.set()
        self.wakeup.set()
