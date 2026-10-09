# Automatic process checks and stable captures

## Scope and limits

The desktop no longer needs a manual “game quit” checkbox. `GameProcessDetector` queries Java 21 `ProcessHandle` twice per status request and returns `Stopped`, `Running(pids)`, or `Unknown(reason)`. `Unknown` blocks capture, upload initiation, and restore. The selected game must be an already validated trusted official JAR; a missing/untrusted selection never implies stopped.

This is **current OS account, visible-process detection**, not a universal statement that no possible writer exists. Account identity comes from `ProcessHandle.Info.user()`, not `user.name` or an environment variable. Positively different owners are outside the default per-user save-directory scope and their argv is not requested. Missing owner information never excuses an unreadable Java/game candidate. A known unrelated non-Java executable can be ignored even when its owner or argv is unavailable; a live process with no visible executable is `Unknown`. Self is excluded by PID. No process is killed, attached, or launched by the production detector; no environment or full `commandLine()` is read, and argv is not logged.

Oracle documents that process lists are snapshots and that executable/argument/user data may be unavailable or platform-dependent. Containers and process namespaces can hide processes entirely. In particular, a syncer isolated from the actual game in a container must not be treated as proof that the desktop game is stopped. Elevated/other-user writers, invisible or arbitrarily renamed native launchers, a new process starting just after a check, Steam Cloud, and other background writers are outside a global process-exclusion guarantee. File consistency checks reduce these risks but do not establish mutual exclusion.

Sources: [ProcessHandle](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ProcessHandle.html), [ProcessHandle.Info](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ProcessHandle.Info.html).

## Supported launch observations

- Selected JAR launched with `java`/Windows `javaw` and visible `-jar` arguments: canonical absolute identity is `Running`.
- Linux relative `-jar` paths: resolved against that process's `/proc/<pid>/cwd`, including paths containing spaces and canonical aliases. A missing cwd prevents resolution and returns `Unknown`.
- Windows: drive/UNC paths, separators, dot segments, executable and path case are normalized; native Windows additionally resolves the real path. Relative paths without a visible absolute cwd are `Unknown`.
- macOS: canonical absolute JAR paths and the known main class are supported; no independent cwd query is claimed.
- Known `com.shatteredpixel.shatteredpixeldungeon.desktop.DesktopLauncher` through the classpath/module launcher is `Running`. Arguments belonging to another parsed main class are not mistaken for a Java launch target; this avoids matching a syncer/test argument merely mentioning the JAR.
- Every other `-jar` launch is conservatively `Unknown`. A different filename cannot prove that the official game JAR was not copied or renamed. Java argument files are not opened; unknown flags, missing/incomplete argv, unresolvable paths, or inventory errors are also `Unknown`.
- Linux's JDK may omit argv for a long classpath. Only when the JDK already identifies `java`/`javaw`, both process/current ownership are positively visible and identical, and JDK argv is absent, a bounded `/proc/<pid>/cmdline` fallback may supply arguments. It requires strict UTF-8, complete NUL termination, at most 2 MiB, at most 16,384 arguments, and 32,768 characters per argument. Kernel PID/start ticks must agree before/after; the live JDK handle's owner, executable and start instant must also remain unchanged. Missing/denied/malformed identity or data fails closed. `argv[0]` never establishes executable identity; command/owner denial is not worked around. No raw argument values are logged.
- Recognizable Shattered native package names/paths and nonstandard wrappers with visible Java/game launch arguments are `Unknown`, not verified native support. Native package identification/version validation remains unimplemented.

## Stable-save boundary

`SaveStabilityGuard` works on the existing adapter's allowlist. It uses the existing bounded `Snapshots.capture` path/content checks, then compares per-file SHA-256 hashes plus file size, full modification time, filesystem file key, and included-directory metadata. It does not compare random snapshot IDs, ZIP bytes, capture timestamps, or excluded device settings. Metadata enumeration is bounded; files remain subject to the existing 64 MiB/4096-file limits.

Default production quiescence is 2000 ms. A capture requires:

1. Fresh stopped observations and two equal fingerprints across the quiet interval.
2. Another stopped/fingerprint check immediately before the actual capture.
3. Actual captured content equal to the quiet fingerprint.
4. Another stopped/fingerprint check after capture, before returning bytes for history or upload.

`captureStable(...)` returns both the exact snapshot and its quiet fingerprint. Profile restore saves that fingerprint from the actual pre-restore backup, writes/registers the original backup, retains existing process/hash checks, and calls `requireUnchanged` immediately before the first deletion. This callback runs outside rollback: a changed file or mtime cancels without restoring stale backup content over an external writer's newer files. Existing root locks, dirty-baseline/conflict logic, backup history, and rollback behavior remain intact.

Process restart or fingerprint drift throws `SaveNotStableException`, allowing automatic work to wait for a new safe observation. Invalid paths, symlinks, oversized data, and archive validation failures remain hard failures. No guard can eliminate a last-instant malicious path replacement or simultaneous writer after the final check; this is not a power-loss transaction or an OS-level game lock.

## API and tests

- `GameProcessDetector(target).status()` / `.isStopped()` always recompute the inventory.
- `SaveStabilityGuard(root, adapter, stopped).fingerprint()` is a read-only observation, not proof of stopped/quiescent state.
- `.awaitStable()`, `.requireUnchanged(fingerprint)`, `.capture(...)`, `.captureStable(...)` enforce fresh process checks.
- `SaveFingerprint.files` is the deterministic content map; `.metadata` supplies the stronger consistency check.
- Capture context providers run immediately before actual capture, after waiting, so capture provenance is not backdated by the quiet interval.

Focused validation on 2026-10-09 passed all 41 tests: 21 detector, 13 guard, and seven Linux argument-reader tests. Cases cover running/unknown/empty/incomplete inventories, multiple Java processes, observation-time spawn/exit, missing argv, current/other/missing owners, Windows case/path handling, macOS absolute/relative behavior, renamed JARs/wrappers, same-size changed bytes with restored mtime, metadata-only changes, changes during and after actual capture, excluded files, symlinks/size limits, and thread interruption. Seven additional Linux argument-reader tests cover NUL/UTF-8 parsing, caps, long argv, denied reads without alternate routes, PID reuse/starttime mismatch, and the positive current-owner gate.

One Linux integration test compiles and starts a harmless two-second fixture JAR in a path containing spaces. It now includes a long classpath exceeding the observed JDK argv buffer. The real inventory recovers complete relative `-jar` argv and Linux cwd; the detector reports `Running`, while an injected denied fallback stays `Unknown` when JDK argv is absent. The fixture exits naturally and a fixture-PID-filtered inventory then reports `Stopped`. It never starts the game or signals existing processes. This proves the same-namespace JDK API path, **not real desktop game detection**. A separate shell sandbox probe exposes only that command's process namespace, so its stopped result is not evidence about existing desktop game windows. Windows/macOS tests inject inventory data; native platform execution and native package detection are not claimed.

An actual GUI-namespace probe subsequently found two separate limitations: a readable Java command with 8,482 bytes of complete kernel argv missing from JDK info, and a live current-owner process whose executable is inaccessible (`AccessDeniedException`). The first motivated the constrained long-argv fallback above. The second still produces `Unknown` in production and blocks save actions. It is not a zombie, is not ignored, and was not reidentified through an alternate route after denial. This cloud GUI environment therefore does not establish a usable successful production auto-upload flow; injected UI fixtures must remain clearly labeled as fixtures.
