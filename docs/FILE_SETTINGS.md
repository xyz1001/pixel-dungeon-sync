# Local file settings

Successful Connect saves the submitted game path, endpoint, username and plaintext
password by atomic replacement of the local JSON. This is the explicitly requested
simple storage policy. No OS credential is read, exported or migrated by the desktop
flow. Legacy configuration without a password requests it once. Authentication
failure requests correction; other connection failures retain the loaded password
for retry. Failed validation does not replace the accepted configuration. Failed
file writes keep the draft editable and report that settings were not saved.

The default workspace is `user.home/.pixel-dungeon-sync`; configuration is its
`.local/config.json`. An explicit `pdsync.workspace` override keeps isolated runs
separate. Gradle launches now use the same user workspace. An old working-directory
configuration is left intact and, when found, reported rather than silently imported.
The Windows verification launcher retains `native-jar-home` and `native-jar-appstate`.

Incomplete game selection is saved independently in `.local/setup-game.txt`. Startup
revalidates the JAR. Completed setup still requires its existing safety baseline;
password persistence never authorizes restore or bypasses comparison safeguards.

Configuration and recovery copies are ignored by Git and excluded from Gradle
archives. No configuration export feature exists. `AppConfig.toString` redacts the
password. Invalid JSON returns a generic error without its source or parser cause;
the original file is preserved, with a backup before recovery writes.

Automated coverage includes same-directory reload, changed password, pending and
completed progress, read-only loopback reconnect, authentication and server failure,
legacy missing password, corrupted JSON recovery, write failure, unverified edits,
fixed workspace selection, and invalid saved game paths. Native UI clicking remains
unavailable; model reload tests are not a claim of interactive wizard coverage.

Windows validation (2026-10-10, JDK 21.0.12.1+1, Gradle 8.14.3, offline):
core 403 tests / 5 platform skips; desktop 58 tests; zero failures or errors.
Two separate JVM processes loaded and atomically re-saved the same synthetic config.
Production entry point opened a responsive native window in an empty isolated fixture
(PID 17760, window handle 2230402), with empty stderr, then was stopped. Existing user
configuration was not read or used for this smoke check. Both built JARs were inspected
and contained no configuration files. No dependency downloads or real-service tests.
