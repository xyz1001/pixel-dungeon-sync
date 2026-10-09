# Independent onboarding review

2026-10-09 UTC. Review of the v10 change against v9 `b9521c9`; no production code was edited by the reviewer. The independent tests remain documentation evidence and are not part of normal Gradle source sets.

## Result

**26 independent checks passed: 17 comparison/recovery checks and 9 discovery checks; zero failures, errors, or skipped tests.** See the adjacent original JUnit XML and `review.log`. The final ordinary build was also inspected: 391 core tests + 40 desktop tests (431 total) passed, with `git diff --check` clean. Native UI evidence was separately inspected as described below; these JVM tests alone do not claim UI coverage. Neither the tests nor the native checks validate real Cloudreve, Windows/macOS, or cross-device use.

The reviewer found these issues during implementation, and verified their fixes in the final source:

1. A stale BOTH_EMPTY decision could release automatic sync. Completion now repeats a guarded comparison and exact proof revalidation; already-linked completion is revalidated too.
2. Equal-content association incorrectly ran restore compatibility checks, rejecting legitimate global-only/inactive-slot profiles. Association no longer performs a restore-only check; actual replacement still does. Equal-content branch resolution uses the same distinction.
3. The first save appearing immediately after an empty decision could become the scheduler's initial clean observation. Completion now seeds the verified empty observation. A restart without a real baseline returns to comparison, covering saves created while the app was closed without inventing ancestry.
4. Selecting a trusted target from Settings could enter discovery without displaying that selection. The verified target is now retained in the discovery state.

5. After a successful remote restore, Home selected the newest empty safety backup and displayed an unrelated compatibility warning. The verified current cloud snapshot is now selected on completion and after restart; native runtime C replay verifies both.
6. An incomplete comparison displayed an unavailable local count as zero. It now says the count is unverified; runtime C confirms the label and unchanged saves/cloud objects.

**No remaining blocker was found in the reviewed scope.** Final application code reviewed is `240c3ea`. The final build passes all 431 ordinary JVM tests; the 26 independent checks remain additional. Documentation-only edits do not alter the executable artifacts.

## Native evidence inspected

The reviewer independently inspected the [native UI report](../native-ui-report.md), new screenshots and matching file/config/request snapshots for missing discovery, picker cancellation, failed authentication, Back/reentry, empty completion/restart, deferred comparison, local upload, remote restore cancellation/success, differing-content preservation, explicit identical-content association, and incomplete-cloud refusal. Screens change within the application root window; the system file picker remains an explicit exception. The recorded window ownership identifies the black background windows as Xfce terminals, not extra sync windows; final cleanup inventory contains no sync/game windows. All 39 delivered screenshot hashes match their manifest.

- Runtime A (`ce51d11`) supplied the initial matrix. Runtime B tested later selection behavior, equal association, incomplete-cloud gating, and an actual newly created official 4.0.2 game. While that game ran, uploads were blocked; after its normal exit, exactly one new snapshot was published automatically. This is a real new-game upload check, not a claim that synthetic recovery fixtures can be loaded by the game.
- Final runtime C (`240c3ea`) replayed remote-only restore, restart, verified snapshot selection, and the incomplete-count label. Screenshots `54c-c-remote-complete-selected.png`, `56-c-restored-restart-home.png`, and `58-c-incomplete-cloud-count-verified.png` show the presentation fixes. The blocked case retained local/cloud bytes, made zero PUTs, and stayed PENDING.
- Runtime C's core and desktop JARs exactly match the final build: core `4e6c8067647cc849aa3cd871bf4da1345f9bafa53d356981007e3e695ded4a60`, desktop `90c93794ecab38c77f75b4e4acf3e3e1ac39f5c1148c713f7e43ae2309108b63`. All A/B/C core JARs are identical. Earlier A/B UI evidence is not attributed to C.
- The production classpath uses `pdsync.MainKt` and contains no injected test launcher. All network operations used the isolated loopback fixture and public demo credentials.

The reviewer also independently opened all completed healthy cases' ZIPs: **10 remote object instances and 3 backup ZIPs** pass archive hashes, byte counts, full file hashes, parent completeness/cycle checks, exact baseline IDs, and final managed-file equality. See [archive check output](native-archive-check.json) and the [read-only reproducer](verify_native_archives.py). The intentionally incomplete cloud case is excluded from successful-archive assertions and checked separately for no writes.

The archive reproducer was run after the native flows finished:

```sh
python3 docs/onboarding-v10-evidence/independent/verify_native_archives.py \
  /workspace/shared/pds-onboarding-v10 \
  local remote different-restore different-upload equal both-empty remote-b
```

## Independent coverage

- Explicit equal-content association and subsequent real parent ancestry; matching descendant content advances only after explicit association; unrelated branches cannot silently associate.
- Missing remembered commits despite empty listing; missing remote objects; missing established local baseline; no interpretation of these failures as an empty cloud.
- Local content or mtime changes, remote changes, session replacement, unknown snapshot selection, and immutable comparison collection protection.
- Exact frozen retry bytes after later edits; a late sibling during local preservation returns unresolved branches without replacing local saves; an empty target can explicitly resolve multiple cloud branches without publishing an empty snapshot.
- Global-only identical saves using the actual Shattered profile implementation.
- No recursive discovery or default-folder creation; unrelated download names ignored; symbolic files/ancestors and lexical traversal rejected; four-candidate bound and multiple-target choice.
- Production static inspection of the exact official JAR; forged manifest/main-class identity is rejected as untrusted.

The source review also checked independent persisted onboarding gating, legacy baseline resume, bounded scan budgets, cancellation/lifetime reservations, session-only new credentials, and unsupported native/Steam candidates being described as search hints rather than verified installations.

## Reproduce

Requirements: JDK 21, this repository's Gradle dependencies, and the previously obtained official Shattered 4.0.2 JAR. Two tests expect the existing fixture at:

`/workspace/shared/pds-append-gameplay/ShatteredPD-v4.0.2-Java.jar`

Its required SHA-256 is `bcefd52a9c14f69c69d3ccc08a212cdd3830b2dc60d78d80895cd803fa1ec602`. It is inspected or copied to a temporary directory, never executed or included with this source evidence. On another environment, adjust only that fixture path in the two test sources; do not substitute a synthetic or different-version JAR and call that the production-trust test.

From the repository root:

```sh
./gradlew :core:test \
  --tests pdsync.OnboardingReviewTest \
  --tests pdsync.DiscoveryReviewTest \
  --init-script docs/onboarding-v10-evidence/independent/review.init.gradle \
  --offline --no-daemon
```

The original cloud run used `GRADLE_USER_HOME=/workspace/shared/pds-gradle` and `/workspace/shared/gradle-dist/gradle-8.14.3/bin/gradle`; its original sources and XML match the adjacent evidence. The reusable init script changes only the external source/report locations from that run. Run serially with other builds of this checkout. Reports from reproductions go to `reproduced-results/` and `reproduced-report/`, preserving the original XML.

All save roots, cloud stores, and discovery roots in these checks were isolated temporary fixtures. No user computer, real cloud account, credentials, or game execution was used.
