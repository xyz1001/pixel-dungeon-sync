from contextlib import closing
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
from pathlib import Path
import sqlite3
import tempfile
import threading
import unittest

from orders import Orders, Plan, Principal, OrderError, extend_ms


def ms(value):
    return int(datetime.fromisoformat(value).timestamp() * 1000)


class TestAuth:
    def authenticate(self, headers):
        identity = headers.get('test-identity')
        if identity not in ('alice', 'bob', 'reviewer'):
            raise OrderError(401, 'AUTHENTICATION_REQUIRED')
        return Principal(identity, 'admin' if identity == 'reviewer' else 'user')


class FakeEntitlements:
    def __init__(self):
        self.values, self.operations, self.calls = {}, {}, []
        self.fail_before = self.fail_after = False
        self.readback_bad = False
        self.lock = threading.Lock()

    def ensure(self, username, expiry, operation):
        with self.lock:
            self.calls.append((username, expiry, operation))
            if self.fail_before:
                self.fail_before = False
                raise TimeoutError('SENSITIVE_UPSTREAM_MESSAGE')
            if operation in self.operations:
                assert self.operations[operation] == (username, expiry)
            self.operations[operation] = (username, expiry)
            self.values[username] = max(self.values.get(username, 0), expiry)
            if self.fail_after:
                self.fail_after = False
                raise TimeoutError('SENSITIVE_UPSTREAM_MESSAGE')

    def observed_expiry(self, username):
        return 0 if self.readback_bad else self.values.get(username, 0)


class OrderTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'test.sqlite'
        self.now = ms('2024-02-29T12:34:56.789+00:00')
        self.plan = Plan('annual-v1', 1234, 'CNY', 'calendar_year', 'sandbox_ledger_only', quota_bytes=5242880, upload_kbps=100, download_kbps=100)
        self.adapter = FakeEntitlements()
        self.service = self.make()

    def make(self, **overrides):
        args = dict(mode='sandbox', authenticator=TestAuth(), entitlements=self.adapter,
                    clock=lambda: self.now)
        args.update(overrides)
        return Orders(self.path, (self.plan,), **args)

    def call(self, path, data=None, identity='alice', method='POST', service=None):
        return (service or self.service).dispatch(method, path, {'test-identity': identity}, data)

    def create(self, identity='alice', key='request_000000000001'):
        status, result = self.call('/api/order/create', {'plan_id': self.plan.id, 'request_key': key}, identity)
        self.assertEqual(200, status, result)
        return result['data']

    def submitted(self, **kw):
        order = self.create(**kw)
        status, result = self.call('/api/order/submit', {'order_id': order['id']}, kw.get('identity', 'alice'))
        self.assertEqual(200, status)
        self.assertEqual('review', result['data']['state'])
        self.assertEqual({}, self.adapter.values)
        return order

    def approve(self, oid, receipt='receipt_00000001', service=None):
        return self.call('/api/admin/approve', {'order_id': oid, 'receipt_ref': receipt, 'channel': 'sandbox'}, 'reviewer', service=service)

    def audit(self, oid, event):
        with closing(sqlite3.connect(self.path)) as db:
            return db.execute('SELECT COUNT(*) FROM audit WHERE order_id=? AND event=?', (oid, event)).fetchone()[0]

    def test_default_disabled_and_reject_live_or_incomplete_configuration(self):
        self.assertEqual(503, self.call('/api/order/create', {}, service=Orders(self.path))[0])
        for kwargs in ({'mode': 'live'}, {'mode': 'sandbox'}):
            with self.assertRaises(ValueError):
                Orders(self.path, **kwargs)
        for plan in (Plan('a', 0, 'CNY', 'calendar_year', 'sandbox_ledger_only'),
                     Plan('a', True, 'CNY', 'calendar_year', 'sandbox_ledger_only'),
                     Plan('a', 1, 'CNY', 'calendar_year', ''),
                     Plan('a', 1, 'CNY', 'fixed_days', 'sandbox_ledger_only', 0)):
            with self.assertRaises(ValueError):
                Orders(self.path, (plan,), mode='sandbox')

    def test_default_auth_denied_and_roles_cannot_be_spoofed(self):
        denied = self.make(authenticator=None)
        self.assertEqual(401, self.call('/api/order/create', {}, service=denied)[0])
        self.assertEqual(401, self.call('/api/admin/queue', identity='unknown', method='GET')[0])
        order = self.submitted()
        self.assertEqual(403, self.call('/api/admin/approve', {'order_id': order['id'], 'receipt_ref': 'receipt0001'})[0])
        self.assertEqual(405, self.call('/api/admin/approve', {}, 'reviewer', 'GET')[0])

    def test_creation_commits_code_before_payment_claim_and_is_idempotent(self):
        a = self.create()
        self.assertEqual('created', a['state'])
        self.assertRegex(a['remark_code'], r'^\d{4}$')
        self.assertEqual(a, self.create())
        self.assertEqual(1, self.audit(a['id'], 'created'))
        self.assertNotEqual(a['remark_code'], self.create('bob')['remark_code'])
        self.assertEqual(409, self.approve(a['id'])[0])

    def test_owner_cannot_read_submit_cancel_another_order(self):
        a = self.create()
        for path, data, method in (
            ('/api/order/status/' + a['id'], None, 'GET'),
            ('/api/order/submit', {'order_id': a['id']}, 'POST'),
            ('/api/order/cancel', {'order_id': a['id']}, 'POST')):
            self.assertEqual(404, self.call(path, data, 'bob', method)[0])
        self.assertEqual(404, self.call('/api/order/status/' + a['remark_code'], method='GET')[0])

    def test_amount_currency_and_term_are_immutable_server_snapshots(self):
        a = self.create()
        body = {'plan_id': self.plan.id, 'request_key': 'request_000000000002', 'amount_minor': 1}
        self.assertEqual(400, self.call('/api/order/create', body)[0])
        self.plan = Plan('annual-v1', 9999, 'USD', 'fixed_days', 'sandbox_ledger_only', 1, 9999, 200, 200)
        self.service = self.make()
        self.assertEqual(a, self.create())
        self.call('/api/order/submit', {'order_id': a['id']})
        status, result = self.approve(a['id'])
        self.assertEqual(200, status)
        self.assertEqual(1234, result['data']['amount_minor'])
        self.assertEqual('CNY', result['data']['currency'])
        self.assertEqual(5242880, result['data']['quota_bytes'])
        self.assertEqual(100, result['data']['upload_kbps'])
        self.assertEqual(ms('2025-02-28T12:34:56.789+00:00'), result['data']['target_ms'])

    def test_duplicate_submit_and_approval_issue_once(self):
        a = self.submitted()
        self.call('/api/order/submit', {'order_id': a['id']})
        first = self.approve(a['id'])
        self.assertEqual(200, first[0])
        self.assertEqual(first, self.approve(a['id']))
        self.assertEqual(1, len(self.adapter.calls))
        self.assertEqual(1, self.audit(a['id'], 'payment_claimed'))
        self.assertEqual(1, self.audit(a['id'], 'approved'))
        self.assertEqual(409, self.approve(a['id'], 'other_receipt')[0])

    def test_failure_before_and_after_remote_write_retries_same_target_across_restart(self):
        for failure in ('fail_before', 'fail_after'):
            with self.subTest(failure=failure):
                self.setUp()
                a = self.submitted()
                setattr(self.adapter, failure, True)
                status, error = self.approve(a['id'])
                self.assertEqual((503, {'error': 'ENTITLEMENT_RETRY_REQUIRED'}), (status, error))
                self.now += 10 * 86400000
                self.service = self.make()
                self.assertEqual(200, self.approve(a['id'])[0])
                self.assertEqual(self.adapter.calls[0], self.adapter.calls[1])
                self.assertEqual(1, len(self.adapter.operations))
                self.assertEqual(1, self.audit(a['id'], 'approved'))

    def test_readback_required_before_approved(self):
        a = self.submitted()
        self.adapter.readback_bad = True
        self.assertEqual(503, self.approve(a['id'])[0])
        status, result = self.call('/api/order/status/' + a['id'], method='GET')
        self.assertEqual('applying', result['data']['state'])
        self.adapter.readback_bad = False
        self.assertEqual(200, self.approve(a['id'])[0])

    def test_parallel_approval_connections_never_double_grant(self):
        a = self.submitted()
        services = [self.make() for _ in range(12)]
        barrier = threading.Barrier(12)
        def run(service):
            barrier.wait()
            return self.approve(a['id'], service=service)[0]
        with ThreadPoolExecutor(max_workers=12) as pool:
            statuses = list(pool.map(run, services))
        self.assertTrue(set(statuses) <= {200, 409}, statuses)
        self.assertIn(200, statuses)
        self.assertEqual(1, len(self.adapter.operations))
        self.assertEqual(1, self.audit(a['id'], 'approval_reserved'))
        self.assertEqual(1, self.audit(a['id'], 'approved'))

    def test_external_call_is_outside_db_transaction_and_live_lease_blocks_retry(self):
        a = self.submitted()
        original = self.adapter.ensure
        def ensure(user, expiry, operation):
            with closing(sqlite3.connect(self.path, timeout=0.1)) as db:
                db.execute('BEGIN IMMEDIATE')
                db.rollback()
            self.assertEqual(409, self.approve(a['id'], service=self.make())[0])
            original(user, expiry, operation)
        self.adapter.ensure = ensure
        self.assertEqual(200, self.approve(a['id'])[0])

    def test_crash_leaves_durable_lease_and_retry_reclaims_after_timeout(self):
        a = self.submitted()
        original = self.adapter.ensure
        def crash(*args):
            original(*args)
            raise KeyboardInterrupt()
        self.adapter.ensure = crash
        with self.assertRaises(KeyboardInterrupt):
            self.approve(a['id'])
        self.assertEqual(409, self.approve(a['id'])[0])
        self.now += 30001
        self.adapter.ensure = original
        self.assertEqual(200, self.approve(a['id'], service=self.make())[0])
        self.assertEqual(self.adapter.calls[0], self.adapter.calls[1])

    def test_renewal_starts_at_later_of_now_or_existing_expiry(self):
        a = self.submitted()
        expiry = self.approve(a['id'])[1]['data']['target_ms']
        b = self.create(key='request_000000000002')
        self.call('/api/order/submit', {'order_id': b['id']})
        extended = self.approve(b['id'], 'receipt_00000002')[1]['data']['target_ms']
        self.assertEqual(extend_ms(expiry, 'calendar_year', 0), extended)
        self.now = extended + 1000
        c = self.create(key='request_000000000003')
        self.call('/api/order/submit', {'order_id': c['id']})
        after_expiry = self.approve(c['id'], 'receipt_00000003')[1]['data']['target_ms']
        self.assertEqual(extend_ms(self.now, 'calendar_year', 0), after_expiry)

    def test_fixed_days_utc_offset_and_dst_independence(self):
        stamp = ms('2024-03-10T01:30:00-05:00')
        self.assertEqual(ms('2024-03-11T06:30:00+00:00'), extend_ms(stamp, 'fixed_days', 1))
        self.assertEqual(extend_ms(ms('2024-01-01T08:00:00+08:00'), 'calendar_year', 0),
                         extend_ms(ms('2024-01-01T00:00:00+00:00'), 'calendar_year', 0))

    def test_receipt_cannot_pay_two_orders_and_pending_order_blocks_second(self):
        a = self.submitted()
        self.assertEqual(409, self.call('/api/order/create', {'plan_id': self.plan.id, 'request_key': 'other_request_0001'})[0])
        self.approve(a['id'])
        b = self.create('bob')
        self.call('/api/order/submit', {'order_id': b['id']}, 'bob')
        self.assertEqual(409, self.approve(b['id'])[0])

    def test_cancel_reject_terminal_and_cannot_cancel_approved(self):
        a = self.create()
        self.assertEqual(200, self.call('/api/order/cancel', {'order_id': a['id']})[0])
        self.assertEqual(409, self.call('/api/order/submit', {'order_id': a['id']})[0])
        b = self.create(key='request_000000000002')
        self.assertEqual(200, self.call('/api/admin/reject', {'order_id': b['id']}, 'reviewer')[0])
        c = self.create(key='request_000000000003')
        self.call('/api/order/submit', {'order_id': c['id']})
        self.approve(c['id'])
        self.assertEqual(409, self.call('/api/order/cancel', {'order_id': c['id']})[0])

    def test_notification_failure_keeps_review_queue_and_never_exposes_secrets(self):
        class BrokenNotification:
            def review_requested(self, *args):
                raise RuntimeError('SENSITIVE_PUSH_KEY')
        self.service = self.make(notifications=BrokenNotification())
        a = self.submitted()
        status, queue = self.call('/api/admin/queue', identity='reviewer', method='GET')
        self.assertEqual(200, status)
        self.assertEqual(a['id'], queue['data']['items'][0]['id'])
        self.assertEqual(1, self.audit(a['id'], 'notification_failed'))
        for secret in ('password', 'SENSITIVE', 'token', 'receipt_ref'):
            self.assertNotIn(secret, json.dumps(queue))

    def test_late_worker_cannot_commit_over_new_renewal(self):
        a = self.submitted()
        started, release = threading.Event(), threading.Event()
        original = self.adapter.ensure
        first = True
        def delayed(*args):
            nonlocal first
            if first:
                first = False
                started.set()
                if not release.wait(5):
                    raise TimeoutError()
            original(*args)
        self.adapter.ensure = delayed
        with ThreadPoolExecutor(max_workers=1) as pool:
            pending = pool.submit(self.approve, a['id'])
            try:
                self.assertTrue(started.wait(2))
                self.now += 30001
                self.assertEqual(200, self.approve(a['id'])[0])
                b = self.create(key='request_000000000002')
                self.call('/api/order/submit', {'order_id': b['id']})
                status, renewed = self.approve(b['id'], 'receipt_00000002')
                self.assertEqual(200, status)
            finally:
                release.set()
            self.assertEqual(409, pending.result()[0])
        self.assertEqual(renewed['data']['target_ms'], self.adapter.values['alice'])
        with closing(sqlite3.connect(self.path)) as db:
            self.assertEqual(renewed['data']['target_ms'], db.execute('SELECT expires_ms FROM entitlements WHERE owner=?', ('alice',)).fetchone()[0])

    def test_review_never_expires_implicitly_and_reused_code_keeps_history(self):
        from unittest.mock import patch
        with patch('orders.secrets.choice', side_effect=lambda values: values[0]):
            a = self.submitted()
            self.now += 999 * 86400000
            b = self.create('bob')
            self.assertNotEqual(a['remark_code'], b['remark_code'])
            self.call('/api/admin/reject', {'order_id': a['id']}, 'reviewer')
            c = self.create(key='request_000000000003')
            self.assertEqual(a['remark_code'], c['remark_code'])
            with closing(sqlite3.connect(self.path)) as db:
                rows = db.execute('SELECT id,state FROM orders WHERE remark_code=?', (a['remark_code'],)).fetchall()
            self.assertEqual({(a['id'], 'rejected'), (c['id'], 'created')}, set(rows))

    def test_code_pool_exhaustion_is_bounded(self):
        with self.service.transaction() as db:
            db.executemany('INSERT INTO orders(id,owner,request_key,plan_id,amount_minor,currency,term,days,expiry_policy,quota_bytes,upload_kbps,download_kbps,state,remark_code,created_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
                           [(str(n), 'owner'+str(n), 'key', 'test', 1, 'CNY', 'fixed_days', 1, 'sandbox_ledger_only', 1, 1, 1, 'review', f'{n:04d}', self.now) for n in range(10000)])
        self.assertEqual((503, {'error': 'REMARK_CODES_EXHAUSTED'}),
                         self.call('/api/order/create', {'plan_id': self.plan.id, 'request_key': 'request_000000000001'}))


if __name__ == '__main__':
    unittest.main()
