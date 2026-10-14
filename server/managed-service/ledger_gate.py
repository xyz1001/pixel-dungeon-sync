"""Durable account grants using the existing orders/trial/Feishu journal.

The default local-gate mode is retained for compatibility. Explicit native
expiry mode applies fixed targets to SFTPGo expiration_date, preserving status
and all unrelated account fields. All cooperating processes must share this
SQLite database; no external account writers or username reuse are permitted.
"""
import base64
import binascii
from contextlib import closing
from pathlib import Path
import re
import secrets
import sqlite3
import time

from feishu_renewals import AccountIdentity, FeishuRenewals
from orders import OrderError, Orders, Principal
from registration_trials import RegistrationProof, RegistrationTrials, TRIAL_MS
from sftpgo_client import SftpgoError
from write_gate import Decision, READ_METHODS, WRITE_METHODS


SCHEMA = """
CREATE TABLE IF NOT EXISTS managed_account_bindings (
 owner TEXT PRIMARY KEY, account_id TEXT NOT NULL UNIQUE, bound_ms INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS managed_registration_proofs (
 operation_id TEXT PRIMARY KEY, owner TEXT NOT NULL UNIQUE,
 account_id TEXT, created_ms INTEGER,
 CHECK((account_id IS NULL AND created_ms IS NULL) OR
       (account_id IS NOT NULL AND created_ms IS NOT NULL AND created_ms>=0))
);
CREATE TABLE IF NOT EXISTS managed_legacy_paid (
 operation_id TEXT PRIMARY KEY, owner TEXT NOT NULL, account_id TEXT NOT NULL,
 source TEXT NOT NULL CHECK(source IN ('order','feishu'))
);
CREATE TABLE IF NOT EXISTS managed_gate_applications (
 operation_id TEXT PRIMARY KEY, owner TEXT NOT NULL, account_id TEXT NOT NULL,
 source TEXT NOT NULL CHECK(source IN ('order','trial','feishu')),
 target_ms INTEGER NOT NULL CHECK(target_ms>=0), applied_ms INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS managed_gate_account ON managed_gate_applications(owner,account_id);
CREATE TABLE IF NOT EXISTS managed_native_registration_intents (
 operation_id TEXT PRIMARY KEY, owner TEXT NOT NULL UNIQUE,
 requested_ms INTEGER NOT NULL CHECK(requested_ms>=0)
);
CREATE TABLE IF NOT EXISTS managed_native_applications (
 operation_id TEXT PRIMARY KEY, owner TEXT NOT NULL, account_id TEXT NOT NULL,
 source TEXT NOT NULL CHECK(source IN ('order','trial','feishu')),
 mode TEXT NOT NULL CHECK(mode='native_expiry'),
 target_ms INTEGER NOT NULL CHECK(target_ms>0),
 observed_ms INTEGER NOT NULL CHECK(observed_ms>=target_ms), applied_ms INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS managed_native_account ON managed_native_applications(owner,account_id);
"""


# Source confirmation and exact managed enrollment are both required. No
# username-only historical grant can enter the native migration automatically.
_CONFIRMED_SOURCES = """
SELECT f.operation_id,b.owner,b.account_id,'feishu' AS source,f.target_ms
FROM managed_account_bindings b JOIN feishu_renewals f
 ON f.owner=b.owner AND f.account_id=b.account_id AND f.state='confirmed'
UNION ALL
SELECT r.operation_id,b.owner,b.account_id,'trial' AS source,r.expires_ms AS target_ms
FROM managed_account_bindings b JOIN managed_registration_proofs p
 ON p.owner=b.owner AND p.account_id=b.account_id
JOIN registration_trials r ON r.operation_id=p.operation_id AND r.owner=p.owner
 AND r.created_ms=p.created_ms AND r.state='confirmed'
UNION ALL
SELECT o.id AS operation_id,b.owner,b.account_id,'order' AS source,o.target_ms
FROM managed_account_bindings b JOIN managed_legacy_paid p
 ON p.owner=b.owner AND p.account_id=b.account_id AND p.source='order'
JOIN orders o ON o.id=p.operation_id AND o.owner=p.owner AND o.state='approved'
"""


def _identity(account, username=None):
    """Convert only adapter-produced account objects, never request JSON."""
    name = getattr(account, 'username', None)
    aid = getattr(account, 'account_id', None)
    if (not isinstance(name, str) or not name or
            username is not None and name != username or
            not isinstance(aid, str) or not aid or len(aid) > 256):
        raise OrderError(503, 'INVALID_ACCOUNT_IDENTITY')
    return AccountIdentity(name, aid)


def _now(clock):
    value = clock()
    if type(value) is not int or value < 0:
        raise OrderError(503, 'INVALID_SERVER_CLOCK')
    return value


