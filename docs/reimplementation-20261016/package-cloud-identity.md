# Current game cloud identity

A recognized Shattered Pixel Dungeon desktop JAR and Android package
`com.shatteredpixel.shatteredpixeldungeon` use the same `shattered` cloud game
namespace. Other Android packages use `package-` plus SHA-256 of the exact
validated package name prefixed with `game-package-v1\n`. Source hashes,
version, signing certificates, Root/embedded mode and platform do not choose
this namespace. Desktop recognizes Shattered JARs only; this is not generic
support for arbitrary JARs or mods whose package cannot be established.

Each operation reads or writes only its configured game namespace and profile.
The default profile remains `default`; an explicitly selected custom profile
remains separate. There is no root-directory namespace scan, historical alias
registry, source-to-namespace mapping or cross-namespace parent lookup. The
manual PC association UI remains removed.

Normal current-namespace append-only commits, parents, immutable byte/hash
validation, conflict detection, selected restore and exact frozen retry retain
their existing rules. A same-package manifest from a different namespace is
still rejected. An external/missing parent remains unsettled; it is never
silently discarded or converted into an unrelated new root.

Local sync state uses the `package-current-v1` generation. Earlier test scopes,
queued uploads, baselines and restore journals are not adopted, migrated or
automatically retried. Those files are left untouched. Current-generation
pending operations and recovery retain their normal behavior across restarts.

No server data is deleted, copied or rewritten by this simplification. There
is no legacy namespace or profile migration and no compatibility guarantee for
old test history, including code53 commits with parents outside the current
namespace. Both current clients use the same package-based rule.

Validation uses isolated synthetic loopback HTTP and host filesystem fixtures.
Android device and Windows native execution are separate verification steps.
