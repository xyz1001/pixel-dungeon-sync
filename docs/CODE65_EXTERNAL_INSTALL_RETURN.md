# code65: external installer return releases the UI wait

Version: 0.5.44-test / 65. Baseline: code64 / 0.5.43-test.

## Root cause and change

The external Activity result previously only checked installed package bytes. An absent or mismatched package left EXTERNAL_INSTALL active indefinitely, including after the user canceled AppManager. The real-device code64 reproduction records an Activity result and resume after pressing Cancel; its numeric result code was not captured. Back did not close that installer's confirmation page.

A real EXTERNAL_INSTALL Activity result now durably transitions EXTERNAL_INSTALL to EXTERNAL_RETURNED. The latter is an unlocked observation, not installation success and not a claim that another app's background installation stopped. Result code is logged internally for diagnosis and is never treated as proof. Ordinary pause/resume, Home switching, multiwindow, or an unrelated Activity result cannot perform this transition.

The returned nonce, package, prepared receipt, version, signer, full APK-set identity, and immutable private APK remain available. Verification still requires matching actual installed bytes and stable component/package metadata. Late matching package broadcasts and subsequent foreground/cold-start checks can complete the observation. Result handling is queued while paused or busy and drained by the retained setup session after work completion or foreground return. Package broadcasts are only recheck signals, never success evidence.

An explicit new source/mode supersedes the observation before another backend is chosen. A different current prepared receipt cannot satisfy the old observation. Each new external request gets its own nonce, monotonic Activity request code, and immutable copy; an old Activity result cannot unlock a newer request, including after explicit manual end/retry; a failed later launch only revokes its own copy. Superseded/unverified copies are deliberately retained rather than revoked on a timer: an external installer may still need them. This is a private-storage retention tradeoff, not a promise that Android preserves a temporary grant forever.

## Preserved behavior

- Ordinary single APKs still launch the default unwrapped ACTION_VIEW handler with an exact content URI and read-only grant. No PackageInstaller-first fallback is restored.
- No controls, prompts, banners, success notices, or new user-visible wording are added.
- No installer enablement, device settings, automatic uninstall, app data clearing, or signing identity changes.
- Old code64 checkpoints did not record whether their Activity had returned. Upgrade alone does not assume a canceled operation or discard an ambiguous old request. A positively confirmed canceled old checkpoint can be cleared through its existing action before testing new code65 requests. New code65 return records survive restart.

## Verification boundary

Production coordinator/Activity-method host regressions cover unlocked cancellation, retained resources, paused/busy return delivery, late matching completion, restart, source replacement, mismatches, duplicate handling, and fresh retry identity. Android framework/PackageManager in these tests are controlled doubles, not a device installation. Release build/lint and device results are recorded separately with the delivered artifact.

Android system broadcast reference: https://developer.android.com/about/versions/14/behavior-changes-14#runtime-receivers-exported