def _confirmed_expiry(db, identity, *, native_expiry=False):
    """The same DB snapshot must confirm binding, operation, and application.

    A desired target, a durable application awaiting confirmation, and any old
    username-only row are all insufficient. All joins include the exact target.
    """
    binding = db.execute('SELECT account_id FROM managed_account_bindings WHERE owner=?',
                         (identity.username,)).fetchone()
    if binding is None:
        return None
    if binding[0] != identity.account_id:
        raise OrderError(409, 'ACCOUNT_IDENTITY_CONFLICT')
    table = 'managed_native_applications' if native_expiry else 'managed_gate_applications'
    return db.execute(f'''SELECT MAX(a.target_ms) FROM {table} a
        WHERE a.owner=? AND a.account_id=? AND (
          (a.source='feishu' AND EXISTS(SELECT 1 FROM feishu_renewals f
            WHERE f.operation_id=a.operation_id AND f.owner=a.owner
            AND f.account_id=a.account_id AND f.target_ms=a.target_ms AND f.state='confirmed')) OR
          (a.source='order' AND EXISTS(SELECT 1 FROM orders o
            JOIN managed_legacy_paid l ON l.operation_id=o.id
            WHERE o.id=a.operation_id AND o.owner=a.owner AND o.target_ms=a.target_ms
            AND o.state='approved' AND l.owner=a.owner AND l.account_id=a.account_id
            AND l.source='order')) OR
          (a.source='trial' AND EXISTS(SELECT 1 FROM registration_trials r
            JOIN managed_registration_proofs p ON p.operation_id=r.operation_id
            WHERE r.operation_id=a.operation_id AND r.owner=a.owner
            AND r.expires_ms=a.target_ms AND r.state='confirmed'
            AND p.owner=a.owner AND p.account_id=a.account_id AND p.created_ms=r.created_ms))
        )''', (identity.username, identity.account_id)).fetchone()[0]


class _Accounts:
    def __init__(self, ledger):
        self.ledger = ledger

    def lookup_exact(self, username):
        # In native mode an existing provider username is not enrollment.
        # Only an exact managed binding or our persisted registration proof
        # permits a Feishu grant. In particular, never mutate ordinary users.
        with self.ledger.journal.transaction() as db:
            account = self.ledger.accounts.lookup(username)
            if account is None:
                return None
            identity = _identity(account, username)
            if self.ledger.native_expiry:
                binding = db.execute('SELECT account_id FROM managed_account_bindings WHERE owner=?',
                                     (username,)).fetchone()
                proof = db.execute('''SELECT 1 FROM managed_registration_proofs p
                    JOIN registration_trials r ON r.operation_id=p.operation_id AND r.owner=p.owner
                    WHERE p.owner=? AND p.account_id=? AND p.created_ms=?
                    AND p.operation_id=?''', (username, identity.account_id,
                        getattr(account, 'created_at_ms', None),
                        getattr(account, 'registration_operation_id', None))).fetchone()
                if binding is None and proof is None:
                    # Native POST may have committed before its reply was lost.
                    # After the provisional expiry, user/password recovery is
                    # correctly rejected upstream. An already-authorized admin
                    # renewal can recover ONLY our exact durable native intent
                    # and server-retained operation marker, never a username.
                    pending = db.execute('''SELECT p.operation_id FROM managed_registration_proofs p
                        JOIN managed_native_registration_intents i
                          ON i.operation_id=p.operation_id AND i.owner=p.owner
                        JOIN registration_trials r
                          ON r.operation_id=p.operation_id AND r.owner=p.owner
                        WHERE p.owner=? AND p.operation_id=?
                          AND p.account_id IS NULL AND p.created_ms IS NULL
                          AND r.state='pending' ''',
                        (username, getattr(account, 'registration_operation_id', None))).fetchone()
                    if pending is None:
                        return None
                    created = getattr(account, 'created_at_ms', None)
                    if type(created) is not int or not 0 <= created <= _now(self.ledger.clock):
                        raise OrderError(503, 'INVALID_REGISTRATION_PROOF')
                    if getattr(account, 'status', None) != 1:
                        raise OrderError(409, 'SFTPGO_ACCOUNT_DISABLED')
                    self.ledger._validate_native_trial_expiry(
                        db, identity, created + TRIAL_MS, getattr(account, 'expiration_date_ms', None))
                    db.execute('''UPDATE managed_registration_proofs SET account_id=?,created_ms=?
                        WHERE owner=? AND operation_id=?''',
                        (identity.account_id, created, username, pending['operation_id']))
                    self.ledger.journal.audit(db, pending['operation_id'], 'system',
                                              'native_registration_binding_recovered')
                if getattr(account, 'status', None) != 1:
                    raise OrderError(409, 'SFTPGO_ACCOUNT_DISABLED')
            self.ledger._bind(db, identity)
            return identity


class _ManagedRenewals(FeishuRenewals):
    def _identity(self, username):
        # Preserve explicit lifecycle/migration conflicts from our trusted
        # wrapper; the generic sandbox class masks all adapter exceptions.
        try:
            return self.accounts.lookup_exact(username)
        except OrderError:
            raise
        except Exception:
            raise OrderError(503, 'ACCOUNT_LOOKUP_UNAVAILABLE') from None

    def _apply(self, scope, identity):
        # Generic journals return confirmed rows immediately. A local-only
        # historical confirmation must first acquire a native application.
        if isinstance(self.adapter, _NativeApplications):
            with self.ledger.connect() as db:
                row = self._read(db, scope)
            if row['state'] == 'confirmed':
                self.adapter.ensure_account(identity, row['target_ms'], row['operation_id'])
                return self.result(row)
        return super()._apply(scope, identity)


