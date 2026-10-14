"""Native-expiry journal tests with a fake provider; no network/credentials."""
import base64
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from datetime import datetime
import multiprocessing
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'manual-orders'))
from feishu_renewals import AccountIdentity
from ledger_gate import AccountWriteGate, ManagedLedger, SftpgoBasicAuthenticator
from orders import OrderError, extend_ms
from registration_trials import TRIAL_MS
from sftpgo_client import SftpgoAccount, SftpgoError
from write_gate import READ_METHODS, WRITE_METHODS


NOW = int(datetime.fromisoformat('2024-02-29T12:34:56.789+00:00').timestamp() * 1000)


def renewal(ledger, message='message-1', username='newuser'):
    return ledger.renew(app_id='app', tenant_key='tenant', message_id=message,
                        chat_id='dedicated', sender_id='admin', username=username)


class NativeProvider:
    """Non-CAS remote fixture: ledger, not this fixture, must serialize writes."""
    def __init__(self, clock):
        self.clock = clock
        self.accounts = {'ordinary': SftpgoAccount('ordinary', 'instance:11:1', 1)}
        self.passwords = {'ordinary': 'password'}
        self.native_create = True
        self.writes = []
        self.ensure_calls = []
        self.create_calls = 0
        self.lose_write_response = False
        self.observed_override = None
        self.pause_target = None
        self.entered = threading.Event()
        self.release = threading.Event()

    def lookup(self, username):
        return self.accounts.get(username)

    def authenticate_basic(self, username, password):
        account = self.lookup(username)
        if (account is None or self.passwords.get(username) != password or account.status != 1 or
                account.expiration_date_ms and account.expiration_date_ms <= self.clock()):
            raise SftpgoError(401, 'AUTHENTICATION_REQUIRED')
        return account

    def create_or_recover(self, username, password, operation_id):
        self.create_calls += 1
        account = self.lookup(username)
        if account is None:
            created = self.clock()
            account = SftpgoAccount(username, f'instance:{len(self.accounts) + 100}:{created}',
                                    created, operation_id, expiration_date_ms=(created + TRIAL_MS
                                        if self.native_create else 0))
            self.accounts[username] = account
            self.passwords[username] = password
        if account.registration_operation_id != operation_id:
            raise SftpgoError(409, 'USERNAME_UNAVAILABLE')
        # Recovery uses the real provider authentication contract: expired
        # accounts cannot obtain a token, even for an exact registration retry.
        return self.authenticate_basic(username, password)

    def _exact(self, identity):
        account = self.lookup(identity.username)
        if account is None or account.account_id != identity.account_id:
            raise SftpgoError(409, 'ACCOUNT_IDENTITY_CONFLICT')
        if account.status != 1:
            raise SftpgoError(409, 'SFTPGO_ACCOUNT_DISABLED')
        return account

    def ensure_account_expiry(self, identity, target_ms):
        account = self._exact(identity)
        self.ensure_calls.append((identity.username, target_ms))
        if self.pause_target == target_ms:
            self.entered.set()
            if not self.release.wait(5):
                raise TimeoutError('fixture pause exceeded')
        target = max(account.expiration_date_ms, target_ms)
        if target != account.expiration_date_ms:
            self.accounts[identity.username] = replace(account, expiration_date_ms=target)
            self.writes.append((identity.username, target))
            if self.lose_write_response:
                self.lose_write_response = False
                raise TimeoutError('not safe to expose fixture private data')
        return target

    def observed_account_expiry(self, identity):
        account = self._exact(identity)
        return (account.expiration_date_ms if self.observed_override is None
                else self.observed_override)


