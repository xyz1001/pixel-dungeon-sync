from contextlib import closing
from dataclasses import replace
from pathlib import Path
import sqlite3
import tempfile
import unittest

from orders import Orders, Plan
from upload_policy import UploadPolicy, OrderUploadAdapter, READ
from test_orders import TestAuth, ms
from test_upload_policy import FencedFake


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

    def test_remote_failure_restart_retry_keeps_one_term_and_not_active_until_confirmed(self):
        oid = self.submitted('new-donation-000001')
        self.fake.fail_after = True
        self.assertEqual(503, self.approve(oid, 'first_receipt_00001')[0])
        state = self.call('/api/order/status/' + oid, method='GET')[1]['data']
        self.assertEqual('applying', state['state'])
        entitlement = self.call('/api/order/entitlement', method='GET')[1]['data']
        self.assertFalse(entitlement['active'])
        self.assertEqual(state['target_ms'], entitlement['expires_ms'])
        self.now += 86400000 * 10
        self.orders = self.make_orders()
        self.orders.adapter = OrderUploadAdapter(self.make_policy())
        for _ in range(2):
            status, result = self.call('/api/admin/retry', {'order_id': oid}, True)
            self.assertEqual(200, status, result)
            self.assertEqual(state['target_ms'], result['data']['target_ms'])
        self.assertTrue(self.call('/api/order/entitlement', method='GET')[1]['data']['active'])
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