class _LocalApplications:
    """Apply/read back LOCAL gate receipts, never remote permission state."""
    def __init__(self, ledger):
        self.ledger = ledger

    def ensure_account(self, identity, expires_at_ms, operation_id):
        current = self.ledger.accounts.lookup(identity.username)
        if current is None or _identity(current, identity.username) != identity:
            raise OrderError(409, 'ACCOUNT_IDENTITY_CONFLICT')
        with self.ledger.journal.transaction() as db:
            self.ledger._bind(db, identity)
            source, target = self.ledger._operation(db, identity, operation_id)
            if type(expires_at_ms) is not int or expires_at_ms != target:
                raise OrderError(409, 'ENTITLEMENT_TARGET_CONFLICT')
            self.ledger._write_application(db, identity, operation_id, source, target)

    def observed_account_expiry(self, identity):
        # This is genuine post-commit local readback. Confirmation in the source
        # journal is a separate step and is also required by the request gate.
        with self.ledger.journal.connect() as db:
            binding = db.execute('SELECT account_id FROM managed_account_bindings WHERE owner=?',
                                 (identity.username,)).fetchone()
            if binding is None or binding[0] != identity.account_id:
                raise OrderError(409, 'ACCOUNT_IDENTITY_CONFLICT')
            return db.execute('SELECT MAX(target_ms) FROM managed_gate_applications WHERE owner=? AND account_id=?',
                              (identity.username, identity.account_id)).fetchone()[0]

    def ensure(self, username, expires_at_ms, operation_id):
        account = self.ledger.accounts.lookup(username)
        if account is None:
            raise OrderError(409, 'ACCOUNT_IDENTITY_CONFLICT')
        self.ensure_account(_identity(account, username), expires_at_ms, operation_id)

    def observed_expiry(self, username):
        account = self.ledger.accounts.lookup(username)
        if account is None:
            raise OrderError(409, 'ACCOUNT_IDENTITY_CONFLICT')
        return self.observed_account_expiry(_identity(account, username))


class _NativeApplications(_LocalApplications):
    """Serialize remote read/modify/readback and receipt under SQLite's lock.

    BEGIN IMMEDIATE spans the complete remote sequence, across processes as
    well as threads. Neither the provider's full-user PUT nor a Python mutex
    alone supplies remote compare-and-swap. Every caller must use this ledger.
    A remote success followed by a local rollback is repaired using the same
    absolute target. No native receipt is fabricated from a local-only one.
    """
    def _ensure_locked(self, db, identity, expires_at_ms, operation_id):
        current = self.ledger.accounts.lookup(identity.username)
        if current is None or _identity(current, identity.username) != identity:
            raise OrderError(409, 'ACCOUNT_IDENTITY_CONFLICT')
        if getattr(current, 'status', None) != 1:
            raise OrderError(409, 'SFTPGO_ACCOUNT_DISABLED')
        self.ledger._bind(db, identity)
        source, target = self.ledger._operation(db, identity, operation_id)
        if type(expires_at_ms) is not int or expires_at_ms != target or target <= 0:
            raise OrderError(409, 'ENTITLEMENT_TARGET_CONFLICT')
        try:
            applied = self.ledger.accounts.ensure_account_expiry(identity, target)
            observed = self.ledger.accounts.observed_account_expiry(identity)
        except SftpgoError as error:
            raise OrderError(error.status, error.code) from None
        if (type(applied) is not int or applied < target or
                type(observed) is not int or observed < target):
            raise OrderError(503, 'NATIVE_EXPIRY_NOT_CONFIRMED')
        if source == 'trial':
            self.ledger._validate_native_trial_expiry(db, identity, target, observed)
        self.ledger._write_native_application(db, identity, operation_id, source, target, observed)

    def ensure_account(self, identity, expires_at_ms, operation_id):
        with self.ledger.journal.transaction() as db:
            self._ensure_locked(db, identity, expires_at_ms, operation_id)

    def ensure(self, username, expires_at_ms, operation_id):
        with self.ledger.journal.transaction() as db:
            account = self.ledger.accounts.lookup(username)
            if account is None:
                raise OrderError(409, 'ACCOUNT_IDENTITY_CONFLICT')
            self._ensure_locked(db, _identity(account, username), expires_at_ms, operation_id)

    def observed_account_expiry(self, identity):
        with self.ledger.journal.transaction() as db:
            binding = db.execute('SELECT account_id FROM managed_account_bindings WHERE owner=?',
                                 (identity.username,)).fetchone()
            if binding is None or binding[0] != identity.account_id:
                raise OrderError(409, 'ACCOUNT_IDENTITY_CONFLICT')
            receipt = db.execute('''SELECT MAX(target_ms) FROM managed_native_applications
                WHERE owner=? AND account_id=? AND mode='native_expiry' ''',
                (identity.username, identity.account_id)).fetchone()[0]
            if receipt is None:
                return None
            try:
                observed = self.ledger.accounts.observed_account_expiry(identity)
            except SftpgoError as error:
                raise OrderError(error.status, error.code) from None
            if type(observed) is not int or observed <= 0:
                raise OrderError(503, 'NATIVE_EXPIRY_NOT_CONFIRMED')
            return min(receipt, observed)


