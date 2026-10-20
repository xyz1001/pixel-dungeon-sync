# Reconstructed code90–92 HOME integration

This is a reconstruction on the independently SHA-verified code89 baseline. It is not the lost original code92 source, and newly added or adapted tests are not recovered original tests. No code93 work, new controls, new user-facing prompts, version changes, signing, device operations or server operations are part of this reconstruction.

## Android evidence and implementation

MainActivity methods were recovered as narrow edits to the trusted code89 Java. Production code92 DEX and JADX references under the separately retained evidence directory support:

- `readComparison(boolean, boolean)`: HOME metadata preview only for a completed foreground HOME; full inspection after operations. DEX offsets 0x14a–0x18a close the identity scope before publishing even a BLOCKED inspection. JADX's synthetic try/finally control flow is incorrect and was not copied.
- `drainAutomaticHistoryRefresh`: metadata HOME inspection under the identity scope. The reconstructed implementation holds observation/history in locals until close succeeds; a failed close cannot publish successful fields. Applying an error clears the former HOME owner as well as inspection/history/proof.
- RESTORE and RESOLVE invoke `withRestoreReadScope(Callable)` around the entire existing operation, including full post-action comparison and onboarding completion. Other actions retain their prior route.
- `comparisonCopy`, `mayRestoreWithoutReplacementConfirmation`, `verifyLaunchInspection`, launch proof gates and history rename with the captured inspection preserve the shipped code91 action/display behavior. Metadata equality only selects the existing restore route; the facade still performs full validation before a write.
- Queue wait, global operation-lock wait and UI completion phases aggregate into the existing FINISHED diagnostic record. No extra stage-log writes or new UI were added. FINISHED follows UI phase closure for success, failure and destroyed-Activity completion.
- SharedHomeContent and HistoryPresentation use `verifiedCommitIds` before claiming no transfer, protection or current-content matching. Existing sorting, labels, timeline layout and conflict dialogs remain unchanged.

The fixed installed-helper two-second wait and repeated game-running/stability checks were not edited by this UI recovery.

## Desktop reconstruction boundary

Android DEX does not contain Desktop Main.kt. Desktop integration is reconstructed from code89 host flow plus the recovered shared core APIs and stated code91 behavior:

- HOME remote reads use `readHomeHistory`, followed by `syncHistory(observed)` for the exact owning session. This does not download or remember ZIPs or create a baseline.
- Manual upload invokes the DEX-evidenced `AppendProfileSession.pushFromHome`. `HomePushResult.Unchanged` updates existing display state and skips automatic-baseline establishment. Different content keeps the original push/branch behavior.
- Local refresh, local naming and local-only capture/archive refresh preserve the captured session-owned metadata graph instead of replacing it with a verified-object cache that may be empty.
- Successful network actions retain their full verified post-action history instead of immediately forcing another metadata read.
- The code89 automatic scheduler is preserved. A metadata-ready HOME may schedule an automatic check, but only `automaticPush`'s new full comparison, `AutomaticUploadEligibility`, and full revalidation authorize publication. Metadata does not become an action proof, establish a baseline, or get passed into an automatic write. No speculative persistent proof state or new launch I/O was introduced.

## Newly reconstructed validation

- Production Android read methods execute against deterministic host ports: identity-close failure on normal/BLOCKED HOME leaves inspection/history/owner empty; metadata cannot acknowledge full-verification status; action refresh is full.
- Production automatic-read block executes with success/BLOCKED/failed-close cases; successful observation fields stay unpublished until close succeeds.
- Production RESTORE/RESOLVE wrapper and operation execute with success and failed refresh, proving scope spans the action, full refresh and onboarding completion and is closed once; PUSH retains its original route.
- Production worker/UI completion methods execute with queued UI, callback failure, work failure, render failure and destroyed Activity, verifying one FINISHED record after all timing scopes close.
- Desktop tests cover zero-object HOME reads, full-verification zero-PUT upload with no baseline, positive different-content branch creation, owner isolation, corrupt-object rejection, and automatic full verification after metadata including new branches and corrupt data.
- Existing code89 source contracts/fixtures were adapted to recovered method signatures and explicit verification IDs without relaxing their safety assertions. Full Python host suite logs and centralized JVM results are retained separately.

These tests are host/JVM evidence, not device timing or live-account/network acceptance evidence.
