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

The Root authorization button displays the selected game, explains the request,
and requires an explicit foreground confirmation. The Root manager determines
whether its grant is temporary or persistent. The app's authorization state is
memory-only and bound to the selected installation; restart requires another
explicit authorization action.

Startup, returning to the activity, and metadata refresh may verify the installed
package but do not probe Root, inspect privileged saves, or export them. Root
preview/comparison is initiated by a user action. The embedded path retains its
existing automatic readiness and preview behavior. Root game launch can verify
the selected installed package and open its normal launcher without requesting
Root or accessing saves.

## Switching and unsupported operations

Mode and game switches go through the runtime's pending-restore and active-install
guards. They cancel the old automatic session and controlled-launch evidence and
clear stale comparisons, selected snapshots, and pending UI confirmations. A
reselection requires a new explicit authorization check and comparison.

The read-only Root backend exposes preview and manual upload. Restore, conflict
replacement, merge writing, and interrupted-backup recovery are disabled according
to backend capabilities, with checks at the operation entry points as well as in
the UI. The generic core comparison and save-format rules remain in force.

Root automatic upload stays disabled pending actual Android lifecycle acceptance.
The service uses the backend boundary and exact selection token, but capability
support alone never allows it to start monitoring or publishing.

## Host checks

```sh
android-app/src/test/run-root-ui-tests.sh
android-app/src/test/run-main-runtime-tests.sh
python3 -m unittest discover -s android-app/src/test/python -p 'test_*contract.py'
```

These checks cover readiness values and source wiring. They do not request Root,
execute Android UI, establish a device's Root state, or replace device testing.
