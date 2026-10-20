# Desktop account cache isolation

## Confirmed defect and boundary

Desktop `DefaultProfileLocation.open` previously passed one shared state root to `ProfileSession`. Its profile key bound the physical save directory, game, endpoint and profile, but not username. Switching accounts with the same other fields reused known commits, observation markers and baselines. `cachedGraph()` could therefore display the previous account's verified cloud records. A fresh read against an empty second account failed closed with `UnsettledRemoteException`, classified as `VALIDATION`, rather than removing those remembered IDs.

A synthetic, offline two-account reproduction established this behavior. It does not prove ownership of any particular real-device cached record.

## Minimal fix

Only the desktop opening boundary now adds `desktop-accounts-v1/<SHA-256 identity>` above the existing game/save-directory profile scope. The length-delimited identity includes normalized endpoint, exact username, profile and provider; passwords/tokens are excluded. No account name appears in the directory name. Cloud routing remains endpoint/game/package namespace/profile. Android routing and its existing username-bound cache remain unchanged.

Old unbound caches, pending work and backups are neither deleted nor migrated. Their account ownership cannot be safely inferred. The new account-scoped cache starts empty and is rebuilt by normal verified reads. This change does not overwrite game saves or transfer anything to the cloud.

## Regression coverage

`DesktopAccountIsolationTest` failed before the fix on both the account separation and legacy-cache-isolation assertions. It covers A/B account switching, an A read completing after B opens, an empty B remote, no imported baseline/pending/history, same-account reopen and password changes, provider separation, opaque account directory names, and preservation of the old unbound directory. Its cloud fixture includes Android provenance with the official Shattered package, namespace and version 896 and is read by the desktop adapter.

Existing `DefaultProfileLocationTest` continues to check same-account history persistence in the new account boundary; game package and Android profile-version tests remain independent compatibility checks.

Validation: 33 targeted tests passed after the fix; full core 833 tests and desktop connection/home/lifetime 53 tests passed. The strengthened baseline/pending version of both new isolation tests was then rerun separately and passed. Evidence is retained in the code79 account-isolation validation bundle. No real account, server, device cache or save was used by these tests.
