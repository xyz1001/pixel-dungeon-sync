"""Offline, synthetic-account renewal/recovery and compatibility regressions."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from dataclasses import replace
from pathlib import Path
import sqlite3
import tempfile
import threading
import unittest
from unittest.mock import patch

from feishu_renewals import AccountIdentity, FeishuRenewals
from orders import Orders, OrderError, Plan, Principal, confirmed_expiry, confirmed_paid_expiry, extend_ms, reserved_paid_expiry
from registration_trials import RegistrationTrials, TRIAL_MS
from test_orders import FakeEntitlements, TestAuth, ms
from test_registration_trials import DurableRegistrationFake
from test_upload_policy import FencedFake
from upload_policy import OrderUploadAdapter, UploadPolicy, PolicyError, READ
from write_gate import SqliteEntitlements, WriteGate


class FakeAccounts:
    def __init__(self):
        self.identities = {'alice': AccountIdentity('alice', 'account-alice'),
                           'bob': AccountIdentity('bob', 'account-bob')}
        self.calls = []

    def lookup_exact(self, username):
        self.calls.append(username)
        return self.identities.get(username)


class AccountEntitlements(FakeEntitlements):
    """In-memory test fixture; ID and monotonic update share the same lock."""
    def __init__(self, accounts):
        super().__init__()
        self.accounts = accounts
        self.lock = threading.RLock()

    def ensure_account(self, identity, expiry, operation):
        with self.lock:
            if self.accounts.identities.get(identity.username) != identity:
                raise ValueError('ACCOUNT_IDENTITY_MISMATCH')
            self.ensure(identity.username, expiry, operation)

    def observed_account_expiry(self, identity):
        if self.accounts.identities.get(identity.username) != identity:
            raise ValueError('ACCOUNT_IDENTITY_MISMATCH')
        return self.observed_expiry(identity.username)


class AccountFencedFake(FencedFake):
    atomic_account_identity = True

    def __init__(self, permissions=None):
        super().__init__(permissions)
        self.lock = threading.RLock()

    def apply_account_permissions(self, owner, account_id, expected_revision, generation, permissions):
        with self.lock:
            if self.account.account_id != account_id:
                raise PolicyError('ACCOUNT_IDENTITY_MISMATCH')
            self.apply_permissions(owner, expected_revision, generation, permissions)


class FeishuRenewalTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'ledger.sqlite'
        self.now = ms('2024-02-29T12:34:56.789+00:00')
        self.accounts = FakeAccounts()
        self.adapter = AccountEntitlements(self.accounts)
        self.service = self.make()
        self.plan = Plan('annual', quota_bytes=100, upload_kbps=100, download_kbps=100)
        self.orders = Orders(self.path, (self.plan,), mode='sandbox', authenticator=TestAuth(),
                             entitlements=self.adapter, clock=lambda: self.now)
        self.gate = WriteGate(enabled=True, authenticator=TestAuth(), ledger=SqliteEntitlements(self.path), clock=lambda: self.now)

    def make(self, **overrides):
        options = dict(enabled=True, accounts=self.accounts, entitlements=self.adapter, clock=lambda: self.now)
        options.update(overrides)
        return FeishuRenewals(self.path, **options)

    def renew(self, message='message-1', service=None, **overrides):
        arguments = dict(app_id='app-1', tenant_key='tenant-1', message_id=message,
                         chat_id='dedicated-chat', sender_id='designated-admin', username='alice')
        arguments.update(overrides)
        return (service or self.service).renew(**arguments)

    def row(self, message='message-1'):
        with self.orders.connect() as db:
            row = db.execute('SELECT * FROM feishu_renewals WHERE message_id=?', (message,)).fetchone()
            return dict(row) if row else None

    def expiry(self):
        with self.orders.connect() as db:
            return confirmed_expiry(db, 'alice')

    def paid(self):
        with self.orders.connect() as db:
            return confirmed_paid_expiry(db, 'alice'), reserved_paid_expiry(db, 'alice')

    def old_order(self, key='legacy-order-000001'):
        user = Principal('alice')
        order = self.orders.create(user, self.plan.id, key)
        self.orders.submit(user, order['id'])
        return order['id']

    def approve(self, oid, receipt='legacy-receipt-01'):
        return self.orders.approve(Principal('reviewer', 'admin'), oid, receipt, 'sandbox', 100, 'CNY')

    def test_disabled_defaults_and_missing_adapters_make_no_grant(self):
        self.assertEqual('disabled', self.renew(service=FeishuRenewals(self.path))['status'])
        self.assertIsNone(self.row())
        self.assertEqual('retry_required', self.renew(service=self.make(accounts=None))['status'])
        self.assertIsNone(self.row())
        result = self.renew(service=self.make(entitlements=None))
        self.assertEqual('retry_required', result['status'])
        self.assertEqual('applying', result['state'])
        self.assertIsNone(self.expiry())
        self.assertEqual([], self.adapter.calls)

    def test_first_year_duplicate_delivery_restart_and_reply_failure_never_renew(self):
        first = self.renew()
        self.assertEqual('confirmed', first['status'])
        self.assertEqual(extend_ms(self.now, 'calendar_year', 0), first['target_ms'])
        self.assertEqual(first['target_ms'], self.expiry())
        self.now += 100000
        # Simulate the bot losing its reply/outbox write after renewal committed.
        self.assertEqual(first, self.renew(service=self.make()))
        self.assertEqual(1, len(self.adapter.calls))
        self.assertEqual(1, self.row()['attempts'])
        with self.orders.connect() as db:
            self.assertEqual(1, db.execute("SELECT count(*) FROM audit WHERE event='feishu_renewal_confirmed'").fetchone()[0])

    def test_different_messages_same_user_each_add_one_year_and_expired_starts_now(self):
        first = self.renew()
        second = self.renew('message-2')
        self.assertEqual(extend_ms(first['target_ms'], 'calendar_year', 0), second['target_ms'])
        self.now = second['target_ms'] + 1000
        self.assertEqual(extend_ms(self.now, 'calendar_year', 0), self.renew('message-3')['target_ms'])

    def test_scope_is_exact_and_idempotency_conflict_never_discloses_old_target(self):
        first = self.renew()
        for field, value in (('username', 'bob'), ('sender_id', 'other-admin'), ('chat_id', 'another-chat')):
            result = self.renew(**{field: value})
            self.assertEqual('conflict', result['status'])
            self.assertIsNone(result['target_ms'])
            self.assertIsNone(result['operation_id'])
        scoped = self.renew(app_id='app-2')
        self.assertEqual(extend_ms(first['target_ms'], 'calendar_year', 0), scoped['target_ms'])

    def test_unknown_user_is_durable_and_later_registration_cannot_replay_authorization(self):
        result = self.renew(username='unknown')
        self.assertEqual('unknown_user', result['status'])
        self.accounts.identities['unknown'] = AccountIdentity('unknown', 'new-account')
        self.assertEqual(result, self.renew(username='unknown', service=self.make()))
        self.assertEqual('conflict', self.renew(username='alice')['status'])
        with self.orders.connect() as db:
            self.assertEqual(0, db.execute('SELECT count(*) FROM entitlements').fetchone()[0])
        self.assertEqual([], self.adapter.calls)
        self.assertEqual('confirmed', self.renew('new-message', username='unknown')['status'])

    def test_username_exact_and_account_snapshot_must_be_authoritative(self):
        for username in (' alice', 'alice\n', '@alice', 'alice bob', '用户', '', None, ['alice']):
            self.assertEqual('rejected', self.renew(username=username)['status'])
        for identity in (True, {'username': 'alice'}, AccountIdentity('bob', 'account-bob'),
                         AccountIdentity('alice', ''), AccountIdentity('alice', 123)):
            with patch.object(self.accounts, 'lookup_exact', return_value=identity):
                self.assertEqual('retry_required', self.renew()['status'])
            self.assertIsNone(self.row())
        self.assertEqual([], self.adapter.calls)

    def test_historical_login_names_are_exact_without_casefold_or_trim(self):
        for index, username in enumerate(('A', '9', '_', 'Alice', 'alice.name')):
            self.accounts.identities[username] = AccountIdentity(username, 'historical-' + str(index))
            result = self.renew('historical-message-' + str(index), username=username)
            self.assertEqual('confirmed', result['status'])
            self.assertEqual(username, result['username'])
        self.assertEqual('unknown_user', self.renew('wrong-case', username='ALICE')['status'])

    def test_adapter_lookup_failure_is_retryable_without_reserving_a_year(self):
        with patch.object(self.accounts, 'lookup_exact', side_effect=TimeoutError('PRIVATE_ACCOUNT_DETAIL')):
            result = self.renew()
        self.assertEqual('retry_required', result['status'])
        self.assertNotIn('PRIVATE_ACCOUNT_DETAIL', repr(result))
        self.assertIsNone(self.row())
        self.assertEqual('confirmed', self.renew()['status'])

    def test_reservation_is_committed_before_io_and_exposes_no_confirmed_expiry(self):
        original = self.adapter.ensure_account
        def inspect(identity, expiry, operation):
            with closing(sqlite3.connect(self.path, timeout=.1)) as db:
                db.execute('BEGIN IMMEDIATE')
                self.assertEqual('applying', db.execute('SELECT state FROM feishu_renewals').fetchone()[0])
                db.rollback()
            self.assertIsNone(self.expiry())
            self.assertEqual(403, self.gate.decide('PUT', {'test-identity': 'alice'}).status)
            return original(identity, expiry, operation)
        with patch.object(self.adapter, 'ensure_account', side_effect=inspect):
            self.assertEqual('confirmed', self.renew()['status'])
        self.assertEqual(204, self.gate.decide('PUT', {'test-identity': 'alice'}).status)

    def test_fail_before_after_and_bad_readback_retry_frozen_target(self):
        for failure in ('fail_before', 'fail_after', 'readback_bad'):
            with self.subTest(failure=failure):
                self.setUp()
                setattr(self.adapter, failure, True)
                failed = self.renew()
                self.assertEqual('retry_required', failed['status'])
                self.assertEqual('applying', self.row()['state'])
                self.assertIsNone(self.expiry())
                self.now += 10000
                setattr(self.adapter, failure, False)
                retried = self.renew(service=self.make())
                self.assertEqual('confirmed', retried['status'])
                self.assertEqual(failed['target_ms'], retried['target_ms'])
                self.assertEqual(self.adapter.calls[0], self.adapter.calls[1])

    def test_reserved_crash_recovery_retains_target_without_treating_it_as_confirmed(self):
        with patch.object(self.service, '_apply', side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt):
                self.renew()
        reserved = self.row()
        self.assertEqual('reserved', reserved['state'])
        self.assertIsNone(self.expiry())
        self.now += 5000
        retried = self.renew(service=self.make())
        self.assertEqual(reserved['target_ms'], retried['target_ms'])
        self.assertEqual('confirmed', retried['status'])

    def test_crash_after_write_requires_expired_lease_and_same_operation(self):
        original = self.adapter.ensure_account
        def crash(*args):
            original(*args)
            raise KeyboardInterrupt
        with patch.object(self.adapter, 'ensure_account', side_effect=crash):
            with self.assertRaises(KeyboardInterrupt):
                self.renew()
        self.assertEqual('applying', self.renew()['status'])
        target = self.row()['target_ms']
        self.now += 30001
        result = self.renew(service=self.make())
        self.assertEqual('confirmed', result['status'])
        self.assertEqual(target, result['target_ms'])
        self.assertEqual(1, len(self.adapter.operations))

    def test_concurrent_same_message_one_target_and_one_confirmation(self):
        services = [self.make() for _ in range(12)]
        barrier = threading.Barrier(len(services))
        def run(service):
            barrier.wait()
            return self.renew(service=service)
        with ThreadPoolExecutor(max_workers=len(services)) as pool:
            results = list(pool.map(run, services))
        self.assertTrue({result['status'] for result in results} <= {'applying', 'confirmed'}, results)
        self.assertEqual(1, len({result['target_ms'] for result in results}))
        self.assertEqual(1, len(self.adapter.operations))

    def test_same_message_concurrent_different_users_has_one_binding(self):
        barrier = threading.Barrier(2)
        def run(username):
            barrier.wait()
            return self.renew(username=username)
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(run, ('alice', 'bob')))
        self.assertEqual({'confirmed', 'conflict'}, {result['status'] for result in results})
        self.assertEqual(1, len(self.adapter.operations))
        conflict = next(result for result in results if result['status'] == 'conflict')
        self.assertIsNone(conflict['target_ms'])
        self.assertIsNone(conflict['operation_id'])

    def test_independent_concurrent_messages_accumulate_even_if_first_apply_fails(self):
        entered, release = threading.Event(), threading.Event()
        original = self.adapter.ensure_account
        def delayed(identity, expiry, operation):
            if expiry == extend_ms(self.now, 'calendar_year', 0):
                entered.set()
                if not release.wait(5):
                    raise TimeoutError()
                raise TimeoutError('first authorization application fails')
            return original(identity, expiry, operation)
        with patch.object(self.adapter, 'ensure_account', side_effect=delayed):
            with ThreadPoolExecutor(max_workers=1) as pool:
                first = pool.submit(self.renew)
                try:
                    self.assertTrue(entered.wait(2))
                    second = self.renew('message-2', service=self.make())
                    self.assertEqual('confirmed', second['status'])
                finally:
                    release.set()
                self.assertEqual('retry_required', first.result()['status'])
        first_target = self.row()['target_ms']
        self.assertEqual(extend_ms(first_target, 'calendar_year', 0), second['target_ms'])
        self.assertEqual(second['target_ms'], self.expiry())
        self.assertEqual(first_target, self.renew()['target_ms'])
        self.assertEqual(second['target_ms'], self.adapter.values['alice'])

    def test_concurrent_many_independent_messages_have_no_lost_year(self):
        services = [self.make() for _ in range(8)]
        barrier = threading.Barrier(len(services))
        def run(pair):
            i, service = pair
            barrier.wait()
            return self.renew('message-' + str(i), service=service)
        with ThreadPoolExecutor(max_workers=len(services)) as pool:
            results = list(pool.map(run, enumerate(services)))
        self.assertEqual({'confirmed'}, {result['status'] for result in results})
        self.assertEqual(8, len({result['target_ms'] for result in results}))
        expected = self.now
        for _ in results:
            expected = extend_ms(expected, 'calendar_year', 0)
        self.assertEqual(expected, self.expiry())

    def test_concurrent_legacy_approval_and_new_message_serialize_same_paid_base(self):
        oid = self.old_order()
        barrier = threading.Barrier(2)
        def legacy():
            barrier.wait()
            return self.approve(oid)['target_ms']
        def message():
            barrier.wait()
            return self.renew()['target_ms']
        with ThreadPoolExecutor(max_workers=2) as pool:
            old, new = pool.submit(legacy), pool.submit(message)
            targets = sorted((old.result(), new.result()))
        self.assertEqual(extend_ms(self.now, 'calendar_year', 0), targets[0])
        self.assertEqual(extend_ms(targets[0], 'calendar_year', 0), targets[1])
        self.assertEqual(targets[1], self.expiry())

    def test_reservation_commit_failure_rolls_back_and_never_calls_adapter(self):
        with patch.object(self.service.ledger, 'audit', side_effect=sqlite3.OperationalError('PRIVATE_DISK_ERROR')):
            result = self.renew()
        self.assertEqual('DATABASE_UNAVAILABLE', result['code'])
        self.assertIsNone(self.row())
        self.assertEqual([], self.adapter.calls)
        self.assertEqual(extend_ms(self.now, 'calendar_year', 0), self.renew()['target_ms'])

    def test_account_delete_recreate_cannot_retry_old_or_carry_old_account_years(self):
        self.adapter.fail_before = True
        first = self.renew()
        self.accounts.identities['alice'] = AccountIdentity('alice', 'replacement-id')
        self.assertEqual('conflict', self.renew()['status'])
        self.assertEqual('conflict', self.renew('message-2')['status'])
        self.assertEqual(first['target_ms'], self.row()['target_ms'])
        self.assertIsNone(self.expiry())
        self.assertEqual(1, len(self.adapter.calls))

    def test_confirmed_redelivery_with_recreated_account_does_not_apply_again(self):
        first = self.renew()
        self.accounts.identities['alice'] = AccountIdentity('alice', 'replacement-id')
        self.assertEqual('conflict', self.renew()['status'])
        self.assertEqual(first['target_ms'], self.row()['target_ms'])
        self.assertEqual(1, len(self.adapter.calls))

    def test_identity_changes_after_lookup_is_rejected_by_account_scoped_writer(self):
        original = self.accounts.lookup_exact
        def lookup(username):
            identity = original(username)
            self.accounts.identities[username] = AccountIdentity(username, 'replacement-id')
            return identity
        with patch.object(self.accounts, 'lookup_exact', side_effect=lookup):
            self.assertEqual('retry_required', self.renew()['status'])
        self.assertIsNone(self.expiry())
        self.assertEqual([], self.adapter.calls)

    def test_trial_does_not_extend_first_paid_year_and_both_queries_agree(self):
        upstream = DurableRegistrationFake(Path(self.temp.name) / 'upstream.sqlite', lambda: self.now)
        trials = RegistrationTrials(self.orders, registrations=upstream, mode='sandbox')
        trial = trials.register('alice', 'Synthetic-password-42')
        self.now += 1000
        paid = self.renew()
        self.assertEqual(extend_ms(self.now, 'calendar_year', 0), paid['target_ms'])
        self.assertNotEqual(extend_ms(trial['expires_ms'], 'calendar_year', 0), paid['target_ms'])
        self.assertEqual(paid['target_ms'], self.orders.dispatch('GET', '/api/order/entitlement', {'test-identity': 'alice'})[1]['data']['expires_ms'])

    def test_applying_renewal_never_activates_and_prior_trial_expires_normally(self):
        upstream = DurableRegistrationFake(Path(self.temp.name) / 'upstream.sqlite', lambda: self.now)
        trial = RegistrationTrials(self.orders, registrations=upstream, mode='sandbox').register('alice', 'Synthetic-password-42')
        self.adapter.fail_before = True
        self.assertEqual('retry_required', self.renew()['status'])
        self.assertEqual(trial['expires_ms'], self.expiry())
        self.now = trial['expires_ms']
        self.assertEqual(403, self.gate.decide('PUT', {'test-identity': 'alice'}).status)
        for method in ('GET', 'HEAD', 'PROPFIND'):
            self.assertEqual(204, self.gate.decide(method, {'test-identity': 'alice'}).status)

    def test_legacy_applying_then_new_message_and_legacy_retry_keeps_two_years(self):
        oid = self.old_order()
        self.adapter.fail_before = True
        with self.assertRaises(OrderError):
            self.approve(oid)
        with self.orders.connect() as db:
            old = db.execute('SELECT target_ms FROM orders WHERE id=?', (oid,)).fetchone()[0]
        new = self.renew()
        self.assertEqual(extend_ms(old, 'calendar_year', 0), new['target_ms'])
        self.assertEqual(old, self.orders.retry(Principal('reviewer', 'admin'), oid)['target_ms'])
        self.assertEqual(new['target_ms'], self.expiry())

    def test_new_applying_then_legacy_approval_counts_new_reserved_year(self):
        self.adapter.fail_before = True
        new = self.renew()
        old = self.approve(self.old_order())
        self.assertEqual(extend_ms(new['target_ms'], 'calendar_year', 0), old['target_ms'])
        self.assertEqual(new['target_ms'], self.renew()['target_ms'])
        self.assertEqual(old['target_ms'], self.expiry())

    def test_additive_migration_keeps_approved_applying_trial_and_audit_unchanged(self):
        upstream = DurableRegistrationFake(Path(self.temp.name) / 'upstream.sqlite', lambda: self.now)
        RegistrationTrials(self.orders, registrations=upstream, mode='sandbox').register('alice', 'Synthetic-password-42')
        first = self.approve(self.old_order())
        second = self.old_order('legacy-order-000002')
        self.adapter.fail_before = True
        with self.assertRaises(OrderError):
            self.approve(second, 'legacy-receipt-02')
        with self.orders.transaction() as db:
            db.execute('DROP TABLE feishu_renewals')
            snapshot = {table: [tuple(r) for r in db.execute('SELECT * FROM ' + table)]
                        for table in ('orders', 'registration_trials', 'entitlements', 'audit')}
            self.assertEqual(first['target_ms'], confirmed_expiry(db, 'alice'))
        self.service = self.make()
        with self.orders.connect() as db:
            for table, rows in snapshot.items():
                self.assertEqual(rows, [tuple(r) for r in db.execute('SELECT * FROM ' + table)])
            self.assertEqual(0, db.execute('SELECT count(*) FROM feishu_renewals').fetchone()[0])
        self.assertEqual(first['target_ms'], self.expiry())
        renewed = self.renew()
        self.assertEqual(extend_ms(extend_ms(first['target_ms'], 'calendar_year', 0), 'calendar_year', 0), renewed['target_ms'])

    def test_stale_worker_cannot_acknowledge_new_lease_or_shorten_newer_message(self):
        entered, release = threading.Event(), threading.Event()
        original = self.adapter.ensure_account
        first_call = True
        def delayed(*args):
            nonlocal first_call
            if first_call:
                first_call = False
                entered.set()
                release.wait(5)
            original(*args)
        with patch.object(self.adapter, 'ensure_account', side_effect=delayed):
            with ThreadPoolExecutor(max_workers=1) as pool:
                first = pool.submit(self.renew)
                try:
                    self.assertTrue(entered.wait(2))
                    self.now += 30001
                    self.assertEqual('confirmed', self.renew(service=self.make())['status'])
                    second = self.renew('message-2')
                finally:
                    release.set()
                self.assertEqual('applying', first.result()['status'])
        self.assertEqual(second['target_ms'], self.expiry())
        self.assertEqual(second['target_ms'], self.adapter.values['alice'])

    def setup_policy(self):
        fake = AccountFencedFake({'/': sorted(READ)})
        fake.account = replace(fake.account, account_id='account-alice')
        policy = UploadPolicy(self.path, adapter=fake, mode='sandbox', clock=lambda: self.now)
        policy.enroll_new_account('alice', ['list', 'download', 'upload'])
        adapter = OrderUploadAdapter(policy)
        self.orders.adapter = adapter
        self.service.adapter = adapter
        return fake, policy, adapter

    def test_policy_bridge_applies_identity_scoped_message_then_confirmed_query(self):
        fake, policy, adapter = self.setup_policy()
        renewed = self.renew()
        self.assertEqual('confirmed', renewed['status'])
        self.assertEqual(renewed['target_ms'], adapter.observed_account_expiry(self.accounts.identities['alice']))
        self.assertIn('upload', fake.account.permissions['/'])
        self.now = renewed['target_ms']
        policy.reconcile('alice')
        self.assertEqual(sorted(READ), fake.account.permissions['/'])
        self.assertEqual(0, fake.account.expiration_date)

    def test_policy_never_promotes_other_reserved_desired_target(self):
        fake, policy, adapter = self.setup_policy()
        with patch.object(self.service, '_apply', side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt):
                self.renew()
            with self.assertRaises(KeyboardInterrupt):
                self.renew('message-2')
        first_target, second_target = self.row()['target_ms'], self.row('message-2')['target_ms']
        self.assertIsNone(self.expiry())
        self.assertEqual(0, policy.reconcile('alice')['expires_ms'])
        self.assertEqual(sorted(READ), fake.account.permissions['/'])
        first = self.renew()
        self.assertEqual('confirmed', first['status'])
        self.assertEqual(first_target, self.expiry())
        self.assertEqual(first_target, adapter.observed_expiry('alice'))
        with self.orders.connect() as db:
            self.assertEqual(second_target, db.execute('SELECT expires_ms FROM entitlements').fetchone()[0])
            self.assertEqual(first_target, db.execute('SELECT expires_ms FROM upload_policy_jobs ORDER BY generation DESC LIMIT 1').fetchone()[0])
        self.now = first_target
        policy.reconcile('alice')
        self.assertEqual(sorted(READ), fake.account.permissions['/'])
        self.assertEqual(403, self.gate.decide('PUT', {'test-identity': 'alice'}).status)
        self.assertEqual('confirmed', self.renew('message-2')['status'])
        self.assertEqual(second_target, self.expiry())

    def test_partially_missing_shared_ledger_cannot_fall_back_to_desired_cache(self):
        fake, policy, adapter = self.setup_policy()
        with patch.object(self.service, '_apply', side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt):
                self.renew()
        with self.orders.transaction() as db:
            db.execute('DROP TABLE orders')
        with self.assertRaises(PolicyError):
            policy.reconcile('alice')
        self.assertEqual(0, fake.calls)
        self.assertEqual(sorted(READ), fake.account.permissions['/'])

    def test_policy_bridge_requires_atomic_account_binding(self):
        fake, policy, adapter = self.setup_policy()
        fake.atomic_account_identity = False
        self.assertEqual('retry_required', self.renew()['status'])
        self.assertEqual(0, fake.calls)
        self.assertIsNone(self.expiry())

    def test_policy_job_additive_migration_preserves_existing_rows(self):
        with self.orders.transaction() as db:
            db.execute('CREATE TABLE managed_upload_policy(owner TEXT PRIMARY KEY,baseline TEXT NOT NULL,generation INTEGER NOT NULL DEFAULT 0)')
            db.execute('CREATE TABLE upload_policy_jobs(owner TEXT,generation INTEGER,expires_ms INTEGER,observed_ms INTEGER,desired TEXT,state TEXT,PRIMARY KEY(owner,generation))')
            db.execute("INSERT INTO upload_policy_jobs VALUES('alice',1,123,100,'{}','retry_required')")
        policy = UploadPolicy(self.path)
        with policy.db() as db:
            row = dict(db.execute('SELECT * FROM upload_policy_jobs').fetchone())
        self.assertEqual(123, row['expires_ms'])
        self.assertEqual('retry_required', row['state'])
        self.assertIsNone(row['operation_id'])
        self.assertIsNone(row['account_id'])


if __name__ == '__main__':
    unittest.main()
