# Preparation platform boundary fixtures

These host-only doubles test the production `PreparationPlatform` version guard.
The API26/27 classpath deliberately has no `SigningInfo` class, `signingInfo`
field, or `getLongVersionCode` method, so an accidental modern API linkage fails.
The API28/36 classpath supplies current and legacy certificates separately and
rejects use of signing-certificate history. None of these fixtures ship in an APK.

They do not emulate Android PackageManager or AndroidKeyStore and do not prove
on-device signing, permission dialogs, installation, or shared-UID operation.
The separate official API26 bootclasspath compilation checks available signatures.

Reference API boundaries:
- https://developer.android.com/reference/android/content/pm/PackageInfo
- https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec
- https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder

`run-preparation-tests.sh` accepts `PDSYNC_APKSIG_JAR` to test the exact patched
runtime dependency. Set `PDSYNC_V1_TEST_APK` to the provenance-checked public AOSP
V1 fixture to include V1 verification and tamper rejection at APIs 26, 27, 28,
and 36. No private fixture key is needed. Existing release APK input similarly
checks V2/V3 signatures, certificate mismatch, and tampering at all four APIs.
The opaque RSA test double contains only the existing release's public modulus;
it is not a generated cryptographic key and throws if encoding is requested.
