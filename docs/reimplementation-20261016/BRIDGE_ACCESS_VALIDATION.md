# Bridge access reimplementation validation

Date: 2026-10-16. Baseline: recovered source at `95b117f`; this report concerns the working-tree bridge changes only. It does not claim recovery of code39–45 source or Android-device execution.

## Implemented behavior

- Main caller access uses Android's caller package plus actual Binder UID, resolved through PackageManager, with a sole main-package UID. A changed main-app signing certificate is not a bridge authorization failure. Game/source/provider/process/namespace bindings and transfer-time integrity checks remain.
- Provider initialization is inert. First-stage `BridgeClient.authenticateAccess()` calls the canonical `authenticate` operation and requires access protocol 1, authenticated identity, and `metadataOnly`. The helper also accepts the explicit method-name alias. Both success and failure paths avoid reading save/workspace state.
- New embedded manifest metadata advertises `BRIDGE_ACCESS_PROTOCOL=1` (implemented in the preparation workstream). Without that marker, the client hashes only the bounded, isolated last canonical `classesN.dex` entry from the installed base APK. Only the known published code45 runtime or the currently bundled runtime permits IPC. Unknown historical runtimes need a constrained update, with no `inspect` fallback.
- Actual code45 compatibility was checked against APK SHA-256 `aa839df6d2e89dfa3be062c73fb219c894ee1a08ec0556ad362d2998bdd311e5`. Its standalone runtime is `92aa07a4ebc64ccc9f78d3dae160afe191a342e7596c5e4b26cf56eb3699893d`. Its dispatcher operation is `authenticate`, and its reply uses `authenticated`, `metadataOnly`, and `accessProtocolVersion`.
- Acquisition and IPC have an 8-second first-stage deadline. At most two abandoned framework calls can remain in workers. Late acquisitions close their clients; late replies are disposed. This is a bound on the app's wait, not a claim that Android cancels arbitrary blocked Binder work.
- Installed bridge discovery uses actual PM provider/component/process/UID/source metadata. It does not treat a generic modified APK or public signing key as proof of an owned bridge. Persisted selection preserves inherited origin/namespace and current package snapshot; cold restore does not need a local receipt or invent an original signer.
- Missing `files` is accepted during second-stage inspection only after a no-follow parent descriptor is opened and its identity verified before/after. ENOENT is distinguished from permission, I/O, symlink, and changed/missing-parent failures. Inspection does not create game or bridge workspace directories. Empty export does not create the game's `files` directory; existing transfer/recovery workspace behavior remains separate.
- Re-selecting the same saved installed binding is a no-op for onboarding/backend-comparison state. Activation is an authenticated explicit activity that returns immediately; the UI workstream owns the once-per-selection automatic bound.

## Executed checks

Commands used the cloud JDK21/Android SDK environment in `recovery-20261016/env/env.sh`.

- `bash tools/android-rebuild/test-helper-host.sh`: 165 assertions passed.
- `python3 -m unittest discover -s android-helper/src/test -p 'test_*.py' -v`: 7 tests passed, including API26–36 adapter/authentication checks, 22 production scanner assertions, and 18 real-production-provider assertions under host framework doubles.
- `android-app/src/test/run-main-runtime-tests.sh` with current `core/build/libs/core.jar`, pinned runtime jars, and `PDSYNC_CODE45_APK` pointing at the verified published APK: complete suite passed. The new installed-bridge discovery/selection/protocol/deadline suite passed 52 assertions, including acceptance of the actual published runtime and refusal after a one-byte change. See `bridge-main-runtime-host.log` for each suite's count.
- Python source contracts: 5 game-access-backend tests, 11 embedded-install tests, and 4 new bridge-access tests passed. These are supplemental wiring guards, not runtime claims.
- First integrated `:android-app:compileReleaseKotlin` and `:android-app:compileReleaseJavaWithJavac` passed offline (50 seconds); the toolchain log was inspected. This is compilation, not final APK packaging or runtime validation.
- Full helper source compiled against Android API36 SDK. The toolchain workstream regenerated standalone runtime DEX with D8 min26: 84,160 bytes, SHA-256 `a1ce8c9c250199849a691f4d6ed5ebec858adc4a59582994e82b39bc7841fc85`.

## Explicit limitations

No Android device/emulator, real Binder, OEM provider acquisition, SELinux, activity activation/installation system page, or Root runtime was exercised here. Android whole-app packaging and final APK verification are reported by the toolchain/parent workstream. A known runtime digest establishes those bridge bytes; it does not authenticate the original game's publisher. The shared namespace retains embedded binding evidence and does not reconstruct a lost original signer.
