# Optional root direct-save source module

This is an independent, **read-only** Android `app_process` job and a pure-host test suite.
The established embedded bridge is unchanged. Nothing here has been executed under
Android root or on a phone. SDK compilation and injected host tests are not runtime
verification or evidence that a particular root manager/SELinux policy permits it.

## Public boundary

Entry point: `io.github.xyz1001.pdsync.root.RootRunner`, with **no arguments**.
A normal-UID caller starts it explicitly after root authorization, sends exactly one
`RootProtocol.Request`, closes stdin, receives exactly one reply, and waits for EOF.
The runner verifies actual `Os.geteuid() == 0` before PM or target filesystem access.
It exits after the job, including framework/Binder threads, and has an unconditional
60-second process watchdog. There is no socket, daemon, network access, credential
input, target-app launch, force-stop, arbitrary shell command, or writable save API.

Commands are `PROBE`, `INSPECT`, `EXPORT`, and `OBSERVE`. `RESTORE` and `RECOVER`
return `UNSUPPORTED` without touching the target. Pure restore research, if present
in a separate subpackage, does not enable either command or provide an Android writer.

`RootBinding` contains the caller-selected package, Android user, application UID,
PM `dataDir`, source identity SHA-256, and exact installed base/split APK path/hash set.
No arbitrary save-root field exists. The runner independently queries PackageManager
for that one package/user and hashes the entire installed APK set before and after
read work. APK hashes bind the signer and manifest bytes too. The source identity is
an opaque caller-side namespace token, not a claim the runner independently derives.
Replies echo the fingerprint of every supplied binding field. Root identity must not
become a WebDAV credential or permission grant.

Only application UIDs (appId 10000–19999) are accepted. PM `dataDir` must be exactly
`/data/user/<user>/<package>` or the user-zero `/data/data/<package>` alias. Only
`files` beneath that root can be read. The system-controlled parent may resolve to
these known aliases; the package component, `files`, and every internal child are
opened with `O_NOFOLLOW`. Directory traversal is anchored to pinned parent file
descriptors through `/proc/self/fd`. If the kernel, SELinux, or OEM blocks that route,
there is no unsafe unanchored fallback. The original PM path is reopened and its
identity compared after work; validating an obsolete but still-open FD is insufficient.

Data/files/transferable entries must have the bound UID owner, no world-write or
set-id/sticky bits, and no foreign-group write. Directories must be real directories;
files must be regular with exactly one hard link. Trusted system ancestors must be
root/system owned and not writable by untrusted groups or everyone. Installed APKs
are likewise read-only, regular, single-link, root/system-owned files in fixed install
prefixes, with a 512 MiB total bound. Unrecognized OEM installation layouts fail closed.

## Save policy and stability

The module compiles the existing public `SaveArchive` and `SaveDirectoryPolicy`
helpers unchanged (plus their `BridgeFailure` dependency). It preserves exact
save allowlists and regular-file-only runtime marker exclusions. A directory or
symlink named `profileInstalled`, `profileinstaller_profileWrittenFor_lastUpdateTime.dat`,
or `keybinds.dat` is not silently excluded. Unknown layouts are never called empty.

Limits are 64 MiB expanded save data, 64 MiB output ZIP, 4096 files, 16384 entries,
and 16 components. Partial `gameN` runs, case aliases, unsafe names, symlinks, devices,
FIFOs, hardlinks, owner/mode changes, growth and short reads fail closed. Each read
compares pre-open, descriptor, and post-read identities. Export performs full hash
inventories before and after a two-second quiet interval, captures safe bytes in
memory, builds a bounded ZIP, repeats the inventory, and rechecks PM/root binding.
It creates no game files, markers, staging directories, backups, or journals.

The existing `SaveArchive.write(File, ...)` necessarily reopens path ancestors, so
this reader does not call it. A small in-memory ZIP writer consumes already safely
read bytes, uses the shared bounds/hashes/allowed names, and preserves the 22-byte
empty ZIP needed on API26. Response writing streams metadata then archive directly,
without copying the entire archive into another protocol payload. Capturing/packing
still uses bounded memory and can fail on devices with insufficient heap; no partial
response is promoted to a valid export. Repeated stable reads are not an atomic kernel
snapshot or a guarantee against an adversarial writer that changes and reverts bytes.

