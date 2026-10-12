# Android game-access boundary

`GameAccessBackend` extends the existing core `AndroidGameBridge` contract. Target
identity, export destination, restore directory, and expected-current hashes stay
unchanged. The Android boundary adds readiness inspection, interrupted-backup
recovery, immutable lifecycle observations, and explicit mode/capability metadata.

`BridgeClient` remains the existing implementation. Its package authentication,
provider transport, target verification, archive handling, readiness decisions,
recovery operation, and failure types are unchanged. Its observation now returns
the shared immutable value type; it reads the same four provider booleans and
returns all false when observation fails.

`GameAccessBackends.forPreparedGame` constructs that same implementation from the
same `PreparedPair` and `AndroidGameIdentity`. `SyncRuntime` and `AutoSyncService`
use this boundary. Main-activity calls and installation readiness remain unchanged.

## Capabilities are not readiness

- `Mode` distinguishes the existing embedded bridge and legacy helper. It reserves
  `ROOT_DIRECT` as an identifier only; there is no Root selection/factory path.
- Capability booleans describe implementation support for export, restore,
  lifecycle observation, and interrupted-backup recovery. They are not permission,
  package trust, save-layout compatibility, or successful device verification.
- The bridge supports those four operations. Every operation retains its existing
  access and trust checks, including the legacy recovery restrictions. A true
  capability never bypasses those checks.
- Automatic upload still requires an embedded prepared game and the existing
  onboarding/paused-state gate. It does not consume capability flags as approval.
- Unknown observations are neither ready nor reliable. No backend method defaults
  to a successful operation or fabricated observation.

No configuration schema, `PreparedPair`, scope/namespace, persisted selection,
core business logic, helper runtime, manifest, or UI changes are included. No
Root readiness or Root save access has been established by this change.

## Host verification

Using the restored SDK environment and current core jar:

```sh
source ../tools/android-env.sh
"$GRADLE_BIN" --offline :core:jar
android-app/src/test/run-game-access-backend-tests.sh
python3 -m unittest discover -s android-app/src/test/python -p 'test_*contract.py'
android-app/src/test/run-main-runtime-tests.sh
```

These host checks cover contracts and compilation. Android ART/Binder/service
behavior, API-26 runtime linkage, and Root behavior require device verification.
