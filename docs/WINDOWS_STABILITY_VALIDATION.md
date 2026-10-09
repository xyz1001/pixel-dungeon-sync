# Windows save metadata stability

2026-10-09, Windows Frank, offline JDK 21/Gradle 8.14.3.

`SaveStabilityGuardTest.stableContentUsesDefaultTwoSecondObservationAndDeterministicHashes`
and `sameSizeWriteWithRestoredMtimeStillChangesFingerprint` failed on unchanged
synthetic files. A bounded probe showed the directory timestamp from Windows
tree enumeration changing between reads despite no intervening write; its
enumeration attributes also lacked file keys. The directory's earlier cached
timestamp preceded the child file creation timestamp.

The guard now explicitly reads each included entry's BasicFileAttributes with
NOFOLLOW_LINKS instead of recording cached tree-enumeration attributes. Content,
size, timestamp, identity, limits and link checks remain enforced. No comparison
or safety check was relaxed, and file identity is now retained on this host.

All 15 SaveStabilityGuard tests passed offline, including two new regressions:
20 freshly created fixture trees retain direct-read timestamps/identities, and
directory timestamp changes still block even when file contents match. Existing
same-size/restored-time writes, mid-capture changes, symlinks and size limits
remain covered. These are synthetic filesystem tests, not real-game validation.
