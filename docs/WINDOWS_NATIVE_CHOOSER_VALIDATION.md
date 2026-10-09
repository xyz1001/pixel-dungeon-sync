# Native game file chooser on Windows

2026-10-09, Windows Frank; follows the separate discovery-copy commit `32cec88`.

Both onboarding and settings now use JDK AWT FileDialog, which delegates to the
platform native file chooser, instead of Swing JFileChooser. The main wizard
remains a single window. No dependency was added.

The dialog is LOAD-only, single-selection, and owned by the current application
frame. Every opening creates and disposes its own dialog. A `*.jar` filename
pattern is supplied for Windows, where JDK FilenameFilter is not implemented;
other platforms also receive a case-insensitive FilenameFilter. A returned
non-JAR filename is rejected before selection changes. Cancel returns null,
preserving the existing target through the unchanged caller guards. Existing
trust validation still applies and no game is executed.

## Verified

- Offline production compilation and all 50 desktop tests passed, including
  four new tests for extension filtering, cancellation, returned paths with
  spaces, and fresh owned modal dialog construction/disposal on repeated use.
- Production MainKt started with isolated HOME/user.home/appstate. The final
  instance was PID 21288, window handle 394478, title `像素地牢 · 存档同步`, responsive
  after the launch command returned. The earlier instance was no longer running.
- A synthetic `game1/game.dat` under the isolated Windows default path remained
  byte-identical after startup. No WebDAV config was created, no game was run,
  and no real save or credential was read. Only the isolated app was left running.
- No new dependencies were downloaded for this change.

## Boundaries and remaining UI matrix

The available environment does not expose the node_repl/@oai/sky runtime required
by its Windows Computer Use skill; browser CUA disables native app control.
Therefore real dialog opening/cancel/selection, focus return, repeated clicking,
and visual text layout were not driven or verified. The tests above construct
dialogs but do not show or interact with the native chooser.

The full actual UI matrix remains pending: navigation/cancel/repeated clicks,
missing/multiple game candidates, existing/empty save content, required WebDAV
fields and connection failures, stale results after navigation, comparison
branches, home button states, retries, restart persistence and game-running
protection. Existing automated tests passed for connection page lifetime (10),
onboarding comparison (25), onboarding config (4), automatic upload (12),
metadata (16), append-only WebDAV (16) and namespace creation (30). These are
automated component/protocol coverage, not actual UI or real Cloudreve coverage.

The unchanged core suite's 16 Windows failures and 4 skips remain as recorded
in WINDOWS_COPY_VALIDATION.md. No passing full Windows or other-OS claim is made.
