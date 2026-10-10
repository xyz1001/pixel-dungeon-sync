# Game selection without a checksum allowlist

2026-10-10, Windows Frank. Supersedes the checksum-selection policy described in
earlier validation reports. The game JAR is never executed or loaded as code.

## Selection

Selection reads the bounded JAR/ZIP and its manifest: the Shattered desktop
Main-Class, Specification-Title, Implementation-Title, release version and
positive integer internal version must be present, with a launcher class entry.
The filename is not version evidence. Missing identity, duplicate/ambiguous
metadata, malformed/oversized archives and contradictory known release/build
pairs remain rejected. ZIP input, expanded-size, entry-count and manifest limits
remain enforced.

There is no release checksum allowlist and no publisher/source authentication
or ranking. A content fingerprint is retained solely for the existing check
that the selected file has not changed before proceeding. The internal legacy
field `TargetGame.trusted` now means identified metadata, not official origin.
Copied or modified metadata does not establish authenticity; this feature does
not attempt to certify a JAR's code or publisher.

An identifiable release outside the known compatibility policies can be selected.
Its restore capability remains unknown and the UI explicitly says restoration
is unavailable. It does not acquire a guessed lower bound from its build number.

## Separate restoration policy

| Release | Internal writer | Supported restore writers |
| --- | ---: | --- |
| 3.3.8 | 896 | 802–896 |
| 4.0.2 | 922 | 850–922 |
| Other identified release | From manifest | Unknown: restoration blocked |

These are version compatibility policies, not a selection allowlist. Every live
game.dat's actual writer is checked independently, after archive verification.
Unknown save versions, missing compatibility, below-minimum writers and newer
writers still block restoration. Existing backups, process, file-stability and
conflict checks are unchanged. Modification that preserves the same version
labels cannot be detected from metadata; no compatibility guarantee for such a
modified loader is claimed. Historical migrations have not all been played.

## User-provided 3.3.8 evidence

The explicitly provided `desktop-3.3.8.jar` is 53,913,290 bytes. Its unfolded
manifest declares Shattered Pixel Dungeon, the expected implementation and
desktop launcher, Specification-Version 3.3.8 and Implementation-Version 896.
No adjacent source checkout was present.

JDK javap static disassembly shows DesktopLauncher reading Specification-Version
and Implementation-Version into Game.version and Game.versionCode. In
GamesInProgress.check, offsets 57–65 read Bundle.getInt("version"), push 802,
and branch to the accepted preview path only when the version is at least 802;
the lower path yields no game info. This supplies the lower-bound evidence.
The upper bound 896 is the synchronizer's anti-downgrade policy. The 4.0.2 lower
bound 850 retains the previously inspected upstream rule.

The modified production inspector read the user's actual JAR successfully:
release 3.3.8, internal 896, minimum 802. Synthetic writers 802 and 896 passed
the compatibility check; 801, 897 and 922 were blocked. No game classes were
initialized, no game was launched, and no real save was read or written. The
user's binary is not included in this repository.

## Regression coverage and limits

Tests cover unknown checksum with consistent metadata, the 3.3.8 manifest and
both compatibility boundaries, an unsupported custom release, contradictory or
missing metadata, filename independence, non-game/corrupt/oversized archives,
and a compiled launcher whose static initializer would write a marker if ever
executed (the marker remains absent). Discovery and UI wording tests confirm
selection no longer requires a release checksum and unknown restoration remains
explicit. Existing archive integrity tests are unchanged.

All builds use the existing offline cache. Native UI automation is unavailable
in this task, so successful static selection and tests are not claimed as actual
file-dialog clicking, real-game loading or other-OS acceptance.

Final offline regression: core 398 tests, 0 failures/errors, 5 existing platform
skips; desktop 51 tests, 0 failures/errors/skips. All 444 executed tests passed.
