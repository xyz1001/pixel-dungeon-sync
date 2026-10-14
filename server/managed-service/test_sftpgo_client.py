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
from types import SimpleNamespace
import unittest

from sftpgo_client import (AdminTokenProvider, MAX_JSON_BYTES, NewAccountTemplate,
                           REGISTRATION_NAMESPACE, RuntimeAdminCredentials,
                           SftpgoClient, SftpgoError, TRIAL_DURATION_MS)


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
        self.accounts, self.passwords, self.calls, self.creates, self.updates = {}, {}, [], [], []
        self.overrides = {}
        self.after_token = None
        self.drop_create_response_once = False
        self.before_create = None
        self.token_claims = None
        self.before_update = None
        self.after_update = None
        self.drop_update_response_once = False
        self.update_response = None
        self.user_token_clock = None
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
                    expiration = account.get('expiration_date', 0)
                    if fixture.user_token_clock is not None and expiration > 0 and expiration < fixture.user_token_clock():
                        self.send_json(401, {'error': 'PRIVATE_FIXTURE_EXPIRED'})
                        return
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

            def do_PUT(self):
                if self.check_override():
                    return
                if not self.path.startswith('/api/v2/users/') or not self.admin():
                    return
                username = self.path.split('?', 1)[0].rsplit('/', 1)[1]
                payload = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                fixture.updates.append(deepcopy(payload))
                if fixture.before_update:
                    fixture.before_update(payload)
                original = fixture.accounts.get(username)
                if original is None:
                    self.send_json(404, {'error': 'not found'})
                    return
                # SFTPGo 2.7.6 update_user is full replacement, with password
                # preserved when omitted. Identity is selected by URL, NOT CAS.
                updated = deepcopy(payload)
                updated.update(id=original['id'], username=original['username'],
                               created_at=original['created_at'], updated_at=original['updated_at'] + 1)
                if 'password' not in updated:
                    updated['password'] = original.get('password', '')
                fixture.accounts[username] = updated
                if fixture.after_update:
                    fixture.after_update(updated)
                if fixture.drop_update_response_once:
                    fixture.drop_update_response_once = False
                    self.connection.shutdown(socket.SHUT_RDWR)
                    self.connection.close()
                    return
                if fixture.update_response is not None:
                    self.send_raw(*fixture.update_response)
                    return
                self.send_json(200, {'message': 'User updated'})

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


