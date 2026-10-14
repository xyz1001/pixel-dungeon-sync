"""Local socket/SQLite tests only; all upstream account calls are synthetic."""
from contextlib import redirect_stderr, redirect_stdout
from dataclasses import replace
import base64
import http.client
import io
import json
from pathlib import Path
import socket
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import service
from ledger_gate import ManagedLedger
from runtime_config import ConfigurationError, PREREQUISITES, RuntimeConfig
from sftpgo_client import SftpgoAccount, SftpgoError

PASSWORD = 'Test-only-password-123'
NOW = 1_700_000_000_000


def basic(username='alice', password=PASSWORD):
    return 'Basic ' + base64.b64encode((username + ':' + password).encode()).decode()


class AccountsFixture:
    def __init__(self, clock):
        self.clock, self.users, self.passwords = clock, {}, {}

    def lookup(self, username):
        return self.users.get(username)

    def authenticate_basic(self, username, password):
        account = self.users.get(username)
        if account is None or self.passwords[username] != password or account.status != 1:
            raise SftpgoError(401, 'INVALID_CREDENTIALS')
        return account

    def create_or_recover(self, username, password, operation_id):
        account = self.users.get(username)
        if account is None:
            account = SftpgoAccount(username, 'instance:' + username + ':1', self.clock(), operation_id,
                                    quota_bytes=5242880, upload_bandwidth_kb=100, download_bandwidth_kb=100)
            self.users[username], self.passwords[username] = account, password
        if account.registration_operation_id != operation_id:
            raise SftpgoError(409, 'USERNAME_UNAVAILABLE')
        return self.authenticate_basic(username, password)


def example():
    return json.loads(Path(__file__).with_name('config.example.json').read_text())


