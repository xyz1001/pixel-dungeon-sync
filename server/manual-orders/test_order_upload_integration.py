from contextlib import closing
from dataclasses import replace
from pathlib import Path
import sqlite3
import tempfile
import unittest

from orders import Orders, Plan, OrderError, extend_ms
from registration_trials import RegistrationTrials, TRIAL_MS
from write_gate import WriteGate, SqliteEntitlements
from upload_policy import UploadPolicy, OrderUploadAdapter, READ
from test_orders import TestAuth, ms
from test_upload_policy import FencedFake
from test_registration_trials import DurableRegistrationFake


class OrderUploadIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'integration.sqlite'
        self.now = ms('2024-02-29T12:00:00+00:00')
        self.plan = Plan('donation', quota_bytes=5242880, upload_kbps=100, download_kbps=100)
        self.orders = self.make_orders()
        self.fake = FencedFake({'/': sorted(READ)})
        self.policy = self.make_policy()
        self.template = ['list', 'download', 'upload', 'create_dirs']
        self.policy.enroll_new_account('alice', self.template)
        self.orders.adapter = OrderUploadAdapter(self.policy)
        self.gate = WriteGate(enabled=True, authenticator=TestAuth(),
                              ledger=SqliteEntitlements(self.path), clock=lambda: self.now)

    def make_orders(self):
        return Orders(self.path, (self.plan,), mode='sandbox', authenticator=TestAuth(), clock=lambda: self.now)

    def make_policy(self):
        return UploadPolicy(self.path, adapter=self.fake, mode='sandbox', clock=lambda: self.now)

    def call(self, path, body=None, admin=False, method='POST'):
        return self.orders.dispatch(method, path, {'test-identity': 'reviewer' if admin else 'alice'}, body)

    def submitted(self, key):
        status, body = self.call('/api/order/create', {'plan_id': 'donation', 'request_key': key})
        self.assertEqual(200, status)
        oid = body['data']['id']
        self.assertEqual(200, self.call('/api/order/submit', {'order_id': oid})[0])
        return oid

    def approve(self, oid, receipt):
        return self.call('/api/admin/approve', {'order_id': oid, 'channel': 'wechat',
            'receipt_ref': receipt, 'actual_amount_minor': 567, 'actual_currency': 'CNY'}, True)

    def test_first_donation_ledger_commits_before_permission_io(self):
        oid = self.submitted('new-donation-000001')
        original = self.fake.apply_permissions
        def inspect(owner, revision, generation, permissions):
            with closing(sqlite3.connect(self.path, timeout=0.1)) as db:
                db.execute('BEGIN IMMEDIATE')
                state, target = db.execute('SELECT state,target_ms FROM orders WHERE id=?', (oid,)).fetchone()
                expiry = db.execute('SELECT expires_ms FROM entitlements WHERE owner=?', (owner,)).fetchone()[0]
                db.rollback()
            self.assertEqual('applying', state)
            self.assertEqual(target, expiry)
            self.assertEqual(ms('2025-02-28T12:00:00+00:00'), expiry)
            return original(owner, revision, generation, permissions)
        self.fake.apply_permissions = inspect
        status, result = self.approve(oid, 'first_receipt_00001')
        self.assertEqual(200, status, result)
        self.assertEqual('approved', result['data']['state'])
        self.assertEqual({'/': sorted(self.template)}, self.fake.account.permissions)
        self.assertEqual(0, self.fake.account.expiration_date)
        self.assertEqual(result['data']['target_ms'], self.orders.adapter.observed_expiry('alice'))

    def registration(self):
        upstream = DurableRegistrationFake(Path(self.temp.name) / 'registration.sqlite', lambda: self.now)
        return RegistrationTrials(self.orders, registrations=upstream, mode='sandbox')

    def test_registration_trial_activates_template_then_expiry_preserves_login_and_reads(self):
        original_account = self.fake.account
        trial = self.registration().register('alice', 'Synthetic-password-42')
        self.assertEqual(self.now + TRIAL_MS, trial['expires_ms'])
        self.assertEqual({'/': sorted(self.template)}, self.fake.account.permissions)
        self.assertEqual(204, self.gate.decide('PUT', {'test-identity': 'alice'}).status)
        self.now = trial['expires_ms']
        self.policy.reconcile('alice')
        self.assertEqual({'/': sorted(READ)}, self.fake.account.permissions)
        self.assertEqual(original_account.status, self.fake.account.status)
        self.assertEqual(original_account.expiration_date, self.fake.account.expiration_date)
        self.assertEqual(403, self.gate.decide('PUT', {'test-identity': 'alice'}).status)
        self.assertEqual(204, self.gate.decide('GET', {'test-identity': 'alice'}).status)
        oid = self.submitted('trial-donation-0001')
        paid = self.approve(oid, 'trial_receipt_0001')[1]['data']
        self.assertEqual(extend_ms(self.now, 'calendar_year', 0), paid['target_ms'])
        self.assertEqual({'/': sorted(self.template)}, self.fake.account.permissions)

    def test_late_trial_application_rereads_paid_ledger_without_shortening_donation(self):
        trials = self.registration()
        trial_expiry = self.now + TRIAL_MS
        self.fake.fail_after = True
        with self.assertRaises(OrderError):
            trials.register('alice', 'Synthetic-password-42')
        self.assertEqual(403, self.gate.decide('PUT', {'test-identity': 'alice'}).status)
        self.now += 1000
        oid = self.submitted('trial-donation-0001')
        paid = self.approve(oid, 'trial_receipt_0001')[1]['data']
        self.assertEqual(extend_ms(self.now, 'calendar_year', 0), paid['target_ms'])
        self.assertEqual(trial_expiry, self.registration().register('alice', 'Synthetic-password-42')['expires_ms'])
        self.assertEqual(paid['target_ms'], self.orders.adapter.observed_expiry('alice'))
        self.assertEqual(paid['target_ms'], self.call('/api/order/entitlement', method='GET')[1]['data']['expires_ms'])
        self.assertEqual(204, self.gate.decide('PUT', {'test-identity': 'alice'}).status)

    def test_remote_failure_restart_retry_keeps_one_term_and_not_active_until_confirmed(self):
        oid = self.submitted('new-donation-000001')
        self.fake.fail_after = True
        self.assertEqual(503, self.approve(oid, 'first_receipt_00001')[0])
        state = self.call('/api/order/status/' + oid, method='GET')[1]['data']
        self.assertEqual('applying', state['state'])
        entitlement = self.call('/api/order/entitlement', method='GET')[1]['data']
        self.assertFalse(entitlement['active'])
        self.assertIsNone(entitlement['expires_ms'])
        self.assertIn('upload', self.fake.account.permissions['/'])
        self.assertEqual(403, self.gate.decide('PUT', {'test-identity': 'alice'}).status)
        self.now += 86400000 * 10
        self.orders = self.make_orders()
        self.orders.adapter = OrderUploadAdapter(self.make_policy())
        for _ in range(2):
            status, result = self.call('/api/admin/retry', {'order_id': oid}, True)
            self.assertEqual(200, status, result)
            self.assertEqual(state['target_ms'], result['data']['target_ms'])
        self.assertTrue(self.call('/api/order/entitlement', method='GET')[1]['data']['active'])
        self.assertEqual(204, self.gate.decide('PUT', {'test-identity': 'alice'}).status)
        with closing(sqlite3.connect(self.path)) as db:
            self.assertEqual(1, db.execute("SELECT count(*) FROM audit WHERE event='approval_reserved'").fetchone()[0])

    def test_expired_account_renewal_uses_new_ledger_and_restores_same_template(self):
        first = self.submitted('new-donation-000001')
        expiry = self.approve(first, 'first_receipt_00001')[1]['data']['target_ms']
        self.now = expiry
        self.policy.reconcile('alice')
        self.assertEqual({'/': sorted(READ)}, self.fake.account.permissions)
        second = self.submitted('new-donation-000002')
        status, renewed = self.approve(second, 'second_receipt_00002')
        self.assertEqual(200, status, renewed)
        self.assertEqual(ms('2026-02-28T12:00:00+00:00'), renewed['data']['target_ms'])
        self.assertEqual({'/': sorted(self.template)}, self.fake.account.permissions)
        self.assertEqual(0, self.fake.account.expiration_date)

    def test_unconfirmed_renewal_preserves_old_grant_until_expiry_and_retry_confirms_extension(self):
        first = self.submitted('new-donation-000001')
        old_expiry = self.approve(first, 'first_receipt_00001')[1]['data']['target_ms']
        self.now = old_expiry - 1000
        second = self.submitted('new-donation-000002')
        self.fake.fail_after = True
        self.assertEqual(503, self.approve(second, 'second_receipt_00002')[0])
        target = self.call('/api/order/status/' + second, method='GET')[1]['data']['target_ms']
        self.assertGreater(target, old_expiry)
        self.assertEqual(204, self.gate.decide('PUT', {'test-identity': 'alice'}).status)
        entitlement = self.call('/api/order/entitlement', method='GET')[1]['data']
        self.assertEqual(old_expiry, entitlement['expires_ms'])
        self.assertTrue(entitlement['active'])
        self.now = old_expiry
        self.orders = self.make_orders()
        self.orders.adapter = OrderUploadAdapter(self.make_policy())
        self.assertIn('upload', self.fake.account.permissions['/'])
        self.assertEqual(403, self.gate.decide('PUT', {'test-identity': 'alice'}).status)
        self.assertFalse(self.call('/api/order/entitlement', method='GET')[1]['data']['active'])
        self.assertEqual(204, self.gate.decide('GET', {'test-identity': 'alice'}).status)
        for _ in range(2):
            self.assertEqual(200, self.call('/api/admin/retry', {'order_id': second}, True)[0])
            self.assertEqual(204, self.gate.decide('PUT', {'test-identity': 'alice'}).status)
            entitlement = self.call('/api/order/entitlement', method='GET')[1]['data']
            self.assertEqual(target, entitlement['expires_ms'])
            self.assertTrue(entitlement['active'])
