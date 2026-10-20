# code88 / 0.5.67-test: conflict chooser dialog

Based on code87 commit 6ac4a8409fdb2bed113f005b249ccccc50730f7c. Keeps its HOME history refresh and the code86 renewal read flow.

The existing observation-bound branch chooser is presented in a modal over an inactive HOME view on Android and Windows. Ordinary upload keeps its existing execution path; this release does not add a pre-upload confirmation. Branch selection, destructive confirmation, merge and preserve-local choices retain their existing semantics and localized strings.

Cancel/Back returns to HOME; busy state prevents dismiss/re-entry. Current account/session/observation and page lifecycle must still match before host callbacks can act. The modal itself only renders existing observations; it does not read or write cloud data.

No server, expiry, login, Root authorization, package preparation or sync-engine protocol changes. Tests use synthetic observations and isolated temporary stores; no real save upload or restore is performed during validation.
