#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Offline coverage of the official-fixture download/cache integrity boundary."""
import base64
import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('public_fixture', HERE / 'public_fixture.py')
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class PublicFixtureTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='pdsync-fixture-cache-test-')
        self.addCleanup(self.temporary.cleanup)
        self.cache = Path(self.temporary.name) / 'cache'
        # Synthetic ASCII data exercises cache policy without embedding an APK.
        self.data = b'public fixture cache test data'
        self.size = patch.object(fixture, 'FIXTURE_SIZE', len(self.data))
        self.digest = patch.object(fixture, 'FIXTURE_SHA256', hashlib.sha256(self.data).hexdigest())
        self.size.start()
        self.digest.start()
        self.addCleanup(self.size.stop)
        self.addCleanup(self.digest.stop)

    def test_cache_miss_fetches_pinned_official_url_once(self):
        with patch.object(fixture.urllib.request, 'urlopen',
                          return_value=io.BytesIO(base64.b64encode(self.data))) as fetch:
            path = fixture.aosp_v1_fixture(self.cache)
            self.assertEqual(path.read_bytes(), self.data)
            self.assertEqual(fixture.aosp_v1_fixture(self.cache), path)
            fetch.assert_called_once_with(fixture.FIXTURE_URL, timeout=30)
        self.assertEqual(list(self.cache.iterdir()), [path])

    def test_existing_cache_is_verified_without_download(self):
        self.cache.mkdir()
        path = self.cache / fixture.FIXTURE_NAME
        path.write_bytes(self.data)
        with patch.object(fixture.urllib.request, 'urlopen') as fetch:
            self.assertEqual(fixture.aosp_v1_fixture(self.cache), path)
            fetch.assert_not_called()

    def test_corrupt_cache_fails_without_download_or_replacement(self):
        self.cache.mkdir()
        path = self.cache / fixture.FIXTURE_NAME
        path.write_bytes(b'corrupt')
        with patch.object(fixture.urllib.request, 'urlopen') as fetch:
            with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'):
                fixture.aosp_v1_fixture(self.cache)
            fetch.assert_not_called()
        self.assertEqual(path.read_bytes(), b'corrupt')

    def test_same_size_wrong_hash_download_is_not_cached(self):
        with patch.object(fixture.urllib.request, 'urlopen',
                          return_value=io.BytesIO(base64.b64encode(b'x' * len(self.data)))):
            with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'):
                fixture.aosp_v1_fixture(self.cache)
        self.assertFalse(self.cache.exists())

    def test_wrong_size_download_is_not_cached(self):
        with patch.object(fixture.urllib.request, 'urlopen',
                          return_value=io.BytesIO(base64.b64encode(self.data[:-1]))):
            with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'):
                fixture.aosp_v1_fixture(self.cache)
        self.assertFalse(self.cache.exists())

    def test_oversized_response_is_not_cached(self):
        with patch.object(fixture.urllib.request, 'urlopen', return_value=io.BytesIO(b'A' * 4096)):
            with self.assertRaisesRegex(ValueError, 'Unexpectedly large'):
                fixture.aosp_v1_fixture(self.cache)
        self.assertFalse(self.cache.exists())

    def test_invalid_base64_is_not_cached(self):
        with patch.object(fixture.urllib.request, 'urlopen', return_value=io.BytesIO(b'not base64!')):
            with self.assertRaises(ValueError):
                fixture.aosp_v1_fixture(self.cache)
        self.assertFalse(self.cache.exists())

    def test_repository_cache_is_rejected_before_download(self):
        with patch.object(fixture.urllib.request, 'urlopen') as fetch:
            for cache in (fixture.REPO, fixture.REPO / 'test-cache'):
                with self.assertRaisesRegex(ValueError, 'outside the source repository'):
                    fixture.aosp_v1_fixture(cache)
            fetch.assert_not_called()

    def test_symlink_into_repository_is_rejected(self):
        self.cache.mkdir()
        (self.cache / fixture.FIXTURE_NAME).symlink_to(fixture.REPO / '.fixture-test')
        with patch.object(fixture.urllib.request, 'urlopen') as fetch:
            with self.assertRaisesRegex(ValueError, 'outside the source repository'):
                fixture.aosp_v1_fixture(self.cache)
            fetch.assert_not_called()

    def test_default_and_environment_cache_are_external(self):
        default = fixture.REPO.parent / 'preparation-test-fixtures'
        self.assertFalse(default.is_relative_to(fixture.REPO))
        with patch.dict(fixture.os.environ, {'PDSYNC_TEST_FIXTURE_CACHE': str(self.cache)}):
            with patch.object(fixture.urllib.request, 'urlopen',
                              return_value=io.BytesIO(base64.b64encode(self.data))):
                self.assertEqual(fixture.aosp_v1_fixture(), self.cache / fixture.FIXTURE_NAME)


if __name__ == '__main__':
    unittest.main()
