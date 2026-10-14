"""Loopback REST fixtures only. No real SFTPGo or production credentials used.

SftpgoRestFixture is reusable by composition tests. It emulates the documented
2.7.6 endpoints, but is not evidence that an actual SFTPGo binary was exercised.
"""

import base64
from copy import deepcopy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import socket
import tempfile
import threading
import unittest

from sftpgo_client import (AdminTokenProvider, MAX_JSON_BYTES, NewAccountTemplate,
                           REGISTRATION_NAMESPACE, RuntimeAdminCredentials,
                           SftpgoClient, SftpgoError)


def fixture_token(username, updated_at):
    def encoded(value):
        return base64.urlsafe_b64encode(json.dumps(value).encode()).decode().rstrip('=')
    return encoded({'alg': 'HS256'}) + '.' + encoded({'username': username, 'sub': str(updated_at)}) + '.fixture-signature'


class SftpgoRestFixture:
    """Explicit loopback server with synthetic credentials and mutation hooks."""

    username = 'fixture-admin'
    password = 'Synthetic-admin-password-42'
    user_password = 'Synthetic-user-password-42'
    instance_id = 'fixture-instance'
    created_at_ms = 1_700_000_000_000

    def __init__(self):
        self.accounts, self.passwords, self.calls, self.creates = {}, {}, [], []
        self.overrides = {}
        self.after_token = None
        self.drop_create_response_once = False
        self.before_create = None
        self.token_claims = None
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def send_json(self, status, value, headers=None):
                data = json.dumps(value).encode('utf-8')
                self.send_raw(status, data, headers=headers)

            def send_raw(self, status, data, content_type='application/json', headers=None):
                self.send_response(status)
                self.send_header('Content-Type', content_type)
                self.send_header('Content-Length', str(len(data)))
                for key, value in (headers or {}).items():
                    self.send_header(key, value)
                self.end_headers()
                try:
                    self.wfile.write(data)
                except (BrokenPipeError, ConnectionResetError):
                    pass

            def check_override(self):
                fixture.calls.append((self.command, self.path))
                override = fixture.overrides.get((self.command, self.path))
                if override is not None:
                    self.send_raw(*override)
                    return True
                return False

            def admin(self):
                if self.headers.get('Authorization') != 'Bearer fixture-admin-token':
                    self.send_json(401, {'error': 'PRIVATE_FIXTURE_ADMIN_FAILURE'})
                    return False
                return True

            def do_GET(self):
                if self.check_override():
                    return
                if self.path == '/api/v2/token':
                    expected = 'Basic ' + base64.b64encode((fixture.username + ':' + fixture.password).encode()).decode()
                    if self.headers.get('Authorization') != expected:
                        self.send_json(401, {'error': 'PRIVATE_FIXTURE_ADMIN_FAILURE'})
                    else:
                        self.send_json(200, {'access_token': 'fixture-admin-token', 'expires_at': '2030-01-01T00:00:00Z'})
                    return
                if self.path.startswith('/api/v2/users/'):
                    if not self.admin():
                        return
                    username = self.path.rsplit('/', 1)[1]
                    account = fixture.accounts.get(username)
                    self.send_json(200 if account else 404, account or {'error': 'not found'})
                    return
                if self.path == '/api/v2/user/token':
                    try:
                        raw = self.headers.get('Authorization', '')
                        if not raw.startswith('Basic '):
                            raise ValueError()
                        username, password = base64.b64decode(raw[6:], validate=True).decode().split(':', 1)
                        if fixture.passwords.get(username) != password or username not in fixture.accounts:
                            raise ValueError()
                    except (ValueError, UnicodeError):
                        self.send_json(401, {'error': 'PRIVATE_FIXTURE_USER_FAILURE'})
                        return
                    account = fixture.accounts[username]
                    token = fixture.token_claims or fixture_token(username, account['updated_at'])
                    if fixture.after_token:
                        fixture.after_token()
                    self.send_json(200, {'access_token': token, 'expires_at': '2030-01-01T00:00:00Z'})
                    return
                self.send_json(404, {'error': 'not found'})

            def do_POST(self):
                if self.check_override():
                    return
                if self.path != '/api/v2/users' or not self.admin():
                    return
                payload = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                fixture.creates.append(deepcopy(payload))
                if fixture.before_create:
                    fixture.before_create(payload)
                username = payload['username']
                if username in fixture.accounts:
                    self.send_json(409, {'error': 'PRIVATE_FIXTURE_DUPLICATE'})
                    return
                account = deepcopy(payload)
                fixture.passwords[username] = account.pop('password')
                account.update(id=len(fixture.accounts) + 1, created_at=fixture.created_at_ms,
                               updated_at=fixture.created_at_ms)
                account['password'] = 'PRIVATE_FIXTURE_HASH'
                fixture.accounts[username] = account
                if fixture.drop_create_response_once:
                    fixture.drop_create_response_once = False
                    self.connection.shutdown(socket.SHUT_RDWR)
                    self.connection.close()
                    return
                self.send_json(201, account)

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.base_url = 'http://127.0.0.1:' + str(self.server.server_port)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *args):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def credentials(self):
        return RuntimeAdminCredentials.from_environment('FIXTURE_ADMIN_USER', 'FIXTURE_ADMIN_PASSWORD',
                    environ={'FIXTURE_ADMIN_USER': self.username, 'FIXTURE_ADMIN_PASSWORD': self.password})

    def client(self, **changes):
        values = dict(instance_id=self.instance_id, admin_credentials=self.credentials(),
                      template=NewAccountTemplate('/fixture/accounts', 123456, 75, 125),
                      no_username_reuse=True, allow_loopback_http=True, timeout=2)
        values.update(changes)
        return SftpgoClient(self.base_url, **values)

    def put_account(self, username='alice', *, operation_id=None, password=None, **changes):
        account = {'username': username, 'id': len(self.accounts) + 1, 'created_at': self.created_at_ms,
                   'updated_at': self.created_at_ms, 'status': 1, 'quota_size': 123456,
                   'upload_bandwidth': 75, 'download_bandwidth': 125, 'additional_info': '',
                   'password': 'PRIVATE_FIXTURE_HASH', 'email': 'fixture@example.invalid'}
        if operation_id is not None:
            account['additional_info'] = json.dumps({REGISTRATION_NAMESPACE: {
                'instance_id': self.instance_id, 'operation_id': operation_id}})
        account.update(changes)
        self.accounts[username] = account
        self.passwords[username] = password or self.user_password
        return account


