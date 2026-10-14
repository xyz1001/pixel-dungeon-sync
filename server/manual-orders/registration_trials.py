"""Sandbox registration journal. No upstream implementation or public route.

The ordinary create callback is deliberately insufficient: successful upstream
creation must atomically retain the operation ID and its original server time,
or offer equivalent trusted recovery evidence. Passwords never enter this DB.
"""
from dataclasses import dataclass
import re
import secrets
import sqlite3

from orders import OrderError


TRIAL_MS = 72 * 60 * 60 * 1000


@dataclass(frozen=True)
class RegistrationProof:
    owner: str
    operation_id: str
    created_ms: int


class DisabledRegistration:
    durable_registration_proof = False

    def register(self, owner, password, operation_id):
        raise OrderError(503, 'REGISTRATION_ADAPTER_DISABLED')


class RegistrationTrials:
    """Inject only into the trusted named-registration handler, never login.

    Adapter contract: atomically create a read-only account plus recoverable
    RegistrationProof; retry the SAME operation after timeout/process death and
    return the SAME original server creation time. A pre-existing account from
    another operation must be rejected. Every retry, including a confirmed one,
    must authenticate the supplied password without changing it. Initial account
    status/login expiry and the separately retained activation template must
    preserve login/download. Template enrollment must finish idempotently before
    returning proof. No production adapter is supplied.
    """
    def __init__(self, orders, *, registrations=None, mode='disabled'):
        if mode not in ('disabled', 'sandbox'):
            raise ValueError('live registration trials are unavailable')
        self.orders, self.mode = orders, mode
        self.registrations = registrations or DisabledRegistration()

    def guard(self):
        if self.mode != 'sandbox' or self.orders.mode != 'sandbox':
            raise OrderError(503, 'REGISTRATION_TRIALS_DISABLED')
        if getattr(self.registrations, 'durable_registration_proof', False) is not True:
            raise OrderError(503, 'DURABLE_REGISTRATION_PROOF_REQUIRED')

    @staticmethod
    def public(row):
        return {key: row[key] for key in ('created_ms', 'expires_ms', 'state')}

    def _reserve(self, owner):
        # Commit intent BEFORE any remote I/O. Retain even after an uncertain
        # failure, so a retry never creates a new operation or restarts the clock.
        with self.orders.transaction() as db:
            row = db.execute('SELECT * FROM registration_trials WHERE owner=?', (owner,)).fetchone()
            if row is None:
                operation = 'registration_' + secrets.token_hex(16)
                db.execute("INSERT INTO registration_trials(owner,operation_id,requested_ms,state) VALUES(?,?,?,'pending')",
                           (owner, operation, self.orders.clock()))
                self.orders.audit(db, operation, 'system', 'registration_reserved')
                row = db.execute('SELECT * FROM registration_trials WHERE owner=?', (owner,)).fetchone()
            return row

    def _record_proof(self, proof):
        """Internal durable boundary; callers must never expose it as an API."""
        with self.orders.transaction() as db:
            row = db.execute('SELECT * FROM registration_trials WHERE owner=?', (proof.owner,)).fetchone()
            if row is None or row['operation_id'] != proof.operation_id:
                raise OrderError(409, 'REGISTRATION_PROOF_CONFLICT')
            if row['state'] != 'pending':
                if row['created_ms'] != proof.created_ms:
                    raise OrderError(409, 'REGISTRATION_PROOF_CONFLICT')
                return
            expiry = proof.created_ms + TRIAL_MS
            db.execute("UPDATE registration_trials SET created_ms=?,expires_ms=?,state='applying' WHERE owner=?",
                       (proof.created_ms, expiry, proof.owner))
            db.execute('INSERT INTO entitlements VALUES(?,?) ON CONFLICT(owner) DO UPDATE SET expires_ms=MAX(entitlements.expires_ms,excluded.expires_ms)',
                       (proof.owner, expiry))
            self.orders.audit(db, proof.operation_id, 'system', 'registration_proved')

    def _apply(self, owner):
        token = secrets.token_hex(16)
        with self.orders.transaction() as db:
            row = db.execute('SELECT * FROM registration_trials WHERE owner=?', (owner,)).fetchone()
            if row['state'] == 'confirmed':
                return self.public(row)
            if row['lease_token'] and row['lease_until_ms'] > self.orders.clock():
                raise OrderError(409, 'REGISTRATION_IN_PROGRESS')
            db.execute('UPDATE registration_trials SET lease_token=?,lease_until_ms=? WHERE owner=?',
                       (token, self.orders.clock() + 30_000, owner))
        failed = False
        try:
            self.orders.adapter.ensure(owner, row['expires_ms'], row['operation_id'])
            observed = self.orders.adapter.observed_expiry(owner)
            if type(observed) is not int or observed < row['expires_ms']:
                raise OrderError(503, 'ENTITLEMENT_NOT_CONFIRMED')
        except Exception:
            failed = True
        with self.orders.transaction() as db:
            current = db.execute('SELECT * FROM registration_trials WHERE owner=?', (owner,)).fetchone()
            if current['lease_token'] != token:
                raise OrderError(409, 'REGISTRATION_IN_PROGRESS')
            db.execute('UPDATE registration_trials SET lease_token=NULL,lease_until_ms=NULL WHERE owner=?', (owner,))
            if failed:
                self.orders.audit(db, row['operation_id'], 'system', 'trial_retry_required')
            else:
                db.execute("UPDATE registration_trials SET state='confirmed' WHERE owner=?", (owner,))
                self.orders.audit(db, row['operation_id'], 'system', 'trial_confirmed')
            result = self.public(db.execute('SELECT * FROM registration_trials WHERE owner=?', (owner,)).fetchone())
        if failed:
            raise OrderError(503, 'REGISTRATION_TRIAL_RETRY_REQUIRED')
        return result

    def register(self, owner, password):
        """No caller timestamp, term, account event ID, or expiry is accepted."""
        self.guard()
        if not isinstance(owner, str) or not re.fullmatch(r'[a-z][a-z0-9_-]{2,31}', owner):
            raise OrderError(400, 'INVALID_USERNAME')
        if not isinstance(password, str) or not password:
            raise OrderError(400, 'INVALID_PASSWORD')
        try:
            row = self._reserve(owner)
            # Always authenticate on retry, even if this trial already confirmed.
            proof = self.registrations.register(owner, password, row['operation_id'])
            if (not isinstance(proof, RegistrationProof) or proof.owner != owner or
                proof.operation_id != row['operation_id'] or type(proof.created_ms) is not int or
                not 0 <= proof.created_ms <= self.orders.clock()):
                raise OrderError(503, 'INVALID_REGISTRATION_PROOF')
            self._record_proof(proof)
            return self._apply(owner)
        except OrderError:
            raise
        except sqlite3.Error:
            raise OrderError(503, 'REGISTRATION_DATABASE_UNAVAILABLE') from None
        except Exception:
            raise OrderError(503, 'REGISTRATION_UPSTREAM_UNAVAILABLE') from None
