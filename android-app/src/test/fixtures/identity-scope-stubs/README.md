# Reconstructed code92 installed identity regressions

These are newly written regression tests for the reconstructed source. They are not the lost original code92 tests. The production reconstruction is based on the verified code92 APK SHA-256 `71550ee4005a76d761e473371eb9723b4380cb4448d1f0409e1b71e13e7d5198`, specifically the `InstalledApkIdentityScope` class and its `Read`, `AndroidFiles`, `Times`, and `FilesAccess` nested classes, plus the identity-scope additions to `BridgeClient`.

Run from the repository root:

```sh
bash android-app/src/test/run-installed-identity-scope-tests.sh
```

The test runner needs Java 17+ with the `jdk.compiler` module and Python 3. It does not require Gradle, Android SDK, a prebuilt core JAR, a device, credentials, or a server.

The first executable directly compiles the reconstructed production `InstalledApkIdentityScope`, real `PackageTrust`, real `InstalledBridgeResolver`, real `InstalledBridgeBinding`, and real `ApkSetIdentity`. Package manager and stat responses are controlled host-only doubles. Test APKs are temporary files whose bytes are hashed by production code. Files are confined to temporary fixture directories.

The second executable uses `BridgeClientIdentityChecks.java.in` as a harness and inserts unchanged production bodies for the two selected-backend constructors, target identity, scope entry/exit, and exchange. Transport and timing endpoints are doubles. This verifies the identity integration's execution branches without claiming a whole-class Android compilation or Binder behavior. The restore method's guard ordering is also checked directly against production source before compiling. The separate Android compilation and UI result-publication checks remain necessary.

Covered behavior:

- All base/split APKs are fully hashed on entry and exit; intermediate requests run fresh PM/provider/canonical/stat checks without repeated APK hashing.
- Package, application package, version/name, install/update times, UID, signer, enabled/shared UID state, data path, source/split paths, split names, provider state, protocol, and source binding are revalidated.
- Stat fences include device, inode, mode, owner/group, link count, size, nanosecond mtime and ctime; nonregular and negative-sized files fail closed.
- Entry/exit races, hidden same-size content changes at exit, symlinks, dot/relative paths, stat/hash failures, double close, nested scopes, irrevocable invalidation, backend/thread separation, and failed-close cleanup are exercised.
- Bridge target failures invalidate a scope, retain existing diagnostic codes, and block result acceptance at close; restore/recover are blocked inside a read scope before transport.
- API26 retains full hashing on every target request; PreparedPair retains a no-op scope and full installed/prepared identity comparisons. No Root source is modified.

The equal-stat intermediate test intentionally confirms the shipped scope contract: intermediate reads reuse the entry hash, while the exit full hash must succeed before publishing a result. It does not claim the metadata fence detects every possible content mutation at the exact moment of an intermediate call.

These fixtures do not emulate Android kernel `lstat`, PM snapshots, signature verification, ART API linkage, Binder, concurrent device writes, or actual diagnostic log aggregation. No host result establishes device timing or device safety by itself. All new fixture and harness files are newly reconstructed tests; production scope is APK-derived and `BridgeClient` is exact89 plus APK-derived identity wiring.
