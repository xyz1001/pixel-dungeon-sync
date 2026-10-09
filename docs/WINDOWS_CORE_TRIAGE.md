# Windows core regression triage

2026-10-09, Windows Frank only, portable Temurin 21 and Gradle 8.14.3. All work
after dependency preparation used `--offline` and synthetic temporary files.

## Original 16 failures

| Suite | Test | Root cause / action |
| --- | --- | --- |
| DefaultProfileLocationTest | fixedRootWinsOverLegacyConfigAndRetainsSameRootHistory | Linux policy given a Windows temporary path; host-native fixture |
| DefaultProfileLocationTest | unrelatedManualScopeStaysSeparateAndUntouched | Same fixture mismatch |
| DefaultProfileLocationTest | explicitPreparationRejectsAncestorSymlinksAndExistingFiles | Same mismatch; link the first actual native ancestor |
| DefaultProfileLocationTest | missingDefaultDoesNotCreateOrFallBackToManualConfig | Wrong path-policy exception masked the intended missing-directory assertion |
| DefaultProfileLocationTest | settingsOnlyDirectoryHasClearMessageAndNeverCreatesFakeSave | Linux policy given a Windows temporary path |
| DefaultProfileLocationTest | explicitXdgAndFutureAdapterAreInjectableWithoutHostReads | XDG is a Unix policy; test it lexically with Unix paths, keep adapter filesystem test native |
| DefaultProfileLocationTest | explicitPreparationCreatesOnlyEmptyDefaultDirectory | Linux policy given a Windows temporary path |
| InstalledGameDiscoveryTest | defaultSaveRejectsFileAndSymlink | Linux policy given a Windows temporary path |
| InstalledGameDiscoveryTest | noHitsDoesNotCreateDirectoriesOrReadHostHome | Same fixture mismatch |
| InstalledGameDiscoveryTest | defaultSaveIsDetectedIndependentlyOfGameJar | Same fixture mismatch |
| InstalledGameDiscoveryTest | unreadableDirectoryIsReportedAsIncompleteRatherThanNotFound | Windows filesystem lacks POSIX permission view; retain test with explicit prerequisite |
| GameProcessDetectorTest | relativeSelectedJarResolvesAgainstActualProcessCwdWithSpaces | Linux detector fixture used native Windows paths; host-native detector mode |
| GameProcessDetectorTest | sameNamedSecondInstallationCannotBeAssumedUnrelated | Same fixture mismatch caused basename association mismatch |
| GameProcessDetectorTest | windowsJavawPathsAreCaseInsensitiveAndResolveDotSegments | Fixed fictional path does not exist on Windows, where real-path checks correctly fail closed; use a real temporary JAR |
| SaveStabilityGuardTest | stableContentUsesDefaultTwoSecondObservationAndDeterministicHashes | Real product issue: stale Windows enumeration timestamp; read fresh NOFOLLOW_LINKS attributes |
| SaveStabilityGuardTest | sameSizeWriteWithRestoredMtimeStillChangesFingerprint | Same cached directory timestamp issue |

Path, discovery and process detection product logic was not loosened. No test was
deleted. The stability fix keeps content and metadata comparisons, limits and
symlink rejection. Two new regressions cover fresh attributes on 20 fixture
trees and rejection of a directory-only timestamp change. This JDK/filesystem
returns null file keys even on direct reads; identity is not guaranteed there.

## Skips

The original four skips are intentional platform prerequisites:

- GameProcessDetectorTest.liveLinuxInventoryObservesHarmlessFixtureAndItsNaturalExit:
  Linux `/proc` process integration test.
- SecretStoreTest.realProcessRunnerUsesPrivatePipesForOwnedFakeSecret:
  requires `/bin/cat`.
- SecretStoreTest.realProcessRunnerTimesOutWithoutLeakingInput:
  requires `/bin/sleep`.
- SecretStoreTest.currentLinuxWithoutHelperFailsClosedForOwnedFakeCredential:
  specifically requires Linux without Secret Service helper.

One additional explicit skip is the retained POSIX permission-denial test above.
It is not a passing Windows ACL-denial test; that native integration is pending.
No OS security settings were changed to make tests pass.

## Final offline result

Core: 393 tests, 0 failures/errors, 5 explicit skips. Desktop: 50 tests, 0
failures/errors/skips. Thus 438 tests executed successfully; production classes
and runtime classpath also built. Component tests do not establish native UI
interaction, real Cloudreve, real-game loading or other-OS acceptance.

The earlier UI changes remain separate commits: discovery wording `32cec88`,
and native chooser `9c9d00c`. The latter uses AWT FileDialog (platform peer), not
Swing JFileChooser, with parent ownership, LOAD/single selection, `*.jar` pattern,
post-selection extension checking and cancellation preserving the old target.
Its four new tests cover filtering, cancel, paths/invalid extension and repeated
owned modal dialog construction/disposal; they do not drive real native clicks
or prove focus behavior. AWT FileDialog exists cross-platform, but macOS/Linux
native dialogs have not been exercised in this Windows task.

Native UI automation remains blocked by the unavailable node_repl/@oai/sky
runtime required by the installed Computer Use skill. No alternate mouse,
keyboard or screenshot automation was used. Enabling that existing native
runtime is the minimum prerequisite to continue the real UI matrix.
