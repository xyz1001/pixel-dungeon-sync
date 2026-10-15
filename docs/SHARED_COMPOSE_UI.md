# Shared Android and desktop UI migration

## Baseline and goal

At `9732b799`, desktop rendered Compose from `desktop/Main.kt`, while Android
rendered native Views from `android-app/MainActivity.java`. Only the synchronization
core and a few presentation models were shared. The HTML/JavaScript prototype is
an interaction reference, not a production client. Official login, registration,
entitlement and appreciation were added to Android but were missing on desktop.

The target is one source of pages, state transitions and account behavior. Android
and desktop retain adapters only for platform capabilities. Existing synchronization,
restore transactions, conflict rules and signing identities are not replaced.

## First vertical slice

`shared-ui/src/main/kotlin` is the authoritative source directory for the connection
step and appreciation subpage. Both application Gradle source sets compile these
same files with the Compose compiler. Android resolves Android Compose artifacts;
desktop resolves desktop Compose artifacts. Desktop Skiko binaries must not enter
the APK. This JVM/Android source-sharing arrangement does not require rewriting
the JVM synchronization core into a multiplatform library.

Platform-free official account clients and policies live in `core`. Their existing
Java package is retained during extraction to preserve tested package-level APIs;
the package name does not imply an Android runtime dependency. There is only one
physical implementation of each client.

The shared slice owns service choice, form drafts, login/registration state,
connection proof, cancellation/stale-result handling and appreciation display.
Platform adapters supply configuration persistence, DAV setup through the existing
core, cache location/clock, clipboard and application navigation callbacks.

The Android main app gains an unsigned release Gradle build. The independent
game bridge remains Java and keeps its existing build and verification path.
Root native libraries, the bridge DEX and API-26 apksig compatibility artifact are
explicit build inputs. Signing remains outside source control.

## Preserved behavior

- Fresh setup starts with “使用官方服务” and “自定义 WebDAV”.
- Existing connection edits preserve their values and permit changing service.
- Login and registration share the deployed protocol and never silently retry
  registration. Unknown registration outcomes do not create another account.
- A current successful DAV test is required before Next/Save; edits invalidate it.
- Passwords may be empty for custom WebDAV. Official registration uses its own
  current server rules; existing-account login has its separate rules.
- Appreciation only displays the server asset, username and expiry. Public image
  display and cached expiry never authorize synchronization.
- Failed operations or cancellation do not overwrite saved configuration.
- No passwords, URLs, response bodies or save contents are added to diagnostics.

## Remaining migration boundaries

The shared save-path slice uses `SharedSavePathScreen` and its capability-gated
state/actions on both platforms. Android supplies its PackageManager/Root path
and an existing guarded export preview; desktop supplies its editable path and
native folder chooser. Missing statistics remain nullable internally, while the
shared wording displays “已进行0场游戏”. A preview from an older draft or page
cannot replace the current preview. Ordinary reads permit Back and register at most
one queued Next; only current foreground/page/selection results may advance.
Failed reads stay failed until an explicit retry. A safely absent desktop directory
is a typed preparation state, not an empty preview, and is created only after Next.
Unrecognized nonempty directories remain failed. Both hosts render the reference
shell and the same Next/Waiting/Retry footer. No shared screen performs filesystem
or network operations. See `UI_REFERENCE_CONTRACT.md` for the visual authority.

This is not completion of the full shared UI. Discovery/game selection, comparison,
home/history, conflict/confirmation and settings still need to move to common
Compose pages and shared presentation state. Android package
installation, Root/embedded bridge access, SAF and foreground observation remain
platform capabilities. Desktop JAR selection, native directory chooser and process
launch/observation remain desktop capabilities.

Follow-up pages must retain the approved four-step interaction and later explicit
product changes, including a single Finish action for verified identical content,
“已进行x场游戏”, cloud/local history columns and concise routine text. The prototype's
simulated content IDs and publication ordering must never replace real core proofs.

## Acceptance

Each migrated slice must compile for both targets and exercise the same controller
tests. Required cases include Back/Cancel, field edits, double clicks, account
switches, late asynchronous responses, failed persistence, empty custom passwords,
registration recovery and authenticated entitlement plus DAV gating. Android
rotation/background behavior and native selectors require actual platform testing;
host tests and compilation alone are not device validation.
