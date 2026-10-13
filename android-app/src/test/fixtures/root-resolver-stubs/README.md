# Root target resolver host boundary

The host runner executes the production RootTargetResolver, SelectedRootGame,
RootBinding, APK-set and source-identity algorithms against a fake PackageManager.
Only Android framework calls, the preparation/key-store boundary and APK file
reads are supplied by doubles. Virtual APKs use Android installation path names
with real SHA-256 over supplied bytes; no real /data path, phone, su or APK is used.
These tests prove resolver decisions and identity binding, not Android PM behavior,
on-device file visibility, cryptographic APK verification or root authorization.
The separate main-runtime suite executes the real PackageTrust policy.
