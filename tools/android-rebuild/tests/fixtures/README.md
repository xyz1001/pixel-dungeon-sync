# Public, read-only signature fixture

No APK or encoded APK is stored in the source repository. `../public_fixture.py` fetches the Android Open Source Project apksig test resource `golden-aligned-v1-out.apk` from immutable commit `184702d9d18877edf9e5296c4e191cf0aa2b5fbb` only when its external local cache is missing.

- Source: https://android.googlesource.com/platform/tools/apksig/+/184702d9d18877edf9e5296c4e191cf0aa2b5fbb/src/test/resources/com/android/apksig/golden-aligned-v1-out.apk
- Upstream license: https://android.googlesource.com/platform/tools/apksig/+/184702d9d18877edf9e5296c4e191cf0aa2b5fbb/LICENSE
- Apache License 2.0; a local license copy is `android-app/assets/licenses/apksig-LICENSE.txt`.
- Size: 7,707 bytes
- SHA-256: `b9513e617253cc5864bccd731adeae270861e690a8dae86c15b1ee2aa3f867f4`

The default cache is `preparation-test-fixtures/` beside the source checkout. Set `PDSYNC_TEST_FIXTURE_CACHE` to use another directory outside the checkout; repository-internal paths are rejected. Both fresh downloads and every reuse must match the fixed size and SHA-256. A corrupted cache fails closed without replacing it. Existing valid cache data is reused without any network request; a first run with no cache requires access to the official URL above (`?format=TEXT`). To run offline, provide the same checksum-verified public object in the external cache as `aosp-golden-aligned-v1-out.apk`.

Run `python3 tools/android-rebuild/tests/test_public_fixture.py` to test cache reuse, integrity failures, and repository-external placement without network access. `test_apksig_compat.py` uses this helper for the actual V1 verification regression.

This public test-only APK is read as inert ZIP/signature data. It must never be installed or executed, must never be included in released app assets, and conveys no production signing identity. No private key is included. The regression test verifies its V1 signature and rejects a separately generated payload-tampered copy.
