# Optional Android Root access UI

Root access is an explicit alternative to the existing embedded save bridge. The
selection screen lists installed applications and binds the selected package via
`RootTargetResolver`. Root selection does not prepare, sign, install, uninstall,
or activate an embedded bridge. Retained embedded preparation remains available
when the user explicitly switches back.

## Separate facts and permissions

`InstallationReadiness.rootDirect` records installed-package identity separately
from save readiness. A package-manager check does not establish Root permission.
Backend capability flags describe supported operations; they do not establish
permission, quiet saves, successful execution, or device acceptance.

Selecting Root persists the user's choice to use that backend for deliberate
checks, sync, game launch, recovery, and enabled exit monitoring for the selected
installation. The Root manager controls the actual grant and its persistence.
The app does not require another custom confirmation or a separate Root check
before each operation after restart. Each operation still verifies the exact
selection, current access, and applicable filesystem/process conditions.

Startup, returning to the activity, and metadata refresh may verify the installed
package but do not probe Root, inspect privileged saves, or export them. Metadata
can enable a deliberate action without reporting READY. The embedded path retains
its existing automatic readiness and preview behavior.

## Recovery and switching

Mode and game switches go through pending-restore and active-install guards. They
cancel the old automatic session and controlled-launch evidence and clear stale
comparisons, selected snapshots, and pending UI confirmations. Reselection needs
a fresh comparison.

An unresolved restore blocks ordinary sync/launch and mode changes, but does not
block checking authorization or recovering the exact already-selected target.
The explicit Root check can probe that target and keeps its recovery-needed state;
it never treats a successful authorization probe as completed recovery or READY.
The Root transaction recovery action checks the retained journal: a committed
operation is verified/cleaned, while an unfinished replacement is rolled back.
The separate core recovery record must still be resolved explicitly.

Restore, conflict replacement, merge writing, and interrupted-transaction recovery
are controlled by backend capabilities, both in the UI and at operation entry
points. A native-library, atomic-rename, or file-attribute support failure is an
explicit capability error, never an authorization or successful-restore claim.
Core comparison, format compatibility, conflict choices, and backup rules remain
in force.

## Controlled launch and automatic upload

The automatic setting is available when the selected backend supports lifecycle
observation and export. Normal Root launch uses the existing controlled-session
path. An already configured selection may refresh its comparison during that
explicit launch, since startup intentionally performs no Root save I/O. The
known missing-save-directory case can open the selected normal launcher without
Root writes so the game can initialize itself.

The service forwards actual lifecycle evidence to the core and applies the existing
exit-upload policy. For Root, reliable foreground evidence records play; reliable
quiet-ready CACHED or ABSENT state permits a later upload attempt. An active or
unknown process state does not qualify, and prolonged unknown evidence stops the
watch. Process absence is not required and no force-stop action exists. Export
and publication still recheck stable saves and conflicts; automatic upload never
selects a conflict winner or performs a restore/merge.

## Host checks

```sh
android-app/src/test/run-root-ui-tests.sh
android-app/src/test/run-root-auto-policy-tests.sh
android-app/src/test/run-main-runtime-tests.sh
python3 -m unittest discover -s android-app/src/test/python -p 'test_*contract.py'
```

The auto-policy script needs the same SDK/core/runtime classpath as the client
host checks. It compiles production Java and executes only its pure evidence gate;
it does not instantiate an Android service. These checks do not request Root,
execute Android UI, establish a device's Root state, or replace device testing.
