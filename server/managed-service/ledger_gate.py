"""Durable local DAV authority, using the existing orders/trial/Feishu journal.

No SFTPGo permission PUT is performed or implied. This authority is effective
only while every managed mutation passes the running gate, the upstream is
inaccessible directly, and provider account deletion/username reuse is disabled.
The SFTPGo adapter's configured instance namespace must remain immutable.
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
from registration_trials import RegistrationProof, RegistrationTrials
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


def _confirmed_expiry(db, identity):
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
    return db.execute('''SELECT MAX(a.target_ms) FROM managed_gate_applications a
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
        account = self.ledger.accounts.lookup(username)
        if account is None:
            return None
        identity = _identity(account, username)
        with self.ledger.journal.transaction() as db:
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
                self.orders.audit(db, operation, 'system', 'registration_reserved')
                row = db.execute('SELECT * FROM registration_trials WHERE owner=?', (owner,)).fetchone()
            if db.execute('SELECT 1 FROM managed_registration_proofs WHERE owner=? AND operation_id=?',
                          (owner, row['operation_id'])).fetchone() is None:
                raise OrderError(409, 'LEGACY_TRIAL_MIGRATION_REQUIRED')
            return row


class ManagedLedger:
    """One local ledger shared by the bot, named registration, and DAV gate.

    accounts must implement lookup(username), authenticate_basic(username,
    password), and create_or_recover(username,password,operation_id). Creation
    must atomically retain an unforgeable-by-the-user operation marker and
    original server-created time; retries must authenticate without resetting
    passwords. Returned account_id is an immutable instance/id/incarnation key.
    """
    def __init__(self, path, *, accounts, enabled=False, clock=None):
        self.path, self.accounts, self.enabled = str(path), accounts, enabled
        self.clock = clock or (lambda: time.time_ns() // 1_000_000)
        self.journal = Orders(path, clock=self.clock)
        with self.journal.connect() as db:
            db.executescript(SCHEMA)
        self.applications = _LocalApplications(self)
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
            return _confirmed_expiry(db, identity)

    def entitlement(self, account):
        """Read-only status for an already authenticated provider account."""
        identity = _identity(account)
        uri = Path(self.path).resolve().as_uri() + '?mode=ro'
        with closing(sqlite3.connect(uri, uri=True, timeout=2)) as db:
            db.execute('BEGIN')
            expiry = _confirmed_expiry(db, identity)
            managed = db.execute('SELECT 1 FROM managed_account_bindings WHERE owner=? AND account_id=?',
                                 (identity.username, identity.account_id)).fetchone() is not None
        return {'source': 'managed_ledger', 'username': identity.username, 'expires_ms': expiry,
                'active': expiry is not None and expiry > _now(self.clock), 'managed': managed}

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

    def authenticate(self, headers):
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
        return _identity(self.accounts.authenticate_basic(username, password), username)


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
