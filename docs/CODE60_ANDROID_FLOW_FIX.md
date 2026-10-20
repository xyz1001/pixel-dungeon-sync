# code60 Android selection and APK action fixes

Version: 0.5.39-test, versionCode 60. Baseline: the verified code59 source archive (SHA-256 `ab0fab9bdd3d807efb8d5ab2c2ca4330d50432e34506d7b7a004da277b2d2e03`).

## Root causes and fixes

1. The installed-app picker was attached to Activity content with `addContentView`. Both async completion paths render the page immediately after showing it. The M3 page replaces that content, detaching and disposing the picker before its first frame. Its zero-size Compose host now belongs to the stable window decor, while the existing shared M3 Dialog owns the visible modal window. Selection, dismissal, stop and destruction remove the host and observer.
2. The actual M3 `SWITCH_TO_EMBEDDED` action only selected APK mode and rendered again. It did not dispatch preparation. `chooseInlineApkMode` now immediately uses the existing guarded source-dispatch pipeline. A selected document's exact base/split URIs reach preparation; an existing bridge does not substitute for that selected input. The old native `GameModeChoiceDialog` has no production callers in code59; testing only its listener missed the real M3 bug.

3. Actual API26 execution exposed a further provider compatibility failure: public game identity loading required `RSAPrivateCrtKey`, although valid Android RSA keys may expose only `RSAKey`. Validation now checks RSA algorithm/modulus, checks the CRT exponent when available, and always proves the pair with a real SHA256withRSA signature. The fixed public certificate pin and compatibility identity remain unchanged. A cloud API26 ART probe confirmed AndroidOpenSSL returns `OpenSSLRSAPrivateKey` without CRT; modulus and signature roundtrip pass. The old APK rejects it and the fixed APK loads it successfully.
4. Already-sanitized preparation-stage diagnostics were being generalized again by the UI. Exact fixed stage messages now survive; arbitrary exception text, appended paths and forged prefixes remain filtered. Executable production-method tests cover the allowlist and a regression mutation.

Existing installation safeguards remain in place: foreground/current-owner gates, pending request exclusion, preparation progress and errors, selected-file receipts, unknown-source permission flow, explicit data-loss confirmation before any uninstall, and system confirmation via IntentSender. No app or user data is automatically removed by this fix.

## Executable regression coverage

- `android-app/src/test/run-installed-app-picker-host-tests.sh` compiles the actual Kotlin adapter against isolated view/window/lifecycle/Compose doubles. It covers completion → render → first frame, later renders, filtering, stale callbacks, single dispatch, dismiss, stop before the first frame, stop after display and destruction. Replacing the fixed attachment with old `addContentView` fails with “Picker was removed by completion's page render before first frame”.
- `android-app/src/test/python/test_selected_apk_action_execution.py` compiles verbatim production MainActivity method bodies, including the real M3 action callback, lifecycle gates, source dispatch, preparation continuation, installer decisions, uninstall gate and IntentSender launch. Android IO/crypto and scheduling are deterministic ports. It covers selected base/splits, installed bridge exclusion, busy/background/stale-owner/recovery gates, resume, retry, signing conflicts, unknown-source permission and duplicate callbacks. A mutation restoring the old render-only choice fails on exact-source preparation.
- Existing real coordinator/receiver/resolver/session-writer host suite covers selected-byte commit and receipt/result boundaries. This is separate from the action integration and does not turn the simulated IO into a real Android installation.

Host tests do not prove Android window rendering, PackageManager behavior or on-device installation. Final build/runtime results are recorded separately in the delivery report. No Windows computer or user phone is used by these checks.

## Signing

The prior build-host private key was unavailable in this cloud environment. Under the user's existing development-stage authorization, this build uses a new development certificate: `d2d9b7d0eaea66be03faa8241e46ef438a120c58f071332e957580b7eb9acfc9`. It cannot update code59 in place. Signing secrets are deliberately outside this source archive. Do not uninstall the old app without preserving any data you need.

## Host/build validation recorded for this revision

- Core: 820 tests, zero failures/errors/skips.
- Desktop/shared UI: 510 tests, 509 passed and one native desktop chooser test skipped; zero failures/errors.
- Android Python suite: 200 passed, including executable action integration and its regression mutation.
- Real APK preparation service with framework boundaries stubbed: 197 checks passed; historical code45-runtime fixture explicitly skipped because its verified APK was unavailable.
- Public signing compatibility: 235 checks passed, including a non-CRT provider key and mismatched-key rejection; restoring the CRT-only restriction fails the new regression.
- Helper security host tests: 165 assertions passed.
- Rebuild utility Python suite: 27 tests passed.

Broader legacy runner checks were also attempted. Two historical assertions fail identically on an untouched copy of the original code59 archive: `ManagedAccountFlowChecks` expects an obsolete restore-method source shape; `RootReadinessChecks` expects old explanatory text. Old `run-root-client-tests.sh` / `run-root-auto-policy-tests.sh` compile Java-only inputs and cannot resolve the current AndroidX/Compose host. The old preparation runner's API26 bootclasspath compilation reports a missing `LambdaMetafactory`; the separate production preparation-service, provider-injection and public-signing suites pass. These failures are not counted as passing and were not “fixed” by changing unrelated production behavior.
