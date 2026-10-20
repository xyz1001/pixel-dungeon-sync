# Selected APK identity host checks

Run `src/test/run-selected-apk-identity-tests.sh` from any directory. This executes
production `SelectedApkIdentity.kt` and `AndroidGameIcon.kt` against isolated
content-provider, PackageManager, resources, graphics and manifest parser doubles.
Shared graphics/metadata doubles come from `../game-icon-stubs`; Context is
replaced by this suite's file/provider-aware version. Synthetic manifest records
are not real APK files. These checks verify reader selection, resource-source
isolation, neutral fallback, limits, cancellation and temporary-file cleanup;
they do not establish Android's binary XML/resource decoding or device rendering.
All doubles live outside AGP's unit-test source sets and must never enter the APK.
