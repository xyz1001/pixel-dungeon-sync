# code68 / 0.5.47-test — Selected APK application identity

The existing selected-game card now resolves the chosen APK's application label, version and icon before preparation or signing. Its layout is unchanged and matches the installed-app card. No controls, notices or permission requests were added. Unknown labels/icons retain the existing neutral placeholder and generic icon, without inventing application metadata.

## Implementation
- SelectedApkIdentity copies only the document-picker URI set into an isolated application-cache directory on the setup worker, validates manifest package/version consistency and exactly one base APK, then reads the base archive's application resources. All temporary files are discarded after the read. Reads are bounded using the existing 2 GiB per-APK and 8 GiB set technical staging limits.
- Label and icon resources come from that exact archive. An installed version of the same package is never used as a substitute. Missing base/split resources fall back neutrally. Package identity, readiness, signing, installation and save access remain owned by existing validated flows, not presentation metadata.
- Completion is attached to an in-memory request token. Replacement (even selecting the same URI again) and cancellation invalidate old results. Rotation uses the retained session's current owner. A committed file source keeps its identity visible while preparation/signing continues.
- Metadata runs off the main thread, does not set the application's busy flag, and does not initiate signing, installation or Root access. Archive icon caching is separate from the installed-icon monitor, retaining only a small rendered bitmap/resource-key entry.
- Prepared APK icons also come from the prepared archive itself, preventing a same-package installed copy from replacing the selected icon.

## Preserved behavior
code67 step-row export/credential text, code66 removal of access-mode bottom messages, and code65 canceled-install behavior are retained. The unrelated top/bottom spacing investigation did not produce UI changes in this release.

## Verification boundaries
Executable host tests cover the production selected-APK reader with Android/parser boundary doubles, exact-archive resource selection, invalid/mixed/split inputs, cleanup and interruption. Executable MainActivity method tests cover asynchronous completion, repeated URI choices, cancellation, rotation and the transition into preparation. Existing shared Compose tests exercise the unchanged card's name/version/icon rendering. These do not establish Android device resource decoding or system document-picker behavior; device verification remains separate.

See the delivered validation file for final test counts, signing, hashes and the unchanged full-lint baseline.