class _RegistrationAdapter:
    durable_registration_proof = True

    def __init__(self, ledger):
        self.ledger = ledger

    def register(self, owner, password, operation_id):
        try:
            account = self.ledger.accounts.create_or_recover(owner, password, operation_id)
        except SftpgoError as error:
            raise OrderError(error.status, error.code) from None
        identity = _identity(account, owner)
        created = getattr(account, 'created_at_ms', None)
        if (getattr(account, 'registration_operation_id', None) != operation_id or
                type(created) is not int or not 0 <= created <= _now(self.ledger.clock)):
            raise OrderError(503, 'INVALID_REGISTRATION_PROOF')
        with self.ledger.journal.transaction() as db:
            proof = db.execute('SELECT * FROM managed_registration_proofs WHERE operation_id=? AND owner=?',
                               (operation_id, owner)).fetchone()
            if proof is None:
                raise OrderError(409, 'LEGACY_TRIAL_MIGRATION_REQUIRED')
            if proof['account_id'] is not None and (proof['account_id'], proof['created_ms']) != (identity.account_id, created):
                raise OrderError(409, 'REGISTRATION_PROOF_CONFLICT')
            self.ledger._bind(db, identity)
            if self.ledger.native_expiry:
                self.ledger._validate_native_trial_expiry(
                    db, identity, created + TRIAL_MS, getattr(account, 'expiration_date_ms', None))
            db.execute('UPDATE managed_registration_proofs SET account_id=?,created_ms=? WHERE operation_id=?',
                       (identity.account_id, created, operation_id))
        return RegistrationProof(owner, operation_id, created)


class _ManagedTrials(RegistrationTrials):
    def __init__(self, ledger):
        self.managed = ledger
        super().__init__(ledger.journal, registrations=_RegistrationAdapter(ledger))

    def guard(self):
        if self.managed.enabled is not True:
            raise OrderError(503, 'REGISTRATION_TRIALS_DISABLED')

    def _apply(self, owner):
        if self.managed.native_expiry:
            with self.orders.connect() as db:
                row = db.execute('SELECT * FROM registration_trials WHERE owner=?', (owner,)).fetchone()
            if row['state'] == 'confirmed':
                self.orders.adapter.ensure(owner, row['expires_ms'], row['operation_id'])
                return self.public(row)
        return super()._apply(owner)

    def _reserve(self, owner):
        # Intent and origin marker commit together, including before first
        # upstream I/O. A legacy username-only trial is never silently adopted.
        with self.orders.transaction() as db:
            row = db.execute('SELECT * FROM registration_trials WHERE owner=?', (owner,)).fetchone()
            if row is None:
                operation = 'registration_' + secrets.token_hex(16)
                db.execute("INSERT INTO registration_trials(owner,operation_id,requested_ms,state) VALUES(?,?,?,'pending')",
                           (owner, operation, _now(self.managed.clock)))
                db.execute('INSERT INTO managed_registration_proofs(operation_id,owner) VALUES(?,?)', (operation, owner))
                if self.managed.native_expiry:
                    db.execute('INSERT INTO managed_native_registration_intents VALUES(?,?,?)',
                               (operation, owner, _now(self.managed.clock)))
                self.orders.audit(db, operation, 'system', 'registration_reserved')
                row = db.execute('SELECT * FROM registration_trials WHERE owner=?', (owner,)).fetchone()
            if db.execute('SELECT 1 FROM managed_registration_proofs WHERE owner=? AND operation_id=?',
                          (owner, row['operation_id'])).fetchone() is None:
                raise OrderError(409, 'LEGACY_TRIAL_MIGRATION_REQUIRED')
            return row


