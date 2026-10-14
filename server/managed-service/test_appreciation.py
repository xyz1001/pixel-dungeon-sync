"""Synthetic in-memory PNG fixtures; no production image, secret, or network."""
from contextlib import redirect_stderr, redirect_stdout
from dataclasses import replace
import hashlib
import http.client
import io
import json
import os
from pathlib import Path
import socket
import struct
import tempfile
import threading
import unittest
from unittest.mock import patch
import zlib

import service
from ledger_gate import ManagedLedger
from runtime_config import ConfigurationError, MAX_APPRECIATION_BYTES, PREREQUISITES, RuntimeConfig, load_appreciation_asset
from test_service import AccountsFixture, NOW, PASSWORD, basic, example


def chunk(kind, data=b''):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))


def png(width=2, height=2, *, compressed=None, interlace=0):
    ihdr = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, interlace)
    if compressed is None:
        compressed = zlib.compress((b'\0' + b'\0' * width * 4) * height)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', ihdr) + chunk(b'IDAT', compressed) + chunk(b'IEND')


class AppreciationConfigTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'appreciation.png'
        self.asset = png()
        self.path.write_bytes(self.asset)
        self.settings = {'enabled': True, 'asset_path': str(self.path),
                         'expected_sha256': hashlib.sha256(self.asset).hexdigest()}

    def assert_invalid_asset(self, raw, code='APPRECIATION_PNG_INVALID'):
        self.path.write_bytes(raw)
        settings = {**self.settings, 'expected_sha256': hashlib.sha256(raw).hexdigest()}
        with self.assertRaisesRegex(ConfigurationError, '^' + code + '$'):
            load_appreciation_asset(settings)

    def test_optional_defaults_disabled_and_disabled_never_opens_asset(self):
        raw = example()
        del raw['appreciation']
        self.assertEqual({'enabled': False}, RuntimeConfig.parse(raw).appreciation)
        with patch('runtime_config.os.open', side_effect=AssertionError('asset opened')):
            self.assertIsNone(load_appreciation_asset({'enabled': False}))
            self.assertIsNone(load_appreciation_asset({**self.settings, 'enabled': False}))

    def test_strict_config_requires_path_and_lowercase_exact_hash(self):
        invalid = [None, {}, {'enabled': 1}, {'enabled': True},
                   {'enabled': True, 'asset_path': str(self.path)},
                   {**self.settings, 'unknown': 'x'},
                   {**self.settings, 'expected_sha256': 'a' * 63},
                   {**self.settings, 'expected_sha256': 'A' * 64},
                   {**self.settings, 'expected_sha256': 123}]
        for path in ('relative.png', '/', '//etc/image.png', '/tmp/../image.png', '/tmp/./image.png', '/tmp//image.png', '/tmp/\nimage.png'):
            invalid.append({**self.settings, 'asset_path': path})
        for settings in invalid:
            with self.subTest(settings=settings), self.assertRaises(ConfigurationError):
                RuntimeConfig.parse({**example(), 'appreciation': settings})
        parsed = RuntimeConfig.parse({**example(), 'appreciation': self.settings})
        self.assertEqual(self.settings, parsed.appreciation)

    def test_load_returns_only_verified_immutable_bytes_and_digest(self):
        asset = load_appreciation_asset(self.settings)
        self.assertEqual(self.asset, asset.png)
        self.assertEqual(self.settings['expected_sha256'], asset.sha256)
        self.assertNotIn(str(self.path), repr(asset))
        self.path.write_bytes(b'replaced after load')
        self.assertEqual(self.asset, asset.png)

    def test_missing_file_hash_mismatch_directory_fifo_and_symlinks_rejected(self):
        cases = [({**self.settings, 'expected_sha256': '0' * 64}, 'APPRECIATION_ASSET_HASH_MISMATCH'),
                 ({**self.settings, 'asset_path': str(self.path) + '.missing'}, 'APPRECIATION_ASSET_UNAVAILABLE'),
                 ({**self.settings, 'asset_path': self.temp.name}, 'APPRECIATION_ASSET_REGULAR_FILE_REQUIRED')]
        link = Path(self.temp.name) / 'linked.png'
        link.symlink_to(self.path)
        cases.append(({**self.settings, 'asset_path': str(link)}, 'APPRECIATION_ASSET_UNAVAILABLE'))
        directory_link = Path(self.temp.name) / 'linked-directory'
        directory_link.symlink_to(self.temp.name, target_is_directory=True)
        cases.append(({**self.settings, 'asset_path': str(directory_link / self.path.name)}, 'APPRECIATION_ASSET_UNAVAILABLE'))
        fifo = Path(self.temp.name) / 'fifo.png'
        os.mkfifo(fifo)
        cases.append(({**self.settings, 'asset_path': str(fifo)}, 'APPRECIATION_ASSET_REGULAR_FILE_REQUIRED'))
        for settings, code in cases:
            with self.subTest(settings=settings), self.assertRaisesRegex(ConfigurationError, '^' + code + '$'):
                load_appreciation_asset(settings)

    def test_size_and_dimensions_are_bounded(self):
        self.assert_invalid_asset(b'', 'APPRECIATION_ASSET_SIZE_INVALID')
        self.assert_invalid_asset(b'x' * (MAX_APPRECIATION_BYTES + 1), 'APPRECIATION_ASSET_SIZE_INVALID')
        for width, height in ((0, 1), (1, 0), (2049, 1), (1, 2049), (1500, 1400)):
            with self.subTest(width=width, height=height):
                self.assert_invalid_asset(png(width, height, compressed=zlib.compress(b'\0' * 5)),
                                          'APPRECIATION_PNG_DIMENSIONS_INVALID')
        # Both dimension and pixel limits are inclusive.
        for width, height in ((2048, 1), (1000, 2000)):
            raw = png(width, height)
            self.path.write_bytes(raw)
            self.assertEqual(raw, load_appreciation_asset({**self.settings, 'expected_sha256': hashlib.sha256(raw).hexdigest()}).png)

    def test_png_structure_crc_compressed_data_and_animation_rejected(self):
        invalid = [b'not a PNG', self.asset[:-1], self.asset + b'extra',
                   self.asset[:29] + bytes([self.asset[29] ^ 1]) + self.asset[30:],
                   png(compressed=b'not zlib'), png(compressed=zlib.compress(b'\0' * 1000)),
                   png(compressed=zlib.compress(b'\0')), png(compressed=zlib.compress(b'\5' + b'\0' * 17)),
                   png(compressed=zlib.compress(b'\0' * 18) + b'extra'),
                   self.asset[:-12] + chunk(b'acTL', struct.pack('>II', 1, 0)) + self.asset[-12:],
                   self.asset[:-12] + chunk(b'ABCD') + self.asset[-12:]]
        for raw in invalid:
            with self.subTest(raw=raw[:16]):
                self.assert_invalid_asset(raw)

    def test_adam7_single_pixel_is_supported(self):
        raw = png(1, 1, interlace=1)
        self.path.write_bytes(raw)
        self.assertEqual(raw, load_appreciation_asset({**self.settings, 'expected_sha256': hashlib.sha256(raw).hexdigest()}).png)

    def test_cli_offline_checks_enabled_asset_without_credentials_or_socket(self):
        path = Path(self.temp.name) / 'config.json'
        path.write_text(json.dumps({**example(), 'appreciation': self.settings}))
        output = io.StringIO()
        with patch.object(service.RuntimeAdminCredentials, '__call__', side_effect=AssertionError('admin secret')), \
             patch.object(service, 'load_runtime_secret', side_effect=AssertionError('bot secret')), \
             patch.object(socket, 'socket', side_effect=AssertionError('network')), \
             redirect_stdout(output):
            self.assertEqual(0, service.main(['validate-config', str(path)]))
        self.assertIn('Appreciation asset validated offline.', output.getvalue())
        self.path.unlink()
        with redirect_stderr(output):
            self.assertEqual(2, service.main(['validate-config', str(path)]))
        self.assertIn('APPRECIATION_ASSET_UNAVAILABLE', output.getvalue())
        self.assertNotIn(str(self.path), output.getvalue())


class AppreciationHttpTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'appreciation.png'
        self.asset = png()
        self.path.write_bytes(self.asset)
        raw = example()
        raw.update(enabled=True, mutations_enabled=True)
        raw['database_path'] = str(Path(self.temp.name) / 'ledger.sqlite')
        raw['sftpgo']['instance_id'] = 'test-instance'
        raw['prerequisites'] = dict.fromkeys(PREREQUISITES, True)
        raw['appreciation'] = {'enabled': True, 'asset_path': str(self.path),
                               'expected_sha256': hashlib.sha256(self.asset).hexdigest()}
        self.config = RuntimeConfig.parse(raw)
        self.accounts = AccountsFixture(lambda: NOW)
        self.ledger = ManagedLedger(self.config.database_path, accounts=self.accounts, enabled=True, clock=lambda: NOW)
        self.application = service.ManagedApplication(self.config, accounts=self.accounts, ledger=self.ledger, clock=lambda: NOW)
        self.ledger.register('alice', PASSWORD)
        self.server = service.create_server(self.application, port=0)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop)

    def stop(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)

    def request(self, path='/api/account/appreciation', *, method='GET', headers=None, body=None):
        connection = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=3)
        try:
            connection.request(method, path, body=body, headers=headers or {})
            response = connection.getresponse()
            raw = response.read()
            return response.status, dict(response.getheaders()), raw
        finally:
            connection.close()

    def test_exact_public_metadata_contract_contains_no_account_data(self):
        status, headers, raw = self.request(headers={'Authorization': basic(), 'X-User': 'other-user'})
        self.assertEqual(200, status)
        self.assertEqual('application/json; charset=utf-8', headers['Content-Type'])
        self.assertEqual('no-store', headers['Cache-Control'])
        self.assertEqual({'code': 200, 'data': {'version': 1, 'payment_mode': 'donation',
                         'image_path': '/api/account/appreciation/image', 'image_sha256': hashlib.sha256(self.asset).hexdigest(),
                         'term': 'calendar_year', 'remark': 'username'}}, json.loads(raw))
        for private in (PASSWORD, 'alice', 'other-user', str(self.path), 'account_id'):
            self.assertNotIn(private.encode(), raw)

    def test_public_metadata_works_without_account_access_or_authentication(self):
        with patch.object(self.accounts, 'authenticate_basic', side_effect=AssertionError('unexpected auth')):
            for headers in ({}, {'Authorization': 'Bearer invalid'}, {'Authorization': basic(password='wrong-password')}):
                with self.subTest(headers=headers):
                    status, response_headers, raw = self.request(headers=headers)
                    self.assertEqual(200, status)
                    self.assertNotIn('WWW-Authenticate', response_headers)
                    self.assertEqual('no-store', response_headers['Cache-Control'])
                    self.assertEqual(self.config.appreciation['expected_sha256'], json.loads(raw)['data']['image_sha256'])
                    for private in ('alice', PASSWORD, 'expires_ms', 'account_id', 'active'):
                        self.assertNotIn(private, raw.decode())

    def test_public_png_is_raw_noncacheable_and_never_authenticates_or_reopens_file(self):
        self.path.unlink()
        with patch.object(self.accounts, 'authenticate_basic', side_effect=AssertionError('unexpected auth')), \
             patch('runtime_config.os.open', side_effect=AssertionError('unexpected file read')):
            status, headers, raw = self.request('/api/account/appreciation/image')
        self.assertEqual(200, status)
        self.assertEqual('image/png', headers['Content-Type'])
        self.assertEqual(str(len(self.asset)), headers['Content-Length'])
        self.assertEqual('no-store', headers['Cache-Control'])
        self.assertEqual('nosniff', headers['X-Content-Type-Options'])
        self.assertNotIn('Location', headers)
        self.assertNotIn('WWW-Authenticate', headers)
        self.assertEqual(self.asset, raw)

    def test_disabled_endpoints_are_404_before_authentication(self):
        self.application.appreciation_asset = None
        with patch.object(self.accounts, 'authenticate_basic', side_effect=AssertionError('unexpected auth')):
            for path in ('/api/account/appreciation', '/api/account/appreciation/image'):
                for headers in ({}, {'Authorization': basic()}):
                    status, _, raw = self.request(path, headers=headers)
                    self.assertEqual(404, status)
                    self.assertEqual('NOT_FOUND', json.loads(raw)['error'])

    def test_exact_paths_only_no_queries_directory_redirect_or_other_methods(self):
        for path in ('/api/account/appreciation/', '/api/account/appreciation/image/',
                     '/api/account/appreciation/image.png', '/api/account/appreciation/image/../../config.json',
                     '/api/account/appreciation?file=/etc/passwd', '/api/account/appreciation/image?path=/etc/passwd',
                     '/api/account/appreciation/%69mage', '//api/account/appreciation/image'):
            with self.subTest(path=path):
                status, headers, raw = self.request(path)
                self.assertIn(status, (400, 404))
                self.assertNotIn('Location', headers)
                self.assertNotEqual(self.asset, raw)
        for path in ('/api/account/appreciation', '/api/account/appreciation/image'):
            self.assertEqual(404, self.request(path, method='POST')[0])
            self.assertEqual(405, self.request(path, method='HEAD')[0])
            self.assertEqual(405, self.request(path, method='PUT')[0])
            self.assertEqual(400, self.request(path, body=b'x')[0])

    def test_bad_asset_stops_application_before_any_listener(self):
        configured = replace(self.config, appreciation={**self.config.appreciation, 'expected_sha256': '0' * 64})
        with self.assertRaisesRegex(ConfigurationError, '^APPRECIATION_ASSET_HASH_MISMATCH$'):
            service.ManagedApplication(configured, accounts=self.accounts, ledger=self.ledger)


if __name__ == '__main__':
    unittest.main()
