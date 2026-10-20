# code66: remove all bottom access-mode notices

Version: 0.5.45-test / 66. Baseline: code65 / 0.5.44-test.

The Android game-selection/access-mode flow no longer passes bottom notice or warning text to the shared screen. The shared screen additionally omits both notice surfaces whenever access-mode cards are present, including stale/nonempty host observations. This covers preparation, waiting, installation incomplete, cancellation, success, failure, Root ungranted/denied/probe failures, compatibility and recovery warnings without matching individual strings.

Mode cards and their existing statuses remain. Existing actions, recovery actions, progress indicator, capability checks, real installation/Root verification, internal outcome/diagnostic records, and code65 external-installer return/cancel handling are unchanged. No new controls, prompts, replacement notices, or alternative-position messages are added. Native system installer and Root-manager dialogs are untouched. Desktop discovery (no access-mode cards) and unrelated pages retain their existing notices.

Regression coverage includes 72 actual Compose renders (3 access modes × 12 representative messages × 2 widths), four statuses, busy/error transitions, no notice/warning semantics or layout nodes, existing cards, independently gated Next, and unchanged desktop diagnostics. Existing cancellation, pending-install actions, repeated-click, foreground/back and stale-observation tests remain. Production Android adapter contract verifies empty message fields and no old notice source selection. Full Android Python/host/Gradle results and artifact fingerprints accompany the build. Host and shared-render tests do not replace Android device QA.
