"""Disabled-by-default, sandbox-only SFTPGo permission reconciliation seam.

No ordinary SFTPGo PUT implementation: it cannot satisfy atomic fencing below.
"""
from dataclasses import dataclass
import json
import sqlite3
from contextlib import contextmanager
import time

from orders import _has_table, confirmed_expiry, application_expiry


class PolicyError(Exception):
    pass


READ = frozenset(('list', 'download'))
WRITE = frozenset(('upload', 'overwrite', 'create_dirs', 'rename', 'rename_files',
                   'rename_dirs', 'copy', 'delete', 'delete_files', 'delete_dirs',
                   'create_symlinks', 'chmod', 'chown', 'chtimes'))
KNOWN = READ | WRITE | {'*'}


@dataclass(frozen=True)
class AccountSnapshot:
    username: str
    revision: str
    permissions: dict
    status: int = 1
    expiration_date: int = 0
    groups: tuple = ()
    virtual_folders: tuple = ()
    # Adapter must explicitly establish absence of inherited/external grants.
    effective_permissions_verified: bool = False
    account_id: str = None


class DisabledAdapter:
    atomic_fenced_permissions = False

    def read(self, username):
        raise PolicyError('ADAPTER_DISABLED')

    def apply_permissions(self, username, expected_revision, generation, permissions):
        raise PolicyError('ADAPTER_DISABLED')


def checked_permissions(account):
    if not isinstance(account, AccountSnapshot) or not account.revision:
        raise PolicyError('INVALID_ACCOUNT_SNAPSHOT')
    if account.status != 1 or account.expiration_date != 0:
        raise PolicyError('LOGIN_POLICY_INCOMPATIBLE')
    if account.groups or account.virtual_folders or not account.effective_permissions_verified:
        raise PolicyError('UNVERIFIED_EFFECTIVE_PERMISSIONS')
    if set(account.permissions) != {'/'}:
        raise PolicyError('SUBPATH_POLICY_UNSUPPORTED')
    values = account.permissions['/']
    if not isinstance(values, (list, tuple)) or any(not isinstance(v, str) or v not in KNOWN for v in values):
        raise PolicyError('UNKNOWN_PERMISSION')
    baseline = sorted(set(values))
    if '*' not in baseline and not READ.issubset(baseline):
        raise PolicyError('BASELINE_MUST_ALLOW_READ')
    return baseline


def desired_permissions(baseline, expires_ms, now_ms):
    if type(expires_ms) is not int or expires_ms < 0:
        raise PolicyError('INVALID_ENTITLEMENT')
    # Never invent or widen active permissions; expiration is an exact boundary.
    return {'/': list(baseline) if expires_ms > now_ms else sorted(READ)}


def new_account_policy(activation_permissions):
    """Trusted server configuration only, never a client-supplied permission list.

    The provisioning caller must use initial_permissions in its CREATE request,
    then persist activation_permissions with enroll_new_account. No writable
    interval is needed, even if registration crashes before local enrollment.
    """
    if not isinstance(activation_permissions, (list, tuple)) or any(
            not isinstance(p, str) or p not in READ | WRITE for p in activation_permissions):
        raise PolicyError('INVALID_ACTIVATION_TEMPLATE')
    template = sorted(set(activation_permissions))
    if not (READ | {'upload'}).issubset(template):
        raise PolicyError('ACTIVATION_TEMPLATE_MUST_ALLOW_READ_UPLOAD')
    return {'initial_permissions': {'/': sorted(READ)},
            'activation_permissions': {'/': template}}


