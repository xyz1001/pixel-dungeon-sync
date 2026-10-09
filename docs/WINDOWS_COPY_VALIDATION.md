# Windows onboarding copy validation

2026-10-09, Windows Frank. Based on `92c9d6bbe7610b72286e2437807f1607564b53a6`.

The first step now distinguishes a trusted selected game, an incomplete scan,
multiple candidates, untrusted candidates and no candidates. Directory existence
is explicitly separate from save content verification in step 3. This change
does not modify synchronization, authentication or discovery decisions.

## Results

- Temurin JDK 21.0.12.1+1 and Gradle 8.14.3; desktop production classes compiled.
- All 46 desktop tests passed offline, including 6 new copy regression tests.
- The unchanged core suite ran 391 tests: 16 failed and 4 skipped. Failures were
  in DefaultProfileLocationTest (7), GameProcessDetectorTest (3),
  InstalledGameDiscoveryTest (4), SaveStabilityGuardTest (2). Several fixtures use
  Linux paths or POSIX permissions on Windows; process and stability failures
  require separate investigation. This is not a passing full Windows suite.
- Production `pdsync.MainKt` ran with isolated HOME/user.home and application
  state, without WebDAV configuration. PID 6904 exposed window handle 920130,
  title `像素地牢 · 存档同步`, and remained responsive after its launch command ended.
- No game was run and no real saves or credentials were read. The missing save
  directory remained absent after startup. No screenshot or visual assertion
  was made: the required native automation runtime was unavailable. AWT emitted
  shell-folder warnings under the isolated profile; window interaction and
  native file selection still require validation.

## Bounded dependency acquisition

Times are UTC. JDK: 11:23:49–11:24:27, 205,073,461 bytes. Gradle:
11:24:45–11:25:14, 137,393,837 bytes. Both ZIP checksums were verified.
Dependency resolution/build: 11:36:25–11:41:36, with 137,512,444 bytes of
dependency artifacts/metadata cached. The complete Gradle cache was 419,961,011
bytes including generated files. These are file sizes, not exact wire traffic.
No repeated JDK/Gradle download or production synchronization was started.
