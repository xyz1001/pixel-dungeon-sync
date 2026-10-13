"""Sandbox-only order foundation. No listener, credentials, or production adapters."""
from contextlib import contextmanager
from dataclasses import dataclass, asdict
from datetime import datetime, timedelta, timezone
import re
import secrets
import sqlite3
import time


class OrderError(Exception):
    def __init__(self, status, code):
        super().__init__(code)
        self.status, self.code = status, code


@dataclass(frozen=True)
class Principal:
    # Construct only inside a trusted authenticator, never from request JSON.
    subject: str
    role: str = 'user'


class DenyAuthentication:
    def authenticate(self, headers):
        raise OrderError(401, 'AUTHENTICATION_REQUIRED')


class DisabledEntitlements:
    def ensure(self, username, expires_at_ms, operation_id):
        raise OrderError(503, 'ENTITLEMENT_ADAPTER_DISABLED')

    def observed_expiry(self, username):
        raise OrderError(503, 'ENTITLEMENT_ADAPTER_DISABLED')


class DisabledNotifications:
    def review_requested(self, order_id, remark_code):
        pass


@dataclass(frozen=True)
class Plan:
    id: str
    amount_minor: None = None
    currency: None = None
    term: str = 'calendar_year'
    expiry_policy: str = 'sandbox_upload_only'
    payment_mode: str = 'donation'
    days: int = 0
    quota_bytes: int = None
    upload_kbps: int = None
    download_kbps: int = None

    def validate(self):
        if not isinstance(self.id, str) or not re.fullmatch(r'[a-z0-9_-]{1,48}', self.id):
            raise ValueError('invalid plan id')
        if self.amount_minor is not None or self.currency is not None or self.payment_mode != 'donation':
            raise ValueError('donation offers have no quoted price or currency')
        if any(type(x) is not int or x <= 0 for x in (self.quota_bytes, self.upload_kbps, self.download_kbps)):
            raise ValueError('explicit positive sandbox limits required')
        if self.expiry_policy != 'sandbox_upload_only':
            raise ValueError('production expiry policy is not implemented')
        if self.term != 'calendar_year' or type(self.days) is not int or self.days != 0:
            raise ValueError('donation grants exactly one UTC calendar year')


def extend_ms(base_ms, term, days):
    """UTC calendar anniversary; leap day clamps to Feb 28. Fixed days are 24h."""
    base = datetime.fromtimestamp(base_ms / 1000, timezone.utc)
    if term == 'calendar_year':
        try:
            end = base.replace(year=base.year + 1)
        except ValueError:
            end = base.replace(year=base.year + 1, day=28)
    else:
        end = base + timedelta(days=days)
    return int(end.timestamp() * 1000)


SCHEMA = """
CREATE TABLE IF NOT EXISTS orders (
 id TEXT PRIMARY KEY, owner TEXT NOT NULL, request_key TEXT NOT NULL,
 plan_id TEXT NOT NULL, amount_minor INTEGER, currency TEXT,
 actual_amount_minor INTEGER, actual_currency TEXT,
 term TEXT NOT NULL, days INTEGER NOT NULL, expiry_policy TEXT NOT NULL,
 quota_bytes INTEGER NOT NULL, upload_kbps INTEGER NOT NULL, download_kbps INTEGER NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('created','review','applying','approved','cancelled','rejected')),
 remark_code TEXT NOT NULL, created_ms INTEGER NOT NULL, target_ms INTEGER,
 reviewer TEXT, channel TEXT, receipt_ref TEXT, attempts INTEGER NOT NULL DEFAULT 0,
 lease_token TEXT, lease_until_ms INTEGER,
 UNIQUE(owner,request_key), UNIQUE(channel,receipt_ref)
);
CREATE UNIQUE INDEX IF NOT EXISTS active_remark ON orders(remark_code)
 WHERE state IN ('created','review','applying');
CREATE UNIQUE INDEX IF NOT EXISTS one_applying_per_owner ON orders(owner)
 WHERE state='applying';
CREATE UNIQUE INDEX IF NOT EXISTS one_pending_per_owner ON orders(owner)
 WHERE state IN ('created','review','applying');
CREATE TABLE IF NOT EXISTS entitlements (
 owner TEXT PRIMARY KEY, expires_ms INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS audit (
 seq INTEGER PRIMARY KEY, order_id TEXT NOT NULL, actor TEXT NOT NULL,
 event TEXT NOT NULL, at_ms INTEGER NOT NULL
);
"""


