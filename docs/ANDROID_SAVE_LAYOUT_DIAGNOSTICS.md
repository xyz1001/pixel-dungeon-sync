# Bounded save-layout diagnostics

The authenticated bridge `inspect` response can succeed (`ok=true`) while save transfer is blocked (`transferReady=false`). The main app preserves the optional layout metadata in the resulting `SAVE_READINESS` failure, so the existing copy-diagnostic action can report the unsupported layout without reading or exporting its files.

## Protocol extension

The API version stays at 1. Older bridge replies without `saveLayoutDiagnosticVersion` remain compatible.

- `saveLayoutDiagnosticVersion`: integer 1
- `saveContentState`: the existing fixed content-state enum
- `includedRootEntries`, `excludedRootEntries`, `unrecognizedRootEntries`: full classification counts, independent of diagnostic truncation
- `rootEntryNames`, `rootEntryTypes`, `rootEntryClassifications`: matching string arrays
- `rootEntriesTruncated`: true when any name or row was omitted

Only immediate root basenames are observed. File type is read with `NOFOLLOW_LINKS` and reported as `REGULAR_FILE`, `DIRECTORY`, `SYMLINK`, `OTHER`, or `UNKNOWN` if optional metadata is unavailable. Classification is `INCLUDED`, `EXCLUDED`, or `UNRECOGNIZED`. No recursion, link-target resolution, file contents, settings values, credentials, or absolute paths are included.

Both ends independently cap the inventory at 64 entries, 96 ASCII basename characters, and 4,096 combined UTF-8 bytes across names, types, and classifications. Array/report framing adds a fixed bounded overhead. Unsafe, control-containing, path-like, non-ASCII, and overlong names are omitted rather than shortened into misleading names; the full classification count is preserved and truncation is marked. The client rejects malformed field types, enum values, inconsistent array sizes, invalid counts, and overflow-sized counts without replacing the original readiness failure with untrusted exception text. The existing root scan limit is 16,384 entries; it still fails with `SAVE_TOO_LARGE` rather than approving a partial scan.

This metadata is informational. It does not grant transfer access, ignore unknown files, expand the archive allowlist, or change the expected type of supported saves. The exact `profileInstalled` exclusion still requires a regular non-linked file. A diagnostic contains relative filenames, which can themselves be private; the copy report asks the user to review them before sharing, and the clipboard remains marked sensitive.

## Verification

- `tools/android-rebuild/test-helper-host.sh`: real temporary filesystem tests for names, types, classification, marker exactness, symlinks, no recursion/content exposure, truncation and root scan limits
- `android-app/src/test/run-main-runtime-tests.sh`: hostile optional provider-bundle tests, bounded report tests, and existing readiness/transport regressions
- `python3 -m unittest discover -s android-app/src/test/python -p 'test*contract.py'`: authenticated inspect, failure-retention, and copy-report wiring
- Unsigned helper/runtime and main-app compilation through `tools/android-rebuild/build-apk.sh`

Host tests and compilation do not establish Android Binder, ART, or real-device UI behavior. The actual device diagnostic is still needed to identify every root entry present on that installation.
