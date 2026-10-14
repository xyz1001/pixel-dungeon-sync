"""Real disposable SQLite authority tests; account provider is a test fixture."""
import base64
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, replace
from datetime import datetime
from pathlib import Path
import sqlite3
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'manual-orders'))
from feishu_renewals import AccountIdentity, FeishuRenewals
from ledger_gate import AccountWriteGate, ManagedLedger, SftpgoBasicAuthenticator
from orders import OrderError, Orders, Plan, Principal, extend_ms
from registration_trials import TRIAL_MS
from write_gate import READ_METHODS, WRITE_METHODS
from sftpgo_client import SftpgoError


def ms(value):
    return int(datetime.fromisoformat(value).timestamp() * 1000)


def basic(username='alice', password='password'):
    return {'Authorization': 'Basic ' + base64.b64encode((username + ':' + password).encode()).decode()}


@dataclass(frozen=True)
class Account:
    username: str
    account_id: str
    created_at_ms: int
    registration_operation_id: str = None


class Provider:
    """Test fixture only; no application implementation uses this store."""
    def __init__(self, clock):
        self.clock = clock
        self.accounts = {'alice': Account('alice', 'instance:11:1700000000000', 1700000000000)}
        self.passwords = {'alice': 'password'}
        self.register_calls = 0
        self.auth_calls = 0
        self.lock = threading.RLock()

    def lookup(self, username):
        return self.accounts.get(username)

    def authenticate_basic(self, username, password):
        self.auth_calls += 1
        if self.passwords.get(username) != password:
            raise OrderError(401, 'AUTHENTICATION_REQUIRED')
        account = self.lookup(username)
        if account is None:
            raise OrderError(401, 'AUTHENTICATION_REQUIRED')
        return account

    def create_or_recover(self, username, password, operation_id):
        with self.lock:
            self.register_calls += 1
            account = self.accounts.get(username)
            if account is None:
                created = self.clock()
                account = Account(username, f'instance:{len(self.accounts) + 100}:{created}', created, operation_id)
                self.accounts[username] = account
                self.passwords[username] = password
            if account.registration_operation_id != operation_id:
                raise OrderError(409, 'ACCOUNT_ALREADY_EXISTS')
            return self.authenticate_basic(username, password)


class LedgerGateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'ledger.sqlite'
        self.now = ms('2024-02-29T12:34:56.789+00:00')
        self.accounts = Provider(lambda: self.now)
        self.ledger = self.make()
        self.auth = SftpgoBasicAuthenticator(self.accounts)
        self.gate = AccountWriteGate(enabled=True, authenticator=self.auth,
                                     ledger=self.ledger, clock=lambda: self.now)

    def make(self, **extra):
        options = dict(accounts=self.accounts, enabled=True, clock=lambda: self.now)
        options.update(extra)
        return ManagedLedger(self.path, **options)

    def renew(self, message='message-1', ledger=None, **extra):
        values = dict(app_id='app', tenant_key='tenant', message_id=message,
                      chat_id='dedicated', sender_id='admin', username='alice')
        values.update(extra)
        return (ledger or self.ledger).renew(**values)

    def identity(self, username='alice'):
        account = self.accounts.lookup(username)
        return AccountIdentity(account.username, account.account_id)

    def query(self, sql, args=()):
        with self.ledger.journal.connect() as db:
            return db.execute(sql, args).fetchone()

    def test_disabled_services_do_not_authenticate_create_or_grant(self):
        disabled = self.make(enabled=False)
        self.assertEqual('disabled', self.renew(ledger=disabled)['status'])
        with self.assertRaisesRegex(OrderError, 'REGISTRATION_TRIALS_DISABLED'):
            disabled.register('newuser', 'password')
        self.assertEqual(0, self.accounts.register_calls)
        gate = AccountWriteGate(authenticator=self.auth, ledger=disabled)
        self.assertEqual(503, gate.decide('PUT', basic()).status)
        self.assertEqual(0, self.accounts.auth_calls)

    def test_local_receipt_and_confirmation_survive_restart(self):
        result = self.renew()
        self.assertEqual('confirmed', result['status'])
        self.assertEqual(extend_ms(self.now, 'calendar_year', 0), result['target_ms'])
        self.assertEqual(result['target_ms'], self.make().expiry(self.identity()))
        self.assertEqual(1, self.query('SELECT COUNT(*) FROM managed_gate_applications')[0])
        self.now += 100000
        self.assertEqual(result, self.renew(ledger=self.make()))
        self.assertEqual(1, self.query('SELECT COUNT(*) FROM managed_gate_applications')[0])
        self.assertEqual(204, self.gate.decide('PUT', basic()).status)

    def test_exact_expiry_each_mutation_reads_and_auth_preserved(self):
        expiry = self.renew()['target_ms']
        self.now = expiry - 1
        for method in WRITE_METHODS:
            self.assertEqual(204, self.gate.decide(method, basic()).status)
        self.now = expiry
        for method in WRITE_METHODS:
            self.assertEqual('UPLOAD_ENTITLEMENT_EXPIRED', self.gate.decide(method, basic()).code)
        for method in READ_METHODS:
            self.assertEqual(204, self.gate.decide(method, basic()).status)
            self.assertEqual(401, self.gate.decide(method, basic(password='wrong')).status)
        self.assertEqual(405, self.gate.decide('TRACE', basic()).status)

    def test_missing_and_failed_ledger_deny_writes_but_leave_reads(self):
        self.assertEqual(403, self.gate.decide('PUT', basic()).status)
        self.path.rename(self.path.with_suffix('.moved'))
        self.assertEqual(503, self.gate.decide('PUT', basic()).status)
        self.assertFalse(self.path.exists())
        for method in READ_METHODS:
            self.assertEqual(204, self.gate.decide(method, basic()).status)

    def test_forged_username_and_invalid_basic_cannot_grant(self):
        self.renew()
        for headers in ({'X-User': 'alice'}, {'Authorization': 'Bearer fake'},
                        {'Authorization': 'Basic !!!'}, {'Authorization': 'Basic '},
                        {'Authorization': 'Basic YWxpY2U6'},
                        {'Authorization': basic()['Authorization'], 'authorization': basic()['Authorization']}):
            self.assertEqual(401, self.gate.decide('PUT', headers).status)
        self.assertEqual(401, self.gate.decide('PUT', basic(password='wrong')).status)
        self.accounts.accounts['bob'] = Account('bob', 'instance:12:1700000000000', 1700000000000)
        self.accounts.passwords['bob'] = 'password'
        headers = {**basic('bob'), 'X-User': 'alice'}
        self.assertEqual(403, self.gate.decide('PUT', headers).status)

    def test_identity_recreation_denies_new_grants_old_replays_and_writes(self):
        self.renew()
        old = self.accounts.accounts['alice']
        self.accounts.accounts['alice'] = replace(old, account_id='instance:11:1800000000000', created_at_ms=1800000000000)
        self.assertEqual('ACCOUNT_IDENTITY_CONFLICT', self.gate.decide('PUT', basic()).code)
        self.assertEqual('conflict', self.renew()['status'])
        self.assertEqual('conflict', self.renew('new-message')['status'])
        self.assertEqual(204, self.gate.decide('GET', basic()).status)

    def test_desired_cache_and_unconfirmed_receipt_are_not_authority(self):
        with self.ledger.journal.transaction() as db:
            db.execute('INSERT INTO entitlements VALUES(?,?)', ('alice', self.now + 10**12))
        self.assertIsNone(self.ledger.expiry(self.identity()))
        with patch.object(self.ledger.applications, 'observed_account_expiry', return_value=None):
            result = self.renew()
        self.assertEqual('retry_required', result['status'])
        self.assertEqual(1, self.query('SELECT COUNT(*) FROM managed_gate_applications')[0])
        self.assertIsNone(self.ledger.expiry(self.identity()))
        self.assertEqual(403, self.gate.decide('PUT', basic()).status)
        frozen = result['target_ms']
        self.now += 40000
        retried = self.renew(ledger=self.make())
        self.assertEqual('confirmed', retried['status'])
        self.assertEqual(frozen, retried['target_ms'])

    def test_crash_after_local_application_recovers_same_target_after_lease(self):
        with patch.object(self.ledger.applications, 'observed_account_expiry', side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt):
                self.renew()
        row = self.query('SELECT * FROM feishu_renewals')
        self.assertEqual('applying', row['state'])
        self.assertIsNone(self.ledger.expiry(self.identity()))
        self.assertEqual('applying', self.renew()['status'])
        self.now += 31000
        recovered = self.renew(ledger=self.make())
        self.assertEqual('confirmed', recovered['status'])
        self.assertEqual(row['target_ms'], recovered['target_ms'])
        self.assertEqual(1, self.query('SELECT COUNT(*) FROM managed_gate_applications')[0])

    def test_uncertain_response_after_confirmation_committed_no_extra_year(self):
        original = self.ledger.renewals._apply
        def lost_reply(scope, identity):
            original(scope, identity)
            raise TimeoutError('private information')
        with patch.object(self.ledger.renewals, '_apply', side_effect=lost_reply):
            result = self.renew()
        self.assertEqual('retry_required', result['status'])
        row = self.query('SELECT * FROM feishu_renewals')
        self.assertEqual('confirmed', row['state'])
        self.assertEqual(row['target_ms'], self.ledger.expiry(self.identity()))
        self.now += 45000
        replay = self.renew(ledger=self.make())
        self.assertEqual('confirmed', replay['status'])
        self.assertEqual(row['target_ms'], replay['target_ms'])
        self.assertEqual(row['operation_id'], replay['operation_id'])

    def test_concurrent_deliveries_and_different_messages_serialize_years(self):
        with ThreadPoolExecutor(max_workers=6) as pool:
            results = list(pool.map(lambda _: self.renew(), range(6)))
        self.assertTrue(all(r['status'] in ('confirmed', 'applying') for r in results))
        first = self.renew()
        self.assertEqual(1, self.query('SELECT COUNT(*) FROM feishu_renewals')[0])
        with ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(lambda n: self.renew(f'message-{n}'), range(2, 6)))
        self.assertTrue(all(r['status'] == 'confirmed' for r in results), results)
        expected = first['target_ms']
        for _ in range(4):
            expected = extend_ms(expected, 'calendar_year', 0)
        self.assertEqual(expected, self.ledger.expiry(self.identity()))

    def test_old_unconfirmed_grant_does_not_shorten_later_confirmation(self):
        with patch.object(self.ledger.applications, 'observed_account_expiry', return_value=None):
            first = self.renew()
        second = self.renew('message-2')
        self.assertEqual(extend_ms(first['target_ms'], 'calendar_year', 0), second['target_ms'])
        retried = self.renew()
        self.assertEqual(first['target_ms'], retried['target_ms'])
        self.assertEqual(second['target_ms'], self.ledger.expiry(self.identity()))

    def test_unknown_user_terminal_no_create_and_message_content_conflict(self):
        unknown = self.renew(username='missing')
        self.assertEqual('unknown_user', unknown['status'])
        self.accounts.accounts['missing'] = Account('missing', 'instance:14:1', 1)
        self.assertEqual(unknown, self.renew(username='missing'))
        conflict = self.renew(username='alice')
        self.assertEqual('conflict', conflict['status'])
        self.assertIsNone(conflict['target_ms'])
        self.assertEqual(0, self.accounts.register_calls)

    def test_trial_original_server_creation_and_password_rechecked_each_retry(self):
        result = self.ledger.register('newuser', 'new-password')
        self.assertEqual({'created_ms': self.now, 'expires_ms': self.now + TRIAL_MS, 'state': 'confirmed'}, result)
        identity = self.identity('newuser')
        self.assertEqual(result['expires_ms'], self.ledger.expiry(identity))
        self.now += TRIAL_MS + 1
        self.assertEqual(result, self.make().register('newuser', 'new-password'))
        self.assertEqual(2, self.accounts.auth_calls)
        with self.assertRaisesRegex(OrderError, 'AUTHENTICATION_REQUIRED'):
            self.ledger.register('newuser', 'wrong')
        self.assertEqual(result['expires_ms'], self.ledger.expiry(identity))
        self.assertEqual(403, self.gate.decide('PUT', basic('newuser', 'new-password')).status)
        self.assertEqual(204, self.gate.decide('GET', basic('newuser', 'new-password')).status)

    def test_paid_first_year_excludes_trial_and_expired_paid_starts_now(self):
        self.ledger.register('newuser', 'password')
        paid = self.renew(username='newuser')
        self.assertEqual(extend_ms(self.now, 'calendar_year', 0), paid['target_ms'])
        self.now = paid['target_ms'] + 1000
        renewed = self.renew('message-2', username='newuser')
        self.assertEqual(extend_ms(self.now, 'calendar_year', 0), renewed['target_ms'])

    def test_registration_post_creation_crash_recovers_original_operation_time(self):
        original = self.accounts.create_or_recover
        def interrupted(*args):
            original(*args)
            raise KeyboardInterrupt
        with patch.object(self.accounts, 'create_or_recover', side_effect=interrupted):
            with self.assertRaises(KeyboardInterrupt):
                self.ledger.register('newuser', 'password')
        account = self.accounts.lookup('newuser')
        self.assertIsNone(self.ledger.expiry(self.identity('newuser')))
        self.now += 20000
        result = self.make().register('newuser', 'password')
        self.assertEqual(account.created_at_ms, result['created_ms'])
        self.assertEqual(account.created_at_ms + TRIAL_MS, result['expires_ms'])
        self.assertEqual(account.registration_operation_id, self.query('SELECT operation_id FROM registration_trials')[0])

    def test_registration_unconfirmed_receipt_does_not_grant_and_retries_fixed_expiry(self):
        with patch.object(self.ledger.applications, 'observed_expiry', return_value=None):
            with self.assertRaisesRegex(OrderError, 'REGISTRATION_TRIAL_RETRY_REQUIRED'):
                self.ledger.register('newuser', 'password')
        first = self.query('SELECT * FROM registration_trials')
        self.assertIsNone(self.ledger.expiry(self.identity('newuser')))
        self.now += 20000
        result = self.make().register('newuser', 'password')
        self.assertEqual(first['expires_ms'], result['expires_ms'])
        self.assertEqual('confirmed', result['state'])

    def test_preexisting_user_cannot_claim_trial_or_reset_password(self):
        with self.assertRaisesRegex(OrderError, 'ACCOUNT_ALREADY_EXISTS'):
            self.ledger.register('alice', 'new-password')
        self.assertEqual('password', self.accounts.passwords['alice'])
        self.assertIsNone(self.ledger.expiry(self.identity()))

    def test_provider_registration_errors_remain_bounded_and_keep_status(self):
        with patch.object(self.accounts, 'create_or_recover', side_effect=SftpgoError(409, 'USERNAME_UNAVAILABLE')):
            with self.assertRaises(OrderError) as caught:
                self.ledger.register('newuser', 'password')
        self.assertEqual(409, caught.exception.status)
        self.assertEqual('USERNAME_UNAVAILABLE', caught.exception.code)
        self.assertEqual(0, self.query('SELECT COUNT(*) FROM managed_gate_applications')[0])

    def test_old_username_only_trial_never_auto_binds(self):
        with self.ledger.journal.transaction() as db:
            db.execute("INSERT INTO registration_trials(owner,operation_id,requested_ms,created_ms,expires_ms,state) VALUES(?,?,?,?,?,'confirmed')",
                       ('alice', 'old-trial', self.now, self.now, self.now + TRIAL_MS))
        self.assertIsNone(self.ledger.expiry(self.identity()))
        self.assertEqual('conflict', self.renew()['status'])
        with self.assertRaisesRegex(OrderError, 'LEGACY_TRIAL_MIGRATION_REQUIRED'):
            self.ledger.register('alice', 'password')
        self.assertEqual(0, self.accounts.register_calls)

    def make_old_order(self):
        plan = Plan('annual', quota_bytes=1, upload_kbps=1, download_kbps=1)
        old = Orders(self.path, (plan,), mode='sandbox', clock=lambda: self.now)
        row = old.create(Principal('alice'), 'annual', 'legacy-key-0000001')
        old.submit(Principal('alice'), row['id'])
        with self.assertRaisesRegex(OrderError, 'ENTITLEMENT_RETRY_REQUIRED'):
            old.approve(Principal('admin', 'admin'), row['id'], 'receipt-00001', 'sandbox', 100, 'CNY')
        return self.query('SELECT * FROM orders WHERE id=?', (row['id'],))

    def test_old_applying_fixed_target_requires_explicit_binding_then_retries(self):
        old = self.make_old_order()
        denied = self.renew()
        self.assertEqual('LEGACY_ACCOUNT_MIGRATION_REQUIRED', denied['code'])
        self.assertEqual('conflict', denied['status'])
        self.assertIsNone(self.ledger.expiry(self.identity()))
        self.ledger.adopt_legacy_paid('alice', self.identity().account_id, [old['id']])
        self.assertIsNone(self.ledger.expiry(self.identity()))
        self.now += 100000
        recovered = self.make().retry_legacy_order(old['id'])
        self.assertEqual('approved', recovered['state'])
        self.assertEqual(old['target_ms'], recovered['target_ms'])
        self.assertEqual(old['target_ms'], self.ledger.expiry(self.identity()))
        paid = self.renew()
        self.assertEqual(extend_ms(old['target_ms'], 'calendar_year', 0), paid['target_ms'])

    def test_old_approved_only_authorizes_after_reviewed_adoption(self):
        old = self.make_old_order()
        with self.ledger.journal.transaction() as db:
            db.execute("UPDATE orders SET state='approved' WHERE id=?", (old['id'],))
        self.assertIsNone(self.ledger.expiry(self.identity()))
        self.ledger.adopt_legacy_paid('alice', self.identity().account_id, [old['id']])
        self.assertEqual(old['target_ms'], self.ledger.expiry(self.identity()))

    def test_migration_wrong_account_or_incomplete_manifest_does_not_bind(self):
        old = self.make_old_order()
        for expected, operations in [('wrong-id', [old['id']]), (self.identity().account_id, []),
                                     (self.identity().account_id, [old['id'], old['id']])]:
            with self.assertRaises(OrderError):
                self.ledger.adopt_legacy_paid('alice', expected, operations)
        self.assertEqual(0, self.query('SELECT COUNT(*) FROM managed_account_bindings')[0])
        self.assertIsNone(self.ledger.expiry(self.identity()))

    def test_old_feishu_applying_target_requires_review_then_same_message_confirmation(self):
        class Existing:
            def lookup_exact(inner, username):
                return self.identity(username)
        old = FeishuRenewals(self.path, enabled=True, accounts=Existing(), clock=lambda: self.now)
        result = self.renew(ledger=old)
        self.assertEqual('retry_required', result['status'])
        self.assertEqual('LEGACY_ACCOUNT_MIGRATION_REQUIRED', self.renew()['code'])
        self.ledger.adopt_legacy_paid('alice', self.identity().account_id, [result['operation_id']])
        self.now += 40000
        recovered = self.renew(ledger=self.make())
        self.assertEqual('confirmed', recovered['status'])
        self.assertEqual(result['target_ms'], recovered['target_ms'])

    def test_entitlement_reports_managed_only_after_binding(self):
        account = self.accounts.lookup('alice')
        self.assertEqual({'source': 'managed_ledger', 'username': 'alice', 'expires_ms': None, 'active': False, 'managed': False}, self.ledger.entitlement(account))
        result = self.renew()
        self.assertEqual({'source': 'managed_ledger', 'username': 'alice', 'expires_ms': result['target_ms'], 'active': True, 'managed': True}, self.ledger.entitlement(account))
        self.now = result['target_ms']
        self.assertFalse(self.ledger.entitlement(account)['active'])


if __name__ == '__main__':
    unittest.main()
