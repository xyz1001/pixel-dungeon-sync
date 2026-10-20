# Reconstructed code92 recovery candidate

This project starts from the SHA-verified exact code89 source and reconstructs code90–92 behavior using the verified shipped code92 APK, DEX disassembly, decompiler references and prior implementation reports. It is not the original lost source tree and does not claim the original commit or source ZIP hash. New tests are explicitly newly authored recovery regressions.

Recovery validation: Core production/test compilation and all 933 core tests passed. Desktop production/test compilation passed; its full runtime suite is in progress. Android Python host coverage passed 290 tests. Full Android module compilation and device acceptance remain unperformed because the replacement environment lacks the Android SDK/toolchain. No reconstructed installable artifact is being released. The source version is 92 / 0.5.71-test solely to match the target being reconstructed.

Preserved scope: code89 history generation-time ordering and prior conflict/refresh UI; code90 operation-local object reuse; code91 metadata HOME; code92 local export/identity reuse and internal timing. The installed helper's fixed two-second wait, stability/running checks and action safety gates remain. Code93 changes are paused.

Exact89 input: ZIP SHA256 334a408b131602728676f5b9babc1add73874a9af0d51d1c8295b2c00ea329e2; 1807 baseline manifest entries.
Verified shipped92 APK SHA256: 71550ee4005a76d761e473371eb9723b4380cb4448d1f0409e1b71e13e7d5198.
APK-derived evidence Library: libfile_27bc7242e9288191b20577a076afee9c.
No private signing keys, credentials or user save data are included. No new signing identity was created.
