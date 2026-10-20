# Code48: honor the APK file selected for modification

The document-picker branch already prepared the chosen file bytes. The incomplete behavior was later in installation/readiness: exact installed-output equality could skip a system installation, and readiness could discover a same-package bridge after a canceled/failed request without checking the selected preparation.

This fix records document-picker installation intent in the preparation receipt before publishing it. The coordinator persists that receipt identity through backgrounding, process recreation, permission refusal, uninstall continuation and installation cancellation. Every such selection requests its own system installation, including identical bytes. Completion requires the current nonce/session's successful system callback, matching receipt, and the expected complete installed APK bytes. A missing installer session alone is not success and does not discard a delayed callback. The existing bounded result-wait/settlement remains.

Before bridge discovery or authentication, the first-step readiness path retains an unfinished selected-file installation as not ready. Successful verified installation releases that requirement. A later explicit installed-application choice can reuse its bridge; merely opening/canceling the choice dialog cannot. Completed/replaced receipts can use saved installed selections without requiring obsolete receipt payloads; if no binding was saved before process death, current metadata restores the target. No ordinary-resume scan or hash was added.

Root dispatch, the mandatory two-option dialog, APK signature/payload validation, and installed-source reuse are otherwise unchanged. The operating system may still reject a downgrade; the existing explicit uninstall/data-loss-confirmation policy is preserved. No automatic uninstall or fallback to a different source was added.

## Cloud validation

- 201 real preparation-service assertions with Android framework boundaries stubbed, actual AAPT2 binary manifests, DEX and signature/payload checks. Picked versions 122, 123 with different content, and 124 are checked against installed bridge version 123. Selected version/source hash/payload and persistent intent survive hot/cold reload; installed bytes, modification time and package metadata remain unchanged.
- 80 executed selected-install policy checks plus 7 production wiring contracts: exact-byte reinstall, failure/cancel, stale receipt/status, delayed result, preparation publication/restart, explicit installed-source replacement and completion.
- Existing setup regressions: 31 setup checks, 51 real native-dialog adapter listener checks against host doubles, 13 Activity/lifecycle wiring contracts, 47 picker checks, 43 catalog checks; 11 embedded-install contracts.
- Android release compilation includes Java/Kotlin/Compose, D8 min26 and release lint-vital. Code48 is 0.5.27-test, with the same development signing certificate as code46/47. The embedded bridge/native payloads and intentionally public game compatibility identity are unchanged.

These are cloud host/build checks. No Android device, real SAF picker, Root permission screen, PackageInstaller confirmation, Binder/OEM activation or actual game installation was exercised. The signed artifact and complete source/Git backup are provided separately. Git publication remains paused at the previously denied public-compatibility-key change; no alternate publishing route is used.