class UploadPolicy:
    def __init__(self, database, *, adapter=None, mode='disabled', clock=None):
        if mode not in ('disabled', 'sandbox'):
            raise PolicyError('LIVE_POLICY_UNAVAILABLE')
        self.path, self.mode = str(database), mode
        self.adapter = adapter or DisabledAdapter()
        self.clock = clock or (lambda: time.time_ns() // 1_000_000)
        with self.db() as db:
            # Shares the order database's authoritative entitlements table.
            db.executescript('''
            CREATE TABLE IF NOT EXISTS managed_upload_policy (
              owner TEXT PRIMARY KEY, baseline TEXT NOT NULL,
              generation INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS upload_policy_jobs (
              owner TEXT NOT NULL, generation INTEGER NOT NULL,
              expires_ms INTEGER NOT NULL, observed_ms INTEGER NOT NULL,
              desired TEXT NOT NULL, state TEXT NOT NULL,
              PRIMARY KEY(owner,generation)
            );
            ''')
            # Additive migration preserves old jobs, generations and baselines.
            db.execute('BEGIN IMMEDIATE')
            try:
                columns = {r['name'] for r in db.execute('PRAGMA table_info(upload_policy_jobs)')}
                for name in ('operation_id', 'account_id'):
                    if name not in columns:
                        db.execute('ALTER TABLE upload_policy_jobs ADD COLUMN ' + name + ' TEXT')
                db.execute('COMMIT')
            except BaseException:
                db.execute('ROLLBACK')
                raise

    @contextmanager
    def db(self):
        db = sqlite3.connect(self.path, timeout=10, isolation_level=None)
        db.row_factory = sqlite3.Row
        try:
            yield db
        finally:
            db.close()

    @contextmanager
    def tx(self):
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            try:
                yield db
                db.execute('COMMIT')
            except BaseException:
                db.execute('ROLLBACK')
                raise

    def guard(self):
        if self.mode != 'sandbox':
            raise PolicyError('UPLOAD_POLICY_DISABLED')

    def enroll(self, owner):
        """Legacy sandbox inspection only; new provisioning uses enroll_new_account."""
        self.guard()
        account = self.adapter.read(owner)
        if account.username != owner:
            raise PolicyError('ACCOUNT_IDENTITY_MISMATCH')
        baseline = checked_permissions(account)
        with self.tx() as db:
            old = db.execute('SELECT baseline FROM managed_upload_policy WHERE owner=?', (owner,)).fetchone()
            if old:
                raise PolicyError('ALREADY_MANAGED')
            db.execute('INSERT INTO managed_upload_policy(owner,baseline) VALUES(?,?)',
                       (owner, json.dumps(baseline)))

    def enroll_new_account(self, owner, activation_permissions):
        """Record a trusted activation template for an already-created READ-ONLY user.

        No remote creation/write, no implicit migration, and no recapturing a
        read-only current profile as the paid activation template.
        """
        self.guard()
        plan = new_account_policy(activation_permissions)
        account = self.adapter.read(owner)
        if account.username != owner:
            raise PolicyError('ACCOUNT_IDENTITY_MISMATCH')
        if checked_permissions(account) != sorted(READ):
            raise PolicyError('NEW_ACCOUNT_MUST_START_READ_ONLY')
        with self.tx() as db:
            if db.execute('SELECT 1 FROM managed_upload_policy WHERE owner=?', (owner,)).fetchone():
                raise PolicyError('ALREADY_MANAGED')
            if db.execute('SELECT 1 FROM entitlements WHERE owner=?', (owner,)).fetchone():
                raise PolicyError('NEW_ACCOUNT_ALREADY_HAS_ENTITLEMENT')
            db.execute('INSERT INTO managed_upload_policy(owner,baseline) VALUES(?,?)',
                       (owner, json.dumps(plan['activation_permissions']['/'])))

    def observation(self, db, owner, account, operation_id=None, expected_account_id=None):
        current = checked_permissions(account)
        if expected_account_id is not None and account.account_id != expected_account_id:
            raise PolicyError('ACCOUNT_IDENTITY_MISMATCH')
        if account.username != owner:
            raise PolicyError('ACCOUNT_IDENTITY_MISMATCH')
        row = db.execute('SELECT * FROM managed_upload_policy WHERE owner=?', (owner,)).fetchone()
        if row is None:
            raise PolicyError('ACCOUNT_NOT_MANAGED')
        baseline = json.loads(row['baseline'])
        if current not in (baseline, sorted(READ)):
            raise PolicyError('EXTERNAL_PERMISSION_CHANGE')
        if _has_table(db, 'orders'):
            # Shared ledgers expose confirmed rights by default. Only a proved
            # explicit application may consume its own reserved target.
            expires_ms = (application_expiry(db, owner, operation_id) if operation_id else
                          confirmed_expiry(db, owner)) or 0
        else:
            # Existing standalone sandbox policy fixtures use a confirmed-only
            # entitlements table. This compatibility path is not a grant source
            # once any shared order/trial/message journal has been initialized.
            if (operation_id is not None or _has_table(db, 'registration_trials') or
                    _has_table(db, 'feishu_renewals')):
                raise PolicyError('ENTITLEMENT_NOT_RESERVED')
            expiry = db.execute('SELECT expires_ms FROM entitlements WHERE owner=?', (owner,)).fetchone()
            expires_ms = expiry[0] if expiry is not None else 0
        now = self.clock()
        desired = desired_permissions(baseline, expires_ms, now)
        return row, {'owner': owner, 'expires_ms': expires_ms, 'observed_ms': now,
                     'active': expires_ms > now, 'before': {'/': current}, 'after': desired,
                     'changes_permissions': current != desired['/'],
                     'preserves_login_and_password': True}

    def dry_run(self, owner, *, operation_id=None, expected_account_id=None):
        self.guard()
        account = self.adapter.read(owner)
        with self.db() as db:
            _, result = self.observation(db, owner, account, operation_id, expected_account_id)
        result['executable'] = self.adapter.atomic_fenced_permissions is True
        return result

    def reconcile(self, owner, *, operation_id=None, expected_account_id=None):
        """No expiry/job policy accepted from callers: reread authoritative ledger.

        The injected sandbox adapter MUST atomically compare revision and reject
        older generations. A capability flag is not a substitute for proving a
        production implementation; this module refuses live mode regardless.
        """
        self.guard()
        if self.adapter.atomic_fenced_permissions is not True:
            raise PolicyError('ATOMIC_FENCING_REQUIRED')
        if expected_account_id is not None and getattr(self.adapter, 'atomic_account_identity', False) is not True:
            raise PolicyError('ATOMIC_ACCOUNT_IDENTITY_REQUIRED')
        account = self.adapter.read(owner)
        with self.tx() as db:
            row, result = self.observation(db, owner, account, operation_id, expected_account_id)
            generation = row['generation'] + 1
            db.execute('UPDATE managed_upload_policy SET generation=? WHERE owner=?', (generation, owner))
            db.execute('INSERT INTO upload_policy_jobs(owner,generation,expires_ms,observed_ms,desired,state,operation_id,account_id) VALUES(?,?,?,?,?,?,?,?)',
                       (owner, generation, result['expires_ms'], result['observed_ms'],
                        json.dumps(result['after']), 'pending', operation_id, expected_account_id))
        # No database lock during remote I/O. On unknown outcome, later attempts
        # get a NEW generation and current ledger, never replay an old expiry.
        try:
            if expected_account_id is None:
                self.adapter.apply_permissions(owner, account.revision, generation, result['after'])
            else:
                self.adapter.apply_account_permissions(owner, expected_account_id, account.revision,
                                                       generation, result['after'])
            observed = self.adapter.read(owner)
            checked_permissions(observed)
            if (observed.username != owner or observed.permissions != result['after'] or
                    (expected_account_id is not None and observed.account_id != expected_account_id)):
                raise PolicyError('POLICY_READBACK_MISMATCH')
        except Exception:
            with self.tx() as db:
                db.execute("UPDATE upload_policy_jobs SET state='retry_required' WHERE owner=? AND generation=?", (owner, generation))
            raise PolicyError('POLICY_RETRY_REQUIRED') from None
        with self.tx() as db:
            db.execute("UPDATE upload_policy_jobs SET state='confirmed' WHERE owner=? AND generation=?", (owner, generation))
        return dict(result, generation=generation)


class OrderUploadAdapter:
    """Sandbox bridge for Orders.adapter; no use of account expiration_date.

    The service must reserve a proved trial or donation in the same database
    before invoking ensure. observed_expiry reports a confirmed permission-job ledger
    target, not the SFTPGo account's login expiration property.
    """
    def __init__(self, policy):
        self.policy = policy

    def _ensure(self, username, expires_at_ms, operation_id, account_id=None):
        self.policy.guard()
        with self.policy.db() as db:
            order = db.execute('SELECT owner,state,target_ms FROM orders WHERE id=?', (operation_id,)).fetchone()
            trial = db.execute('SELECT owner,state,expires_ms FROM registration_trials WHERE operation_id=?', (operation_id,)).fetchone()
            renewal = db.execute('SELECT owner,state,target_ms,account_id FROM feishu_renewals WHERE operation_id=?', (operation_id,)).fetchone()
            ledger = db.execute('SELECT expires_ms FROM entitlements WHERE owner=?', (username,)).fetchone()
            paid_reserved = (order is not None and order['owner'] == username and
                             order['state'] in ('applying', 'approved') and order['target_ms'] == expires_at_ms)
            trial_reserved = (trial is not None and trial['owner'] == username and
                              trial['state'] in ('applying', 'confirmed') and trial['expires_ms'] == expires_at_ms)
            message_reserved = (renewal is not None and renewal['owner'] == username and
                                renewal['state'] in ('applying', 'confirmed') and renewal['target_ms'] == expires_at_ms and
                                account_id is not None and renewal['account_id'] == account_id)
            if account_id is not None and not message_reserved:
                raise PolicyError('ACCOUNT_IDENTITY_MISMATCH')
            if (not (paid_reserved or trial_reserved or message_reserved) or ledger is None or ledger[0] < expires_at_ms):
                raise PolicyError('ENTITLEMENT_NOT_RESERVED')
        self.policy.reconcile(username, operation_id=operation_id, expected_account_id=account_id)

    def ensure(self, username, expires_at_ms, operation_id):
        self._ensure(username, expires_at_ms, operation_id)

    def ensure_account(self, identity, expires_at_ms, operation_id):
        self._ensure(identity.username, expires_at_ms, operation_id, identity.account_id)

    def observed_expiry(self, username):
        self.policy.guard()
        with self.policy.db() as db:
            job = db.execute('SELECT * FROM upload_policy_jobs WHERE owner=? ORDER BY generation DESC LIMIT 1', (username,)).fetchone()
            if job is None or job['state'] != 'confirmed':
                raise PolicyError('PERMISSIONS_NOT_CONFIRMED')
        preview = self.policy.dry_run(username, operation_id=job['operation_id'], expected_account_id=job['account_id'])
        if (job['expires_ms'] != preview['expires_ms'] or
                json.loads(job['desired']) != preview['after'] or preview['before'] != preview['after']):
            raise PolicyError('PERMISSIONS_NOT_CONFIRMED')
        return job['expires_ms']

    def observed_account_expiry(self, identity):
        account = self.policy.adapter.read(identity.username)
        if account.username != identity.username or account.account_id != identity.account_id:
            raise PolicyError('ACCOUNT_IDENTITY_MISMATCH')
        return self.observed_expiry(identity.username)