class ClientTests(unittest.TestCase):
    operation = 'registration_fixture_0001'

    def setUp(self):
        self.fixture = SftpgoRestFixture().__enter__()
        self.addCleanup(self.fixture.__exit__)
        self.client = self.fixture.client()

    def error(self, code, callback, *args):
        with self.assertRaises(SftpgoError) as caught:
            callback(*args)
        self.assertEqual(code, caught.exception.code)
        self.assertEqual(code, str(caught.exception))
        self.assertNotIn('PRIVATE', repr(caught.exception))
        return caught.exception

    def test_configuration_does_not_connect_or_read_runtime_credentials(self):
        creds = RuntimeAdminCredentials.from_file('/nonexistent/explicit/fixture-creds.json')
        client = SftpgoClient('https://example.invalid', instance_id='fixture', admin_credentials=creds)
        self.assertIsInstance(client.admin_tokens, AdminTokenProvider)
        self.assertEqual([], self.fixture.calls)
        self.error('SFTPGO_IDENTITY_INVARIANT_REQUIRED', client.authenticate_basic, 'alice', 'fixture')
        self.error('SFTPGO_IDENTITY_INVARIANT_REQUIRED', client.create_or_recover, 'alice', 'fixture', self.operation)

    def test_exact_lookup_and_safe_public_fields(self):
        self.assertIsNone(self.client.lookup('missing'))
        self.fixture.put_account()
        original = deepcopy(self.fixture.accounts)
        account = self.client.lookup('alice')
        self.assertEqual('sftpgo:fixture-instance:1:1700000000000', account.account_id)
        public = account.public_info('https://example.invalid/dav/')
        self.assertEqual({'webdav_url', 'username', 'quota_bytes', 'quota_mb', 'upload_bandwidth_kb', 'download_bandwidth_kb'}, set(public))
        self.assertNotIn('PRIVATE', json.dumps(public))
        self.assertNotIn('password', repr(account))
        self.assertEqual(original, self.fixture.accounts)
        self.assertIsNone(self.client.lookup('Alice'))

    def test_authenticated_principal_uses_server_proof_and_stable_instance_id(self):
        self.fixture.put_account()
        account = self.client.authenticate_basic('alice', self.fixture.user_password)
        other = self.fixture.client(instance_id='another-instance').lookup('alice')
        self.assertNotEqual(account.account_id, other.account_id)
        self.assertEqual(account, self.client.lookup('alice'))
        self.assertEqual(1, self.fixture.calls.count(('GET', '/api/v2/user/token')))

    def test_omitted_zero_bandwidth_matches_sftpgo_sdk_json(self):
        account = self.fixture.put_account(quota_size=0)
        del account['upload_bandwidth']
        del account['download_bandwidth']
        identity = self.client.authenticate_basic('alice', self.fixture.user_password)
        self.assertEqual((0, 0, 0), (identity.quota_bytes, identity.upload_bandwidth_kb, identity.download_bandwidth_kb))
        account['upload_bandwidth'] = None
        self.error('SFTPGO_INVALID_ACCOUNT_IDENTITY', self.client.lookup, 'alice')

    def test_wrong_password_fails_without_writes(self):
        self.fixture.put_account()
        self.error('INVALID_CREDENTIALS', self.client.authenticate_basic, 'alice', 'wrong')
        self.assertEqual([], self.fixture.creates)

    def test_preexisting_other_or_missing_marker_never_modified(self):
        for marker in (None, 'registration_other_0001'):
            with self.subTest(marker=marker):
                self.fixture.put_account(operation_id=marker, description='external field must survive')
                before = deepcopy(self.fixture.accounts)
                self.error('USERNAME_UNAVAILABLE', self.client.create_or_recover, 'alice', self.fixture.user_password, self.operation)
                self.assertEqual(before, self.fixture.accounts)
        self.assertEqual([], self.fixture.creates)
        self.assertNotIn(('GET', '/api/v2/user/token'), self.fixture.calls)

    def test_registration_atomic_marker_and_original_server_time(self):
        account = self.client.create_or_recover('alice', self.fixture.user_password, self.operation)
        self.assertEqual(self.operation, account.registration_operation_id)
        self.assertEqual(self.fixture.created_at_ms, account.created_at_ms)
        payload = self.fixture.creates[0]
        self.assertEqual(self.fixture.user_password, payload['password'])
        self.assertEqual(self.operation, json.loads(payload['additional_info'])[REGISTRATION_NAMESPACE]['operation_id'])
        self.assertNotIn('created_at', payload)
        self.assertNotIn('id', payload)
        self.assertEqual('/fixture/accounts/alice', payload['home_dir'])
        self.assertEqual(0, payload['expiration_date'])
        self.assertEqual(['SSH', 'FTP'], payload['filters']['denied_protocols'])
        self.assertIn('write-disabled', payload['filters']['web_client'])
        self.assertEqual(123456, payload['quota_size'])

    def test_retry_after_success_reauthenticates_does_not_reset_or_update(self):
        account = self.client.create_or_recover('alice', self.fixture.user_password, self.operation)
        self.fixture.created_at_ms += 10 * 24 * 60 * 60 * 1000
        recovered = self.fixture.client().create_or_recover('alice', self.fixture.user_password, self.operation)
        self.assertEqual(account, recovered)
        self.error('INVALID_CREDENTIALS', self.client.create_or_recover, 'alice', 'Wrong-password-42', self.operation)
        self.assertEqual(1, len(self.fixture.creates))
        self.assertTrue(all(method in ('GET', 'POST') for method, path in self.fixture.calls))

    def test_lost_create_response_recovers_from_same_marker_after_restart(self):
        self.fixture.drop_create_response_once = True
        self.error('SFTPGO_UPSTREAM_UNAVAILABLE', self.client.create_or_recover, 'alice', self.fixture.user_password, self.operation)
        original = self.fixture.accounts['alice']['created_at']
        self.fixture.created_at_ms += 900_000_000
        recovered = self.fixture.client().create_or_recover('alice', self.fixture.user_password, self.operation)
        self.assertEqual(original, recovered.created_at_ms)
        self.assertEqual(1, len(self.fixture.creates))

    def test_concurrent_same_operation_duplicate_create_recovers_proof(self):
        def before_create(payload):
            self.fixture.put_account(operation_id=self.operation)
        self.fixture.before_create = before_create
        account = self.client.create_or_recover('alice', self.fixture.user_password, self.operation)
        self.assertEqual(self.operation, account.registration_operation_id)
        self.assertEqual(1, len(self.fixture.creates))

    def test_changed_id_creation_or_update_around_auth_rejected(self):
        for field in ('id', 'created_at', 'updated_at'):
            with self.subTest(field=field):
                self.fixture.put_account()
                self.fixture.after_token = lambda key=field: self.fixture.accounts['alice'].__setitem__(key, self.fixture.accounts['alice'][key] + 1)
                self.error('ACCOUNT_IDENTITY_CONFLICT', self.client.authenticate_basic, 'alice', self.fixture.user_password)
        # These checks detect observed races, not ABA or remote atomicity.

    def test_token_subject_and_username_must_match_admin_lookup(self):
        self.fixture.put_account()
        for token in (fixture_token('other', self.fixture.created_at_ms), fixture_token('alice', 42)):
            self.fixture.token_claims = token
            self.error('ACCOUNT_IDENTITY_CONFLICT', self.client.authenticate_basic, 'alice', self.fixture.user_password)

    def test_marker_namespace_duplicate_and_instance_mismatch_fail_closed(self):
        for marker in ('{"%s":{"instance_id":"other","operation_id":"%s"}}' % (REGISTRATION_NAMESPACE, self.operation),
                       '{"x":1,"x":2}', '{"operation_id":"%s"}' % self.operation):
            self.fixture.put_account(additional_info=marker)
            self.error('USERNAME_UNAVAILABLE', self.client.create_or_recover, 'alice', self.fixture.user_password, self.operation)

    def test_identity_missing_zero_bool_or_username_mismatch_rejected(self):
        for change in ({'id': 0}, {'id': True}, {'created_at': 0}, {'created_at': '1700000000000'},
                       {'username': 'ALICE'}, {'status': True}, {'updated_at': -1}):
            self.fixture.put_account(**change)
            if change.get('username'):
                self.fixture.accounts['alice'] = self.fixture.accounts[change['username']]
            self.error('SFTPGO_INVALID_ACCOUNT_IDENTITY', self.client.lookup, 'alice')

    def test_redirect_never_followed_or_secret_echoed(self):
        self.fixture.overrides[('GET', '/api/v2/token')] = (302, b'PRIVATE_REDIRECT', 'text/plain',
                                                          {'Location': self.fixture.base_url + '/credential-leak'})
        self.error('SFTPGO_REDIRECT_REJECTED', self.client.lookup, 'alice')
        self.assertEqual([('GET', '/api/v2/token')], self.fixture.calls)

    def test_bad_json_oversize_html_and_raw_failure_are_redacted(self):
        cases = [(200, b'{"access_token":"PRIVATE","access_token":"OTHER"}'),
                 (200, b'[]'), (200, b'{"x": NaN}'),
                 (200, b'PRIVATE' * (MAX_JSON_BYTES // 7 + 1)),
                 (500, b'PRIVATE_SERVER_EXCEPTION')]
        for status, payload in cases:
            with self.subTest(status=status, size=len(payload)):
                self.fixture.overrides[('GET', '/api/v2/token')] = (status, payload)
                expected = 'SFTPGO_ADMIN_AUTH_UNAVAILABLE' if status != 200 else 'SFTPGO_INVALID_RESPONSE'
                self.error(expected, self.client.lookup, 'alice')
        self.fixture.overrides[('GET', '/api/v2/token')] = (200, b'PRIVATE', 'text/html')
        self.error('SFTPGO_INVALID_RESPONSE', self.client.lookup, 'alice')

    def test_strict_validation_precedes_any_network(self):
        for username in ('../alice', ' alice', 'alice/', 'alice?x=1'):
            self.error('INVALID_USERNAME', self.client.lookup, username)
        for password in ('$argon2id$pretend', '{SHA}pretend', 'tiny', 'newline\npassword'):
            self.error('INVALID_PASSWORD', self.client.create_or_recover, 'alice', password, self.operation)
        self.error('INVALID_REGISTRATION_OPERATION', self.client.create_or_recover, 'alice', self.fixture.user_password, 'short')
        self.assertEqual([], self.fixture.calls)


class ConfigurationTests(unittest.TestCase):
    def credentials(self, env=None):
        return RuntimeAdminCredentials.from_environment('FIXTURE_USER', 'FIXTURE_PASSWORD', environ=env or {})

    def test_https_or_explicit_exact_loopback_only(self):
        for url in ('http://localhost:8080', 'http://127.0.0.2:8080', 'http://127.0.0.1.evil.invalid',
                    'https://user:password@example.invalid', 'https://example.invalid/path',
                    'https://example.invalid?x=1', 'https://example.invalid/#x', 'https://example.invalid:0',
                    'https://example.invalid\n', 'ftp://example.invalid', 'http://[::ffff:127.0.0.1]:8080'):
            with self.subTest(url=url), self.assertRaises(ValueError):
                SftpgoClient(url, instance_id='fixture', admin_credentials=self.credentials(), allow_loopback_http=True)
        with self.assertRaises(ValueError):
            SftpgoClient('http://127.0.0.1', instance_id='fixture', admin_credentials=self.credentials())
        SftpgoClient('http://[::1]:8080', instance_id='fixture', admin_credentials=self.credentials(), allow_loopback_http=True)
        for timeout in (0, -1, 16, float('nan'), True):
            with self.assertRaises(ValueError):
                SftpgoClient('https://example.invalid', instance_id='fixture', admin_credentials=self.credentials(), timeout=timeout)

    def test_environment_source_is_explicit_and_runtime_only(self):
        env = {'FIXTURE_USER': 'fixture-user', 'FIXTURE_PASSWORD': 'fixture-password'}
        provider = self.credentials(env)
        self.assertEqual(('fixture-user', 'fixture-password'), provider())
        env['FIXTURE_PASSWORD'] = 'changed-fixture-password'
        self.assertEqual('changed-fixture-password', provider()[1])
        self.assertNotIn('password', repr(provider))
        del env['FIXTURE_PASSWORD']
        with self.assertRaises(SftpgoError):
            provider()
        with self.assertRaises(ValueError):
            RuntimeAdminCredentials.from_environment('SAME', 'SAME')

    def test_existing_private_file_source_no_save_or_symlink(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'fixture.json'
            original = b'{"username":"fixture-user","password":"fixture-password"}'
            path.write_bytes(original)
            path.chmod(0o600)
            provider = RuntimeAdminCredentials.from_file(str(path))
            self.assertEqual(('fixture-user', 'fixture-password'), provider())
            self.assertEqual(original, path.read_bytes())
            path.chmod(0o644)
            with self.assertRaises(SftpgoError):
                provider()
            path.chmod(0o600)
            link = Path(directory) / 'link.json'
            link.symlink_to(path)
            with self.assertRaises(SftpgoError):
                RuntimeAdminCredentials.from_file(str(link))()

    def test_template_requires_explicit_safe_home_and_limits(self):
        for home in ('relative', '/', '//tmp/accounts', '/tmp/../accounts', '/tmp/accounts/'):
            with self.assertRaises(ValueError):
                NewAccountTemplate(home, 12345, 10, 20)
        for amount in (0, -1, True, '12345'):
            with self.assertRaises(ValueError):
                NewAccountTemplate('/fixture/accounts', amount, 10, 20)


if __name__ == '__main__':
    unittest.main()
