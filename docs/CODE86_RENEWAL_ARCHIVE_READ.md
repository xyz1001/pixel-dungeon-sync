# code86 / 0.5.65-test: renewal archive reads

Based on code85 (0a11f4eaf1fef5ddfebaf12d6392dc2286ee689d).

## Fix

A fresh account response on the appreciation page previously updated entitlement information without reopening the failed/unavailable HOME archive observation. Returning through Settings therefore left local/cloud history unverified.

Android and Windows now retain a memory-only, account-scoped, one-shot recovery request. A current confirmed usable account result marks it ready; failures or still-unavailable accounts cancel it. The read starts only on HOME, including navigation through Settings, using the existing loading overlay. Duplicate refreshes coalesce. A fresh local/cloud snapshot replaces failed observations. Page/account/selection changes and canceled or late results cannot publish into a different scope.

The recovery path avoids cloud namespace provisioning, upload and restore. Android uses existing save-access readiness without requesting new Root authorization or package preparation. Windows suppresses automatic work from the old observation while rebuilding the read-only baseline. Existing manual actions and their authorization gates remain unchanged. No new controls or messages are introduced.

## Verification

Production-path host tests cover active refresh then HOME read once, Settings transit, repeated refresh, unavailable/failure results, account/selection isolation, readiness gating, fresh facade ownership, canceled/hidden reads and no namespace preparation. Shared controller tests cover accepted versus stale callbacks and retained-host delivery. Desktop tests cover one-shot account recovery and existing archive-read wiring. Full JVM and Android host checks are reported in the release verification artifact; phone/server behavior is not claimed by these host tests.