class ManagedLedger:
    """One durable ledger shared by the bot, named registration, and DAV gate.

    accounts must implement lookup(username), authenticate_basic(username,
    password), and create_or_recover(username,password,operation_id). Creation
    must atomically retain an unforgeable-by-the-user operation marker and
    original server-created time; retries must authenticate without resetting
    passwords. Returned account_id is an immutable instance/id/incarnation key.
    Native mode additionally needs ensure_account_expiry(identity,target_ms)
    and observed_account_expiry(identity), both returning actual milliseconds.
    Its creation adapter must set a finite provisional 72-hour native expiry.
    """
    def __init__(self, path, *, accounts, enabled=False, clock=None, native_expiry=False):
        if type(native_expiry) is not bool:
            raise ValueError('native_expiry must be a boolean')
        self.native_expiry = native_expiry
        self.path, self.accounts, self.enabled = str(path), accounts, enabled
        self.clock = clock or (lambda: time.time_ns() // 1_000_000)
        self.journal = Orders(path, clock=self.clock)
        with self.journal.connect() as db:
            db.executescript(SCHEMA)
        self.applications = _NativeApplications(self) if native_expiry else _LocalApplications(self)
        self.journal.adapter = self.applications
        self.renewals = _ManagedRenewals(path, enabled=enabled, accounts=_Accounts(self),
                                      entitlements=self.applications, clock=self.clock)
        self.trials = _ManagedTrials(self)

    def _bind(self, db, identity):
        binding = db.execute('SELECT account_id FROM managed_account_bindings WHERE owner=?',
                             (identity.username,)).fetchone()
        if binding is not None and binding[0] != identity.account_id:
            raise OrderError(409, 'ACCOUNT_IDENTITY_CONFLICT')
        # Legacy paid data must be explicitly reviewed before it participates in
        # max(existing_paid, now)+year arithmetic. A desired cache is ignored.
        unbound_order = db.execute('''SELECT 1 FROM orders o WHERE owner=? AND state IN ('applying','approved')
            AND NOT EXISTS(SELECT 1 FROM managed_legacy_paid p WHERE p.operation_id=o.id
                AND p.owner=o.owner AND p.account_id=? AND p.source='order')''',
            (identity.username, identity.account_id)).fetchone()
        unbound_trial = db.execute('''SELECT 1 FROM registration_trials r WHERE owner=?
            AND NOT EXISTS(SELECT 1 FROM managed_registration_proofs p
                WHERE p.owner=r.owner AND p.operation_id=r.operation_id)''', (identity.username,)).fetchone()
        incompatible_feishu = db.execute('''SELECT 1 FROM feishu_renewals WHERE owner=?
            AND state<>'unknown_user' AND account_id<>?''', (identity.username, identity.account_id)).fetchone()
        if incompatible_feishu:
            raise OrderError(409, 'ACCOUNT_IDENTITY_CONFLICT')
        if unbound_order or unbound_trial:
            raise OrderError(409, 'LEGACY_ACCOUNT_MIGRATION_REQUIRED')
        if binding is None:
            unbound_feishu = db.execute('''SELECT 1 FROM feishu_renewals f WHERE owner=? AND state<>'unknown_user'
                AND NOT EXISTS(SELECT 1 FROM managed_legacy_paid p WHERE p.operation_id=f.operation_id
                    AND p.owner=f.owner AND p.account_id=? AND p.source='feishu')''',
                (identity.username, identity.account_id)).fetchone()
            if unbound_feishu:
                raise OrderError(409, 'LEGACY_ACCOUNT_MIGRATION_REQUIRED')
            db.execute('INSERT INTO managed_account_bindings VALUES(?,?,?)',
                       (identity.username, identity.account_id, _now(self.clock)))

    def _operation(self, db, identity, operation_id):
        rows = []
        row = db.execute("SELECT owner,account_id,target_ms FROM feishu_renewals WHERE operation_id=? AND state IN ('applying','confirmed')", (operation_id,)).fetchone()
        if row is not None:
            if (row['owner'], row['account_id']) != (identity.username, identity.account_id):
                raise OrderError(409, 'ACCOUNT_IDENTITY_CONFLICT')
            rows.append(('feishu', row['target_ms']))
        row = db.execute('''SELECT r.owner,r.expires_ms,p.account_id FROM registration_trials r
            JOIN managed_registration_proofs p ON p.operation_id=r.operation_id AND p.owner=r.owner
            WHERE r.operation_id=? AND r.state IN ('applying','confirmed') AND p.created_ms=r.created_ms''', (operation_id,)).fetchone()
        if row is not None:
            if (row['owner'], row['account_id']) != (identity.username, identity.account_id):
                raise OrderError(409, 'ACCOUNT_IDENTITY_CONFLICT')
            rows.append(('trial', row['expires_ms']))
        row = db.execute('''SELECT o.owner,o.target_ms,p.account_id FROM orders o
            JOIN managed_legacy_paid p ON p.operation_id=o.id AND p.owner=o.owner AND p.source='order'
            WHERE o.id=? AND o.state IN ('applying','approved')''', (operation_id,)).fetchone()
        if row is not None:
            if (row['owner'], row['account_id']) != (identity.username, identity.account_id):
                raise OrderError(409, 'ACCOUNT_IDENTITY_CONFLICT')
            rows.append(('order', row['target_ms']))
        if len(rows) != 1 or type(rows[0][1]) is not int or rows[0][1] < 0:
            raise OrderError(409, 'ENTITLEMENT_NOT_RESERVED')
        return rows[0]

    def _write_application(self, db, identity, operation_id, source, target):
        old = db.execute('SELECT * FROM managed_gate_applications WHERE operation_id=?', (operation_id,)).fetchone()
        expected = identity.username, identity.account_id, source, target
        if old is not None:
            if tuple(old[k] for k in ('owner', 'account_id', 'source', 'target_ms')) != expected:
                raise OrderError(409, 'ENTITLEMENT_APPLICATION_CONFLICT')
            return
        db.execute('INSERT INTO managed_gate_applications VALUES(?,?,?,?,?,?)',
                   (operation_id, *expected, _now(self.clock)))
        self.journal.audit(db, operation_id, 'system', 'local_gate_application_committed')

    def _validate_native_trial_expiry(self, db, identity, target, observed):
        # A provisional create expiry must not silently stretch the original
        # 72-hour proof under clock skew. A legitimate frozen paid reservation
        # can explain a higher remote value on a later registration retry.
        if type(observed) is not int or observed < 0:
            raise OrderError(503, 'NATIVE_EXPIRY_NOT_CONFIRMED')
        if observed <= target:
            return
        paid = db.execute('''SELECT MAX(target_ms) FROM (
            SELECT target_ms FROM feishu_renewals
              WHERE owner=? AND account_id=? AND state IN ('reserved','applying','confirmed')
            UNION ALL
            SELECT o.target_ms FROM orders o JOIN managed_legacy_paid p
              ON p.operation_id=o.id AND p.owner=o.owner AND p.source='order'
              WHERE o.owner=? AND p.account_id=? AND o.state IN ('applying','approved')
            )''', (identity.username, identity.account_id,
                    identity.username, identity.account_id)).fetchone()[0]
        if type(paid) is not int or observed > paid:
            raise OrderError(409, 'REGISTRATION_CLOCK_CONFLICT')

    def _write_native_application(self, db, identity, operation_id, source, target, observed):
        expected = identity.username, identity.account_id, source, 'native_expiry', target
        old = db.execute('SELECT * FROM managed_native_applications WHERE operation_id=?',
                         (operation_id,)).fetchone()
        if old is not None:
            if tuple(old[k] for k in ('owner', 'account_id', 'source', 'mode', 'target_ms')) != expected:
                raise OrderError(409, 'ENTITLEMENT_APPLICATION_CONFLICT')
            db.execute('''UPDATE managed_native_applications SET observed_ms=MAX(observed_ms,?),
                applied_ms=? WHERE operation_id=?''', (observed, _now(self.clock), operation_id))
            return
        db.execute('INSERT INTO managed_native_applications VALUES(?,?,?,?,?,?,?,?)',
                   (operation_id, *expected, observed, _now(self.clock)))
        self.journal.audit(db, operation_id, 'system', 'native_expiry_application_confirmed')

    def assert_native_migration_ready(self):
        """Startup check only: inspect local receipts without remote writes.

        A fresh empty database is ready. Existing exact bindings must have a
        native application, and every source-confirmed original target needs
        its own matching receipt. Local-only applications do not count. A new
        native-origin registration may still be pending: its creation adapter
        atomically set finite expiry, and the durable intent permits recovery
        but never serves as an entitlement/application receipt.
        This is not a proof against external provider edits; deployment must
        prohibit those and preserve the shared SQLite writer boundary.
        """
        if not self.native_expiry:
            raise OrderError(503, 'NATIVE_EXPIRY_DISABLED')
        uri = Path(self.path).resolve().as_uri() + '?mode=ro'
        with closing(sqlite3.connect(uri, uri=True, timeout=2)) as db:
            db.execute('BEGIN')
            missing = db.execute('''SELECT 1 FROM managed_account_bindings b
                WHERE NOT EXISTS(SELECT 1 FROM managed_native_applications a
                  WHERE a.owner=b.owner AND a.account_id=b.account_id
                  AND a.mode='native_expiry' AND a.observed_ms>=a.target_ms)
                AND NOT EXISTS(SELECT 1 FROM managed_native_registration_intents i
                  JOIN managed_registration_proofs p ON p.operation_id=i.operation_id
                    AND p.owner=i.owner AND p.account_id=b.account_id
                  JOIN registration_trials r ON r.operation_id=i.operation_id
                    AND r.owner=i.owner AND r.state IN ('pending','applying')
                  WHERE i.owner=b.owner)
                LIMIT 1''').fetchone()
            incomplete = db.execute(f'''WITH confirmed AS ({_CONFIRMED_SOURCES})
                SELECT 1 FROM confirmed c WHERE c.target_ms IS NULL OR c.target_ms<=0
                OR NOT EXISTS(SELECT 1 FROM managed_native_applications a
                  WHERE a.operation_id=c.operation_id AND a.owner=c.owner
                  AND a.account_id=c.account_id AND a.source=c.source
                  AND a.mode='native_expiry' AND a.target_ms=c.target_ms
                  AND a.observed_ms>=c.target_ms) LIMIT 1''').fetchone()
            if missing or incomplete:
                raise OrderError(503, 'NATIVE_EXPIRY_MIGRATION_REQUIRED')
        return True

    def reconcile_native_accounts(self, *, limit=100, after_owner=None):
        """Explicit offline repair of a bounded page of exact managed bindings.

        Reapply only already source-confirmed absolute targets, including old
        expired ones; never calculate another term or adopt an unknown owner.
        Reports contain counts and fixed error codes. The opaque pagination
        cursor is an existing owner key and must not be printed publicly.
        """
        if self.enabled is not True or not self.native_expiry:
            raise OrderError(503, 'NATIVE_EXPIRY_DISABLED')
        if (type(limit) is not int or not 1 <= limit <= 1000 or
                after_owner is not None and (not isinstance(after_owner, str) or len(after_owner) > 64)):
            raise OrderError(400, 'INVALID_MIGRATION_PAGE')
        with self.journal.connect() as db:
            bindings = db.execute('''SELECT owner,account_id FROM managed_account_bindings
                WHERE (? IS NULL OR owner>?) ORDER BY owner LIMIT ?''',
                (after_owner, after_owner, limit + 1)).fetchall()
        has_more = len(bindings) > limit
        bindings = bindings[:limit]
        report = {'processed': len(bindings), 'applied': 0,
                  'operations_applied': 0, 'skipped': 0, 'failed': 0,
                  'errors': {}, 'has_more': has_more,
                  'next_owner': bindings[-1]['owner'] if has_more else None}
        safe_codes = {'ACCOUNT_IDENTITY_CONFLICT', 'SFTPGO_ACCOUNT_DISABLED',
                      'NATIVE_EXPIRY_NOT_CONFIRMED', 'REGISTRATION_CLOCK_CONFLICT', 'ENTITLEMENT_APPLICATION_CONFLICT',
                      'ENTITLEMENT_TARGET_CONFLICT', 'LEGACY_ACCOUNT_MIGRATION_REQUIRED',
                      'SFTPGO_NATIVE_EXPIRY_DISABLED', 'SFTPGO_ACCOUNT_WRITER_INVARIANT_REQUIRED'}
        for binding in bindings:
            identity = AccountIdentity(binding['owner'], binding['account_id'])
            try:
                with self.journal.transaction() as db:
                    rows = db.execute(f'''WITH confirmed AS ({_CONFIRMED_SOURCES})
                        SELECT * FROM confirmed WHERE owner=? AND account_id=?
                        ORDER BY target_ms,operation_id''',
                        (identity.username, identity.account_id)).fetchall()
                    for row in rows:
                        self.applications._ensure_locked(db, identity, row['target_ms'], row['operation_id'])
                if rows:
                    report['applied'] += 1
                    report['operations_applied'] += len(rows)
                else:
                    report['skipped'] += 1
            except Exception as error:
                code = getattr(error, 'code', None)
                if isinstance(error, sqlite3.Error):
                    code = 'DATABASE_UNAVAILABLE'
                elif code not in safe_codes:
                    code = 'NATIVE_EXPIRY_UNAVAILABLE'
                report['failed'] += 1
                report['errors'][code] = report['errors'].get(code, 0) + 1
        return report

    def migrate_native_accounts(self, *, limit=100, after_owner=None):
        """Alias for one explicit migration page; follow next_owner."""
        return self.reconcile_native_accounts(limit=limit, after_owner=after_owner)

    def renew(self, **kwargs):
        return self.renewals.renew(**kwargs)

    def register(self, username, password):
        return self.trials.register(username, password)

    def expiry(self, identity):
        if not isinstance(identity, AccountIdentity):
            raise OrderError(401, 'AUTHENTICATION_REQUIRED')
        # Read-only connection never creates a missing/mistyped ledger. A single
        # snapshot also avoids mixing a binding read with later source changes.
        uri = Path(self.path).resolve().as_uri() + '?mode=ro'
        with closing(sqlite3.connect(uri, uri=True, timeout=2)) as db:
            db.execute('BEGIN')
            return _confirmed_expiry(db, identity, native_expiry=self.native_expiry)

    def entitlement(self, account):
        """Read-only status for an already authenticated provider account."""
        identity = _identity(account)
        uri = Path(self.path).resolve().as_uri() + '?mode=ro'
        with closing(sqlite3.connect(uri, uri=True, timeout=2)) as db:
            db.execute('BEGIN')
            expiry = _confirmed_expiry(db, identity, native_expiry=self.native_expiry)
            managed = db.execute('SELECT 1 FROM managed_account_bindings WHERE owner=? AND account_id=?',
                                 (identity.username, identity.account_id)).fetchone() is not None
        now = _now(self.clock)
        active = expiry is not None and expiry > now
        if self.native_expiry:
            observed = getattr(account, 'expiration_date_ms', None)
            active = (active and getattr(account, 'status', None) == 1 and
                      type(observed) is int and observed >= expiry)
        return {'source': 'managed_ledger', 'username': identity.username, 'expires_ms': expiry,
                'active': bool(active), 'managed': managed, 'server_time_ms': now}

    def adopt_legacy_paid(self, username, expected_account_id, operation_ids):
        """OFFLINE operator-only migration; never expose as an HTTP endpoint.

        Operator must independently prove all listed legacy payments belong to
        this exact surviving account. No username-only automatic inference.
        All historical paid operations for the owner must be listed; targets
        and source states remain unchanged. Old trials require a separate
        reviewed migration and are refused here. Mismatched Feishu IDs cannot
        be reassigned. Retrying applying records still uses the frozen target.
        """
        if self.enabled is not True:
            raise OrderError(503, 'MANAGED_LEDGER_DISABLED')
        current = self.accounts.lookup(username)
        if current is None or _identity(current, username).account_id != expected_account_id:
            raise OrderError(409, 'ACCOUNT_IDENTITY_CONFLICT')
        identity = _identity(current, username)
        if not isinstance(operation_ids, (list, tuple)) or not all(isinstance(v, str) for v in operation_ids):
            raise OrderError(400, 'INVALID_MIGRATION_MANIFEST')
        with self.journal.transaction() as db:
            binding = db.execute('SELECT account_id FROM managed_account_bindings WHERE owner=?', (username,)).fetchone()
            if binding is not None and binding[0] != expected_account_id:
                raise OrderError(409, 'ACCOUNT_IDENTITY_CONFLICT')
            if db.execute('SELECT 1 FROM registration_trials WHERE owner=?', (username,)).fetchone():
                raise OrderError(409, 'LEGACY_TRIAL_MIGRATION_REQUIRED')
            records = [('order', r['id'], r['target_ms'], r['state']) for r in db.execute(
                "SELECT * FROM orders WHERE owner=? AND state IN ('applying','approved')", (username,))]
            for row in db.execute("SELECT * FROM feishu_renewals WHERE owner=? AND state<>'unknown_user'", (username,)):
                if row['account_id'] != expected_account_id:
                    raise OrderError(409, 'ACCOUNT_IDENTITY_CONFLICT')
                records.append(('feishu', row['operation_id'], row['target_ms'], row['state']))
            if not records or len(operation_ids) != len(set(operation_ids)) or set(operation_ids) != {r[1] for r in records}:
                raise OrderError(409, 'INCOMPLETE_MIGRATION_MANIFEST')
            for source, operation, target, state in records:
                previous = db.execute('SELECT owner,account_id,source FROM managed_legacy_paid WHERE operation_id=?', (operation,)).fetchone()
                if previous is not None and tuple(previous) != (username, expected_account_id, source):
                    raise OrderError(409, 'ACCOUNT_IDENTITY_CONFLICT')
                db.execute('INSERT OR IGNORE INTO managed_legacy_paid VALUES(?,?,?,?)',
                           (operation, username, expected_account_id, source))
                if state in ('approved', 'confirmed'):
                    self._write_application(db, identity, operation, source, target)
                self.journal.audit(db, operation, 'offline-operator', 'legacy_paid_binding_reviewed')
            self._bind(db, identity)

    def retry_legacy_order(self, operation_id):
        """Internal operator retry, only for explicitly adopted legacy records."""
        if self.enabled is not True:
            raise OrderError(503, 'MANAGED_LEDGER_DISABLED')
        with self.journal.connect() as db:
            row = db.execute("SELECT owner,account_id FROM managed_legacy_paid WHERE operation_id=? AND source='order'", (operation_id,)).fetchone()
            if row is None:
                raise OrderError(409, 'LEGACY_ACCOUNT_MIGRATION_REQUIRED')
        account = self.accounts.lookup(row['owner'])
        if account is None or _identity(account, row['owner']).account_id != row['account_id']:
            raise OrderError(409, 'ACCOUNT_IDENTITY_CONFLICT')
        if self.native_expiry:
            with self.journal.connect() as db:
                order = db.execute('SELECT state,target_ms FROM orders WHERE id=?', (operation_id,)).fetchone()
            if order is not None and order['state'] == 'approved':
                self.applications.ensure_account(_identity(account, row['owner']), order['target_ms'], operation_id)
        return self.journal.retry(Principal('offline-operator', 'admin'), operation_id)


class SftpgoBasicAuthenticator:
    """Validate Basic credentials through the bounded, strict provider adapter.

    Neither X-User nor client identity headers are trusted. The HTTP host must
    reject duplicate Authorization headers and TLS must protect credentials.
    The provider's no-username-reuse deployment invariant is required because
    SFTPGo password tokens do not carry a provable immutable incarnation ID.
    """
    def __init__(self, accounts):
        self.accounts = accounts

    def authenticate_account(self, headers):
        values = [v for k, v in headers.items() if isinstance(k, str) and k.lower() == 'authorization']
        if len(values) != 1 or not isinstance(values[0], str) or len(values[0]) > 8192:
            raise OrderError(401, 'AUTHENTICATION_REQUIRED')
        match = re.fullmatch(r'(?i:Basic) ([A-Za-z0-9+/]+={0,2})', values[0])
        if match is None:
            raise OrderError(401, 'AUTHENTICATION_REQUIRED')
        try:
            decoded = base64.b64decode(match.group(1), validate=True).decode('utf-8')
            username, password = decoded.split(':', 1)
        except (ValueError, UnicodeError, binascii.Error):
            raise OrderError(401, 'AUTHENTICATION_REQUIRED') from None
        if not username or not password or any(ord(c) < 32 or ord(c) == 127 for c in username + password):
            raise OrderError(401, 'AUTHENTICATION_REQUIRED')
        account = self.accounts.authenticate_basic(username, password)
        _identity(account, username)
        return account

    def authenticate(self, headers):
        # DAV gates need only the immutable identity; private account status
        # needs the same authenticated snapshot's native expiry/status fields.
        return _identity(self.authenticate_account(headers))


class AccountWriteGate:
    def __init__(self, *, enabled=False, authenticator=None, ledger=None, clock=None):
        self.enabled, self.auth, self.ledger = enabled, authenticator, ledger
        self.clock = clock or (lambda: time.time_ns() // 1_000_000)

    def decide(self, method, headers):
        if self.enabled is not True:
            return Decision(503, 'WRITE_GATE_DISABLED')
        if method not in READ_METHODS | WRITE_METHODS:
            return Decision(405, 'METHOD_NOT_ALLOWED')
        try:
            identity = self.auth.authenticate(headers)
            if not isinstance(identity, AccountIdentity) or _identity(identity) != identity:
                raise OrderError(401, 'AUTHENTICATION_REQUIRED')
        except Exception:
            return Decision(401, 'AUTHENTICATION_REQUIRED')
        # Native mode delegates all read/write expiration enforcement to
        # SFTPGo authentication. Startup must check migration readiness before
        # serving this route; the old upload-only split no longer applies.
        if getattr(self.ledger, 'native_expiry', False):
            return Decision(204, 'PASS_TO_SFTPGO')
        # Reads retain upstream authentication/authorization and do not depend
        # on ledger availability or expired/absent upload rights.
        if method in READ_METHODS:
            return Decision(204, 'PASS_TO_SFTPGO')
        try:
            expiry = self.ledger.expiry(identity)
            now = _now(self.clock)
            if expiry is None:
                return Decision(403, 'UPLOAD_ENTITLEMENT_REQUIRED')
            if type(expiry) is not int or expiry < 0:
                return Decision(503, 'ENTITLEMENT_UNAVAILABLE')
            if expiry <= now:
                return Decision(403, 'UPLOAD_ENTITLEMENT_EXPIRED')
            return Decision(204, 'PASS_TO_SFTPGO')
        except OrderError as error:
            if error.code == 'ACCOUNT_IDENTITY_CONFLICT':
                return Decision(403, error.code)
            return Decision(503, 'ENTITLEMENT_UNAVAILABLE')
        except Exception:
            return Decision(503, 'ENTITLEMENT_UNAVAILABLE')