class ConfigTests(unittest.TestCase):
    def test_disabled_example_and_missing_flags_default_disabled(self):
        raw = example()
        self.assertFalse(RuntimeConfig.parse(raw).enabled)
        del raw['enabled'], raw['mutations_enabled']
        parsed = RuntimeConfig.parse(raw)
        self.assertFalse(parsed.enabled or parsed.mutations_enabled)

    def test_unknown_fields_wrong_types_and_nonloopback_fail(self):
        changes = [(['listen', 'host'], '0.0.0.0'), (['listen', 'host'], 'localhost'),
                   (['enabled'], 1), (['listen', 'max_workers'], 1000),
                   (['listen', 'request_timeout_seconds'], float('nan')),
                   (['sftpgo', 'admin_credentials'], {'password': 'DO_NOT_EMBED'}),
                   (['sftpgo', 'base_url'], 'https://username:password@example.invalid'),
                   (['sftpgo', 'base_url'], 'http://example.invalid:8088'),
                   (['database_path'], '/var/../private.sqlite'),
                   (['public_webdav_url'], 'http://public.example.invalid')]
        for keys, value in changes:
            with self.subTest(keys=keys):
                raw, node = example(), None
                node = raw
                for key in keys[:-1]:
                    node = node[key]
                node[keys[-1]] = value
                with self.assertRaises(ConfigurationError):
                    RuntimeConfig.parse(raw)
        raw = example()
        raw['unknown'] = True
        with self.assertRaises(ConfigurationError):
            RuntimeConfig.parse(raw)

    def test_mutations_require_every_attestation_and_enabled_listener(self):
        for absent in PREREQUISITES:
            raw = example()
            raw.update(enabled=True, mutations_enabled=True)
            raw['sftpgo']['instance_id'] = 'test-instance'
            raw['prerequisites'] = dict.fromkeys(PREREQUISITES, True)
            raw['prerequisites'][absent] = False
            with self.subTest(absent=absent), self.assertRaises(ConfigurationError):
                RuntimeConfig.parse(raw)
        raw['prerequisites'] = dict.fromkeys(PREREQUISITES, True)
        self.assertTrue(RuntimeConfig.parse(raw).mutations_enabled)
        raw['enabled'] = False
        with self.assertRaises(ConfigurationError):
            RuntimeConfig.parse(raw)

    def test_native_expiry_is_opt_in_and_requires_single_account_writer(self):
        raw = example()
        del raw['account_expiry']
        self.assertEqual({'enabled': False, 'single_writer': False}, RuntimeConfig.parse(raw).account_expiry)
        raw.update(enabled=True, mutations_enabled=True)
        raw['sftpgo']['instance_id'] = 'test-instance'
        raw['prerequisites'] = dict.fromkeys(PREREQUISITES, True)
        for settings in ({}, {'enabled': True}, {'enabled': 1, 'single_writer': True},
                         {'enabled': True, 'single_writer': False},
                         {'enabled': True, 'single_writer': True, 'unknown': True}):
            with self.subTest(settings=settings), self.assertRaises(ConfigurationError):
                RuntimeConfig.parse({**raw, 'account_expiry': settings})
        raw['account_expiry'] = {'enabled': True, 'single_writer': True}
        self.assertTrue(RuntimeConfig.parse(raw).account_expiry['enabled'])
        raw['prerequisites']['all_managed_aliases_gated'] = False
        self.assertTrue(RuntimeConfig.parse(raw).account_expiry['enabled'])
        raw['mutations_enabled'] = False
        with self.assertRaisesRegex(ConfigurationError, 'NATIVE_EXPIRY_REQUIRES_ENABLED_SINGLE_WRITER'):
            RuntimeConfig.parse(raw)

    def test_native_migration_command_refuses_disabled_policy(self):
        with patch.object(service, 'build_application', side_effect=AssertionError('runtime opened')), \
             redirect_stderr(io.StringIO()):
            self.assertEqual(2, service.main(['migrate-native-expiry', str(Path(__file__).with_name('config.example.json'))]))

    def test_feishu_requires_mutations_and_complete_config(self):
        raw = example()
        raw['feishu']['enabled'] = True
        with self.assertRaises(ConfigurationError):
            RuntimeConfig.parse(raw)

    def test_duplicate_config_keys_and_oversize_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'config.json'
            for content in ('{"enabled":false,"enabled":true}', ' ' * 32769):
                path.write_text(content)
                with self.assertRaises(ConfigurationError):
                    RuntimeConfig.load(path)

    def test_cli_offline_does_not_read_credentials_or_network(self):
        with patch.object(service.RuntimeAdminCredentials, '__call__', side_effect=AssertionError('secret read')), \
             patch.object(socket, 'socket', side_effect=AssertionError('network')), \
             redirect_stdout(io.StringIO()):
            self.assertEqual(0, service.main(['validate-config', str(Path(__file__).with_name('config.example.json'))]))

    def test_cli_serve_disabled_fails_without_listener_or_credentials(self):
        output = io.StringIO()
        with patch.object(service, 'create_server', side_effect=AssertionError('listener')), \
             patch.object(service.RuntimeAdminCredentials, '__call__', side_effect=AssertionError('secret read')), \
             redirect_stderr(output):
            self.assertEqual(2, service.main(['serve', str(Path(__file__).with_name('config.example.json'))]))
        self.assertIn('SERVICE_DISABLED', output.getvalue())


class HttpTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.now = NOW
        raw = example()
        raw.update(enabled=True, mutations_enabled=True)
        raw['database_path'] = str(Path(self.temporary.name) / 'ledger.sqlite')
        raw['sftpgo']['instance_id'] = 'test-instance'
        raw['prerequisites'] = dict.fromkeys(PREREQUISITES, True)
        self.config = RuntimeConfig.parse(raw)
        self.accounts = AccountsFixture(lambda: self.now)
        self.ledger = ManagedLedger(self.config.database_path, accounts=self.accounts, enabled=True, clock=lambda: self.now)
        self.application = service.ManagedApplication(self.config, accounts=self.accounts, ledger=self.ledger, clock=lambda: self.now)
        self.server = service.create_server(self.application, port=0)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop)

    def stop(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)

    def request(self, method='GET', path='/healthz', body=None, headers=None):
        connection = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=3)
        try:
            connection.request(method, path, body=body, headers=headers or {})
            response = connection.getresponse()
            data = response.read()
            return response.status, dict(response.getheaders()), json.loads(data) if data else None
        finally:
            connection.close()

    def register(self, username='alice', password=PASSWORD, action='register'):
        return self.request('POST', '/api/register', json.dumps({'version': 2, 'action': action, 'username': username, 'password': password}), {'Content-Type': 'application/json'})

    def gate(self, method='PUT', authorization=None, extra=None):
        headers = {'Authorization': authorization or basic(), 'X-Original-Method': method}
        headers.update(extra or {})
        return self.request(path='/_internal/dav-authorize', headers=headers)

    def raw(self, request):
        with socket.create_connection(self.server.server_address, timeout=3) as connection:
            connection.sendall(request)
            result = b''
            while True:
                chunk = connection.recv(4096)
                if not chunk:
                    break
                result += chunk
            return result

    def test_register_actual_ledger_then_scoped_entitlement_and_gate(self):
        status, headers, payload = self.register()
        self.assertEqual(200, status)
        self.assertEqual('no-store', headers['Cache-Control'])
        self.assertEqual({'webdav_url', 'username', 'quota_bytes', 'quota_mb', 'upload_bandwidth_kb', 'download_bandwidth_kb'}, set(payload['data']))
        self.assertNotIn(PASSWORD, json.dumps(payload))
        self.assertNotIn('account_id', json.dumps(payload))
        status, _, payload = self.request(path='/api/account/entitlement', headers={'Authorization': basic()})
        self.assertEqual(200, status)
        self.assertEqual('managed_ledger', payload['data']['source'])
        self.assertEqual(NOW + 72 * 3600_000, payload['data']['expires_ms'])
        self.assertTrue(payload['data']['active'])
        self.assertEqual(204, self.gate()[0])

    def test_expiry_denies_every_write_retains_reads_and_login(self):
        self.assertEqual(200, self.register()[0])
        self.now += 72 * 3600_000
        for method in service.WRITE_METHODS:
            with self.subTest(method=method):
                self.assertEqual(403, self.gate(method)[0])
        for method in service.READ_METHODS:
            with self.subTest(method=method):
                self.assertEqual(204, self.gate(method)[0])
        self.assertEqual(200, self.register(action='login')[0])
        self.assertEqual(401, self.register(password='wrong-password', action='login')[0])
        self.assertEqual(401, self.gate(authorization=basic(password='wrong-password'))[0])
        self.assertEqual(200, self.register()[0])
        self.assertEqual(403, self.gate()[0])

    def test_cross_account_header_spoof_does_not_grant_other_account_rights(self):
        self.register()
        self.now += 72 * 3600_000
        self.register('bob')
        self.assertEqual(403, self.gate(extra={'X-User': 'bob', 'X-Account-ID': 'instance:bob:1'})[0])
        self.assertEqual(204, self.gate(authorization=basic('bob'))[0])
        status, _, payload = self.request(path='/api/account/entitlement', headers={'Authorization': basic(), 'X-User': 'bob'})
        self.assertEqual(200, status)
        self.assertEqual('alice', payload['data']['username'])
        self.assertFalse(payload['data']['active'])

    def test_changed_identity_cannot_consume_old_trial(self):
        self.register()
        self.accounts.users['alice'] = replace(self.accounts.users['alice'], account_id='instance:alice:2')
        self.assertEqual(403, self.gate()[0])
        self.assertEqual(409, self.request(path='/api/account/entitlement', headers={'Authorization': basic()})[0])

    def test_database_fault_fails_writes_closed_and_keeps_authenticated_reads(self):
        self.register()
        with patch.object(self.ledger, 'expiry', side_effect=RuntimeError('PRIVATE_DSN password')):
            status, _, payload = self.gate()
            self.assertEqual(503, status)
            self.assertNotIn('PRIVATE', json.dumps(payload))
            self.assertEqual(204, self.gate('GET')[0])

    def test_disabled_mutations_leave_login_available(self):
        self.register()
        self.application.config = replace(self.config, mutations_enabled=False)
        self.assertEqual(503, self.register('bob')[0])
        self.assertEqual(200, self.register(action='login')[0])
        self.assertNotIn('bob', self.accounts.users)

    def test_duplicate_headers_transfer_encoding_expect_and_bad_host(self):
        host = f'Host: 127.0.0.1:{self.server.server_port}\r\n'.encode()
        for extra in (b'Authorization: Basic a\r\nauthorization: Basic b\r\n',
                      b'Content-Length: 0\r\nContent-Length: 0\r\n',
                      b'Transfer-Encoding: chunked\r\n', b'Expect: 100-continue\r\n',
                      b'X-Test: one\r\n folded\r\n'):
            result = self.raw(b'GET /healthz HTTP/1.0\r\n' + host + extra + b'\r\n')
            self.assertIn(b' 400 ', result.split(b'\r\n')[0])
        self.assertEqual(400, self.request(headers={'Host': 'attacker.invalid'})[0])

    def test_bad_json_legacy_oversize_and_credential_validator(self):
        bodies = ('{}', '', '[]', '{"version":2,"version":2}', '{"x":NaN}', '{"x":')
        for body in bodies:
            with self.subTest(body=body):
                self.assertEqual(400, self.request('POST', '/api/register', body, {'Content-Type': 'application/json'})[0])
        self.assertEqual(413, self.request('POST', '/api/register', 'a' * 4097, {'Content-Type': 'application/json'})[0])
        self.assertEqual(400, self.register('Alice')[0])
        self.assertEqual(400, self.register(password='$HASH-INPUT-REJECTED')[0])
        self.assertEqual(400, self.register(password='short')[0])
        self.assertFalse(self.accounts.users)

    def test_only_exact_routes_and_trusted_original_method(self):
        for path in ('/api/register?x=y', '/%61pi/register', '/api//register', '/register', '/upload', '/dav/', '/'):
            self.assertIn(self.request('POST', path, '{}', {'Content-Type': 'application/json'})[0], (400, 404))
        self.assertEqual(403, self.gate(method='GET, PUT')[0])
        self.assertEqual(403, self.request(path='/_internal/dav-authorize', headers={'Authorization': basic()})[0])
        self.assertEqual(400, self.request(path='/healthz', headers={'X-Original-Method': 'GET'})[0])
        self.assertEqual(403, self.request(headers={'Origin': 'https://attacker.invalid'})[0])
        self.assertEqual(403, self.request(headers={'Cookie': 'session=untrusted'})[0])

    def test_auth_errors_and_arbitrary_upstream_exception_redacted(self):
        for authorization in ('Bearer token', 'Basic !!!', basic(password='wrong-password')):
            status, headers, payload = self.request(path='/api/account/entitlement', headers={'Authorization': authorization})
            self.assertEqual(401, status)
            self.assertIn('Basic', headers['WWW-Authenticate'])
        with patch.object(self.accounts, 'authenticate_basic', side_effect=RuntimeError('VERY_PRIVATE PASSWORD')):
            status, _, payload = self.register(action='login')
        self.assertEqual(503, status)
        self.assertNotIn('VERY_PRIVATE', json.dumps(payload))

    def test_health_never_claims_route_enforcement(self):
        self.assertFalse(self.request()[2]['route_enforcement_verified'])

    def test_bounded_workers_reject_extra_connections_and_deadline_closes_slow_request(self):
        configured = replace(self.config, listen={**self.config.listen, 'max_workers': 1, 'request_timeout_seconds': 1})
        application = service.ManagedApplication(configured, accounts=self.accounts, ledger=self.ledger)
        server = service.create_server(application, port=0)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        first = socket.create_connection(server.server_address, timeout=3)
        try:
            first.sendall(b'GET /healthz HTTP/1.0\r\n')
            # Wait for admission of the intentionally incomplete first request.
            until = time.monotonic() + 0.5
            while server.workers._value and time.monotonic() < until:
                time.sleep(0.005)
            with socket.create_connection(server.server_address, timeout=3) as second:
                self.assertIn(b' 503 ', second.recv(4096).split(b'\r\n')[0])
            self.assertEqual(b'', first.recv(4096))
        finally:
            first.close()
            server.shutdown()
            server.server_close()
            thread.join(2)

    def test_public_route_never_accepts_internal_mutation_or_renewal(self):
        self.register()
        for path in ('/renew', '/api/renew', '/api/order/approve', '/_internal/adopt', '/feishu/event'):
            self.assertEqual(404, self.request('POST', path, '{}', {'Content-Type': 'application/json'})[0])
        self.assertEqual(405, self.request('PUT', '/api/register')[0])

    def test_missing_sdk_fails_before_listener_starts(self):
        import sdk_transport
        configured = replace(self.config, feishu={'enabled': True, 'binding_file': '/synthetic/binding.json',
                            'app_secret': {'env': 'SYNTHETIC_APP_SECRET'}, 'queue_path': '/synthetic/queue.sqlite'})
        binding = service.Binding(True, 'app', 'tenant', 'chat', ('admin',), 'bot')
        with patch.object(service, 'build_application', return_value=self.application), \
             patch.object(service.Binding, 'load', return_value=binding), \
             patch.object(service, 'load_runtime_secret', return_value='synthetic-not-real'), \
             patch.object(sdk_transport, '_load_sdk', side_effect=RuntimeError('PRIVATE SDK payload')), \
             patch.object(service, 'create_server') as listener:
            with self.assertRaisesRegex(ConfigurationError, '^FEISHU_SDK_UNAVAILABLE_OR_VERSION_MISMATCH$'):
                service.serve(configured)
            listener.assert_not_called()

    def test_feishu_starts_after_http_and_http_closes_on_sdk_return(self):
        import sdk_transport
        configured = replace(self.config, feishu={'enabled': True, 'binding_file': '/synthetic/binding.json',
                            'app_secret': {'env': 'SYNTHETIC_APP_SECRET'}, 'queue_path': '/synthetic/queue.sqlite'})
        application = service.ManagedApplication(configured, accounts=self.accounts, ledger=self.ledger)
        binding = service.Binding(True, 'app', 'tenant', 'chat', ('admin',), 'bot')
        servers = []
        actual_create = service.create_server
        def create(app):
            built = actual_create(app, port=0)
            servers.append(built)
            return built
        def run(received_binding, queue_path, ledger, secret):
            self.assertIs(binding, received_binding)
            self.assertIs(self.ledger, ledger)
            connection = http.client.HTTPConnection('127.0.0.1', servers[0].server_port, timeout=3)
            connection.request('GET', '/healthz')
            self.assertEqual(200, connection.getresponse().status)
            connection.close()
        with patch.object(service, 'build_application', return_value=application), \
             patch.object(service.Binding, 'load', return_value=binding), \
             patch.object(service, 'load_runtime_secret', return_value='synthetic-not-real'), \
             patch.object(sdk_transport, '_load_sdk'), \
             patch.object(sdk_transport, 'run_service', side_effect=run) as running, \
             patch.object(service, 'create_server', side_effect=create), redirect_stdout(io.StringIO()):
            service.serve(configured)
        self.assertEqual(1, running.call_count)
        self.assertEqual(-1, servers[0].socket.fileno())


if __name__ == '__main__':
    unittest.main()
