"""Offline renewal ledger; call only after trusted Feishu event authorization.

No HTTP listener, signature verifier, Feishu transport, account lookup or real
permission implementation is supplied here. Message text is never an identity
proof. The bot verifies platform scope, dedicated chat and designated sender;
this service independently resolves the exact existing product account.
"""
from dataclasses import dataclass
import re
import secrets
import sqlite3

from orders import Orders, OrderError, extend_ms, reserved_paid_expiry


@dataclass(frozen=True)
class AccountIdentity:
    username: str
    account_id: str


class DisabledAccounts:
    def lookup_exact(self, username):
        raise OrderError(503, 'ACCOUNT_ADAPTER_DISABLED')


class DisabledAccountEntitlements:
    def ensure_account(self, identity, expires_at_ms, operation_id):
        raise OrderError(503, 'ENTITLEMENT_ADAPTER_DISABLED')

    def observed_account_expiry(self, identity):
        raise OrderError(503, 'ENTITLEMENT_ADAPTER_DISABLED')


class FeishuRenewals:
    """Internal, disabled-by-default service, not an authentication boundary.

    accounts.lookup_exact returns AccountIdentity with a stable authoritative
    account ID, or None. No fuzzy match, inferred account, or account creation.
    entitlements.ensure_account(identity, absolute_expiry_ms, operation_id) must
    atomically target that same immutable account ID, be monotonic/idempotent,
    and perform bounded I/O. observed_account_expiry(identity) must read back
    that account's applied entitlement. A username-only writer is insufficient.
    Neither adapter is implemented for production.
    """
    def __init__(self, path, *, enabled=False, accounts=None, entitlements=None, clock=None):
        self.enabled = enabled
        # Reuse the same additive schema, connection and transaction discipline.
        # No Orders API, Principal or receipt is needed by this authorization path.
        self.ledger = Orders(path, clock=clock)
        self.accounts = accounts or DisabledAccounts()
        self.adapter = entitlements or DisabledAccountEntitlements()

    @staticmethod
    def result(row=None, *, status=None, username=None, code=None):
        result = {'status': status or row['state'],
                  'username': row['owner'] if row is not None else username,
                  'target_ms': row['target_ms'] if row is not None else None,
                  'operation_id': row['operation_id'] if row is not None else None}
        if row is not None:
            result['state'] = row['state']
        if code:
            result['code'] = code
        return result

    @staticmethod
    def _scope_valid(values):
        # Opaque platform IDs: no normalization, whitespace, secrets, or text.
        return all(isinstance(value, str) and re.fullmatch(r'[A-Za-z0-9_.:@-]{1,256}', value)
                   for value in values)

    @staticmethod
    def _same_message(row, chat_id, sender_id, username):
        if (row['chat_id'], row['sender_id'], row['owner']) != (chat_id, sender_id, username):
            raise OrderError(409, 'IDEMPOTENCY_CONFLICT')

    def _read(self, db, scope):
        return db.execute('SELECT * FROM feishu_renewals WHERE app_id=? AND tenant_key=? AND message_id=?', scope).fetchone()

    def _identity(self, username):
        try:
            identity = self.accounts.lookup_exact(username)
        except Exception:
            raise OrderError(503, 'ACCOUNT_LOOKUP_UNAVAILABLE') from None
        if identity is not None and (not isinstance(identity, AccountIdentity) or
                identity.username != username or not isinstance(identity.account_id, str) or
                not identity.account_id or len(identity.account_id) > 256):
            raise OrderError(503, 'INVALID_ACCOUNT_IDENTITY')
        return identity

    def _reserve(self, scope, chat_id, sender_id, username, identity):
        with self.ledger.transaction() as db:
            row = self._read(db, scope)
            if row is not None:
                self._same_message(row, chat_id, sender_id, username)
                return row
            # Never silently carry another incarnation's authorizations to a
            # recreated username. Existing legacy records need trusted migration.
            if identity is not None and db.execute(
                    'SELECT 1 FROM feishu_renewals WHERE owner=? AND account_id IS NOT NULL AND account_id<>?',
                    (username, identity.account_id)).fetchone():
                raise OrderError(409, 'ACCOUNT_IDENTITY_CONFLICT')
            now = self.ledger.clock()
            if type(now) is not int or now < 0:
                raise OrderError(503, 'INVALID_SERVER_CLOCK')
            operation = 'feishu_' + secrets.token_hex(16)
            target = (extend_ms(max(now, reserved_paid_expiry(db, username) or 0), 'calendar_year', 0)
                      if identity is not None else None)
            state = 'reserved' if identity is not None else 'unknown_user'
            db.execute('''INSERT INTO feishu_renewals(
                operation_id,app_id,tenant_key,message_id,chat_id,sender_id,owner,
                account_id,received_ms,target_ms,state) VALUES(?,?,?,?,?,?,?,?,?,?,?)''',
                       (operation, *scope, chat_id, sender_id, username,
                        identity.account_id if identity else None, now, target, state))
            if target is not None:
                db.execute('INSERT INTO entitlements VALUES(?,?) ON CONFLICT(owner) DO UPDATE SET expires_ms=MAX(entitlements.expires_ms,excluded.expires_ms)',
                           (username, target))
            self.ledger.audit(db, operation, sender_id,
                              'feishu_renewal_reserved' if target is not None else 'feishu_unknown_user')
            return self._read(db, scope)

    def _apply(self, scope, identity):
        token = secrets.token_hex(16)
        with self.ledger.transaction() as db:
            row = self._read(db, scope)
            if row['state'] == 'confirmed':
                return self.result(row)
            if row['lease_token'] and row['lease_until_ms'] > self.ledger.clock():
                return self.result(row, status='applying', code='RENEWAL_IN_PROGRESS')
            db.execute("UPDATE feishu_renewals SET state='applying',attempts=attempts+1,lease_token=?,lease_until_ms=? WHERE operation_id=?",
                       (token, self.ledger.clock() + 30_000, row['operation_id']))
            self.ledger.audit(db, row['operation_id'], row['sender_id'], 'feishu_apply_started')
        failed = False
        try:
            self.adapter.ensure_account(identity, row['target_ms'], row['operation_id'])
            observed = self.adapter.observed_account_expiry(identity)
            if type(observed) is not int or observed < row['target_ms']:
                raise OrderError(503, 'ENTITLEMENT_NOT_CONFIRMED')
        except Exception:
            failed = True
        with self.ledger.transaction() as db:
            current = self._read(db, scope)
            if current['lease_token'] != token:
                # A late worker may not acknowledge a newer worker's lease.
                return self.result(current, status='applying', code='RENEWAL_IN_PROGRESS')
            db.execute('UPDATE feishu_renewals SET lease_token=NULL,lease_until_ms=NULL WHERE operation_id=?',
                       (row['operation_id'],))
            if failed:
                self.ledger.audit(db, row['operation_id'], row['sender_id'], 'feishu_apply_retry_required')
            else:
                db.execute("UPDATE feishu_renewals SET state='confirmed' WHERE operation_id=?", (row['operation_id'],))
                self.ledger.audit(db, row['operation_id'], row['sender_id'], 'feishu_renewal_confirmed')
            current = self._read(db, scope)
            return self.result(current, status='retry_required' if failed else None,
                               code='ENTITLEMENT_RETRY_REQUIRED' if failed else None)

    def renew(self, *, app_id, tenant_key, message_id, chat_id, sender_id, username):
        """One authorized message grants one year; every retry fixes its target.

        No price, transaction, trial duration, start time or caller expiry exists
        in this API. Reply delivery is separate and cannot change this record.
        """
        if self.enabled is not True:
            return self.result(status='disabled', username=username, code='FEISHU_RENEWALS_DISABLED')
        scope = app_id, tenant_key, message_id
        if (not self._scope_valid((*scope, chat_id, sender_id)) or
                not isinstance(username, str) or not re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}', username)):
            return self.result(status='rejected', code='INVALID_RENEWAL_REQUEST')
        row = None
        try:
            with self.ledger.connect() as db:
                row = self._read(db, scope)
            if row is not None:
                self._same_message(row, chat_id, sender_id, username)
                if row['state'] == 'unknown_user':
                    return self.result(row)
            identity = self._identity(username)
            if row is not None and (identity is None or row['account_id'] != identity.account_id):
                raise OrderError(409, 'ACCOUNT_IDENTITY_CONFLICT')
            if row is None:
                row = self._reserve(scope, chat_id, sender_id, username, identity)
                if row['state'] == 'unknown_user':
                    return self.result(row)
                # Another delivery may have won the reservation while lookup ran.
                if identity is None or row['account_id'] != identity.account_id:
                    raise OrderError(409, 'ACCOUNT_IDENTITY_CONFLICT')
            return self._apply(scope, identity)
        except OrderError as error:
            # Never leak another account's target or platform identifiers on
            # same-message/different-recipient conflicts.
            if error.status == 409:
                return self.result(status='conflict', username=username, code=error.code)
            return self.result(row, status='retry_required', username=username, code=error.code)
        except sqlite3.Error:
            return self.result(row, status='retry_required', username=username, code='DATABASE_UNAVAILABLE')
        except Exception:
            return self.result(row, status='retry_required', username=username, code='RENEWAL_UNAVAILABLE')
