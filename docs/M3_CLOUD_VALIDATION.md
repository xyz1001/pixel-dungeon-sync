# code59 M3 cloud validation

Version: Android `0.5.38-test`, versionCode `59`.

This is the cloud implementation following the retained code58 baseline.
The associated source manifest records the actual packaged files; older
validation and handoff documents in this repository remain historical evidence.

## Input provenance

- Baseline commit: `da329ea4ff87a01a3fcf873c703d8ff950804f71` (code58).
- User-authorized cloud-only handoff archive SHA-256:
  `75da5dd06bfb726221f43f4c41accebd661210d0f834e21d8f7f3ad002aea392`.
- All 27 incoming changed/added Kotlin files were individually checked against
  the handoff manifest before overlay. The baseline worktree was retained.
- Original v21-M3 HTML SHA-256:
  `3a81e38b4cf5052bd7e40fc8d164bbadf3c19feddcf4b0694d6d647e8c7f50e4`.
- Actual HTML and reference screenshot pixels were inspected on this cloud
  executor before implementation. Reference viewport is 1280x900 or 390x900,
  DPR1, excluding only prototype switch/test-console controls. Screenshot scene
  filenames do not always identify the displayed page, so their inventory and
  actual pixels were checked.

## Changes

Reference-driven shared theme/shell/icons, game and service selection, account
panel, responsive home action cards, history geometry/pills and inline naming,
settings/path modal, confirmation presentation/backdrop, and retained safe
comparison/multi-head screens. Android access selection now uses typed Root/APK
cards and the existing guarded real workflows. No cloud synchronization,
recovery, content-equivalence or merge algorithm has been replaced.

## Validation method and boundaries

Production `ImageComposeScene` tests compile the same shared UI source used by
Android. They render Skia/software PNGs, inspect layout/semantics and dispatch
real pointer, text and key events. Synthetic fixtures invoke guarded callbacks
without opening real saves, sending credentials or performing remote writes.
Canonical M3 captures include viewport metadata and semantic/action snapshots.
Ordinary body captures are explicitly labelled when they test a dialog body
rather than a native window.

No Android device/emulator/KVM is available in this executor. Android release
compilation, API26/36 APK signature verification and JVM host tests do not
establish device installation, Root/Binder, OS installer, real IME or Android
lifecycle execution. The native desktop display is inaccessible from the shell;
one native chooser test is skipped. No Windows device was used or modified.

Pixel identity to the browser prototype is not claimed. Native font fallback,
font rasterization, elevation and modal backdrop differ. Real account/game/
history values and safety/recovery controls intentionally replace synthetic
prototype data. No fake renewal or merge-preview plan is displayed. The narrow
Android game card is reflowed to keep real identity and selectors readable;
it does not copy the prototype's overlapping long-title layout.

## Signing and installation

The final APK uses the separately authorized new development certificate,
SHA-256 `c8abff58567323eec90ae8782f00cc828568a93fa51b10632051ba2003e3db79`.
The private key/password are excluded from source, evidence and deliverables.
This certificate differs from the earlier APK certificate, so Android cannot
install it over that earlier app as an update. No uninstall or data deletion
was performed. Back up existing configuration/saves before making any manual
installation or removal decision.

## Rebuilding

Use JDK 21, Gradle 8.14.3, Android SDK 26/36 and Build Tools 36.0.0.
The normal command is `./gradlew :core:test :desktop:test
:android-app:assembleRelease --offline --no-daemon --console=plain` when
the pinned dependencies are cached. Supply the independent bridge-runtime,
four-ABI root-native and API26-compatible apksig inputs using the variables
documented in `shared-ui/README.md`; no private signing material belongs in
the source tree. The build emits an unsigned release APK. Cloud verification
used the restored toolchain wrapper described in the handoff, not a modified
production build configuration.

The bridge Java source and native source were unchanged by this UI work.
Bridge-runtime SHA-256 is
`a1ce8c9c250199849a691f4d6ed5ebec858adc4a59582994e82b39bc7841fc85`.
The root-native inputs were retained unchanged from code58; the final
artifact verification records each ABI hash and compares packaged bytes.

## Remaining differences and device checks

- Real installed-app picker adds search, package identifiers, show-all and
  scrollable results needed for actual installed games.
- Narrow game-card reflow avoids overlapping identity/action controls.
- Native fonts, shadows and modal background effects differ from Chromium.
- Android retains the previous save-failure behavior: a submitted rename
  draft closes and the real failure is shown. Desktop preserves its draft.
- Device QA remains required for Root/Binder, OS installer/permissions,
  rotation, lifecycle, real keyboard/IME and installation with the new cert.
- No prototype test-parameter panel, fabricated entitlement renewal or
  unverified merge-plan counts have been turned into production features.

## Final evidence index

- `m3-validation/captures/`: 106 current shared-production Compose PNGs with
  semantics and exact viewport metadata. Six obsolete captures without
  matching metadata were excluded.
- `m3-validation/junit-summary.json`: complete final core/desktop XML totals.
- `m3-validation/logs/`: successful host regressions, final Python contracts,
  focused dialog sizing regression and final integrated build output.
- `m3-validation/artifact-verification.json`: final APK identity, signature,
  alignment and signed/unsigned payload equality checks.

The final dialog sizing regression checks the real text line extents and
42dp button height: desktop three-button actions stay on one row with the
long merge label unwrapped; the narrow viewport wraps the whole merge
button onto the next row. Branch captures settle past the pressed ripple.

## Final results

- Core: 820 tests, zero failures/errors/skips.
- Desktop/shared UI: 510 tests, zero failures/errors, one native chooser skip
  because the shell cannot access X11 (509 executed successfully).
- Android source contracts: 197 tests, all passed; JVM host safety/flow suites
  also passed as recorded in the attached successful logs.
- Final integrated core/desktop/release build: BUILD SUCCESSFUL.
- APK: 8,198,231 bytes; SHA-256
  `83da72bfa5ad5a76efcd4da6faae26b330d5b45eb94a85eb6631b048e28e5207`.
- Signature verified at API26 (v2) and API36 (v3); 16KiB ZIP alignment verified.
- All128 ZIP payload entries are byte-identical before/after signing; bridge
  and four ABI native inputs match the retained code58 inputs exactly.

API26's verifier warns that the v2 signature refers to the newer scheme ID3,
which is not understood by API26; API26 verification still succeeds using v2,
and API36 independently verifies the v3 signature. No signing error is hidden.
