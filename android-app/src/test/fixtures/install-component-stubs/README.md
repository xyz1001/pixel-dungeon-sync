# Install-component result host boundary

Run from the repository root:

```sh
android-app/src/test/run-install-component-tests.sh
```

This isolated suite executes the **real production** `InstallCoordinator`,
`InstallResultReceiver`, `InstalledBridgeResolver`, `InstalledBridgeBinding`,
`SelectedApkInstallPolicy`, `InstallProgressPolicy`, `InstallSessionWriter`,
`InstallDiagnostics`, `ApkSetIdentity`, and `SystemConfirmationToken`.
It does not replace or modify the existing runtime-test fixtures.

The following are host doubles:

- Android framework, PackageManager, PackageInstaller and PendingIntent
- Disk-backed SharedPreferences, recreated from committed files in a fresh context
- Preparation/cache services, PackageTrust metadata accessors, runtime selection
  and diagnostic sinks

The real session writer reads selected fixture bytes, verifies their length and
hash, and writes them to an in-memory PackageInstaller stream before commit.
The fixture bytes are not a signed APK. Installed APK paths deliberately do not
exist. PackageTrust APK hashing, signer-key access and post-request preparation
reloads fail the test if invoked. The real resolver validates the installed
component's PM metadata, provider binding, namespace and independent UID using
the modeled PackageManager. Cryptographic signer extraction is a double.

The SyncRuntime double revalidates the component with the real resolver and
enforces the production active-request backend-switch guard. It additionally
requires persisted completion proof before accepting the binding, catching a
regression that switches the backend before settling the coordinator.

The scenarios cover:

- No early reuse before a selected file is written and submitted; no component
  scans on ordinary READY reconciliation or while an installer session is pending
- Success, cancellation, failure, explicit activity return and missing callback
  with a vanished session, accepting a valid own component of the target package
  even when its source/version differ from the selected file
- Missing request receipts, mismatched pending receipts and unrelated callbacks
- Absent packages/components and malformed metadata remaining pending until
  explicit settlement, or succeeding if valid metadata appears later
- Durable request/return/completion checkpoints and code48 returned READY recovery
- Duplicate/late callbacks and stable completion proof without source/hash reads
- Changed selected bytes and callback-creation failure never authorizing reuse

This is an executable coordinator/receiver/resolver integration at a host boundary.
It is **not** Android instrumentation, a real OS process-death test, an installer
UI test, a signed-APK install, provider IPC, save-access/authentication evidence,
or execution of `MainActivity` lifecycle handlers. Reconciliation retries and
explicit settlement are driven directly by the test. Android timing, platform
publication and callback delivery are modeled rather than demonstrated.
