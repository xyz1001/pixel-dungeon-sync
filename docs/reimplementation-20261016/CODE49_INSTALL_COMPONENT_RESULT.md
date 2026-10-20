# Code 49: finish installation by installed package component

## User policy, superseding code 48's result proof

Selecting an APK file and choosing Modify still prepares and submits **that file** to the system installer. It cannot skip the system flow because another version or identical bytes already exist. After the system flow returns, the user explicitly accepts the currently installed package if it contains this tool's valid component. A cancellation/missing callback is not by itself a failure. This means an old valid component can be accepted after cancellation; code 49 does not claim that the selected bytes were installed in that case.

The preparation, generated APK signature/content validation, selected-source provenance, session write hash/length/fsync, package target binding, Binder caller checks and save path checks remain unchanged.

## Fix

- Code 48 required the selected output hash, `RESULT` and `STATUS_SUCCESS`; an OEM omitting/changing the callback could retain the explicit-file pending guard after successful installation.
- The result reconciler now queries `InstalledBridgeResolver` for the requested package. It validates the package's own component/metadata, not its equality to the selected input version/hash/signature.
- Component lookup is gated behind a persisted actual installation request and a return boundary: terminal callback, return from the requested system UI, or disappearance of the submitted system session. Merely preparing/committing while a confirmation is pending cannot reuse the old component early.
- Both Activity cancellation and missing Activity result after the expected system UI departure are handled. Confirmation launch and returned state survive the relevant checkpoint; cold restart can observe an ended session without replaying a stale confirmation.
- A code 48 already-settled failed request retaining its selected/request receipt and target can be reconciled on next startup. New/unsubmitted selections and mismatched receipts cannot inherit this recovery.
- PackageManager publication can lag the callback. Missing/invalid components remain pending for the existing maximum 12 targeted checks, spaced 500 ms; then the operation ends without enabling Next. No recurring foreground/focus scanner is added.
- After accepting the installed component, first-step Next is enabled using metadata only, with access still `NOT_CHECKED`. Activation/authentication/save reading occurs on the existing second-step path. Completed configured sessions keep their normal cold-start behavior.
- Redundant error-state Root/Install Game buttons are removed in a separate UI commit. The mandatory two-option selection dialog remains for both input routes.

## Validation boundary

Host tests execute the policy and real installation coordinator with Android framework/system-service doubles. Package/component resolver tests use controlled PM metadata. Source contracts cover Activity wiring, bounded retry, ordinary resume, first-step access separation and the required mode dialog. Android release compilation/D8/lint-vital and signing/ZIP/alignment checks cover packaging.

No phone, Android system installer UI, real Binder/SELinux, SAF picker, OEM lifecycle or game has been exercised for this change. Actual device verification remains required; no user data or installed applications are modified by the cloud tests.