class ProcessProvider:
    """Separate on-disk fake remote state, with deliberately non-atomic PUT."""
    def __init__(self, path):
        self.path = str(path)

    def lookup(self, username):
        with sqlite3.connect(self.path, timeout=5) as db:
            row = db.execute('SELECT account_id,created_ms,expiry FROM remote_accounts WHERE username=?',
                             (username,)).fetchone()
        return SftpgoAccount(username, row[0], row[1], expiration_date_ms=row[2]) if row else None

    def observed_account_expiry(self, identity):
        account = self.lookup(identity.username)
        if account is None or account.account_id != identity.account_id:
            raise SftpgoError(409, 'ACCOUNT_IDENTITY_CONFLICT')
        return account.expiration_date_ms

    def ensure_account_expiry(self, identity, target_ms):
        with sqlite3.connect(self.path, timeout=5) as db:
            db.execute('UPDATE concurrency SET active=active+1,maximum=MAX(maximum,active+1)')
        try:
            account = self.lookup(identity.username)
            # The remote snapshot is now stale if another process can enter.
            time.sleep(0.05)
            target = max(account.expiration_date_ms, target_ms)
            with sqlite3.connect(self.path, timeout=5) as db:
                db.execute('UPDATE remote_accounts SET expiry=? WHERE username=?',
                           (target, identity.username))
            return target
        finally:
            with sqlite3.connect(self.path, timeout=5) as db:
                db.execute('UPDATE concurrency SET active=active-1')


def _renew_process(path, provider, now, message, start_path, result_path):
    try:
        ledger = ManagedLedger(path, accounts=provider, enabled=True,
                               native_expiry=True, clock=lambda: now)
        deadline = time.monotonic() + 5
        while not Path(start_path).exists():
            if time.monotonic() >= deadline:
                raise TimeoutError('fixture start timeout')
            time.sleep(0.01)
        result = renewal(ledger, message)
    except Exception as error:
        result = {'status': 'fixture_failure', 'type': type(error).__name__}
    Path(result_path).write_text(json.dumps(result))


class NativeExpiryLedgerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'ledger.sqlite'
        self.now = NOW
        self.accounts = NativeProvider(lambda: self.now)
        self.ledger = self.make()

    def make(self, **options):
        defaults = dict(accounts=self.accounts, enabled=True, clock=lambda: self.now,
                        native_expiry=True)
        defaults.update(options)
        return ManagedLedger(self.path, **defaults)

    def query(self, sql, args=()):
        with self.ledger.journal.connect() as db:
            return db.execute(sql, args).fetchone()

    def identity(self, username='newuser'):
        account = self.accounts.lookup(username)
        return AccountIdentity(account.username, account.account_id)

    def register(self, username='newuser'):
        return self.ledger.register(username, 'password')

    def legacy_renew(self, username='ordinary', message='message-1'):
        return renewal(self.make(native_expiry=False), message, username)

    def test_native_trial_initial_expiry_and_confirmed_target_are_72_hours(self):
        self.assertTrue(self.ledger.assert_native_migration_ready())
        result = self.register()
        self.assertEqual(self.now + TRIAL_MS, result['expires_ms'])
        self.assertEqual('confirmed', result['state'])
        self.assertEqual(result['expires_ms'], self.accounts.lookup('newuser').expiration_date_ms)
        self.assertEqual(result['expires_ms'], self.make().expiry(self.identity()))
        self.assertEqual(1, self.query('SELECT COUNT(*) FROM managed_native_applications')[0])
        self.assertEqual(0, self.query('SELECT COUNT(*) FROM managed_gate_applications')[0])
        status = self.ledger.entitlement(self.accounts.lookup('newuser'))
        self.assertTrue(status['active'])
        self.assertTrue(status['managed'])
        self.assertEqual(self.now, status['server_time_ms'])
        self.assertTrue(self.ledger.assert_native_migration_ready())

    def test_final_trial_uses_provider_creation_time_after_request(self):
        original = self.accounts.create_or_recover
        def delayed_create(*args):
            self.now += 5000
            return original(*args)
        with patch.object(self.accounts, 'create_or_recover', side_effect=delayed_create):
            result = self.register()
        self.assertEqual(NOW + 5000, result['created_ms'])
        self.assertEqual(NOW + 5000 + TRIAL_MS, result['expires_ms'])
        self.assertEqual(NOW, self.query('SELECT requested_ms FROM registration_trials')[0])

    def test_provisional_expiry_later_than_provider_creation_plus_72h_is_not_confirmed(self):
        original = self.accounts.create_or_recover
        def skewed_create(*args):
            account = original(*args)
            account = replace(account, created_at_ms=account.created_at_ms - 1000)
            self.accounts.accounts[account.username] = account
            return account
        with patch.object(self.accounts, 'create_or_recover', side_effect=skewed_create):
            with self.assertRaisesRegex(OrderError, 'REGISTRATION_CLOCK_CONFLICT'):
                self.register()
        self.assertEqual('pending', self.query('SELECT state FROM registration_trials')[0])
        self.assertEqual(0, self.query('SELECT COUNT(*) FROM managed_native_applications')[0])
        self.assertIsNone(self.ledger.expiry(self.identity()))

    def test_paid_target_explains_higher_expiry_on_registration_retry(self):
        trial = self.register()
        paid = renewal(self.ledger)
        self.assertEqual(trial, self.register())
        self.assertEqual(paid['target_ms'], self.accounts.lookup('newuser').expiration_date_ms)
        self.accounts.lose_write_response = True
        applying = renewal(self.ledger, 'message-2')
        self.assertEqual('retry_required', applying['status'])
        self.assertEqual(trial, self.register())
        self.assertEqual(applying['target_ms'], self.accounts.lookup('newuser').expiration_date_ms)

    def test_unexplained_higher_trial_readback_never_confirms(self):
        original = self.accounts.ensure_account_expiry
        def excessive_expiry(identity, target):
            original(identity, target)
            account = self.accounts.lookup(identity.username)
            self.accounts.accounts[identity.username] = replace(account, expiration_date_ms=target + 1)
            return target + 1
        with patch.object(self.accounts, 'ensure_account_expiry', side_effect=excessive_expiry):
            with self.assertRaisesRegex(OrderError, 'REGISTRATION_TRIAL_RETRY_REQUIRED'):
                self.register()
        self.assertEqual('applying', self.query('SELECT state FROM registration_trials')[0])
        self.assertEqual(0, self.query('SELECT COUNT(*) FROM managed_native_applications')[0])
        self.assertIsNone(self.ledger.expiry(self.identity()))

    def test_paid_first_year_does_not_stack_trial_and_expired_paid_starts_now(self):
        self.register()
        first = renewal(self.ledger)
        self.assertEqual(extend_ms(self.now, 'calendar_year', 0), first['target_ms'])
        self.assertEqual(first['target_ms'], self.accounts.lookup('newuser').expiration_date_ms)
        self.now = first['target_ms'] + 1
        second = renewal(self.ledger, 'message-2')
        self.assertEqual(extend_ms(self.now, 'calendar_year', 0), second['target_ms'])
        self.assertEqual('confirmed', second['status'])

    def test_basic_authenticator_retains_authenticated_account_snapshot_for_status(self):
        self.register()
        auth = SftpgoBasicAuthenticator(self.accounts)
        headers = {'Authorization': 'Basic ' + base64.b64encode(b'newuser:password').decode()}
        with patch.object(self.accounts, 'authenticate_basic', wraps=self.accounts.authenticate_basic) as authenticate:
            account = auth.authenticate_account(headers)
            self.assertEqual(1, authenticate.call_count)
            self.assertEqual(self.now + TRIAL_MS, account.expiration_date_ms)
            self.assertEqual(1, account.status)
            self.assertTrue(self.ledger.entitlement(account)['active'])
        self.assertEqual(self.identity(), auth.authenticate(headers))
        wrong = replace(account, username='different')
        with patch.object(self.accounts, 'authenticate_basic', return_value=wrong):
            with self.assertRaisesRegex(OrderError, 'INVALID_ACCOUNT_IDENTITY'):
                auth.authenticate_account(headers)

    def test_expired_trial_refuses_all_methods_without_changing_status(self):
        target = self.register()['expires_ms']
        gate = AccountWriteGate(enabled=True, authenticator=SftpgoBasicAuthenticator(self.accounts),
                                ledger=self.ledger, clock=lambda: self.now)
        headers = {'Authorization': 'Basic ' + base64.b64encode(b'newuser:password').decode()}
        self.now = target - 1
        for method in READ_METHODS | WRITE_METHODS:
            self.assertEqual(204, gate.decide(method, headers).status)
        self.now = target
        for method in READ_METHODS | WRITE_METHODS:
            self.assertEqual(401, gate.decide(method, headers).status)
        self.assertEqual(1, self.accounts.lookup('newuser').status)
        self.assertFalse(self.ledger.entitlement(self.accounts.lookup('newuser'))['active'])

    def test_unknown_and_unmanaged_ordinary_accounts_are_not_bound_or_written(self):
        for message, name in [('unknown-message', 'missing'), ('ordinary-message', 'ordinary')]:
            result = renewal(self.ledger, message, name)
            self.assertEqual('unknown_user', result['status'])
            self.assertEqual(result, renewal(self.make(), message, name))
        self.assertEqual(0, self.accounts.create_calls)
        self.assertEqual([], self.accounts.writes)
        self.assertEqual([], self.accounts.ensure_calls)
        self.assertEqual(0, self.query('SELECT COUNT(*) FROM managed_account_bindings')[0])
        self.assertEqual(0, self.accounts.lookup('ordinary').expiration_date_ms)

    def test_lost_remote_response_retries_same_fixed_target_once(self):
        self.register()
        self.accounts.lose_write_response = True
        first = renewal(self.ledger)
        self.assertEqual('retry_required', first['status'])
        self.assertEqual(first['target_ms'], self.accounts.lookup('newuser').expiration_date_ms)
        self.assertEqual(0, self.query("SELECT COUNT(*) FROM managed_native_applications WHERE source='feishu'")[0])
        self.now += 60000
        retried = renewal(self.make())
        self.assertEqual('confirmed', retried['status'])
        self.assertEqual(first['target_ms'], retried['target_ms'])
        self.assertEqual(first['operation_id'], retried['operation_id'])
        self.assertEqual(1, len(self.accounts.writes))
        self.assertEqual(retried, renewal(self.make()))
        self.assertEqual(1, len(self.accounts.writes))

    def test_remote_readback_required_before_native_receipt(self):
        self.register()
        self.accounts.observed_override = 0
        first = renewal(self.ledger)
        self.assertEqual('retry_required', first['status'])
        self.assertEqual(0, self.query("SELECT COUNT(*) FROM managed_native_applications WHERE source='feishu'")[0])
        self.assertEqual(self.now + TRIAL_MS, self.ledger.expiry(self.identity()))
        self.accounts.observed_override = None
        self.assertEqual(first['target_ms'], renewal(self.ledger)['target_ms'])

    def test_native_receipt_still_requires_source_confirmation(self):
        self.register()
        with patch.object(self.ledger.applications, 'observed_account_expiry', return_value=None):
            first = renewal(self.ledger)
        self.assertEqual('retry_required', first['status'])
        self.assertEqual(1, self.query("SELECT COUNT(*) FROM managed_native_applications WHERE source='feishu'")[0])
        self.assertEqual(self.now + TRIAL_MS, self.ledger.expiry(self.identity()))
        self.assertEqual(first['target_ms'], renewal(self.make())['target_ms'])
        self.assertEqual(first['target_ms'], self.ledger.expiry(self.identity()))

    def test_crash_after_remote_write_rolls_back_receipt_then_repairs(self):
        self.register()
        with patch.object(self.ledger, '_write_native_application', side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt):
                renewal(self.ledger)
        row = self.query('SELECT * FROM feishu_renewals')
        self.assertEqual('applying', row['state'])
        self.assertEqual(row['target_ms'], self.accounts.lookup('newuser').expiration_date_ms)
        self.assertEqual(0, self.query("SELECT COUNT(*) FROM managed_native_applications WHERE source='feishu'")[0])
        self.now += 31000
        recovered = renewal(self.make())
        self.assertEqual('confirmed', recovered['status'])
        self.assertEqual(row['target_ms'], recovered['target_ms'])
        self.assertEqual(1, len(self.accounts.writes))

    def test_old_local_confirmation_needs_native_receipt_and_migration_is_fixed(self):
        old = self.legacy_renew()
        native = self.make()
        identity = self.identity('ordinary')
        self.assertIsNone(native.expiry(identity))
        self.assertEqual({'source': 'managed_ledger', 'username': 'ordinary', 'expires_ms': None,
                          'active': False, 'managed': True, 'server_time_ms': self.now},
                         native.entitlement(self.accounts.lookup('ordinary')))
        with self.assertRaisesRegex(OrderError, 'NATIVE_EXPIRY_MIGRATION_REQUIRED'):
            native.assert_native_migration_ready()
        self.assertEqual([], self.accounts.writes)
        self.now = old['target_ms'] + 5000
        report = native.migrate_native_accounts()
        self.assertEqual((1, 1, 0), (report['processed'], report['applied'], report['failed']))
        self.assertTrue(native.assert_native_migration_ready())
        self.assertEqual(old['target_ms'], self.accounts.lookup('ordinary').expiration_date_ms)
        self.assertEqual(old['target_ms'], native.expiry(identity))
        self.assertFalse(native.entitlement(self.accounts.lookup('ordinary'))['active'])
        self.assertEqual(1, len(self.accounts.writes))
        native.migrate_native_accounts()
        self.assertEqual(1, len(self.accounts.writes))

    def test_later_native_receipt_does_not_hide_unmigrated_earlier_confirmation(self):
        first = self.legacy_renew()
        second = renewal(self.ledger, 'message-2', 'ordinary')
        self.assertEqual('confirmed', second['status'])
        self.assertEqual(second['target_ms'], self.ledger.expiry(self.identity('ordinary')))
        with self.assertRaisesRegex(OrderError, 'NATIVE_EXPIRY_MIGRATION_REQUIRED'):
            self.ledger.assert_native_migration_ready()
        self.assertEqual(first, renewal(self.ledger, username='ordinary'))
        self.assertTrue(self.ledger.assert_native_migration_ready())
        self.assertEqual(second['target_ms'], self.accounts.lookup('ordinary').expiration_date_ms)

    def test_confirmed_same_message_retry_repairs_missing_native_application(self):
        old = self.legacy_renew()
        self.now += 10000
        result = renewal(self.ledger, username='ordinary')
        self.assertEqual(old, result)
        self.assertEqual(old['target_ms'], self.accounts.lookup('ordinary').expiration_date_ms)
        self.assertTrue(self.ledger.assert_native_migration_ready())

    def test_confirmed_trial_retry_repairs_missing_native_application(self):
        self.accounts.native_create = False
        local = self.make(native_expiry=False)
        old = local.register('newuser', 'password')
        self.assertEqual(0, self.accounts.lookup('newuser').expiration_date_ms)
        native = self.make()
        self.assertEqual(old, native.register('newuser', 'password'))
        self.assertEqual(old['expires_ms'], self.accounts.lookup('newuser').expiration_date_ms)
        self.assertTrue(native.assert_native_migration_ready())

    def test_migration_only_bound_confirmed_records_not_unknown_or_username_legacy(self):
        old = self.legacy_renew()
        with self.ledger.journal.transaction() as db:
            db.execute("INSERT INTO registration_trials(owner,operation_id,requested_ms,created_ms,expires_ms,state) VALUES(?,?,?,?,?,'confirmed')",
                       ('unboundlegacy', 'legacy-trial', self.now, self.now, self.now + TRIAL_MS))
        self.accounts.accounts['unboundlegacy'] = SftpgoAccount('unboundlegacy', 'instance:77:1', 1)
        self.accounts.passwords['unboundlegacy'] = 'password'
        report = self.ledger.reconcile_native_accounts()
        self.assertEqual(1, report['processed'])
        self.assertEqual([('ordinary', old['target_ms'])], self.accounts.writes)
        self.assertEqual(0, self.accounts.lookup('unboundlegacy').expiration_date_ms)
        self.assertEqual('unknown_user', renewal(self.ledger, 'legacy-message', 'unboundlegacy')['status'])

    def test_registration_lost_response_and_bound_applying_restart_remain_recoverable(self):
        original = self.accounts.create_or_recover
        def interrupted(*args):
            original(*args)
            raise KeyboardInterrupt
        with patch.object(self.accounts, 'create_or_recover', side_effect=interrupted):
            with self.assertRaises(KeyboardInterrupt):
                self.register()
        self.assertEqual(self.now + TRIAL_MS, self.accounts.lookup('newuser').expiration_date_ms)
        self.assertEqual(1, self.query('SELECT COUNT(*) FROM managed_native_registration_intents')[0])
        self.assertTrue(self.make().assert_native_migration_ready())
        with patch.object(self.accounts, 'observed_account_expiry', return_value=0):
            with self.assertRaisesRegex(OrderError, 'REGISTRATION_TRIAL_RETRY_REQUIRED'):
                self.make().register('newuser', 'password')
        self.assertEqual(1, self.query('SELECT COUNT(*) FROM managed_account_bindings')[0])
        self.assertEqual(0, self.query('SELECT COUNT(*) FROM managed_native_applications')[0])
        restarted = self.make()
        self.assertTrue(restarted.assert_native_migration_ready())
        self.assertFalse(restarted.entitlement(self.accounts.lookup('newuser'))['active'])
        self.now += 10000
        recovered = restarted.register('newuser', 'password')
        self.assertEqual(NOW + TRIAL_MS, recovered['expires_ms'])
        self.assertTrue(self.make().assert_native_migration_ready())

    def test_expired_lost_create_response_recovers_via_exact_admin_renewal_only(self):
        original = self.accounts.create_or_recover
        def interrupted(*args):
            original(*args)
            raise KeyboardInterrupt
        with patch.object(self.accounts, 'create_or_recover', side_effect=interrupted):
            with self.assertRaises(KeyboardInterrupt):
                self.register()
        created = self.accounts.lookup('newuser').created_at_ms
        self.assertEqual(0, self.query('SELECT COUNT(*) FROM managed_account_bindings')[0])
        self.assertIsNone(self.query('SELECT account_id FROM managed_registration_proofs')[0])
        self.now = created + TRIAL_MS + 1
        with self.assertRaisesRegex(OrderError, 'AUTHENTICATION_REQUIRED'):
            self.make().register('newuser', 'password')
        self.assertEqual('pending', self.query('SELECT state FROM registration_trials')[0])
        paid = renewal(self.make())
        self.assertEqual('confirmed', paid['status'])
        self.assertEqual(extend_ms(self.now, 'calendar_year', 0), paid['target_ms'])
        self.assertEqual(created, self.query('SELECT created_ms FROM managed_registration_proofs')[0])
        self.assertEqual('pending', self.query('SELECT state FROM registration_trials')[0])
        self.assertEqual(self.identity().account_id,
                         self.accounts.authenticate_basic('newuser', 'password').account_id)
        original_trial = self.make().register('newuser', 'password')
        self.assertEqual({'created_ms': created, 'expires_ms': created + TRIAL_MS, 'state': 'confirmed'},
                         original_trial)
        self.assertEqual(paid['target_ms'], self.accounts.lookup('newuser').expiration_date_ms)
        self.assertTrue(self.make().assert_native_migration_ready())
        with self.assertRaisesRegex(OrderError, 'AUTHENTICATION_REQUIRED'):
            self.make().register('newuser', 'wrong-password')

    def test_admin_recovery_needs_both_native_intent_and_exact_server_marker(self):
        original = self.accounts.create_or_recover
        def interrupted(*args):
            original(*args)
            raise KeyboardInterrupt
        with patch.object(self.accounts, 'create_or_recover', side_effect=interrupted):
            with self.assertRaises(KeyboardInterrupt):
                self.register()
        account = self.accounts.lookup('newuser')
        self.accounts.accounts['newuser'] = replace(account, registration_operation_id='wrong-operation')
        self.assertEqual('unknown_user', renewal(self.ledger, 'wrong-marker')['status'])
        self.assertEqual(0, self.query('SELECT COUNT(*) FROM managed_account_bindings')[0])
        self.accounts.accounts['newuser'] = account
        with self.ledger.journal.transaction() as db:
            db.execute('DELETE FROM managed_native_registration_intents')
        self.assertEqual('unknown_user', renewal(self.ledger, 'missing-intent')['status'])
        self.assertEqual(0, self.query('SELECT COUNT(*) FROM managed_account_bindings')[0])
        self.assertIsNone(self.query('SELECT account_id FROM managed_registration_proofs')[0])
        self.assertEqual([], self.accounts.writes)
        self.assertEqual([], self.accounts.ensure_calls)

    def test_old_local_only_applying_binding_blocks_native_startup(self):
        self.accounts.native_create = False
        local = self.make(native_expiry=False)
        with patch.object(local.applications, 'observed_expiry', return_value=None):
            with self.assertRaisesRegex(OrderError, 'REGISTRATION_TRIAL_RETRY_REQUIRED'):
                local.register('newuser', 'password')
        self.assertEqual(0, self.query('SELECT COUNT(*) FROM managed_native_registration_intents')[0])
        self.assertEqual(0, self.ledger.reconcile_native_accounts()['applied'])
        with self.assertRaisesRegex(OrderError, 'NATIVE_EXPIRY_MIGRATION_REQUIRED'):
            self.ledger.assert_native_migration_ready()
        local.register('newuser', 'password')
        self.assertEqual(1, self.ledger.reconcile_native_accounts()['applied'])
        self.assertTrue(self.ledger.assert_native_migration_ready())

    def test_recreated_identity_and_disabled_status_never_receive_a_write(self):
        old = self.legacy_renew()
        original = self.accounts.lookup('ordinary')
        self.accounts.accounts['ordinary'] = replace(original, account_id='instance:11:2')
        report = self.ledger.reconcile_native_accounts()
        self.assertEqual({'ACCOUNT_IDENTITY_CONFLICT': 1}, report['errors'])
        self.assertEqual('conflict', renewal(self.ledger, username='ordinary')['status'])
        self.assertEqual([], self.accounts.writes)
        self.accounts.accounts['ordinary'] = replace(original, status=0)
        report = self.ledger.reconcile_native_accounts()
        self.assertEqual({'SFTPGO_ACCOUNT_DISABLED': 1}, report['errors'])
        result = renewal(self.ledger, 'message-2', 'ordinary')
        self.assertEqual('SFTPGO_ACCOUNT_DISABLED', result['code'])
        self.assertEqual([], self.accounts.writes)
        self.assertEqual(0, self.accounts.lookup('ordinary').status)
        self.assertEqual(1, self.query('SELECT COUNT(*) FROM feishu_renewals')[0])

    def test_migration_report_is_bounded_sanitized_and_paginated(self):
        for name in ('alice', 'bob', 'carol'):
            self.accounts.accounts[name] = SftpgoAccount(name, f'instance:{name}:1', 1)
            self.accounts.passwords[name] = 'password'
            self.legacy_renew(username=name, message=f'message-{name}')
        with patch.object(self.accounts, 'ensure_account_expiry', side_effect=RuntimeError('secret credential')):
            page = self.ledger.reconcile_native_accounts(limit=1)
        self.assertEqual(1, page['processed'])
        self.assertEqual({'NATIVE_EXPIRY_UNAVAILABLE': 1}, page['errors'])
        self.assertNotIn('secret', str(page))
        self.assertEqual('alice', page['next_owner'])
        next_page = self.ledger.reconcile_native_accounts(limit=1, after_owner=page['next_owner'])
        self.assertEqual('bob', next_page['next_owner'])
        final = self.ledger.reconcile_native_accounts(limit=1, after_owner=next_page['next_owner'])
        self.assertFalse(final['has_more'])
        self.assertIsNone(final['next_owner'])
        for invalid in (0, -1, 1001, True):
            with self.assertRaisesRegex(OrderError, 'INVALID_MIGRATION_PAGE'):
                self.ledger.reconcile_native_accounts(limit=invalid)

    def test_old_applying_and_new_message_interleaving_cannot_reduce_new_target(self):
        self.register()
        self.accounts.lose_write_response = True
        old = renewal(self.ledger)
        self.assertEqual('retry_required', old['status'])
        new_target = extend_ms(old['target_ms'], 'calendar_year', 0)
        self.accounts.pause_target = new_target
        with ThreadPoolExecutor(max_workers=2) as pool:
            new = pool.submit(renewal, self.make(), 'message-2')
            self.assertTrue(self.accounts.entered.wait(2))
            old_retry = pool.submit(renewal, self.make())
            time.sleep(0.05)
            self.assertFalse(old_retry.done())
            self.accounts.release.set()
            newer, older = new.result(timeout=5), old_retry.result(timeout=5)
        self.assertEqual('confirmed', newer['status'])
        self.assertEqual('confirmed', older['status'])
        self.assertEqual(old['target_ms'], older['target_ms'])
        self.assertEqual(new_target, newer['target_ms'])
        self.assertEqual(new_target, self.accounts.lookup('newuser').expiration_date_ms)
        self.assertEqual(new_target, self.ledger.expiry(self.identity()))
        self.assertEqual([old['target_ms'], new_target], [v[1] for v in self.accounts.writes])

    def test_concurrent_messages_and_duplicate_delivery_keep_fixed_targets(self):
        self.register()
        with ThreadPoolExecutor(max_workers=6) as pool:
            duplicates = list(pool.map(lambda _: renewal(self.make()), range(6)))
        self.assertTrue(all(r['status'] in ('confirmed', 'applying') for r in duplicates), duplicates)
        first = renewal(self.ledger)
        with ThreadPoolExecutor(max_workers=4) as pool:
            different = list(pool.map(lambda n: renewal(self.make(), f'message-{n}'), range(2, 6)))
        self.assertTrue(all(r['status'] == 'confirmed' for r in different), different)
        expected = first['target_ms']
        for _ in range(4):
            expected = extend_ms(expected, 'calendar_year', 0)
        self.assertEqual(expected, self.accounts.lookup('newuser').expiration_date_ms)
        self.assertEqual(expected, self.ledger.expiry(self.identity()))
        self.assertEqual(5, self.query('SELECT COUNT(*) FROM feishu_renewals')[0])

    @unittest.skipUnless('fork' in multiprocessing.get_all_start_methods(), 'fork-only shared fixture')
    def test_real_independent_processes_share_sqlite_remote_writer_lock(self):
        self.register()
        context = multiprocessing.get_context('fork')
        provider = ProcessProvider(Path(self.temp.name) / 'fake-remote.sqlite')
        account = self.accounts.lookup('newuser')
        with sqlite3.connect(provider.path) as db:
            db.execute('CREATE TABLE remote_accounts(username TEXT, account_id TEXT, created_ms INTEGER, expiry INTEGER)')
            db.execute('INSERT INTO remote_accounts VALUES(?,?,?,?)',
                       (account.username, account.account_id, account.created_at_ms, account.expiration_date_ms))
            db.execute('CREATE TABLE concurrency(active INTEGER, maximum INTEGER)')
            db.execute('INSERT INTO concurrency VALUES(0,0)')
        start_path = Path(self.temp.name) / 'start-workers'
        result_paths = [Path(self.temp.name) / f'result-{n}.json' for n in range(4)]
        workers = [context.Process(target=_renew_process, args=(str(self.path), provider,
                    self.now, f'process-message-{n}', str(start_path), str(result_paths[n]))) for n in range(4)]
        try:
            for worker in workers:
                worker.start()
            start_path.touch()
            for worker in workers:
                worker.join(timeout=10)
                self.assertEqual(0, worker.exitcode)
            replies = [json.loads(path.read_text()) for path in result_paths]
            self.assertTrue(all(r['status'] == 'confirmed' for r in replies), replies)
            with sqlite3.connect(provider.path) as db:
                self.assertEqual(1, db.execute('SELECT maximum FROM concurrency').fetchone()[0])
            expected = self.now
            for _ in workers:
                expected = extend_ms(expected, 'calendar_year', 0)
            self.assertEqual(expected, provider.lookup('newuser').expiration_date_ms)
            self.assertEqual(expected, self.ledger.expiry(self.identity()))
        finally:
            for worker in workers:
                if worker.is_alive():
                    worker.terminate()
                worker.join(timeout=5)

    def test_receipt_write_lock_spans_provider_lookup_write_readback(self):
        self.register()
        original_lookup = self.accounts.lookup
        original_ensure = self.accounts.ensure_account_expiry
        original_observed = self.accounts.observed_account_expiry
        checked = []
        def assert_locked(stage):
            db = sqlite3.connect(self.path, timeout=0, isolation_level=None)
            try:
                with self.assertRaises(sqlite3.OperationalError):
                    db.execute('BEGIN IMMEDIATE')
            finally:
                db.close()
            checked.append(stage)
        def lookup(name):
            assert_locked('lookup')
            return original_lookup(name)
        def ensure(identity, target):
            assert_locked('write')
            return original_ensure(identity, target)
        def observed(identity):
            assert_locked('readback')
            return original_observed(identity)
        with patch.object(self.accounts, 'lookup', side_effect=lookup), \
                patch.object(self.accounts, 'ensure_account_expiry', side_effect=ensure), \
                patch.object(self.accounts, 'observed_account_expiry', side_effect=observed):
            self.assertEqual('confirmed', renewal(self.ledger)['status'])
        self.assertIn('write', checked)
        self.assertIn('readback', checked)


if __name__ == '__main__':
    unittest.main()
