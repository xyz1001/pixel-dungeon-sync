# Android code47: explicit access-mode selection

This patch changes only Android's first-step source/mode handoff. Desktop and the remaining shared pages are unchanged.

- Both installed-app selection and system APK-file selection offer the same native `使用 Root` / `修改 APK` dialog. Opening or cancelling it performs no Root authorization, preparation, installation, or persistent target change. A known embedded bridge does not skip the choice.
- The pending source is held in the existing in-memory setup session across rotation. Dispatch is one-shot and owner-bound; stale, duplicate, background, or cancelled dialog callbacks cannot choose a mode.
- For a picked-file Root choice, the service reads each file once into disposable app-private storage, parses the real binary manifest, and requires exactly one base and a consistent package/version across its selected splits. This is identity inspection, not source-signature authentication or APK preparation. The installed target is matched by package only; its version need not equal the picked APK version. A missing installed package produces a short error and never falls back to APK modification.
- Root permission success enables manual Next. Save-directory inspection remains in step two.
- Only an explicit modification choice can prepare/install a selected file or reuse an installed usable bridge. Source and error state are retained for a deliberate retry; no automatic modification is implied by a failure.

## Validation

The native dialog adapter's actual button/cancel listeners run against inert Android framework doubles. Tests cover both sources, both modes, initial no-op, cancellation, stale/double callbacks, background/rotation, and retained SAF source identity. MainActivity source contracts verify the production entry points, installed-package lookup before Root authorization, and the separation from preparation and save inspection. The preparation-service test executes real binary-manifest/SAF-adapter code and verifies temporary-file cleanup and unchanged source bytes. Existing Root authorization, target-binding, picker and setup regressions also run.

Release compilation includes Java, Kotlin, Compose, D8 min26 and release lint-vital. The release uses the same main development certificate as code46 (`8407ba84f80a85d68153fdb786b7a37c6b0a6be7784be9acb4f53969b0fc66eb`); the intentionally public game compatibility certificate is unchanged. Bridge DEX and native payloads must match code46 byte-for-byte.

No Android device, real system file picker, Root prompt, Binder/OEM activation or package installation is exercised by these cloud host checks. The APK is provided for that subsequent device validation.
