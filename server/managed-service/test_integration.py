"""Real service HTTP + real REST client + real SQLite; upstream/Feishu are fixtures.

No real SFTPGo binary, real bot connection, secrets, or production deployment.
"""
import base64
from http.client import HTTPConnection
import json
from pathlib import Path
import tempfile
import threading
from types import SimpleNamespace as NS
import unittest
from unittest.mock import patch

from service import ManagedApplication, create_server
from runtime_config import PREREQUISITES, RuntimeConfig
from ledger_gate import ManagedLedger
from bot import Binding, DurableQueue, RenewalBot
from orders import extend_ms
from registration_trials import TRIAL_MS
from test_sftpgo_client import SftpgoRestFixture


class ManagedIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.upstream = SftpgoRestFixture().__enter__()
        self.addCleanup(self.upstream.__exit__)
        self.now = self.upstream.created_at_ms
        self.path = Path(self.temp.name) / 'ledger.sqlite'
        self.client = self.upstream.client()
        self.ledger = ManagedLedger(self.path, accounts=self.client, enabled=True, clock=lambda: self.now)
        self.config = RuntimeConfig.parse({
            'version': 1, 'enabled': True, 'mutations_enabled': True,
            'listen': {'host': '127.0.0.1', 'port': 8088, 'max_workers': 4, 'request_timeout_seconds': 5},
            'database_path': str(self.path), 'public_webdav_url': 'https://dav.example.invalid/dav/',
            'sftpgo': {'base_url': self.upstream.base_url, 'instance_id': 'fixture-instance',
                'admin_credentials': {'username_env': 'FIXTURE_USER', 'password_env': 'FIXTURE_PASSWORD'},
                'allow_loopback_http': True, 'timeout_seconds': 2,
                'new_account': {'home_root': '/fixture/accounts', 'quota_bytes': 123456,
                               'upload_bandwidth_kb': 75, 'download_bandwidth_kb': 125}},
            'prerequisites': {key: True for key in PREREQUISITES}, 'feishu': {'enabled': False}})
        self.server = create_server(ManagedApplication(self.config, accounts=self.client, ledger=self.ledger,
                                                      clock=lambda: self.now), port=0)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)

    def stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.assertFalse(self.thread.is_alive())

    def request(self, path, *, body=None, username=None, password=None, original_method=None):
        headers = {}
        if username is not None:
            value = username + ':' + (password or self.upstream.user_password)
            headers['Authorization'] = 'Basic ' + base64.b64encode(value.encode()).decode()
        if original_method:
            headers['X-Original-Method'] = original_method
        if body is not None:
            body = json.dumps(body).encode()
            headers['Content-Type'] = 'application/json'
        connection = HTTPConnection('127.0.0.1', self.server.server_port, timeout=5)
        try:
            connection.request('POST' if body is not None else 'GET', path, body=body, headers=headers)
            response = connection.getresponse()
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
        finally:
            connection.close()

    def account(self, action='register', username='alice', password=None):
        return self.request('/api/register', body={'version': 2, 'action': action, 'username': username,
                            'password': password or self.upstream.user_password})

    def gate(self, method='PUT', username='alice', password=None):
        return self.request('/_internal/dav-authorize', username=username, password=password,
                            original_method=method)[0]

    def entitlement(self, username='alice'):
        status, response = self.request('/api/account/entitlement', username=username)
        self.assertEqual(200, status)
        self.assertNotIn('sandbox', response)
        self.assertEqual('managed_ledger', response['data']['source'])
        return response['data']

    def test_registration_login_trial_boundary_and_real_http_gate(self):
        status, response = self.account()
        self.assertEqual(200, status)
        self.assertEqual(123456, response['data']['quota_bytes'])
        self.assertNotIn('password', response['data'])
        original = self.entitlement()['expires_ms']
        self.assertEqual(self.now + TRIAL_MS, original)
        self.assertEqual(204, self.gate())
        self.now += 10000
        self.assertEqual(200, self.account('login')[0])
        self.assertEqual(200, self.account()[0])
        self.assertEqual(original, self.entitlement()['expires_ms'])
        self.assertEqual(1, len(self.upstream.creates))
        self.now = original
        self.assertFalse(self.entitlement()['active'])
        for method in ('PUT', 'MKCOL', 'MOVE', 'DELETE', 'COPY', 'PROPPATCH', 'LOCK', 'UNLOCK'):
            self.assertEqual(403, self.gate(method))
        for method in ('GET', 'HEAD', 'PROPFIND', 'OPTIONS'):
            self.assertEqual(204, self.gate(method))
        self.assertEqual(200, self.account('login')[0])
        self.assertEqual(401, self.gate('GET', password='wrong-fixture-password'))

    def test_uncertain_create_response_restart_and_original_trial(self):
        self.upstream.drop_create_response_once = True
        status, error = self.account()
        self.assertEqual(503, status)
        self.assertEqual('REGISTRATION_TRIAL_RETRY_REQUIRED', error['error'])
        self.assertEqual(1, len(self.upstream.creates))
        self.assertEqual(403, self.gate())  # Remote account exists, local grant not confirmed.
        self.now += 60000
        self.ledger = ManagedLedger(self.path, accounts=self.upstream.client(), enabled=True, clock=lambda: self.now)
        # Retry through a newly composed HTTP handler to exercise disk recovery.
        self.stop_server()
        self.server = create_server(ManagedApplication(self.config, accounts=self.client, ledger=self.ledger,
                                                      clock=lambda: self.now), port=0)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.assertEqual(200, self.account()[0])
        self.assertEqual(self.upstream.created_at_ms + TRIAL_MS, self.entitlement()['expires_ms'])
        self.assertEqual(1, len(self.upstream.creates))
        self.assertEqual(204, self.gate())

    def test_registration_rule_boundaries_through_real_http_and_rest_adapter(self):
        for username in ('legacy_name', 'legacy-name', 'UpperCase', 'ab', '123' + '4' * 30):
            status, error = self.account(username=username, password='123456')
            self.assertEqual((400, 'INVALID_USERNAME'), (status, error['error']))
        for password in ('12345', '😀' * 5, 'x' * 129, '$12345', '{12345', 'abc\x85def'):
            status, error = self.account(username='xyz1001', password=password)
            self.assertEqual((400, 'INVALID_PASSWORD'), (status, error['error']))
        self.assertEqual([], self.upstream.creates)
        for username, password in (('xyz1001', '123456'), ('0name', '😀' * 6), ('123', ' 1234 ')):
            with self.subTest(username=username):
                status, response = self.account(username=username, password=password)
                self.assertEqual(200, status)
                self.assertEqual(username, response['data']['username'])
                self.assertEqual(200, self.account('login', username, password)[0])
        self.assertEqual(3, len(self.upstream.creates))

    def test_legacy_names_keep_login_feishu_annual_renewal_and_entitlement(self):
        binding = Binding(True, 'cli_fixture', 'tenant_fixture', 'oc_fixture', ('ou_admin',), 'ou_bot')
        queue = DurableQueue(Path(self.temp.name) / 'legacy-queue.sqlite', clock=lambda: self.now)
        worker = RenewalBot(binding, queue, self.ledger, object())
        for index, username in enumerate(('legacy_name', 'legacy-name', 'Legacy.Account-One')):
            self.upstream.put_account(username)
            self.assertEqual(200, self.account('login', username)[0])
            self.assertFalse(self.entitlement(username)['active'])
            event = NS(schema='2.0', header=NS(app_id=binding.app_id, tenant_key=binding.tenant_key,
                       event_type='im.message.receive_v1'), event=NS(sender=NS(sender_type='user',
                       tenant_key=binding.tenant_key, sender_id=NS(open_id='ou_admin')), message=NS(
                       message_id='om_legacy_' + str(index), chat_id=binding.chat_id, chat_type='group',
                       message_type='text', content=json.dumps({'text': username}), mentions=[])))
            self.assertTrue(worker.accept_sdk_event(event))
            self.assertTrue(worker.process_one())
            self.assertEqual(extend_ms(self.now, 'calendar_year', 0), self.entitlement(username)['expires_ms'])
            self.assertTrue(self.entitlement(username)['active'])
            self.assertEqual(200, self.account('login', username)[0])
            self.assertEqual(204, self.gate(username=username))
            # The new naming policy must never silently rewrite a legacy identity.
            self.assertEqual(username, self.upstream.accounts[username]['username'])
        self.assertEqual([], self.upstream.creates)

    def test_trial_application_failure_reports_registration_recovery_not_login(self):
        with patch.object(self.ledger.applications, 'ensure', side_effect=OSError('private fixture detail')):
            status, error = self.account()
        self.assertEqual(503, status)
        self.assertEqual('REGISTRATION_TRIAL_RETRY_REQUIRED', error['error'])
        self.assertNotIn('private', json.dumps(error))
        self.assertEqual(200, self.account('login')[0])
        self.assertEqual(403, self.gate())  # Login never grants or repairs a pending trial.
        self.now += 60000
        self.assertEqual(200, self.account()[0])
        self.assertEqual(1, len(self.upstream.creates))
        self.assertEqual(self.upstream.created_at_ms + TRIAL_MS, self.entitlement()['expires_ms'])
        self.assertEqual(204, self.gate())

    def test_bot_queue_grant_reply_failure_restart_and_duplicate(self):
        self.assertEqual(200, self.account()[0])
        binding = Binding(True, 'cli_fixture', 'tenant_fixture', 'oc_fixture', ('ou_admin',), 'ou_bot')
        queue_path = Path(self.temp.name) / 'queue.sqlite'
        class Replies:
            def send_reply(self, *args):
                raise TimeoutError('synthetic reply uncertainty')
        def bot():
            return RenewalBot(binding, DurableQueue(queue_path, clock=lambda: self.now),
                              ManagedLedger(self.path, accounts=self.client, enabled=True, clock=lambda: self.now), Replies())
        def event(message):
            return NS(schema='2.0', header=NS(app_id=binding.app_id, tenant_key=binding.tenant_key,
                       event_type='im.message.receive_v1'), event=NS(sender=NS(sender_type='user',
                       tenant_key=binding.tenant_key, sender_id=NS(open_id='ou_admin')), message=NS(
                       message_id=message, chat_id=binding.chat_id, chat_type='group', message_type='text',
                       content=json.dumps({'text': 'alice'}), mentions=[])))
        worker = bot()
        self.assertTrue(worker.accept_sdk_event(event('om_first')))
        self.assertTrue(worker.process_one())
        first = extend_ms(self.now, 'calendar_year', 0)
        self.assertEqual(first, self.entitlement()['expires_ms'])  # No remaining trial added.
        self.assertTrue(worker.reply_one())
        worker = bot()
        self.assertTrue(worker.accept_sdk_event(event('om_first')))
        self.assertFalse(worker.process_one())
        self.assertEqual(first, self.entitlement()['expires_ms'])
        self.assertTrue(worker.accept_sdk_event(event('om_second')))
        self.assertTrue(worker.process_one())
        self.assertEqual(extend_ms(first, 'calendar_year', 0), self.entitlement()['expires_ms'])
        self.assertEqual(204, self.gate())

    def test_missing_cross_account_recreated_identity_and_legacy_create(self):
        self.assertEqual(200, self.account()[0])
        self.upstream.put_account('bob')
        self.assertEqual(403, self.gate(username='bob'))
        self.assertEqual(401, self.gate(username='unknown'))
        self.assertEqual(400, self.request('/api/register', body={})[0])
        self.assertEqual(1, len(self.upstream.creates))
        self.upstream.accounts['alice']['id'] += 10
        self.assertEqual(403, self.gate())
        self.assertEqual(204, self.gate('GET'))
        self.assertEqual(409, self.request('/api/account/entitlement', username='alice')[0])


if __name__ == '__main__':
    unittest.main()