class Orders:
    def __init__(self, path, plans=(), *, mode='disabled', authenticator=None,
                 entitlements=None, notifications=None, clock=None):
        if mode not in ('disabled', 'sandbox'):
            raise ValueError('live collection is unavailable in phase one')
        self.mode = mode
        self.plans = {p.id: p for p in plans}
        if len(self.plans) != len(plans):
            raise ValueError('duplicate plan')
        for p in plans:
            p.validate()
        if mode == 'sandbox' and not plans:
            raise ValueError('sandbox requires explicit plans')
        self.path = str(path)
        self.auth = authenticator or DenyAuthentication()
        self.adapter = entitlements or DisabledEntitlements()
        self.notifications = notifications or DisabledNotifications()
        self.clock = clock or (lambda: time.time_ns() // 1_000_000)
        with self.connect() as db:
            columns = {r['name']: r for r in db.execute('PRAGMA table_info(orders)')}
            if columns and ('actual_amount_minor' not in columns or columns['amount_minor']['notnull']):
                raise ValueError('donation contract requires a fresh sandbox database; no automatic migration')
            db.executescript(SCHEMA)

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=10, isolation_level=None)
        db.row_factory = sqlite3.Row
        try:
            yield db
        finally:
            db.close()

    @contextmanager
    def transaction(self):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            try:
                yield db
                db.execute('COMMIT')
            except BaseException:
                db.execute('ROLLBACK')
                raise

    def audit(self, db, order, actor, event):
        db.execute('INSERT INTO audit(order_id,actor,event,at_ms) VALUES(?,?,?,?)',
                   (order, actor, event, self.clock()))

    @staticmethod
    def public(row):
        return {k: row[k] for k in ('id', 'plan_id', 'amount_minor', 'currency',
                'actual_amount_minor', 'actual_currency', 'term', 'days', 'quota_bytes', 'upload_kbps', 'download_kbps', 'state', 'remark_code', 'created_ms', 'target_ms')}

    @staticmethod
    def owned(db, order_id, principal):
        row = db.execute('SELECT * FROM orders WHERE id=?', (order_id,)).fetchone()
        if row is None or (principal.role != 'admin' and row['owner'] != principal.subject):
            raise OrderError(404, 'ORDER_NOT_FOUND')
        return row

    def create(self, principal, plan_id, request_key):
        if not isinstance(request_key, str) or not re.fullmatch(r'[A-Za-z0-9_-]{16,80}', request_key):
            raise OrderError(400, 'INVALID_REQUEST_KEY')
        with self.transaction() as db:
            old = db.execute('SELECT * FROM orders WHERE owner=? AND request_key=?',
                             (principal.subject, request_key)).fetchone()
            if old:
                if old['plan_id'] != plan_id:
                    raise OrderError(409, 'IDEMPOTENCY_CONFLICT')
                return self.public(old)
            plan = self.plans.get(plan_id)
            if plan is None:
                raise OrderError(400, 'UNKNOWN_PLAN')
            if db.execute("SELECT 1 FROM orders WHERE owner=? AND state IN ('created','review','applying')", (principal.subject,)).fetchone():
                raise OrderError(409, 'PENDING_ORDER_EXISTS')
            used = {r[0] for r in db.execute("SELECT remark_code FROM orders WHERE state IN ('created','review','applying')")}
            available = [f'{n:04d}' for n in range(10000) if f'{n:04d}' not in used]
            if not available:
                raise OrderError(503, 'REMARK_CODES_EXHAUSTED')
            oid = secrets.token_hex(16)
            db.execute('INSERT INTO orders(id,owner,request_key,plan_id,amount_minor,currency,term,days,expiry_policy,quota_bytes,upload_kbps,download_kbps,state,remark_code,created_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
                       (oid, principal.subject, request_key, plan.id, plan.amount_minor,
                        plan.currency, plan.term, plan.days, plan.expiry_policy, plan.quota_bytes,
                        plan.upload_kbps, plan.download_kbps, 'created',
                        secrets.choice(available), self.clock()))
            self.audit(db, oid, principal.subject, 'created')
            return self.public(self.owned(db, oid, principal))

    def submit(self, principal, oid):
        notify = False
        with self.transaction() as db:
            row = self.owned(db, oid, principal)
            if row['state'] == 'created':
                db.execute("UPDATE orders SET state='review' WHERE id=?", (oid,))
                self.audit(db, oid, principal.subject, 'payment_claimed')
                notify = True
            elif row['state'] not in ('review', 'applying', 'approved'):
                raise OrderError(409, 'INVALID_STATE')
            result = self.public(self.owned(db, oid, principal))
        if notify:
            try:
                self.notifications.review_requested(oid, result['remark_code'])
            except Exception:
                # Notification loss never loses the durable review queue.
                with self.transaction() as db:
                    self.audit(db, oid, 'system', 'notification_failed')
        return result

    def close(self, principal, oid, state):
        with self.transaction() as db:
            row = self.owned(db, oid, principal)
            if row['state'] == state:
                return self.public(row)
            if row['state'] not in ('created', 'review'):
                raise OrderError(409, 'INVALID_STATE')
            db.execute('UPDATE orders SET state=? WHERE id=?', (state, oid))
            self.audit(db, oid, principal.subject, state)
            return self.public(self.owned(db, oid, principal))

    def approve(self, principal, oid, receipt_ref, channel, actual_amount_minor, actual_currency):
        if type(actual_amount_minor) is not int or not 0 < actual_amount_minor <= 100_000_000:
            raise OrderError(400, 'INVALID_ACTUAL_AMOUNT')
        if not isinstance(actual_currency, str) or not re.fullmatch(r'[A-Z]{3}', actual_currency):
            raise OrderError(400, 'INVALID_ACTUAL_CURRENCY')
        if channel not in ('sandbox', 'wechat', 'alipay'):
            raise OrderError(400, 'INVALID_CHANNEL')
        if not isinstance(receipt_ref, str) or not re.fullmatch(r'[A-Za-z0-9_-]{8,100}', receipt_ref):
            raise OrderError(400, 'INVALID_RECEIPT_REFERENCE')
        # Phase 1 durably reserves an ABSOLUTE target before external I/O.
        with self.transaction() as db:
            row = self.owned(db, oid, principal)
            if row['state'] in ('applying', 'approved'):
                if (row['receipt_ref'] != receipt_ref or row['channel'] != channel or
                    row['actual_amount_minor'] != actual_amount_minor or row['actual_currency'] != actual_currency):
                    raise OrderError(409, 'APPROVAL_CONFLICT')
            elif row['state'] == 'review':
                if db.execute('SELECT 1 FROM orders WHERE channel=? AND receipt_ref=?', (channel, receipt_ref)).fetchone():
                    raise OrderError(409, 'RECEIPT_ALREADY_USED')
                current = db.execute('SELECT expires_ms FROM entitlements WHERE owner=?', (row['owner'],)).fetchone()
                target = extend_ms(max(self.clock(), current[0] if current else 0), row['term'], row['days'])
                db.execute("UPDATE orders SET state='applying',target_ms=?,reviewer=?,receipt_ref=?,channel=?,actual_amount_minor=?,actual_currency=? WHERE id=?",
                           (target, principal.subject, receipt_ref, channel, actual_amount_minor, actual_currency, oid))
                self.audit(db, oid, principal.subject, 'approval_reserved')
            else:
                raise OrderError(409, 'PAYMENT_NOT_SUBMITTED')
        # Durable leased task; no network request runs inside a SQLite transaction.
        token = secrets.token_hex(16)
        with self.transaction() as db:
            row = self.owned(db, oid, principal)
            if row['state'] == 'approved':
                return self.public(row)
            if row['lease_token'] and row['lease_until_ms'] > self.clock():
                raise OrderError(409, 'APPROVAL_IN_PROGRESS')
            db.execute('UPDATE orders SET attempts=attempts+1,lease_token=?,lease_until_ms=? WHERE id=?',
                       (token, self.clock() + 30_000, oid))
            self.audit(db, oid, principal.subject, 'apply_started')
        failed = False
        try:
            # Adapter contract: bounded I/O, idempotent by operation_id, monotonic
            # absolute expiry. Replayed/stale calls must never shorten entitlement.
            self.adapter.ensure(row['owner'], row['target_ms'], row['id'])
            observed = self.adapter.observed_expiry(row['owner'])
            if type(observed) is not int or observed < row['target_ms']:
                raise OrderError(503, 'ENTITLEMENT_NOT_CONFIRMED')
        except Exception:
            failed = True
        with self.transaction() as db:
            current = self.owned(db, oid, principal)
            if current['lease_token'] != token:
                raise OrderError(409, 'APPROVAL_IN_PROGRESS')
            db.execute('UPDATE orders SET lease_token=NULL,lease_until_ms=NULL WHERE id=?', (oid,))
            if failed:
                self.audit(db, oid, principal.subject, 'apply_retry_required')
            else:
                db.execute('INSERT INTO entitlements VALUES(?,?) ON CONFLICT(owner) DO UPDATE SET expires_ms=excluded.expires_ms',
                           (row['owner'], row['target_ms']))
                db.execute("UPDATE orders SET state='approved' WHERE id=?", (oid,))
                self.audit(db, oid, principal.subject, 'approved')
            result = self.public(self.owned(db, oid, principal))
        if failed:
            raise OrderError(503, 'ENTITLEMENT_RETRY_REQUIRED')
        return result

    def retry(self, principal, oid):
        # Only a trusted admin route reaches here. Do not require the operator
        # to retain/re-enter a receipt or expose it through the queue.
        with self.connect() as db:
            row = self.owned(db, oid, principal)
            if row['state'] not in ('applying', 'approved'):
                raise OrderError(409, 'APPROVAL_NOT_RESERVED')
        return self.approve(principal, oid, row['receipt_ref'], row['channel'], row['actual_amount_minor'], row['actual_currency'])

    def dispatch(self, method, path, headers=None, body=None):
        """Transport-independent API. Hosting/auth integration is deliberately absent.

        GET status/queue are read-only. All state changes require POST. A future
        cookie-authenticated host must additionally enforce CSRF and Origin checks.
        """
        try:
            if self.mode != 'sandbox':
                raise OrderError(503, 'PAYMENTS_DISABLED')
            principal = self.auth.authenticate(headers or {})
            if not isinstance(principal, Principal) or not principal.subject or principal.role not in ('user', 'admin'):
                raise OrderError(401, 'AUTHENTICATION_REQUIRED')
            data = {} if body is None else body
            if not isinstance(data, dict):
                raise OrderError(400, 'INVALID_REQUEST')
            routes = {
                '/api/order/plans': ('GET', set(), 'user'),
                '/api/order/mine': ('GET', set(), 'user'),
                '/api/order/entitlement': ('GET', set(), 'user'),
                '/api/order/create': ('POST', {'plan_id', 'request_key'}, 'user'),
                '/api/order/submit': ('POST', {'order_id'}, 'user'),
                '/api/order/cancel': ('POST', {'order_id'}, 'user'),
                '/api/admin/approve': ('POST', {'order_id', 'receipt_ref', 'channel', 'actual_amount_minor', 'actual_currency'}, 'admin'),
                '/api/admin/retry': ('POST', {'order_id'}, 'admin'),
                '/api/admin/reject': ('POST', {'order_id'}, 'admin'),
                '/api/admin/queue': ('GET', set(), 'admin'),
            }
            if path.startswith('/api/admin/queue/'):
                route = ('GET', set(), 'admin')
            elif path.startswith('/api/order/status/'):
                route = ('GET', set(), 'user')
            else:
                route = routes.get(path)
            if route is None:
                raise OrderError(404, 'NOT_FOUND')
            if method != route[0]:
                raise OrderError(405, 'METHOD_NOT_ALLOWED')
            if principal.role != route[2]:
                raise OrderError(403, 'FORBIDDEN')
            if set(data) != route[1] or any(not isinstance(v, str) for k, v in data.items() if k != 'actual_amount_minor'):
                raise OrderError(400, 'INVALID_REQUEST')
            if path == '/api/order/plans':
                result = [asdict(plan) for plan in self.plans.values()]
            elif path == '/api/order/entitlement':
                with self.connect() as db:
                    row = db.execute('SELECT expires_ms FROM entitlements WHERE owner=?', (principal.subject,)).fetchone()
                    result = {'source': 'sandbox_ledger', 'expires_ms': row[0] if row else None,
                              'active': bool(row and row[0] > self.clock())}
            elif path == '/api/order/mine':
                with self.connect() as db:
                    result = [self.public(r) for r in db.execute(
                        'SELECT * FROM orders WHERE owner=? ORDER BY created_ms DESC,id DESC LIMIT 100',
                        (principal.subject,))]
            elif path == '/api/order/create':
                result = self.create(principal, data['plan_id'], data['request_key'])
            elif path == '/api/order/submit':
                result = self.submit(principal, data['order_id'])
            elif path == '/api/order/cancel':
                result = self.close(principal, data['order_id'], 'cancelled')
            elif path == '/api/admin/reject':
                result = self.close(principal, data['order_id'], 'rejected')
            elif path == '/api/admin/approve':
                result = self.approve(principal, data['order_id'], data['receipt_ref'], data['channel'], data['actual_amount_minor'], data['actual_currency'])
            elif path == '/api/admin/retry':
                result = self.retry(principal, data['order_id'])
            elif path == '/api/admin/queue' or path.startswith('/api/admin/queue/'):
                cursor = path.removeprefix('/api/admin/queue/') if path != '/api/admin/queue' else '0'
                if not re.fullmatch(r'[0-9]{1,18}', cursor):
                    raise OrderError(400, 'INVALID_CURSOR')
                with self.connect() as db:
                    rows = db.execute("SELECT rowid AS cursor,* FROM orders WHERE rowid>? AND state IN ('review','applying') ORDER BY rowid LIMIT 101", (int(cursor),)).fetchall()
                    result = {'items': [dict(self.public(r), owner=r['owner'], channel=r['channel']) for r in rows[:100]],
                              'next_cursor': str(rows[99]['cursor']) if len(rows) > 100 else None}
            else:
                with self.connect() as db:
                    result = self.public(self.owned(db, path.removeprefix('/api/order/status/'), principal))
            return 200, {'sandbox': True, 'data': result}
        except OrderError as e:
            return e.status, {'error': e.code}
        except sqlite3.Error:
            return 503, {'error': 'DATABASE_UNAVAILABLE'}
        except Exception:
            return 503, {'error': 'SERVICE_UNAVAILABLE'}
