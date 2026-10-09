# v10 three-step onboarding: independent native UI validation

Date: 2026-10-09 UTC. Scope: production Linux Compose desktop on dot's cloud desktop; isolated test HOME, data, manager workspace and loopback WebDAV fixture. This is actual mouse/keyboard UI testing, with filesystem hashes and HTTP request evidence. No real Cloudreve service, user credentials, or user saves were used. The user's separate Linux v9 installation was not accessed.

## Outcome

The bounded three-step workflow passed the scenarios below. Two misleading presentation issues found in the initial runtime were fixed by the implementation worker and replayed on the final runtime. No unresolved data-safety failure was found in this matrix. This is not an all-platform, all-network-failure or real-provider certification.

## Frozen runtime coverage

All runtimes share core SHA256 `4e6c8067647cc849aa3cd871bf4da1345f9bafa53d356981007e3e695ded4a60`. Each runtime was copied into a separate directory after its build freeze, without replacing JARs under a running app. Dependencies match the established v9 harness.

| Runtime | Desktop SHA256 | Source HEAD | Actual native UI coverage |
|---|---|---|---|
| A | `48ed0921182de47df62ef70e7ec47dcebdbfa9fae8db25775dd924615cdee1b5` | `ce51d114820c2977814dde185a0cf66af38aff2a` | Missing/default discovery, picker/cancel, three fields and 401, back/reentry/restart, local-only/defer/upload, remote-only/cancel/restore, different-content restore and upload |
| B | `1e7fedf3ab6fb79438017c460c173224593e456441a81d655cccb8b7213bc58e` | `024ebf1fb183149abe7340c6347def1f17c588c9` | Equal-content association, incomplete-cloud blocked/retry, both-empty completion and official-game first-save automatic upload |
| C | `90c93794ecab38c77f75b4e4acf3e3e1ac39f5c1148c713f7e43ae2309108b63` | `240c3ea5e35f708b05513d580e6a30a984c89039` | Fresh remote-only restore plus restart selection regression; blocked-cloud label regression |

A→B changes the home snapshot selection after verified completion/reload. B→C changes the unverified file-count label. Core remains byte-identical. The entire matrix was not rerun on C.

## Observed passes

1. **Missing default location and game.** Step 1 showed the precise isolated default path, no trusted target and disabled Continue. Discovery and a cancelled native picker did not create saves, config or cloud objects. Selecting the official JAR enabled Continue. Continue prepared only the empty default directory. Bounded automatic discovery also found the official JAR in isolated Downloads in the other scenarios.
2. **Connection and navigation.** Step 2 presented URL, username and password only. Wrong fixture password produced HTTP 401 and no persisted config or PUT. Back returned to discovery; rescan retained the verified JAR; returning to connection cleared the cancelled draft. Password was blank after restart. Loopback connection was exercised in the actual desktop network namespace, not a separate tool-shell namespace.
3. **No saves on either side.** Comparison identified both empty; explicit Enter Home recorded COMPLETE without a baseline or cloud object. Restart before a real baseline returned to comparison (PENDING) after reconnect, preventing silent acceptance of future offline-created saves.
4. **Local-only.** Recommendation appeared before any PUT. Deferring left DEFERRED and automatic upload paused. Home Upload reentered comparison without publishing. Restart retained the gate. Explicit upload produced one immutable ZIP and one commit, established baseline, entered COMPLETE and preserved local bytes.
5. **Remote-only.** A verified sole remote snapshot was recommended. Restore required explicit confirmation; Cancel left local empty, baseline absent and zero PUT. Confirm restored exact bytes, made an empty safety backup, established the remote baseline and entered COMPLETE; remote objects unchanged. Final C replay also verified completion and restart select the actual remote snapshot rather than its newer empty safety backup.
6. **Both sides different, choose remote.** Before replacing local content, the operation retained the original local bytes in a backup and independent root commit. It then wrote a resolution commit naming both prior tips as parents. Final local hash equals the chosen remote, baseline is the resolution, one tip remains and COMPLETE is set. Four PUT requests added the local ZIP/commit and resolution ZIP/commit; the original remote object and commit remain byte-identical.
7. **Both sides different, choose local upload.** Explicit upload preserved local bytes and old remote objects, adding one independent ZIP/commit. Two tips remain visible, no winner was chosen automatically, and onboarding remains PENDING for an explicit branch decision. Two PUT requests. This was deliberately stopped at the valid unresolved state; it is not claimed as completed conflict resolution.
8. **Equal content without association.** Comparison required explicit lineage association rather than guessing from matching bytes. Confirming association wrote the verified baseline and COMPLETE, with no PUT, no local content change and no backup.
9. **Incomplete cloud.** A valid commit with its ZIP absent caused BLOCKED, unavailable snapshot warning and no upload/restore controls. Recompare remained blocked. Local hash, remote files and baseline absence were unchanged; zero PUT. C displays an unverified file-count message rather than the previous misleading zero.
10. **Official-game new-save path.** Started official Shattered Pixel Dungeon 4.0.2 (internal 922), created a warrior run, entered floor 1 and moved. While the same-account game process ran, production process detection showed the running-game state; automatic writes stayed at zero and a manually requested upload failed validation. After normal game-window close, an automatic PUSH succeeded, adding exactly one ZIP and commit without another upload click. Its three payload entries match both the manifest and local hashes. COMPLETE and the new baseline persist. settings.xml was correctly outside this synchronized snapshot. This run used the production detector without an injected process guard.

