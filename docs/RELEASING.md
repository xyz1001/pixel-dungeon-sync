# CI and releases

The public version is `android-app/AndroidManifest.xml`'s `versionName`; increment
`versionCode` for each Android update. The tag is exactly `v<versionName>` (for
example **`v0.5.114-test`**, an example, not a tag created by this change).
Tags with a suffix are GitHub prereleases. Do not move/reuse release tags.

## Checks and artifacts

`.github/workflows/packages.yml` runs on branch pushes, pull requests and manual
dispatch. Dispatch cannot publish; optional `verify_signing=true` signs and verifies
all six packages after the build jobs succeed, without creating a tag or release.
All tag builds use the same
tests before publishing. No `pull_request_target`, persistent checkout token or
signing secret is used by build jobs. Only the tag release job has contents:write.
Actions are pinned to reviewed commit SHAs. New branch runs cancel old branch runs;
tag runs are serialized and are not automatically canceled.

Toolchain: JDK 21 (JVM 17 output), Gradle 8.14.3 wrapper with checksum, Compose
1.9.3, AGP 8.11.1, Android SDK 26/36, Build Tools 36.0.0, NDK 27.2.12479018.
The native CI build uses the official NDK, retains the existing API26/ELF/symbol/
16KiB checks, and compiles all four ABIs. Bridge DEX and API26 apksig compatibility
JAR are regenerated from source (the compatibility patch checks its SDK input hash).
No phone, retained build outputs or user configuration is required.

| Artifact | Runner / architecture | Validation |
|---|---|---|
| Android APK | Ubuntu / arm64-v8a, armeabi-v7a, x86, x86_64 | Python host suites, bridge/native host tests, release lint, 16KiB alignment; tag signatures at API26/36 |
| Windows EXE | Windows 2022 / x64 | JVM/shared UI tests and bundled-runtime window smoke |
| Windows portable ZIP | Windows 2022 / x64 | Complete app-image including Java, extracted ZIP launcher smoke |
| Linux DEB | Ubuntu 24.04 / x64 | JVM/shared UI tests, package metadata/extraction and Xvfb window smoke |
| Linux .pkg.tar.zst | Arch container / x64 | Non-root makepkg; pacman metadata/list/install; installed window smoke |
| macOS DMG | macOS 15 / arm64 | JVM/shared UI tests, hdiutil verify, read-only mounted DMG launcher smoke |

Desktop smoke uses the existing `PDSYNC_SMOKE=1` and a temporary workspace; it
checks an actual displayed window, not merely file existence. Linux needs ordinary
GUI/system libraries; Java is bundled. Desktop game configuration currently accepts
only identity-verified Shattered Pixel Dungeon JARs. Android's support for variants
does not imply equivalent desktop variant support.

## Product and verification status

Production commit `b943e2c` routes desktop through `DesktopSimpleSession` to the same
`SimpleSaveSync` protocol as Android. `SimpleSaveProtocol` shares history traversal,
comparison classification, and restore parsing. Both use `latest` plus
`saves/<content-hash>.zip` and three-field JSON (`hashId`, `parentId`, `name`), rather
than two mutually invisible desktop/Android histories.

Real desktop UI verification has covered cloud upload, restoring B with equal file
contents and continuing in the official game, canceling a conflict without writes,
confirming E with parent B while retaining the old cloud files, account isolation,
and history arrows/refresh. New-layout 404, authentication 401, and service 503 are
distinguished; the branch-conflict wording was also checked. These results are not
an Android-device round trip or a public-network performance measurement. Package
smoke tests cannot substitute for either.

