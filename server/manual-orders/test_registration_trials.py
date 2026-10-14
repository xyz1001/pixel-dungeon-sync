"""Offline registration recovery fixtures; no production account/adapter exists."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from dataclasses import replace
import hashlib
from pathlib import Path
import sqlite3
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

from orders import Orders, OrderError, Plan, Principal, extend_ms
from registration_trials import RegistrationTrials, RegistrationProof, TRIAL_MS
from test_orders import FakeEntitlements, TestAuth, ms
from write_gate import WriteGate, SqliteEntitlements, READ_METHODS, WRITE_METHODS

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'sftpgo-auth'))
import auth_v2


class DurableRegistrationFake:
    """Test-only independent upstream DB: create and proof commit atomically.

    The password digest is only a synthetic fixture for ownership checks; this
    is not a production password storage or registration implementation.
    """
    durable_registration_proof = True

    def __init__(self, path, clock):
        self.path, self.clock = path, clock
        self.fail_after = False
        self.calls = []
        with closing(sqlite3.connect(path)) as db:
            db.execute('CREATE TABLE IF NOT EXISTS accounts(owner TEXT PRIMARY KEY, operation_id TEXT UNIQUE, created_ms INTEGER, fixture_digest TEXT)')
            db.commit()

    def register(self, owner, password, operation_id):
        self.calls.append((owner, operation_id))
        digest = hashlib.sha256(password.encode()).hexdigest()
        with closing(sqlite3.connect(self.path, timeout=10)) as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT operation_id,created_ms,fixture_digest FROM accounts WHERE owner=?', (owner,)).fetchone()
            if row is None:
                row = (operation_id, self.clock(), digest)
                db.execute('INSERT INTO accounts VALUES(?,?,?,?)', (owner, *row))
            elif row[0] != operation_id:
                raise OrderError(409, 'USERNAME_UNAVAILABLE')
            elif row[2] != digest:
                raise OrderError(401, 'INVALID_CREDENTIALS')
            db.commit()
        if self.fail_after:
            self.fail_after = False
            raise TimeoutError('PRIVATE_UPSTREAM_DETAIL')
        return RegistrationProof(owner, row[0], row[1])


class RegistrationTrialTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'ledger.sqlite'
        self.upstream_path = Path(self.temp.name) / 'upstream.sqlite'
        self.now = ms('2024-02-29T12:34:56.789+00:00')
        self.adapter = FakeEntitlements()
        self.plan = Plan('annual', quota_bytes=5242880, upload_kbps=100, download_kbps=100)
        self.restart()
        self.gate = WriteGate(enabled=True, authenticator=TestAuth(), ledger=SqliteEntitlements(self.path), clock=lambda: self.now)

    def restart(self):
        self.orders = Orders(self.path, (self.plan,), mode='sandbox', authenticator=TestAuth(), entitlements=self.adapter, clock=lambda: self.now)
        self.upstream = DurableRegistrationFake(self.upstream_path, lambda: self.now)
        self.trials = RegistrationTrials(self.orders, registrations=self.upstream, mode='sandbox')

    def register(self, owner='alice', password='Synthetic-password-42'):
        return self.trials.register(owner, password)

    def entitlement(self):
        return self.orders.dispatch('GET', '/api/order/entitlement', {'test-identity': 'alice'})[1]['data']

    def row(self):
        with self.orders.connect() as db:
            return dict(db.execute('SELECT * FROM registration_trials WHERE owner=?', ('alice',)).fetchone())

    def desired(self):
        with self.orders.connect() as db:
            row = db.execute('SELECT expires_ms FROM entitlements WHERE owner=?', ('alice',)).fetchone()
            return row[0] if row else None

    def donate(self, key='donation-request-0001'):
        user, admin = Principal('alice'), Principal('reviewer', 'admin')
        order = self.orders.create(user, self.plan.id, key)
        self.orders.submit(user, order['id'])
        return self.orders.approve(admin, order['id'], key + '-receipt', 'sandbox', 1234, 'CNY')

    def test_disabled_by_default_missing_adapter_and_live_mode(self):
        for trials in (RegistrationTrials(self.orders), RegistrationTrials(self.orders, mode='sandbox'),
                       RegistrationTrials(self.orders, registrations=object(), mode='sandbox')):
            with self.assertRaises(OrderError) as caught:
                trials.register('alice', 'test')
            self.assertEqual(503, caught.exception.status)
        with self.assertRaises(ValueError):
            RegistrationTrials(self.orders, mode='live')
        self.assertEqual([], self.upstream.calls)

    def test_success_grants_exactly_72_hours_once_and_hides_proof_identifiers(self):
        grant = self.register()
        self.assertEqual({'created_ms': self.now, 'expires_ms': self.now + TRIAL_MS, 'state': 'confirmed'}, grant)
        self.assertTrue(self.entitlement()['active'])
        self.assertEqual(grant['expires_ms'], self.entitlement()['expires_ms'])
        for method in WRITE_METHODS:
            self.assertEqual(204, self.gate.decide(method, {'test-identity': 'alice'}).status)
        self.now += 1000
        self.restart()
        self.assertEqual(grant, self.register())
        self.assertEqual(1, len(self.adapter.operations))
        with self.orders.connect() as db:
            self.assertEqual(1, db.execute('SELECT count(*) FROM registration_trials').fetchone()[0])
            self.assertEqual(1, db.execute("SELECT count(*) FROM audit WHERE event='trial_confirmed'").fetchone()[0])
            self.assertNotIn('password', ' '.join(r[1] for r in db.execute('PRAGMA table_info(registration_trials)')))

    def test_new_registration_rules_reject_before_reservation_or_upstream_io(self):
        for owner in ('ab', '12', 'Aname', 'XYZ1001', 'abc_def', 'abc-def', 'a_-', 'abc.def',
                      ' abc', 'abc ', 'a' * 33):
            with self.subTest(owner=owner), self.assertRaises(OrderError) as caught:
                self.register(owner, '123456')
            self.assertEqual('INVALID_USERNAME', caught.exception.code)
        for password in ('12345', '😀' * 5, 'x' * 129, '😀' * 129, '$12345', '{12345',
                         'bad\npassword', 'bad\x7fpassword', 'bad\x85password', 'bad\ud800password'):
            with self.subTest(length=len(password)), self.assertRaises(OrderError) as caught:
                self.register('xyz1001', password)
            self.assertEqual('INVALID_PASSWORD', caught.exception.code)
        self.assertEqual([], self.upstream.calls)
        with self.orders.connect() as db:
            self.assertEqual(0, db.execute('SELECT count(*) FROM registration_trials').fetchone()[0])

    def test_new_numeric_names_and_six_code_point_passwords(self):
        for owner, password in (('xyz1001', '123456'), ('0name', '😀' * 6), ('123', '中' * 6),
                                ('000', ' 1234 '), ('a' * 32, 'x' * 128), ('1' * 32, '😀' * 128)):
            with self.subTest(owner=owner):
                grant = self.register(owner, password)
                self.assertEqual(self.now + TRIAL_MS, grant['expires_ms'])
                self.assertEqual(grant, self.register(owner, password))

    def test_historical_underscore_and_hyphen_records_recover_same_operation(self):
        for index, owner in enumerate(('legacy_name', 'legacy-name')):
            operation = 'registration_historical_' + str(index)
            password = 'Synthetic-password-42'
            with self.orders.transaction() as db:
                db.execute("INSERT INTO registration_trials(owner,operation_id,requested_ms,state) VALUES(?,?,?,'pending')",
                           (owner, operation, self.now))
            proof = self.upstream.register(owner, password, operation)
            self.now += 1000
            self.restart()
            grant = self.register(owner, password)
            self.assertEqual(proof.created_ms + TRIAL_MS, grant['expires_ms'])
            self.assertEqual(grant, self.register(owner, password))
            with self.assertRaises(OrderError) as caught:
                self.register(owner, 'Wrong-password-42')
            self.assertEqual('INVALID_CREDENTIALS', caught.exception.code)
            with self.orders.connect() as db:
                row = db.execute('SELECT * FROM registration_trials WHERE owner=?', (owner,)).fetchone()
                self.assertEqual(operation, row['operation_id'])
                self.assertEqual('confirmed', row['state'])
            self.assertTrue(all(call == (owner, operation) for call in self.upstream.calls))

    def test_exact_expiry_preserves_all_reads_and_retry_does_not_renew(self):
        grant = self.register()
        self.now = grant['expires_ms']
        for method in WRITE_METHODS:
            self.assertEqual(403, self.gate.decide(method, {'test-identity': 'alice'}).status)
        for method in READ_METHODS:
            self.assertEqual(204, self.gate.decide(method, {'test-identity': 'alice'}).status)
        self.assertEqual(grant, self.register())
        self.assertFalse(self.entitlement()['active'])

    def test_trial_starts_at_upstream_success_not_intent_or_later_response(self):
        requested = self.now
        original = self.upstream.register
        def slow_create(*args):
            self.now += 5000
            proof = original(*args)
            self.now += 10000
            return proof
        with patch.object(self.upstream, 'register', side_effect=slow_create):
            grant = self.register()
        self.assertEqual(requested, self.row()['requested_ms'])
        self.assertEqual(requested + 5000, grant['created_ms'])
        self.assertEqual(requested + 5000 + TRIAL_MS, grant['expires_ms'])

    def test_creation_success_lost_response_recovers_original_timestamp_after_restart(self):
        created = self.now
        self.upstream.fail_after = True
        with self.assertRaises(OrderError):
            self.register()
        pending = self.row()
        self.assertEqual('pending', pending['state'])
        self.assertIsNone(self.desired())
        self.assertIsNone(self.entitlement()['expires_ms'])
        self.assertEqual(403, self.gate.decide('PUT', {'test-identity': 'alice'}).status)
        self.now += TRIAL_MS + 1000
        self.restart()
        grant = self.register()
        self.assertEqual(created + TRIAL_MS, grant['expires_ms'])
        self.assertEqual(pending['operation_id'], self.row()['operation_id'])
        self.assertFalse(self.entitlement()['active'])

    def test_creation_success_ledger_write_failure_recovers_original_timestamp(self):
        created = self.now
        with patch.object(self.trials, '_record_proof', side_effect=sqlite3.OperationalError('PRIVATE_DISK_FAILURE')):
            with self.assertRaises(OrderError) as error:
                self.register()
        self.assertEqual('REGISTRATION_DATABASE_UNAVAILABLE', error.exception.code)
        self.assertEqual('pending', self.row()['state'])
        self.now += 24 * 60 * 60 * 1000
        self.restart()
        self.assertEqual(created + TRIAL_MS, self.register()['expires_ms'])
        with closing(sqlite3.connect(self.upstream_path)) as db:
            self.assertEqual(1, db.execute('SELECT count(*) FROM accounts').fetchone()[0])

    def test_intent_write_failure_never_creates_upstream_account(self):
        with patch.object(self.trials, '_reserve', side_effect=sqlite3.OperationalError('disk')):
            with self.assertRaises(OrderError):
                self.register()
        self.assertEqual([], self.upstream.calls)

    def test_bad_credentials_cannot_recover_pending_or_reuse_confirmed_account(self):
        self.upstream.fail_after = True
        with self.assertRaises(OrderError):
            self.register()
        for expected_state in ('pending', 'confirmed'):
            with self.subTest(state=expected_state):
                with self.assertRaises(OrderError) as error:
                    self.register(password='Someone-elses-password')
                self.assertEqual(401, error.exception.status)
                self.assertEqual(expected_state, self.row()['state'])
                if expected_state == 'pending':
                    self.assertIsNone(self.entitlement()['expires_ms'])
                    self.register()

    def test_preexisting_account_cannot_claim_a_trial(self):
        self.upstream.register('alice', 'Synthetic-password-42', 'unrelated-operation')
        with self.assertRaises(OrderError) as error:
            self.register()
        self.assertEqual('USERNAME_UNAVAILABLE', error.exception.code)
        self.assertEqual('pending', self.row()['state'])
        self.assertIsNone(self.desired())
        self.assertFalse(self.entitlement()['active'])

    def test_wrong_identity_operation_and_timestamp_proofs_fail_closed(self):
        original = self.upstream.register
        variants = (lambda p: replace(p, owner='bob'), lambda p: replace(p, operation_id='unrelated'),
                    lambda p: replace(p, created_ms=True), lambda p: replace(p, created_ms=self.now + 1),
                    lambda p: {'owner': p.owner, 'created_ms': p.created_ms})
        for mutate in variants:
            with patch.object(self.upstream, 'register', side_effect=lambda *args: mutate(original(*args))):
                with self.assertRaises(OrderError) as error:
                    self.register()
            self.assertEqual('INVALID_REGISTRATION_PROOF', error.exception.code)
            self.assertIsNone(self.desired())
        grant = self.register()
        self.now += 1000
        with patch.object(self.upstream, 'register', side_effect=lambda *args: replace(original(*args), created_ms=self.now)):
            with self.assertRaises(OrderError) as error:
                self.register()
        self.assertEqual('REGISTRATION_PROOF_CONFLICT', error.exception.code)
        self.assertEqual(grant['expires_ms'], self.entitlement()['expires_ms'])

    def test_permission_failure_and_readback_failure_do_not_confirm_trial(self):
        for failure in ('fail_before', 'fail_after', 'readback_bad'):
            with self.subTest(failure=failure):
                self.setUp()
                setattr(self.adapter, failure, True)
                with self.assertRaises(OrderError):
                    self.register()
                target = self.row()['expires_ms']
                self.assertEqual('applying', self.row()['state'])
                self.assertIsNone(self.entitlement()['expires_ms'])
                self.assertEqual(403, self.gate.decide('PUT', {'test-identity': 'alice'}).status)
                self.now += 1000
                setattr(self.adapter, failure, False)
                self.restart()
                self.assertEqual(target, self.register()['expires_ms'])
                self.assertEqual(self.adapter.calls[0], self.adapter.calls[1])

    def test_crash_after_permission_write_requires_lease_expiry_then_same_grant(self):
        original = self.adapter.ensure
        def crash(*args):
            original(*args)
            raise KeyboardInterrupt()
        with patch.object(self.adapter, 'ensure', side_effect=crash):
            with self.assertRaises(KeyboardInterrupt):
                self.register()
        target = self.row()['expires_ms']
        with self.assertRaises(OrderError) as error:
            self.register()
        self.assertEqual('REGISTRATION_IN_PROGRESS', error.exception.code)
        self.now += 30001
        self.restart()
        self.assertEqual(target, self.register()['expires_ms'])

    def test_concurrent_registration_has_one_operation_and_grant(self):
        barrier = threading.Barrier(8)
        def run(_):
            barrier.wait()
            try:
                return self.register()['expires_ms']
            except OrderError as error:
                self.assertEqual('REGISTRATION_IN_PROGRESS', error.code)
                return None
        with ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(run, range(8)))
        self.assertEqual({self.now + TRIAL_MS}, {value for value in results if value is not None})
        self.assertEqual(1, len(set(self.upstream.calls)))
        self.assertEqual(1, len(self.adapter.operations))

    def test_first_donation_starts_at_approval_and_only_paid_renewal_carries(self):
        trial = self.register()
        self.now += 1000
        paid = self.donate()
        self.assertLess(self.now, trial['expires_ms'])
        self.assertEqual(extend_ms(self.now, 'calendar_year', 0), paid['target_ms'])
        self.assertNotEqual(extend_ms(trial['expires_ms'], 'calendar_year', 0), paid['target_ms'])
        self.assertEqual(extend_ms(paid['target_ms'], 'calendar_year', 0), self.donate('donation-request-0002')['target_ms'])

    def test_late_trial_retry_cannot_shorten_or_extend_donation(self):
        created = self.now
        self.upstream.fail_after = True
        with self.assertRaises(OrderError):
            self.register()
        self.now += 1000
        paid = self.donate()
        self.now += 1000
        self.restart()
        self.assertEqual(created + TRIAL_MS, self.register()['expires_ms'])
        self.assertEqual(paid['target_ms'], self.desired())
        self.assertEqual(paid['target_ms'], self.entitlement()['expires_ms'])
        self.assertEqual(paid['target_ms'], self.adapter.values['alice'])

    def test_applying_donation_preserves_trial_only_until_its_original_expiry(self):
        trial = self.register()
        self.adapter.fail_after = True
        with self.assertRaises(OrderError):
            self.donate()
        self.assertEqual(trial['expires_ms'], self.entitlement()['expires_ms'])
        self.assertEqual(204, self.gate.decide('PUT', {'test-identity': 'alice'}).status)
        self.now = trial['expires_ms']
        self.assertEqual(403, self.gate.decide('PUT', {'test-identity': 'alice'}).status)
        with self.orders.connect() as db:
            oid = db.execute('SELECT id FROM orders').fetchone()[0]
        paid = self.orders.retry(Principal('reviewer', 'admin'), oid)
        self.assertEqual(paid['target_ms'], self.entitlement()['expires_ms'])
        self.assertEqual(204, self.gate.decide('PUT', {'test-identity': 'alice'}).status)

    def test_older_donation_database_keeps_approved_rights_without_importing_applying(self):
        first = self.donate()
        self.adapter.fail_after = True
        with self.assertRaises(OrderError):
            self.donate('donation-request-0002')
        # Simulate the pre-trial donation schema, without inventing trial rows.
        with self.orders.transaction() as db:
            db.execute('DROP TABLE registration_trials')
        self.restart()
        self.assertEqual(first['target_ms'], self.entitlement()['expires_ms'])
        with self.orders.connect() as db:
            self.assertEqual(0, db.execute('SELECT count(*) FROM registration_trials').fetchone()[0])
        self.now = first['target_ms']
        self.assertFalse(self.entitlement()['active'])
        self.assertEqual(403, self.gate.decide('PUT', {'test-identity': 'alice'}).status)

    def auth_env(self):
        return {'registration_trials': self.trials, 'check_ip_rate_limit': lambda ip: True,
                'create_sftpgo_user': lambda *args: self.fail('legacy creation must not run'),
                'SFTPGO_API_URL': 'https://example.invalid'}

    def test_named_auth_hook_and_login_never_issues_or_renews_trial(self):
        env = self.auth_env()
        auth_v2._attempts.clear()
        with patch.object(auth_v2, 'account_info', return_value={'username': 'alice'}), patch.object(auth_v2, 'fetch_json', return_value={'access_token': 'fixture'}):
            self.assertEqual({'username': 'alice'}, auth_v2.perform(env, 'register', 'alice', 'Synthetic-password-42', 'fixture-ip'))
            first = self.row()
            self.now = first['expires_ms']
            self.assertEqual({'username': 'alice'}, auth_v2.perform(env, 'login', 'alice', 'Synthetic-password-42', 'fixture-ip'))
            self.assertEqual(first, self.row())
            self.assertEqual(1, len(self.upstream.calls))
            self.assertFalse(self.entitlement()['active'])
            with self.assertRaises(auth_v2.ApiError) as error:
                auth_v2.perform(env, 'register', 'alice', 'wrong-password', 'fixture-ip')
            self.assertEqual(401, error.exception.status)
        for field in ('created_ms', 'expires_ms', 'trial_days', 'operation_id'):
            with self.assertRaises(auth_v2.ApiError):
                auth_v2.credentials({'version': 2, 'action': 'register', 'username': 'alice', 'password': 'Synthetic-password-42', field: self.now})

    def test_auth_hook_failure_is_redacted_and_never_falls_back_to_legacy_create(self):
        self.upstream.fail_after = True
        with patch.object(auth_v2, 'account_info') as account_info:
            with self.assertRaises(auth_v2.ApiError) as error:
                auth_v2.perform(self.auth_env(), 'register', 'alice', 'Synthetic-password-42', 'fixture-ip')
            self.assertEqual(503, error.exception.status)
            self.assertEqual('REGISTRATION_UPSTREAM_UNAVAILABLE', error.exception.code)
            account_info.assert_not_called()
        self.assertEqual('pending', self.row()['state'])


if __name__ == '__main__':
    unittest.main()
