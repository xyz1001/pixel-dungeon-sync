> Historical document. For code59, the v21-M3 design and two-step setup contract in [M3_REFERENCE_CONTRACT.md](M3_REFERENCE_CONTRACT.md) supersedes older colours, geometry, account layout and setup-step descriptions below. Historical validation results do not validate code59.

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
- New or edited drafts require a current successful DAV test before Next/Save;
  edits and background transitions invalidate that proof. An unchanged connection
  matching durable endpoint, account, password, profile and provider may use
  Next/Save directly after reopening. This only starts a fresh DAV verification
  (and authenticated official entitlement check); persistence and activation still
  require success. Saved configuration never implies login or reusable DAV proof.
  Any edit, service change or new attempt consumes this saved re-entry permission;
  reverting a field cannot revive it. Missing or unreadable saved credentials fail closed.
- Passwords may be empty for custom WebDAV. Official registration uses its own
  current server rules; existing-account login has its separate rules.
- Appreciation only displays the server asset, username and expiry. Public image
  display and cached expiry never authorize synchronization.
- Failed operations or cancellation do not overwrite saved configuration.
- No passwords, URLs, response bodies or save contents are added to diagnostics.

## Remaining migration boundaries

The four ordinary setup pages now use `SharedGameSelectionScreen`,
`SharedSavePathScreen`, `SharedConnectionScreen` and `SharedComparisonScreen`.
Game identity, selected source presentation, common shell and navigation are shared;
hosts still supply actual installed-app/APK/JAR choices, verification and preparation.

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

The shared comparison slice uses `SharedComparisonScreen` and the same confirmation
modal on both platforms. Empty and verified-identical states expose one explicit
Finish; local/cloud-only states require a progress choice; multiple cloud heads
remain explicitly selectable. Both hosts bind callbacks to the current account,
page, observation and selection. A failed/expired observation becomes recheckable;
it cannot strand the user with a repeatedly failing Finish or hide a frozen upload.
Successful mutations require fresh core confirmation before completing setup.
Android retains its separate game-file restoration journal.

The shared home/history slice uses `SharedHomeScreen` and `SharedHistorySection`
on both platforms. Upload, restore, launch and settings retain their existing host
capabilities and operations. Selection uses actual snapshot IDs, survives paging
and same-profile refreshes, and is invalidated when its account/profile changes.
The default restore target is distinct from an explicit selection. Drawing or
paging does not export saves or mutate the mirror. History is ordered globally by
save-content time before pagination; same-column straight arrows use measured
card geometry. Platform adapters retain their operation and stale-callback guards.

Both settings hosts now render `SharedSettingsScreen`: bordered current-value/action
rows and a fixed Return footer. Hosts provide only supported capabilities. File and
directory selection, log export, system authorization and existing maintenance
confirmations remain platform operations. Maintenance actions are grouped behind
one entry instead of being expanded into routine settings. Retained callbacks
recheck the current page, account and capabilities before invoking those operations.

The remaining ordinary multi-head chooser now uses `SharedBranchChoiceScreen` on
both platforms. It displays real observed branch IDs and summaries, leaves selection
non-mutating, and exposes one Handle Conflict action with the common Cancel/
Overwrite/Cancel dialog. Confirmation is bound to its observation, selection and
capabilities; stale or consumed callbacks cannot resolve a new selection. Desktop
retains `checkResolution`; Android retains its actual restore capability and
preview flow. Both retain the core pending/recovery guards. Android's separately
supported local-preservation upload is not turned into an implicit branch choice.

Routine naming uses `SharedNamingDialog`, and routine conflict/operation confirmation
uses `SharedConfirmationDialog`. The ordinary four-step flow, home/history, settings,
branch chooser and routine dialogs now compile from common physical UI sources.
Remaining host UI is limited to actual system selectors, installation/Root access,
specific run-identity maintenance and system maintenance menus. Android package
installation, Root/embedded bridge access, SAF and foreground observation remain
platform capabilities. Desktop JAR selection, native directory chooser and process
launch/observation, and each platform's file/transaction operations remain host-owned.

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

The current actual desktop interaction environment is Linux, with a minimum window
width of approximately 750 pixels. Shared offscreen Compose renders also exercise
320- and 500-pixel viewports, including the branch chooser's scrolling body and fixed
footer. These are not claims of actual Android device or 320-pixel desktop-window
testing. Android lifecycle, installation/authorization and SAF interaction have not
been verified on a device in this pass; final build/test results must be reported
separately from the shared-source and offscreen evidence.

131 removes save-merge actions and previews from shared confirmation components. Secondary merge actions no longer exist; overwrite/cancel retain their observation and selection guards.
