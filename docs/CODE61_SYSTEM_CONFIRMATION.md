# code61 Android system-confirmation compatibility

Version: 0.5.40-test / 61. Source baseline: code60 archive SHA-256
`d6445c6e2f64f391f5777686df4bd5b59247c0ca3b46cdaf6fb64b955a0f2928`.

## User-device evidence and confidence

The supplied code60 diagnostic export identifies Android 16/API36, OnePlus
PJA110. Preparation, APK validation, session write and commit complete. A
pending-user-action callback follows, then successful confirmation-token
validation. No installer completion is recorded. Repeated reconciliation does
not establish that the installer UI appeared. No raw user log is included here.

The app targeted API36 but launched a retained PendingIntent/IntentSender
without the sender-side foreground launch options documented for recent
Android versions. Launch guards were latched before the launch and survived
exceptions; successful worker completion was logged before the UI callback.
This is a plausible explanation consistent with the device log, not proof of
an OEM denial reason: the original export did not record that platform reason.

## Changes

- Foreground sender options: API36+ ALLOW_IF_VISIBLE, API34–35 ALLOWED, earlier
  APIs preserve the null-options path. API-specific symbols are isolated.
  Current-owner, resumed, current-request and source validation gates remain.
  No persistent creator privilege or intent-security opt-out is added.
- A throwing launch clears both launch guards, keeps the same token/session,
  and allows retry. An explicit Continue system confirmation action can retry
  an unobserved launch while foreground after a two-second duplicate-click
  throttle. Automatic polling never repeatedly launches a dialog. Returned
  requests cannot be replayed through this retry action.
- OEM intents without an action retain their action absence across Android's
  URI filter serialization (parseUri otherwise inserts ACTION_VIEW). Only the
  filter identity is persisted; original extras remain inside the system token.
  Lost tokens are never reconstructed from incomplete serialized extras.
  This separate defect is verified from Android behavior, not established as
  the cause of the supplied log.
- Closed-enum diagnostics distinguish token retention, launch attempt,
  accepted launch call (PENDING, never “UI appeared”), Activity departure and
  Activity result. Departure only proves pause and may be another interruption.
- A pending request shows its actual installer status, and exposes an explicit
  continuation action as a fallback. No automatic uninstall/data deletion.

## References

- https://developer.android.com/guide/components/activities/secure-bal
- https://developer.android.com/privacy-and-security/risks/intent-redirection
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/content/Intent.java

## Validation

- Core: 821 tests, zero failures/errors/skips.
- Android Python suite: 201 tests, including production-method execution and
  a regression mutation removing sender launch options.
- Runtime host suite: 838 recovery assertions and 719 SDK compatibility
  assertions, plus the remaining runtime checks.
- Real coordinator/component host suite: 19 scenarios, 265 assertions,
  including retained-token/session retry without another commit.
- Release assembly and release lint-vital checks pass. APK v2/v3 signatures,
  verification for min SDK26 and SDK36, and zip alignment pass. All five
  bridge-runtime/native inputs match the verified code60 APK byte-for-byte.
- Actual AOSP API36 ART probe confirms OLD_NULL_ACTION_ROUNDTRIP=false and
  NULL_ACTION_PRESERVED=true. This proves the separate filter serialization
  defect on Android, not the cause of the user's OnePlus launch symptom.

See the final delivery validation report for installer UI execution and
remaining limits. Host fixture tests are never evidence of OnePlus UI behavior.

## Signing

The prior private development signing key was not recoverable. The user's
existing development-stage authorization permits a new development identity.
The new certificate is SHA-256
`e3f6230cc5b4926ab507f8d59170250e86033f02030a241ebafb89c1f7641e94`.
It cannot update code60 in place. No existing app/data was removed. Signing
secrets are excluded from source and deliverables.

## Final runtime boundary

AOSP API36 booted and the original code60 app installed/launched. The full
selected-APK installer flow remained blocked by system-app ANRs and emulated
shared storage remaining in `checking` state; no matched main-app installer
confirmation comparison was established. The separate lightweight UI probe
was not granted its own install-source permission and was stopped without
bypassing it. It supplies no successful confirmation-launch evidence.

The code61 main-app installer UI, the user's OnePlus, actual game package and
actual saves remain unverified. The actual API36 ART filter probe and all
listed host/build checks are complete. No Windows or user-device data was
modified. The unchanged desktop full test matrix was not rerun this revision.
