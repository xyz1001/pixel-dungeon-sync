# External installer host boundaries

These doubles compile only in run-external-installer-tests.sh. Production storage,
handoff intent construction, provider path validation, read-only methods and copy
verification execute on the host. Framework URI permission enforcement, default-handler resolution,
PackageManager publication, preparation cryptography and actual APK installation
still require Android integration/device coverage. Never package these doubles.