Most state-matrix fixtures use a deliberately synthetic one-file game.dat with version 922 to isolate sync decisions. They were not loaded in the game. Only the both-empty/new-game scenario used real gameplay-generated saves.

## Integrity checkpoints

Synthetic local/remote hash: `83fd241f751cae591a85c9cfd73d07296afc2f1a94d3a9c2ffd8121d1e21e256`.
Different local hash retained by backup/root commit: `7052a89c534e467919a99c9f00be4ca3a0d45f4bcdfa6b94966877ecf1cc1448`.
Remote fixture root: `323ee044-0c48-453d-a12c-f726b7d5b209`.
Preserve/restore resolution: `160a0f78-4f8f-4ab5-b15b-c1dbaa08410a`.
Official-game automatic commit: `9d4e9323-b75f-483f-bb16-152f30853d96`.
Official JAR SHA256: `bcefd52a9c14f69c69d3ccc08a212cdd3830b2dc60d78d80895cd803fa1ec602`.

The original remote fixture files were rehashed after all restore/upload scenarios and remain unchanged. `evidence/both-empty-archive-verification.json` confirms actual ZIP entries, not just manifest declarations. Scenario `*-final-check.json` files include local hashes, complete parent graphs, backups, baselines, progress states and PUT counts. Snapshot files include UTC timestamps and full request logs. Counts describe each named run; restart logs are separately preserved where the launcher overwrote its current log.

## Single-window observation

The onboarding pages, inline confirmations and home shared one normal top-level sync window. The explicitly opened native file picker is an additional modal dialog. The framed black background window seen in screenshots belongs to a pre-existing Xfce terminal (ID 35657033); another maximized terminal is ID 35651587. They are not duplicate sync windows. `evidence/native-window-ownership.json` records the inventory: one normal pdsync-MainKt window plus Java content/canvas/focus child surfaces. The official game itself was a separate expected window during the gameplay test.

## Evidence guide

- `screenshots/01-missing-discovery.png`, `04b-missing-selected-jar.png`, `05b-missing-connection.png`, `06b-missing-auth-failure.png`, `09-missing-reentry-cleared.png`, `12-missing-restart-gated.png`
- `15-local-comparison-gated.png`, `16-local-deferred-home.png`, `19-local-restart-choice.png`, `20b-local-upload-home.png`
- `22-remote-only-compare.png`, `23-remote-restore-confirmation.png`, `24-remote-cancelled-unchanged.png`
- `27-different-comparison.png`, `28-different-preserve-restore-confirm.png`, `29b-different-preserved-restored-home.png`, `32-upload-keeps-both-branches.png`
- `34-b-equal-needs-association.png`, `35-b-equal-association-confirm.png`, `36b-b-equal-associated-home.png`
- `41-b-empty-comparison.png`, `42-b-empty-home-before-game.png`, `45-b-sync-while-game-running.png`, `46b-b-manual-running-block-result.png`, `47-b-official-game-resumed.png`, `48-b-real-game-before-exit.png`, `50-b-new-game-auto-upload-verified.png`
- Final C: `54c-c-remote-complete-selected.png`, `55-c-restored-restart-gate.png`, `56-c-restored-restart-home.png`, `58-c-incomplete-cloud-count-verified.png`.

Intermediate screenshots are preserved, including in-progress frames; their names alone are not proof of terminal completion. Use the final screenshots and corresponding JSON checks above. Some comparison reasons remain English within the Chinese UI; this is visible in evidence and was not evaluated as complete localization.

## Not exercised here

Real Cloudreve/provider authentication, TLS errors, timeouts, disconnect-during-write, tampered manifests, multiple live clients racing, other operating systems, packaging/installers, manual selection of an untrusted JAR, every discovery directory, and the full branch-resolution UI after the different-upload result. Unit/integration test totals belong to the implementation/review workers and are not represented as independently rerun in this native UI report.


## Final replay and cleanup

C blocked replay shows `文件数量尚未完成核验`, retains exactly the pre-replay local and remote hashes, performs zero PUT and leaves PENDING with no baseline. See `blocked-c-integrity-verification.json`. Restored-profile restart also performs zero PUT and preserves COMPLETE. All test sync/game windows and fixture server processes were closed at the end; `native-windows-after-cleanup.json` has no pdsync or Shattered game window.

The selected screenshot manifest records each unedited image, its exact runtime and SHA256. Remaining raw working screenshots stay outside this deliverable. All state/request/hash evidence is retained under evidence/.