class NativeExpiryTests(unittest.TestCase):
    operation = ClientTests.operation
    setUp = ClientTests.setUp
    error = ClientTests.error

    def native_client(self, **changes):
        options = dict(native_expiry_enabled=True, no_external_account_writers=True,
                       clock=lambda: self.fixture.created_at_ms)
        options.update(changes)
        return self.fixture.client(**options)

    def account(self, **changes):
        self.fixture.put_account(**changes)
        return self.client.lookup('alice')

    def test_default_disabled_and_explicit_invariants_before_network(self):
        identity = SimpleNamespace(username='alice', account_id='not-a-real-account')
        self.error('SFTPGO_NATIVE_EXPIRY_DISABLED', self.client.ensure_account_expiry, identity, 1)
        missing_writer = self.native_client(no_external_account_writers=False)
        self.error('SFTPGO_ACCOUNT_WRITER_INVARIANT_REQUIRED', missing_writer.ensure_account_expiry, identity, 1)
        missing_lifecycle = self.native_client(no_username_reuse=False)
        self.error('SFTPGO_IDENTITY_INVARIANT_REQUIRED', missing_lifecycle.ensure_account_expiry, identity, 1)
        self.error('SFTPGO_IDENTITY_INVARIANT_REQUIRED', missing_lifecycle.observed_account_expiry, identity)
        self.assertEqual([], self.fixture.calls)

    def test_full_latest_snapshot_preserved_password_omitted_and_disconnect_requested(self):
        identity = self.account(expiration_date=0,
            permissions={'/': ['list', 'download'], '/private': ['*']},
            filesystem={'provider': 1, 's3config': {'bucket': 'fixture', 'access_secret': {'status': 'Redacted'}}},
            groups=[{'name': 'fixture-group', 'type': 1}],
            filters={'denied_protocols': ['SSH'], 'totp_config': {'enabled': True}},
            virtual_folders=[{'name': 'fixture-folder', 'virtual_path': '/external'}],
            additional_info='existing metadata', public_keys=['fixture-public-key'],
            description='must survive', future_config={'new_field': 42})
        # A caller's old account snapshot must never be used as PUT input.
        self.fixture.accounts['alice']['quota_size'] += 10
        original = deepcopy(self.fixture.accounts['alice'])
        target = self.fixture.created_at_ms + TRIAL_DURATION_MS
        self.fixture.calls.clear()
        self.assertEqual(target, self.native_client().ensure_account_expiry(identity, target))
        expected = deepcopy(original)
        expected.pop('password')
        expected['expiration_date'] = target
        self.assertEqual([expected], self.fixture.updates)
        expected['password'] = original['password']
        expected['updated_at'] += 1
        self.assertEqual(expected, self.fixture.accounts['alice'])
        self.assertEqual([('GET', '/api/v2/users/alice'),
                          ('PUT', '/api/v2/users/alice?disconnect=1'),
                          ('GET', '/api/v2/users/alice')],
                         [call for call in self.fixture.calls if call[1] != '/api/v2/token'])
        self.assertNotIn('password', self.fixture.updates[0])

    def test_unlimited_zero_is_replaced_by_finite_target(self):
        for omitted in (False, True):
            with self.subTest(omitted=omitted):
                identity = self.account(expiration_date=0)
                if omitted:
                    self.fixture.accounts['alice'].pop('expiration_date')
                client = self.native_client()
                self.assertEqual(0, client.observed_account_expiry(identity))
                self.assertEqual(1, client.ensure_account_expiry(identity, 1))
                self.assertEqual(1, self.fixture.accounts['alice']['expiration_date'])
        self.assertEqual(2, len(self.fixture.updates))

    def test_monotonic_fixed_absolute_targets_and_retry_are_idempotent(self):
        identity = self.account(expiration_date=100)
        client = self.native_client()
        for target in (99, 100, 101, 101, 100, 200, 150):
            self.assertGreaterEqual(client.ensure_account_expiry(identity, target), target)
        self.assertEqual([101, 200], [row['expiration_date'] for row in self.fixture.updates])
        self.assertEqual(200, client.observed_account_expiry(identity))

    def test_lost_update_response_reads_back_committed_target_without_replay(self):
        identity = self.account(expiration_date=0)
        self.fixture.drop_update_response_once = True
        self.assertEqual(123, self.native_client().ensure_account_expiry(identity, 123))
        self.assertEqual(123, self.native_client().ensure_account_expiry(identity, 123))
        self.assertEqual(1, len(self.fixture.updates))

    def test_lost_update_with_incomplete_postcondition_is_not_success(self):
        identity = self.account(expiration_date=0)
        self.fixture.drop_update_response_once = True
        self.fixture.after_update = lambda updated: updated.__setitem__('expiration_date', 0)
        self.error('SFTPGO_UPSTREAM_UNAVAILABLE', self.native_client().ensure_account_expiry, identity, 123)
        self.assertEqual(1, len(self.fixture.updates))

    def test_malformed_success_is_resolved_only_by_exact_readback(self):
        identity = self.account(expiration_date=0)
        self.fixture.update_response = (200, b'PRIVATE_NOT_JSON', 'text/plain')
        self.assertEqual(123, self.native_client().ensure_account_expiry(identity, 123))
        self.fixture.after_update = lambda updated: updated.__setitem__('expiration_date', 0)
        self.error('SFTPGO_INVALID_RESPONSE', self.native_client().ensure_account_expiry, identity, 124)
        self.assertEqual(2, len(self.fixture.updates))

    def test_successful_put_requires_positive_sufficient_readback(self):
        for observed in (0, 122):
            with self.subTest(observed=observed):
                identity = self.account(expiration_date=0)
                self.fixture.after_update = lambda updated, value=observed: updated.__setitem__('expiration_date', value)
                self.error('SFTPGO_EXPIRY_NOT_CONFIRMED', self.native_client().ensure_account_expiry, identity, 123)

    def test_expiration_boundaries_reject_nonpositive_nonnumeric_and_overflow_targets(self):
        identity = self.account(expiration_date=0)
        self.fixture.calls.clear()
        for target in (0, -1, True, False, 1.0, '1', None, 2**63):
            self.error('INVALID_EXPIRATION_TARGET', self.native_client().ensure_account_expiry, identity, target)
        self.assertEqual([], self.fixture.calls)
        self.assertEqual(2**63 - 1, self.native_client().ensure_account_expiry(identity, 2**63 - 1))

    def test_invalid_remote_expiration_is_never_overwritten_or_confirmed(self):
        identity = self.account(expiration_date=0)
        for expiration in (-1, True, False, 1.5, '1', None, 2**63):
            self.fixture.accounts['alice']['expiration_date'] = expiration
            self.error('SFTPGO_INVALID_EXPIRATION', self.native_client().ensure_account_expiry, identity, 123)
            self.error('SFTPGO_INVALID_EXPIRATION', self.native_client().observed_account_expiry, identity)
        self.assertEqual([], self.fixture.updates)

    def test_disabled_account_is_not_reenabled_changed_or_confirmed(self):
        identity = self.account(status=0, expiration_date=100)
        original = deepcopy(self.fixture.accounts)
        client = self.native_client()
        self.error('SFTPGO_ACCOUNT_DISABLED', client.ensure_account_expiry, identity, 200)
        self.error('SFTPGO_ACCOUNT_DISABLED', client.ensure_account_expiry, identity, 99)
        self.error('SFTPGO_ACCOUNT_DISABLED', client.observed_account_expiry, identity)
        self.assertEqual([], self.fixture.updates)
        self.assertEqual(original, self.fixture.accounts)

    def test_exact_identity_prevents_reused_missing_and_other_instance_writes(self):
        for change in ('id', 'created_at', 'missing', 'other_instance', 'renamed'):
            with self.subTest(change=change):
                identity = self.account(expiration_date=0)
                client = self.native_client()
                if change == 'missing':
                    self.fixture.accounts.pop('alice')
                elif change == 'other_instance':
                    client = self.native_client(instance_id='another-instance')
                elif change == 'renamed':
                    self.fixture.accounts['other'] = self.fixture.accounts.pop('alice')
                    self.fixture.accounts['other']['username'] = 'other'
                else:
                    self.fixture.accounts['alice'][change] += 1
                self.error('ACCOUNT_IDENTITY_CONFLICT', client.ensure_account_expiry, identity, 123)
                self.error('ACCOUNT_IDENTITY_CONFLICT', client.observed_account_expiry, identity)
        self.assertEqual([], self.fixture.updates)

    def test_identity_change_or_manual_disable_after_put_fails_readback(self):
        for field, code in (('id', 'ACCOUNT_IDENTITY_CONFLICT'),
                            ('created_at', 'ACCOUNT_IDENTITY_CONFLICT'),
                            ('status', 'SFTPGO_ACCOUNT_DISABLED')):
            with self.subTest(field=field):
                identity = self.account(expiration_date=0)
                self.fixture.after_update = lambda updated, key=field: updated.__setitem__(key, 0 if key == 'status' else updated[key] + 1)
                self.error(code, self.native_client().ensure_account_expiry, identity, 123)
        # Readback detects observed races; it cannot supply remote CAS/ABA safety.

    def test_invalid_identity_shape_rejected_before_network(self):
        for identity in (None, {'username': 'alice', 'account_id': 'x'},
                         SimpleNamespace(username='../alice', account_id='x'),
                         SimpleNamespace(username='alice', account_id=True),
                         SimpleNamespace(username='alice', account_id='x' * 257)):
            self.error('INVALID_ACCOUNT_IDENTITY', self.native_client().ensure_account_expiry, identity, 1)
        self.assertEqual([], self.fixture.calls)

    def test_upstream_errors_are_fixed_redacted_and_never_retry_put(self):
        identity = self.account(expiration_date=0)
        for status in (400, 401, 403, 404, 429, 500):
            self.fixture.overrides[('PUT', '/api/v2/users/alice?disconnect=1')] = (status, b'PRIVATE_ACCOUNT_CONFIG')
            expected = 'ACCOUNT_IDENTITY_CONFLICT' if status == 404 else 'SFTPGO_EXPIRY_UPDATE_UNAVAILABLE'
            self.error(expected, self.native_client().ensure_account_expiry, identity, 123)
        self.assertEqual(6, self.fixture.calls.count(('PUT', '/api/v2/users/alice?disconnect=1')))
        self.assertEqual([], self.fixture.updates)

    def test_readback_failure_never_confirms_success(self):
        identity = self.account(expiration_date=0)
        self.fixture.after_update = lambda updated: self.fixture.overrides.__setitem__(
            ('GET', '/api/v2/users/alice'), (500, b'PRIVATE_ACCOUNT_CONFIG'))
        self.error('SFTPGO_ACCOUNT_LOOKUP_UNAVAILABLE', self.native_client().ensure_account_expiry, identity, 123)
        self.assertEqual(1, len(self.fixture.updates))

    def test_native_registration_initial_deadline_is_finite_and_never_reset_on_recovery(self):
        client = self.native_client(clock=lambda: self.fixture.created_at_ms - 20)
        account = client.create_or_recover('alice', self.fixture.user_password, self.operation)
        expected = self.fixture.created_at_ms - 20 + TRIAL_DURATION_MS
        self.assertEqual(expected, account.expiration_date_ms)
        self.assertEqual(expected, self.fixture.creates[0]['expiration_date'])
        self.fixture.created_at_ms += 1000
        recovered = self.native_client().create_or_recover('alice', self.fixture.user_password, self.operation)
        self.assertEqual(account, recovered)
        self.assertEqual(1, len(self.fixture.creates))
        self.assertEqual([], self.fixture.updates)

    def test_native_lost_create_response_does_not_reset_original_deadline(self):
        self.fixture.drop_create_response_once = True
        self.error('SFTPGO_UPSTREAM_UNAVAILABLE', self.native_client().create_or_recover,
                   'alice', self.fixture.user_password, self.operation)
        original = self.fixture.accounts['alice']['expiration_date']
        self.fixture.created_at_ms += 1000
        recovered = self.native_client().create_or_recover('alice', self.fixture.user_password, self.operation)
        self.assertEqual(original, recovered.expiration_date_ms)
        self.assertEqual(1, len(self.fixture.creates))

    def test_native_expired_registration_retry_stays_expired_without_recreate(self):
        client = self.native_client()
        account = client.create_or_recover('alice', self.fixture.user_password, self.operation)
        # Official native boundary is expiry < now, rather than <= now.
        self.fixture.user_token_clock = lambda: account.expiration_date_ms
        self.assertEqual(account, client.authenticate_basic('alice', self.fixture.user_password))
        self.fixture.user_token_clock = lambda: account.expiration_date_ms + 1
        self.error('INVALID_CREDENTIALS', client.create_or_recover, 'alice', self.fixture.user_password, self.operation)
        self.assertEqual(account.expiration_date_ms, self.fixture.accounts['alice']['expiration_date'])
        self.assertEqual(1, len(self.fixture.creates))
        self.assertEqual([], self.fixture.updates)

    def test_native_registration_invalid_clock_cannot_create_unlimited_account(self):
        for now in (0, -1, True, 1.5, '123', None, 2**63 - TRIAL_DURATION_MS):
            self.error('INVALID_SERVER_CLOCK', self.native_client(clock=lambda value=now: value).create_or_recover,
                       'alice', self.fixture.user_password, self.operation)
        self.assertEqual([], self.fixture.creates)


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

    def test_native_expiry_flags_and_clock_require_explicit_valid_types(self):
        for changes in ({'native_expiry_enabled': 1}, {'native_expiry_enabled': 'true'},
                        {'no_external_account_writers': 1}, {'no_external_account_writers': None},
                        {'clock': 123}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                SftpgoClient('https://example.invalid', instance_id='fixture',
                             admin_credentials=self.credentials(), **changes)


if __name__ == '__main__':
    unittest.main()
