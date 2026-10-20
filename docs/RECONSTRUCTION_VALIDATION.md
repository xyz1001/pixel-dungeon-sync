# Reconstructed code92 validation and limitations

This recovery is based on the exact code89 source archive and the separately retained, SHA-verified shipped code92 APK. It is **reconstructed source**, not the missing original code92 commit or a byte-identical copy of its original source archive. Original lost tests cannot be recovered from the production APK; added tests are newly authored.

## Recovered behavior

- code90: bounded operation-local snapshot reuse, fresh publication reads, and hash-only fingerprints using the original safe file-reading rules.
- code91: zero-ZIP metadata HOME graph discovery, bounded concurrent metadata requests and cancellation, explicit object-verification authority, verified action upgrade, and same-content upload without publication.
- code92: a single verified local HOME capture reused for mirror and summary, installed-bridge operation-scoped APK identity checks, and bounded internal phase totals attached to the existing FINISHED diagnostic record.
- Existing code89 ordering, conflict dialog, renewal/manual refresh behavior and all baseline files remain present. SafeSaveTree and its two-second wait, repeated stability checks and game-running checks remain unchanged. No code93 changes are included.

Desktop Main.kt is not contained in the Android APK. Its metadata integration was reconstructed using code89's host flow and the recovered shared APIs. The original automatic scheduler continues to use fresh full verification in automaticPush before any publication. This Desktop reconstruction does not claim source identity with the missing original code92 Desktop file.

## Verification environment

Core and Desktop are built with the existing cached Gradle 8.14.3, Kotlin 2.2.20 and JDK 21. A separate verification settings file omits the Android module because this replacement workspace lacks the Android SDK and Android Gradle plugin cache. No project dependency versions are changed to accommodate that environment. Headless software rendering uses an existing writable temporary directory and the existing test game JAR.

Host tests execute production methods against deterministic JVM doubles. They cover scope cleanup and failed close, identity invalidation, metadata proof isolation, graph faults, same-content zero writes, branch publication, snapshot races, retained two-second synthetic bridge delay, and diagnostic aggregation. Their timing is not phone timing.

Full Android module compilation, APK packaging/signing, ART/device execution, installation acceptance and real account/server tests have not been performed on this reconstructed tree. No installable reconstructed APK is delivered. No signing key, credential or user save is included.

## Test results

Final centralized JVM results will be recorded here after the recovery run completes. Independently completed Android Python host coverage: 290 tests, all passed. Installed identity scope: 128 assertions; extracted production BridgeClient identity methods: 40 assertions; both passed. The existing helper and protocol sources remain unchanged from exact89.