The table above describes configured checks. Check the exact commit's
[Actions run](https://github.com/xyz1001/pixel-dungeon-sync/actions/workflows/packages.yml)
for completed job results and downloadable build artifacts; a successful build-only
run does not publish a release. CI setup does not create a formal version tag.
Record the workflow URL, commit, job conclusions, and artifacts before claiming
a build is verified. The key owner approved the retained Android identity and the four
repository signing Secrets were configured on 2026-10-30. Do not publish unsigned Android output as an
installable release or call a non-release run an end-to-end release test.

Connection credentials retain their existing plaintext storage behavior:
Android's app-private `noBackup/webdav.properties` and desktop's workspace
`.local/config.json` (default workspace: `~/.pixel-dungeon-sync`). Legacy Android
key migration code is not evidence that the current file is encrypted. Do not put
these files in build inputs, caches, artifacts, issue attachments, or release assets.

Compose 1.9.3 requires positive macOS major version. Its internal bundle version
is `1.<versionCode>.0`; public filenames/tag remain `versionName`. Windows/DEB use
the numeric part of versionName; pacman replaces `-` with `.` in internal pkgver.
Windows packages are unsigned (no Authenticode). macOS has no Developer ID signing
or notarization. No signing certificate, Apple account or paid access is configured
by these workflows. OS trust restrictions remain a distribution limitation.

## README download status maintenance

The user READMEs describe downloads and everyday use. Keep protocol implementation,
UI acceptance results, and release maintenance here instead of adding progress
reports to the README. The product/verification section above retains the desktop
checks and Android round-trip boundary removed from the user guide.

On 2026-11-02, the repository releases API returned an empty list. The
[signing-validation run](https://github.com/xyz1001/pixel-dungeon-sync/actions/runs/41074899460)
for `2ed4098faf83b14fe823ba36b0334e246a17c08f` still had unexpired artifact
`12987715941`, `verified-release-packages` (319,474,849 bytes), expiring at
2026-11-13 14:59:02 UTC. This is the temporary test download linked in both READMEs,
not a published Release. Update both language versions when it expires or a release
becomes available; do not replace it with an ordinary unsigned branch APK.

`android-test-reports` contains Android Lint reports, not an installer or an
on-device test result. Desktop `tests-*` artifacts are also development evidence.
The six-package collection contains the signed Android APK and bundled-runtime
desktop packages; platform artifact sizes therefore cannot be compared as installed
application sizes. The package assembly and checksums are defined in
`tools/ci/release.py`.

## Android signing prerequisite — explicit approval required

Configure these repository Actions Secrets **only after the key owner approves
uploading the existing signing material to `xyz1001/pixel-dungeon-sync`**:

* `PDSYNC_KEYSTORE_BASE64`: base64 bytes of the existing keystore (not a new/debug key).
* `PDSYNC_KEY_ALIAS`: existing alias.
* `PDSYNC_STORE_PASSWORD`: keystore password.
* `PDSYNC_KEY_PASSWORD`: key password (repeat the store password when identical).

The retained signer must equal `tools/ci/android-signer.sha256`, verified from the
existing signed code135 APK (public certificate only). Current game preparation
binds the application's actual certificate into each prepared game's metadata;
the legacy helper test certificate is not the current application release key.
The retained certificate is named `CN=PDSync Development`: it is the existing
test-installation identity, explicitly approved by its owner for releases on 2026-10-30.
Any replacement or transfer requires the key owner's explicit approval;
the workflow neither generates a new key nor silently changes that identity.
Changing the signer breaks ordinary updates and existing bridge bindings.
Secrets grant the tag workflow and explicitly requested manual signing-validation job signing
capability; anyone able to modify trusted release code can potentially use them.
Never put values in source, workflow inputs, command-line password arguments or
logs. The alias is also read in-process, not passed in process arguments.
The workflow decodes the key in a temporary owner-only file and removes it
on exit. Missing secrets or a wrong signer fail before creating a release.
Without secrets ordinary CI still produces an explicitly **unsigned, noninstallable**
Android build artifact. It is never presented as a signed distributable APK.

## Publish and retry

For a non-publishing signing check, run the **Test and package** workflow manually
on the reviewed branch with `verify_signing=true`. The resulting
`verified-release-packages` artifact contains the six packages and `SHA256SUMS.txt`.
The job has read-only repository permission and receives no GitHub release token.
This validates real APK signing and package assembly, not Release publication.

1. Review code and public version, increment versionCode, run CI on the intended
   commit and confirm all jobs. Configure signing secrets with explicit approval.
2. Create and push the matching tag on that reviewed commit. Creating a tag is a
   separate maintainer action; CI setup does not create one automatically.
3. All four build jobs must succeed. The release job signs/verifies Android,
   checks all six files, generates SHA256SUMS.txt and release notes, creates a
   draft, uploads every asset, checks attachment sizes, then publishes the draft.

A build/sign/check failure cannot publish a partial release. Failed attachment
uploads may leave a **draft**; rerunning the same tag can complete that draft.
A published release is never overwritten by reruns: use a new version and tag.
No automatic tag creation or main-branch merge occurs. Final tag publication
and Gatekeeper/SmartScreen behavior require separate validation;
successful non-release CI alone does not validate them.

Official references: [Actions syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax),
[GitHub CLI releases](https://cli.github.com/manual/gh_release_create),
[runner images](https://github.com/actions/runner-images),
[Compose packaging](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html),
[SDK Manager](https://developer.android.com/tools/sdkmanager),
[PKGBUILD](https://man.archlinux.org/man/PKGBUILD.5.en).
