# Cloud recovery checkpoint: Android 0.5.25 / 46

This is a reimplementation from the recovered three-step source baseline, not a claim that missing commits were recovered verbatim.

## Baseline and boundaries

- Recovery baseline: `95b117f569ba8861e14c62f23c02cc9351d6f607`.
- Android package remains `io.github.xyz1001.pdsync.android`, min API26 / target API36.
- Main app output is unsigned. No replacement main release private key was generated.
- The intentionally public game compatibility key is recovered from the verified code45 APK. Its certificate SHA-256 is `cf56b878de677aa7b453a8bde1fbc78c282212b2126ae7aca28297eb25649e76`. It is not a publisher-authentication credential or the main app signing key.

## Reimplemented behavior

- Three-step shared UI remains the recovered baseline. Android first-page selection is read-only until the user chooses Root or APK preparation; application labels and icons are present.
- Root first-page work checks permission only. Its Next action begins the second-page save inspection. Missing save directories are empty only after parent/access validation; permission and I/O errors stay errors.
- Embedded access uses an actual caller package / Binder UID check without a fixed client certificate pin. The first-page handshake does not read save files. Timeouts and bounded worker cleanup prevent indefinite busy state.
- Installed embedded bridges can be discovered without a local receipt. Current and independently verified code45 runtimes are supported; unknown historical runtime bytes are not assumed compatible.
- APK preparation accepts independently validated structurally unsigned input with explicit unknown original signer provenance. Present-but-invalid signatures, ambiguous ZIPs and altered signed content still fail. Generated output still receives real API26/API36 apksig verification and unchanged payload checks.
- Retained and recognized injected sources keep their source namespace when selected through Root. Unknown original certificates are not fabricated.
- Heavy preparation validation precedes uninstall confirmation. Same-process unchanged staged files reuse their validated proof; cold or changed sessions validate again. Streaming install size/hash/fsync and OS confirmation remain required.
- Ordinary resume does not trigger component rescanning. Actual pending permission/activation/install returns have bounded, target-bound continuation. Automatic game-exit synchronization remains separate.
- Setup has no technical detail controls or request-state buttons. Log export is accessible globally without requiring account/game/readiness success.
- Preparation diagnostics preserve bounded copy bytes/SHA/EOF/cache agreement, parsed package/version/SDK, apksig issue parameters, cause classification and code locations. Paths, credentials, arbitrary exception text and save content are excluded.

## Verification scope

- Core: 774 tests passed.
- Android main runtime: 4,270 host checks, including 149 diagnostic-adapter and 52 installed-bridge checks.
- Root selection: 38 host checks; permission-only Root probe: 37.
- Signing/container: 230; recovery: 138; receipt: 51; reuse: 16; API26/27: 18; API28/36: 16.
- Provider injection: 79, plus independent AAPT2 manifest-tree comparison.
- Helper: 165 host assertions and 7 Python tests, including 18 checks exercising the production Provider against framework doubles.
- Android UI/source contracts: 171; setup: 31; picker: 47; catalog: 43.
- All four native ABIs were rebuilt and are byte-identical to the code45 native entries. Native host JNI and API26 apksig compatibility checks passed.
- Production preparation service: 63 checks, including actual code45 runtime upgrade, single SAF-stream consumption, trusted framework parent alias success, and internal staging/receipt/leaf link rejection. Unsigned release artifact checks are recorded in the delivered build report.

No Android device, actual SAF picker, real Binder/OEM activation, package installer, Android Root/SELinux, or game operation was executed in this cloud run. The unavailable user APK was not tested; representative real-manifest/DEX/apksig fixtures exercise the observed code45 failure class. Full desktop tests were attempted but the cloud headless/AWT/native extraction environment caused failures; that attempt is not a desktop pass.

## Build inputs

Use the existing explicit Android build properties/environment inputs for SDK, API26-compatible apksig JAR, independently built bridge DEX and four native libraries. See `tools/android-rebuild` and `android-app/build.gradle.kts`. Dependencies were fetched from pinned official inputs, with no full NDK or emulator installation. No account configuration or signing secret is required for the unsigned build.
