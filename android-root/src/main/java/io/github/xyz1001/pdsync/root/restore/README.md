# Root restore transaction boundary

`RootRestoreTransaction` is a pure, host-testable state machine. It does not invoke
`su`, parse ZIPs, access Android files, stop a game or change SELinux policy.
An Android adapter must satisfy `FileAccess` before exposing restore/recovery as
a working capability. Passing host tests is not device validation.

## Shared-core inputs

- The normal sync facade supplies the existing expected-current-files hash map.
- The existing shared `SaveArchive` reader validates bounded incoming bytes,
  permitted paths and complete runs. The adapter then creates `Incoming` with an
  opaque local content token plus hashes, sizes and modification times. There are
  no archive-supplied UID, GID, mode, SELinux label or filesystem paths in this API.
- `Target` combines `RootBinding.fingerprint()` with the canonical PM dataDir,
  game UID and pinned dataDir/files device and inode identities. Re-resolve the
  actual PM package, user, UID, APK set and canonical directory before access.
- The existing core still owns cloud publication, compatibility/schema decisions,
  snapshot identity, metadata sidecars and its outer restore journal.

## Local attributes

Existing replacement paths retain exact locally observed UID/GID/mode/SELinux
attributes. New entries use consistent same-type local sibling attributes, or the
nearest verified local directory's owner/group/category with private 0600/0700
permissions. Ambiguous sibling attributes and type changes are refused. Confirm
inherited categories are valid on the device before publishing staged entries.
Never accept remote attributes, guess a global app category, recursively
chown/relabel the game, or disable SELinux.

## Filesystem adapter requirements

1. Hold one exclusive per-target lease. Resolve areas from the bound target and
   adapter-generated exact receipt, never from caller paths.
2. Recheck reliable lifecycle state, PM identity and root identities before and
   after staging, scans and mutations. Foreground, active or unobservable states
   fail. A reliably observed cached/absent state is not a new-launch exclusion.
3. Place staging under the PM dataDir, outside `files`, with UID 0 and mode 0700.
   Pin directory descriptors/identities. Reject links, special entries, aliases
   and traversal. Verify target game attributes on staged payload before exposure.
4. Preserve a separate verified, durable original backup before any mutation.
   It must not be a hard link to a file the game can change.
5. The exact bounded workspace receipt must bind payload and directory inode
   identities. `Tree` is a content/security-attribute model, not an inode ledger:
   matching content on a substituted inode never waives physical identity checks.
6. Require atomic no-replace rename, intended as a narrow native
   `renameat2(RENAME_NOREPLACE)` wrapper. `Os.rename`, `File.renameTo` or
   `Files.move` after an existence check do not satisfy this. Unsupported kernels
   or filesystems fail capability checks; never fall back to ordinary rename or
   copy/delete.
7. Equal `st_dev` is insufficient across bind mounts. Preflight the actual parent
   relationship before mutation with bounded adapter-owned non-save probe entries,
   then remove only those exact entries.
8. No-replace prevents destination clobbering, but not source substitution. Recheck
   source identity immediately before moving and captured identity afterward. Keep
   an unexpected captured inode and original backup; report recovery-required.
   Do not overwrite an unknown live destination to put a captured file back.
9. Fsync payloads, journal replacements and affected directories. Durable journal
   updates are compare-and-replace. A failed call may have taken effect.

## Finite recovery

The deterministic plan moves changed top-level old entries into `DISPLACED`, then
verified incoming entries into `LIVE`. Each step is one no-replace rename. Before
a step, the durable journal cursor permits that step. After it, a full layout
check precedes durable cursor advancement.

Only the recorded layout or its single permitted adjacent move may be accepted
after a crash. The adapter checks physical identities as well as tree equality.
A different live/staged/backup/metadata state is not a retry signal: preserve all
recoverable data and the journal without guessed writes. Explicit recovery rolls
back the known prefix in reverse; it never resumes an uncommitted forward restore.

`COMMITTED` and `ROLLED_BACK` are durable terminal markers. Cleanup failures are
distinct from uncertain mutation outcomes. Cleanup never changes live saves,
reclaims a verified finalized backup according to adapter policy, and removes
the exact transaction journal last. The Android adapter reclaims successful private
backups rather than accumulating an arbitrary fixed-count limit. Interrupted or
conflicting operations retain their recoverable data.

`RestoreJournalCodec` is a deterministic strict-schema local format with a 16 MiB
bound. It rejects corruption, trailing data, duplicate/unsorted paths, malformed
UTF-8, invalid owners/paths and impossible phase cursors. Its checksum detects
corruption, not malicious edits: journals belong in the pinned root-private area,
and cloud archives must never be decoded as local journals.

## Host validation

Run `bash android-root/src/test/run-restore-host-tests.sh` from the repository.
The fake filesystem injects exceptions/crashes before and after forward/reverse
operations, journal publication and cleanup. Tests cover stale expected hashes,
unknown mutations, changed attributes/backup/target/workspace, metadata derivation
and malformed journals. They do not establish Linux syscall semantics, Android
mount topology, root-manager authorization or SELinux behavior. Real validation
requires an explicitly authorized usable rooted device; no process is force-stopped
and no security policy is weakened to make the backend work.