`PROBE` requires the PM root and `files` directory to exist. Missing original-game
storage returns `MISSING_DIRECTORY` and means to launch the game first; it does not
mean a root manager denied authorization. `INSPECT` can report stable content while
lifecycle state is unknown or busy. Its OK status alone is not export readiness.
`EXPORT` additionally requires reliable CACHED or ABSENT observations around scans.
Restore/recovery capability is always false.

## UID lifecycle observation

The only spawned command is the fixed argument vector:

`/system/bin/cmd`, `activity`, `get-uid-state`, `<bound decimal UID>`

There is no shell command supplied by the caller, process-list scan, all-app scan,
PID matching, or PID-as-foreground inference. Output is capped at 4096 bytes, timeout
is three seconds, and every unknown/malformed/failed/timed-out result is unreliable.
The parser cross-checks both integer and named state against explicitly checked
AOSP schemas from API26, API28, API29 and API36. Exact known numeric/symbolic pairs
are recognized independently of the OS API number (minimum API26), including
intervening releases and backported schemas. Unknown or mismatched pairs are unreliable. CACHED means a process may still exist;
it does not mean foreground. Background services are not accepted as quiescent.

Reference sources:
- [API26 getUidState output](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-8.0.0_r1/services/core/java/com/android/server/am/ActivityManagerShellCommand.java)
- [API26 process states](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-8.0.0_r1/core/java/android/app/ActivityManager.java)
- [API28 process states](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-9.0.0_r1/core/java/android/app/ActivityManager.java)
- [API29 process states](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-10.0.0_r1/core/java/android/app/ActivityManager.java)
- [API36 getUidState output](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/services/core/java/com/android/server/am/ActivityManagerShellCommand.java)
- [API36 process states](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/core/java/android/app/ProcessStateEnum.aidl)

## Wire v1

All integers are Java DataInput/DataOutput big-endian; strings are a signed 32-bit
UTF-8 byte length followed by strict UTF-8 bytes, maximum 2048 bytes. No Java object
serialization is used. All extra/trailing fields and trailing frames are rejected.

Envelope: magic `0x50445231`, version `1`, signed 32-bit payload length.
Request maximum is 65536 bytes; response maximum is 64 MiB + 8192 bytes.

Request payload:
1. unsigned command byte: PROBE=1, INSPECT=2, EXPORT=3, OBSERVE=4, RESTORE=5, RECOVER=6
2. package string; user int; UID int; PM dataDir string; source SHA-256 string
3. APK count int (1–64); sorted path string and SHA-256 string for each APK

Response payload:
1. unsigned status byte (explicit numeric codes in `RootProtocol.Status`)
2. fixed detail string equal to the status enum name; binding fingerprint string
3. content-state string; file count int; expanded byte count long; content fingerprint string
4. observation-state string; reliability byte (0 or 1)
5. archive byte count int, then exactly that many ZIP bytes (EXPORT OK only)

Errors contain fixed diagnostic codes, never exception strings, raw save content,
credentials, or target paths. The normal caller must validate binding/command/status,
stage the streamed archive privately, verify ZIP contents and fingerprint, and only
then accept the export. Receiving bytes before EOF is not success.

## Verification

Run `android-root/src/test/run-host-tests.sh`. It compiles only platform-independent
classes, shared helper sources, and injected test fixtures. It never starts the runner,
`su`, `app_process`, `cmd`, ADB, or a device session. It covers protocol framing,
identity/path rejection, filesystem types/ownership/modes/hardlinks, shared marker
policy, races, limits, lifecycle parsing, root gating, and unsupported write commands.

Android-only sources are compile-checked with SDK36, with API27 timestamp access
isolated behind an API-level branch and Linux O_CLOEXEC/O_DIRECTORY constants used
where public API26 stubs omit names. Reflection-based PackageManager user context,
root-manager launch, `/proc/self/fd`, SELinux, and Android lifecycle behavior still
need authorized real-device testing. No `runtimeVerified=true` flag exists here.
