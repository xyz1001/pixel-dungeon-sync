#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Checksum-pinned, repository-external AOSP data for signature regression tests.

The public APK is inert input to ApkVerifier. Never install or execute it.
Only a verified cache miss downloads the fixed upstream object; every use checks
the cached bytes. No APK or encoded copy belongs in the source repository.
"""
import base64
import hashlib
import os
from pathlib import Path
import tempfile
import urllib.request

REPO = Path(__file__).resolve().parents[3]
FIXTURE_NAME = 'aosp-golden-aligned-v1-out.apk'
FIXTURE_SIZE = 7707
FIXTURE_SHA256 = 'b9513e617253cc5864bccd731adeae270861e690a8dae86c15b1ee2aa3f867f4'
FIXTURE_URL = (
    'https://android.googlesource.com/platform/tools/apksig/+/'
    '184702d9d18877edf9e5296c4e191cf0aa2b5fbb/'
    'src/test/resources/com/android/apksig/golden-aligned-v1-out.apk?format=TEXT'
)


def verify_fixture(data):
    if len(data) != FIXTURE_SIZE or hashlib.sha256(data).hexdigest() != FIXTURE_SHA256:
        raise ValueError('AOSP fixture size or SHA-256 mismatch; refusing unverified test data')


def aosp_v1_fixture(cache_dir=None):
    """Return verified local data, fetching from immutable official source once.

    PDSYNC_TEST_FIXTURE_CACHE may select an external cache. An invalid existing
    cache is an error, not a reason to silently replace evidence or run a test.
    """
    if cache_dir is None:
        cache_dir = os.environ.get('PDSYNC_TEST_FIXTURE_CACHE',
                                   str(REPO.parent / 'preparation-test-fixtures'))
    cache = Path(cache_dir).expanduser().resolve()
    fixture = cache / FIXTURE_NAME
    if cache.is_relative_to(REPO) or fixture.resolve().is_relative_to(REPO):
        raise ValueError('Public APK fixture cache must be outside the source repository')
    if fixture.exists():
        verify_fixture(fixture.read_bytes())
        return fixture

    # Gitiles serves binary objects as base64. Bound the response before decoding
    # so an unexpected server response cannot become an unbounded download.
    maximum_encoded_size = ((FIXTURE_SIZE + 2) // 3) * 4 + 1024
    with urllib.request.urlopen(FIXTURE_URL, timeout=30) as response:
        encoded = response.read(maximum_encoded_size + 1)
    if len(encoded) > maximum_encoded_size:
        raise ValueError('Unexpectedly large response for the pinned AOSP fixture')
    data = base64.b64decode(b''.join(encoded.split()), validate=True)
    verify_fixture(data)

    cache.mkdir(parents=True, exist_ok=True)
    # A partial download is never visible as a cached fixture. Concurrent test
    # processes can safely publish the same fully verified immutable bytes.
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(prefix='.aosp-v1-', dir=cache, delete=False) as output:
            temporary = Path(output.name)
            output.write(data)
        os.replace(temporary, fixture)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return fixture
