import base64
from contextlib import contextmanager
import http.client
import json
from pathlib import Path
import tempfile
import threading
import unittest

from http_api import SandboxDisplay, create_server
from orders import Orders, Plan, Principal, OrderError
from test_orders import FakeEntitlements

# Non-payment 1x1 PNG and synthetic identity fixtures. Never an actual QR/key.
PNG = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5S8AAAAASUVORK5CYII=')


class FixtureAuth:
    def authenticate(self, headers):
        # This implementation is only injected by the tests, never by the server.
        subject = headers.get('X-Test-Principal')
        if subject not in ('alice', 'bob', 'reviewer'):
            raise OrderError(401, 'AUTHENTICATION_REQUIRED')
        return Principal(subject, 'admin' if subject == 'reviewer' else 'user')


class HttpTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.plan = Plan('fixture-annual',
                         quota_bytes=5242880, upload_kbps=100, download_kbps=100)
        self.adapter = FakeEntitlements()
        self.service = Orders(Path(self.temp.name) / 'orders.sqlite', (self.plan,),
                              mode='sandbox', authenticator=FixtureAuth(), entitlements=self.adapter)
        self.displays = {self.plan.id: SandboxDisplay('Sandbox fixture - NOT FOR PAYMENT',
                        'Simulation only. Do not transfer funds.', PNG)}

    @contextmanager
    def running(self, service=None, displays=None):
        server = create_server(service or self.service, enabled=True,
                               displays=self.displays if displays is None else displays)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            yield server.server_port
        finally:
            server.shutdown()
            server.server_close()
            worker.join(2)

    def request(self, port, method, path, body=None, identity='alice', headers=None, raw=None):
        client = http.client.HTTPConnection('127.0.0.1', port, timeout=5)
        try:
            hdr = {'X-Test-Principal': identity}
            payload = raw
            if body is not None:
                payload = json.dumps(body)
                hdr['Content-Type'] = 'application/json'
            if headers:
                hdr.update(headers)
            client.request(method, path, payload, hdr)
            response = client.getresponse()
            data = response.read()
            self.assertEqual('no-store', response.getheader('Cache-Control'))
            return response.status, json.loads(data) if response.getheader('Content-Type').startswith('application/json') else data
        finally:
            client.close()

    def test_real_http_end_to_end_and_idempotency(self):
        with self.running() as port:
            status, plans = self.request(port, 'GET', '/api/order/plans')
            self.assertEqual(200, status)
            self.assertTrue(plans['sandbox'])
            self.assertIsNone(plans['data'][0]['amount_minor'])
            self.assertEqual('donation', plans['data'][0]['payment_mode'])
            self.assertEqual((200, PNG), self.request(port, 'GET', plans['data'][0]['payment_image_path']))
            create = {'plan_id': self.plan.id, 'request_key': 'fixture-request-0001'}
            status, order = self.request(port, 'POST', '/api/order/create', create)
            self.assertEqual(200, status)
            self.assertEqual('created', order['data']['state'])
            self.assertEqual((status, order), self.request(port, 'POST', '/api/order/create', create))
            oid = order['data']['id']
            self.assertEqual(1, len(self.request(port, 'GET', '/api/order/mine')[1]['data']))
            self.assertEqual([], self.request(port, 'GET', '/api/order/mine', identity='bob')[1]['data'])
            for _ in range(2):
                self.assertEqual('review', self.request(port, 'POST', '/api/order/submit', {'order_id': oid})[1]['data']['state'])
            self.assertEqual({}, self.adapter.values)
            approve = {'order_id': oid, 'channel': 'sandbox', 'receipt_ref': 'fixture-receipt-0001', 'actual_amount_minor': 1234, 'actual_currency': 'CNY'}
            for _ in range(2):
                self.assertEqual('approved', self.request(port, 'POST', '/api/admin/approve', approve, 'reviewer')[1]['data']['state'])
            status, approved = self.request(port, 'GET', '/api/order/status/' + oid)
            self.assertEqual(200, status)
            self.assertEqual(self.adapter.values['alice'], approved['data']['target_ms'])
            self.assertEqual(1, len(self.adapter.operations))

    def test_ownership_roles_get_approval_and_body_tampering(self):
        with self.running() as port:
            data = {'plan_id': self.plan.id, 'request_key': 'fixture-request-0001'}
            oid = self.request(port, 'POST', '/api/order/create', data)[1]['data']['id']
            self.assertEqual(404, self.request(port, 'GET', '/api/order/status/'+oid, identity='bob')[0])
            self.assertEqual(404, self.request(port, 'POST', '/api/order/submit', {'order_id': oid}, 'bob')[0])
            self.assertEqual(403, self.request(port, 'POST', '/api/admin/approve', {'order_id': oid})[0])
            self.assertEqual(405, self.request(port, 'GET', '/api/admin/approve', identity='reviewer')[0])
            self.assertEqual(401, self.request(port, 'GET', '/api/order/plans', identity='unknown')[0])
            self.assertEqual(400, self.request(port, 'POST', '/api/order/create', dict(data, amount_minor=1, owner='reviewer'))[0])

    def test_disabled_listener_mode_and_missing_auth_or_fixture(self):
        with self.assertRaises(ValueError):
            create_server(self.service)
        for host in ('0.0.0.0', 'localhost', '::', '192.168.1.1'):
            with self.assertRaises(ValueError):
                create_server(self.service, enabled=True, host=host)
        disabled = Orders(Path(self.temp.name) / 'disabled.sqlite')
        with self.running(disabled) as port:
            self.assertEqual((503, {'error': 'PAYMENTS_DISABLED'}), self.request(port, 'GET', '/api/order/plans'))
        denied = Orders(Path(self.temp.name) / 'denied.sqlite', (self.plan,), mode='sandbox')
        with self.running(denied) as port:
            self.assertEqual(401, self.request(port, 'GET', '/api/order/plans')[0])
        with self.running(displays={}) as port:
            self.assertEqual((503, {'error': 'SANDBOX_DISPLAY_REQUIRED'}), self.request(port, 'GET', '/api/order/plans'))
            self.assertEqual(503, self.request(port, 'POST', '/api/order/create', {'plan_id': self.plan.id, 'request_key': 'fixture-request-0001'})[0])

    def test_request_limits_and_no_browser_session_or_query_secrets(self):
        with self.running() as port:
            self.assertEqual(403, self.request(port, 'GET', '/api/order/plans', headers={'Origin': 'https://example.test'})[0])
            self.assertEqual(403, self.request(port, 'GET', '/api/order/plans', headers={'Cookie': 'session=test'})[0])
            self.assertEqual(400, self.request(port, 'GET', '/api/order/plans', headers={'Host': 'attacker.test'})[0])
            self.assertEqual(400, self.request(port, 'GET', '/api/admin/approve?secret=test')[0])
            self.assertEqual(413, self.request(port, 'POST', '/api/order/create', raw='x'*4097)[0])
            self.assertEqual(415, self.request(port, 'POST', '/api/order/create', raw='{}')[0])
            self.assertEqual(400, self.request(port, 'POST', '/api/order/create', raw='{"plan_id":"a","plan_id":"b"}', headers={'Content-Type': 'application/json'})[0])
            self.assertEqual(405, self.request(port, 'DELETE', '/api/order/create')[0])

    def test_admin_queue_paginates_101_orders_without_loss_or_duplicates(self):
        with self.service.transaction() as db:
            db.executemany('INSERT INTO orders(id,owner,request_key,plan_id,amount_minor,currency,term,days,expiry_policy,quota_bytes,upload_kbps,download_kbps,state,remark_code,created_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
                           [(str(n), 'owner'+str(n), 'key', 'fixture', None, None, 'calendar_year', 0, 'sandbox_upload_only', 1, 1, 1, 'review', f'{n:04d}', n) for n in range(101)])
        with self.running() as port:
            seen, cursor = [], '0'
            while cursor is not None:
                status, page = self.request(port, 'GET', '/api/admin/queue/' + cursor, identity='reviewer')
                self.assertEqual(200, status)
                seen.extend(item['id'] for item in page['data']['items'])
                cursor = page['data']['next_cursor']
            self.assertEqual([str(n) for n in range(101)], seen)
            self.assertEqual(403, self.request(port, 'GET', '/api/admin/queue/0')[0])

    def test_entitlement_is_authenticated_local_ledger_not_remote_guess(self):
        with self.running() as port:
            self.assertEqual((200, {'sandbox': True, 'data': {'source': 'sandbox_ledger', 'expires_ms': None, 'active': False}}),
                             self.request(port, 'GET', '/api/order/entitlement'))

    def test_admin_retry_after_new_store_and_http_restart_needs_only_id(self):
        with self.running() as port:
            body = {'plan_id': self.plan.id, 'request_key': 'fixture-request-0001'}
            oid = self.request(port, 'POST', '/api/order/create', body)[1]['data']['id']
            self.assertEqual(409, self.request(port, 'POST', '/api/admin/retry', {'order_id': oid}, 'reviewer')[0])
            self.request(port, 'POST', '/api/order/submit', {'order_id': oid})
            self.adapter.fail_after = True
            approved = {'order_id': oid, 'channel': 'sandbox', 'receipt_ref': 'fixture-receipt-0001', 'actual_amount_minor': 1234, 'actual_currency': 'CNY'}
            self.assertEqual(503, self.request(port, 'POST', '/api/admin/approve', approved, 'reviewer')[0])
        restarted = Orders(self.service.path, (self.plan,), mode='sandbox',
                           authenticator=FixtureAuth(), entitlements=self.adapter)
        with self.running(restarted) as port:
            queue = self.request(port, 'GET', '/api/admin/queue/0', identity='reviewer')[1]
            self.assertNotIn('receipt_ref', json.dumps(queue))
            recovered_id = queue['data']['items'][0]['id']
            self.assertEqual(403, self.request(port, 'POST', '/api/admin/retry', {'order_id': recovered_id})[0])
            self.assertEqual(405, self.request(port, 'GET', '/api/admin/retry', identity='reviewer')[0])
            for _ in range(2):
                status, result = self.request(port, 'POST', '/api/admin/retry', {'order_id': recovered_id}, 'reviewer')
                self.assertEqual(200, status)
                self.assertEqual('approved', result['data']['state'])
            self.assertEqual(self.adapter.calls[0], self.adapter.calls[1])
            self.assertEqual(1, len(self.adapter.operations))


if __name__ == '__main__':
    unittest.main()
