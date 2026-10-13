# Optional root direct-save source module

This is an optional Android `app_process` file-access backend with host-testable
read, export, transactional restore and recovery paths.
The established embedded bridge is unchanged. Nothing here has been executed under
Android root or on a phone. SDK compilation and injected host tests are not runtime
verification or evidence that a particular root manager/SELinux policy permits it.

## Public boundary

Entry point: `io.github.xyz1001.pdsync.root.RootRunner`, with **no arguments**.
A normal-UID caller starts it explicitly after root authorization, sends exactly one
`RootProtocol.Request`, closes stdin, receives exactly one reply, and waits for EOF.
The runner verifies actual `Os.geteuid() == 0` before PM or target filesystem access.
It exits after the job, including framework/Binder threads, with a monotonic
25-second probe/observe, 50-second inspect/export, or 120-second restore/recovery
watchdog. There is no socket, daemon, network access, credential input, target-app
launch, force-stop or caller-defined shell command. Cancelling the normal-UID `su`
client does not prove its root child has exited; an uncertain outcome retains the
outer restore journal, and the private transaction lock and watchdog bound recovery.

Commands are `PROBE`, `INSPECT`, `EXPORT`, `OBSERVE`, `RESTORE` and `RECOVER`.
Only the latter two construct the mutation adapter. The implementation capability
is distinct from actual authorization, native/kernel availability and device validation.

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
Restore/recovery additionally require the native no-replace primitive and local
filesystem/attribute preflight. A successful read probe does not prove these work.

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

## Transactional restore and recovery

The root job reuses the existing shared `SaveArchive` validator and pure
`restore/RootRestoreTransaction` state machine. The entire bounded incoming ZIP,
expected-current hash map, requested final fingerprint and outer frame EOF are
verified before changing live saves. Only recognized transferable entries are
considered; runtime markers and unknown files are not copied or deleted.

The adapter pins the selected game's data and files directories, and keeps a
UID-0/0700 workspace beside `files`. It serializes jobs with a nonblocking lock,
creates a separate verified durable original backup, stages validated bytes, and
preserves owner/group/mode/SELinux attributes learned locally. Archive data cannot
supply those attributes. Each top-level publication uses the single native
`renameat2(RENAME_NOREPLACE)` primitive. Unsupported kernels and cross-mount moves
return explicit errors; there is no overwrite-rename fallback.

A write-ahead journal and inode receipt admit only the recorded layout or its one
permitted adjacent move after interruption. Recovery rolls back that known prefix.
Unexpected new files, substituted inodes or conflicting destinations are preserved
and reported rather than overwritten. Verified successful cleanup reclaims the
private backup, staging and displaced copies; no fixed number-of-restores limit is
imposed. Cleanup failures retain a resumable terminal receipt.

Read/inspect/export/observe operations hold a read guard against an existing
workspace and reject pending recovery or concurrent mutation. The guard creates no
workspace during ordinary reads. PROBE remains available to authorize recovery.

Both the start and final boundary hash the complete installed APK set. Intermediate
transaction checks use a one-job PM and APK inode/device/owner/mode/size/time stamp,
so the same APK is not rehashed for every moved file. The metadata cache is never
persisted or accepted in place of the final full hash. Foreground, active background
or unknown lifecycle states refuse mutation; reliably CACHED/ABSENT states plus
stable scans permit a normal game exit without force-stopping it. Repeated checks
cannot prevent every external new launch; detected interference retains backups.

## Wire v2

`RootProtocol` is the authoritative field/enum definition. Integer fields use Java
DataInput/DataOutput big-endian encoding, and strings are bounded strict UTF-8.
No Java serialization is used. The fixed envelope has magic `0x50445231`, version
`2`, and a bounded payload length. Extra fields, frames and trailing bytes fail.

Every request has a fresh 32-hex request ID, one of the six fixed commands, and an
exact `RootBinding`. Restore adds expected-current and desired SHA-256 inventory
fingerprints and a bounded streamed ZIP. A response echoes the request ID, command,
binding, expected and desired fingerprints and reports a typed outcome. The main
client checks these echoes, EOF, exact inventory, file count and expanded bytes
before accepting success. Recovery success alone does not claim the requested
forward restore was applied.

Errors contain fixed diagnostic stage/category codes and bounded errno, never raw
exception strings, credentials, save contents or target paths. No unbounded archive
request buffer or arbitrary operation argument is accepted.

## Verification

Run the host suites from the repository:

- `bash android-root/src/test/run-host-tests.sh`
- `bash android-root/src/test/run-installed-stamp-tests.sh`
- `bash android-root/src/test/run-mutation-tests.sh`
- `bash android-root/src/test/run-restore-host-tests.sh`
- `bash android-root/src/test/run-adapter-tests.sh`
- `bash android-app/src/test/run-root-client-tests.sh`
- `bash android-app/src/test/run-root-ui-tests.sh`
- `bash android-app/src/test/run-root-auto-policy-tests.sh`

These compile platform-independent classes, shared helper sources and injected
fixtures. It never starts the runner,
`su`, `app_process`, `cmd`, ADB, or a device session. It covers protocol framing,
identity/path rejection, filesystem types/ownership/modes/hardlinks, shared marker
policy, races, limits, lifecycle parsing, root gating, and read/restore protocol failures. Mutation suites inject interruptions, unsupported
kernels, replaced targets, incomplete archives and unknown concurrent entries.
`tools/android-root-native/README.md` documents the small official-input cross-build
and separate host syscall/JNI tests; passing them is not Android root execution.

Android-only sources are compile-checked with SDK36, with API27 timestamp access
isolated behind an API-level branch. `RootOpenFlags` selects the actual process ABI
from the platform O_NOFOLLOW value where public stubs omit O_DIRECTORY; ARM/arm64
and x86/x86_64 use different flag layouts. It rejects unknown layouts rather than
passing the x86 O_DIRECTORY bit as ARM O_DIRECT. Fixed KERNEL_* diagnostic stages
preserve the failed operation and bounded errno without exposing paths. Reflection-based PackageManager user context,
root-manager launch, `/proc/self/fd`, SELinux, and Android lifecycle behavior still
need authorized real-device testing. No `runtimeVerified=true` flag exists here.
