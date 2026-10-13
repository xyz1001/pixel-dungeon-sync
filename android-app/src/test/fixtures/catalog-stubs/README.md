# Installed-app catalog host boundary

The isolated runner compiles the production InstalledAppCatalog and
InstalledAppPickerModel against minimal Android metadata doubles. No other host
suite uses these stubs. They expose no APK-file, signer, root, or install APIs.

Tests cover visible installed packages, launcher and explicit-selection union,
launcher enabled flags, system/updated-system classification, metadata/label
fallback, exact-self exclusion, deduplication, immutable snapshots, removal races,
and propagation of PackageManager discovery errors. They do not establish Android
package visibility, launcher behavior, or device filesystem access. A populated
sourceDir is metadata only; identity and file validation happen after selection.
