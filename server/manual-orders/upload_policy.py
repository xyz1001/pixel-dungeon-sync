"""Disabled-by-default, sandbox-only SFTPGo permission reconciliation seam.

No ordinary SFTPGo PUT implementation: it cannot satisfy atomic fencing below.
"""
from dataclasses import dataclass
import json
import sqlite3
from contextlib import contextmanager
import time


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
    if type(expires_ms) is not int or expires_ms <= 0:
        raise PolicyError('MISSING_ENTITLEMENT_BASELINE')
    # Never invent or widen active permissions; expiration is an exact boundary.
    return {'/': list(baseline) if expires_ms > now_ms else sorted(READ)}


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
        """Explicit sandbox enrollment; stores permission baseline, no remote writes."""
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

    def observation(self, db, owner, account):
        current = checked_permissions(account)
        if account.username != owner:
            raise PolicyError('ACCOUNT_IDENTITY_MISMATCH')
        row = db.execute('SELECT * FROM managed_upload_policy WHERE owner=?', (owner,)).fetchone()
        if row is None:
            raise PolicyError('ACCOUNT_NOT_MANAGED')
        baseline = json.loads(row['baseline'])
        if current not in (baseline, sorted(READ)):
            raise PolicyError('EXTERNAL_PERMISSION_CHANGE')
        expiry = db.execute('SELECT expires_ms FROM entitlements WHERE owner=?', (owner,)).fetchone()
        if expiry is None:
            raise PolicyError('MISSING_ENTITLEMENT_BASELINE')
        now = self.clock()
        desired = desired_permissions(baseline, expiry[0], now)
        return row, {'owner': owner, 'expires_ms': expiry[0], 'observed_ms': now,
                     'active': expiry[0] > now, 'before': {'/': current}, 'after': desired,
                     'changes_permissions': current != desired['/'],
                     'preserves_login_and_password': True}

    def dry_run(self, owner):
        self.guard()
        account = self.adapter.read(owner)
        with self.db() as db:
            _, result = self.observation(db, owner, account)
        result['executable'] = self.adapter.atomic_fenced_permissions is True
        return result

    def reconcile(self, owner):
        """No expiry/job policy accepted from callers: reread authoritative ledger.

        The injected sandbox adapter MUST atomically compare revision and reject
        older generations. A capability flag is not a substitute for proving a
        production implementation; this module refuses live mode regardless.
        """
        self.guard()
        if self.adapter.atomic_fenced_permissions is not True:
            raise PolicyError('ATOMIC_FENCING_REQUIRED')
        account = self.adapter.read(owner)
        with self.tx() as db:
            row, result = self.observation(db, owner, account)
            generation = row['generation'] + 1
            db.execute('UPDATE managed_upload_policy SET generation=? WHERE owner=?', (generation, owner))
            db.execute('INSERT INTO upload_policy_jobs VALUES(?,?,?,?,?,?)',
                       (owner, generation, result['expires_ms'], result['observed_ms'],
                        json.dumps(result['after']), 'pending'))
        # No database lock during remote I/O. On unknown outcome, later attempts
        # get a NEW generation and current ledger, never replay an old expiry.
        try:
            self.adapter.apply_permissions(owner, account.revision, generation, result['after'])
            observed = self.adapter.read(owner)
            checked_permissions(observed)
            if observed.username != owner or observed.permissions != result['after']:
                raise PolicyError('POLICY_READBACK_MISMATCH')
        except Exception:
            with self.tx() as db:
                db.execute("UPDATE upload_policy_jobs SET state='retry_required' WHERE owner=? AND generation=?", (owner, generation))
            raise PolicyError('POLICY_RETRY_REQUIRED') from None
        with self.tx() as db:
            db.execute("UPDATE upload_policy_jobs SET state='confirmed' WHERE owner=? AND generation=?", (owner, generation))
        return dict(result, generation=generation)
