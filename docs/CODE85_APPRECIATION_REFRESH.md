# code85 / 0.5.64-test — appreciation expiry refresh

Baseline: code84, commit `036f5afe7b36895814b4279f6244b7ef01024e68`.

## Scope

The authorized Refresh / 刷新 action is wired to the existing shared appreciation controller, for the standalone page and inline onboarding appreciation. Android and Windows compile the same shared UI. Existing button styling and existing error notices are reused; there is no new payment flow, success dialog, background poll, automatic sync request, or credential persistence.

The existing refresh reads public appreciation metadata/image and authenticates the private `GET /api/account/entitlement` using the existing Basic credentials. It never calls registration/login, writes a server expiry, tests DAV, or saves/activates configuration. Finite expiry normalization remains exclusively at the HTTP parser boundary (subtract one UTC calendar year once); unlimited and unknown expiries retain their established semantics.

The flow rejects duplicate clicks while busy. Closing, Back, changing accounts, and source/client generation changes discard late results. Only a current successful authenticated response updates trusted expiry and account state; a failed read does not manufacture expiry or renewal.

Android home reads the existing trusted account cache. Its existing Upload/Restore actions now additionally require confirmed same-boot non-expired metadata; pending/failed explicit refreshes mark process-local UI uncertainty until a new authenticated result arrives. This never changes the displayed expiry, grants a sync lease, persists credentials, or overrides existing local capability/save conditions. Desktop receives current authenticated account facts through a lifecycle-checked host callback to update its display deadline. This display state is identity-scoped and cannot create a sync lease. Every remote operation still calls the original fresh authorization path; newer operation-confirmed expiry supersedes older display evidence. Configuration completion and password storage behavior are unchanged.

## Verification

Added controller tests for duplicate clicks, latest expiry, expiration/renewal, 503 failure, late Back/account/close callbacks, Basic GET-only requests and no DAV/config writes. Desktop tests distinguish fresh display state from per-operation authority. Render tests exercise standalone/Step 3/Step 4, Android image-save and desktop variants, 320/500/1165 widths, English narrow layout, busy/disabled/error states and the bilingual large-font matrix.

The build report accompanying this source records executed checks and final package hashes. Offscreen Compose rendering is host verification, not Windows execution or Android device verification. Device installation/game verification is separate.

The legacy standalone `run-managed-account-flow-tests.sh` includes pre-code84 source-string assertions (for example `hasOfficialAccess` in configuration completion) which fail on the unchanged code84 baseline as well. This release does not change production authorization/configuration behavior to satisfy those obsolete assertions.
