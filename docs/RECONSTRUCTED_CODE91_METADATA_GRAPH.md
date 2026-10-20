# Reconstructed metadata graph implementation

This is reconstructed Kotlin source, not a byte-for-byte recovery of the lost code91/code92 source. The starting files are verified code89 source. The shipped code92 Android APK provides production-behavior evidence. The tests in `ReconstructedMetadataGraphTest.kt` are new tests written for this recovery.

## File origins and scope

- `core/src/main/kotlin/pdsync/AppendOnly.kt`: exact code89 base, with APK-derived code90 transport scope defaults/publication readback boundary and code91 metadata discovery, graph verification labels, and manifest validation.
- `core/src/main/kotlin/pdsync/MetadataReadCancellation.kt`: reconstructed from the APK's class, method signatures, and cancellation behavior.
- `core/src/test/kotlin/pdsync/ReconstructedMetadataGraphTest.kt`: newly reconstructed regression coverage, not recovered original tests.
- Diagnostic trace accessor and WebDAV cancellation transport are integrated by their respective file owners. This document does not claim those files or the complete application are independently verified.

## Evidence and preserved boundaries

The APK's `AppendRepository`, `AppendStore`, `CommitGraph`, `CommitMetadataGraph`, `RemoteVerification`, and `MetadataReadCancellation` classes establish these APIs and behavior:

- `discover(store)` remains object-verifying discovery.
- `discoverMetadata(store, parallelism = 4)` allows one through four concurrent commit requests and returns a metadata-only wrapper.
- Metadata discovery never calls `readObject` or records an object-verified observation. Cached verified graphs remain filtered by verified observation timestamps. `setBaseline` first requires object verification.
- All manifest identities, parent relationships, package/profile ownership, file hashes, safe paths, portable-name aliases, complete run paths, and declared run-version relationships are checked before metadata is accepted.
- Pagination, total entry count, discovered ancestry count, and whole-discovery byte limits remain enforced.
- Metadata workers capture the caller's `DiagnosticTrace` and bind/unbind it locally. Completion-service results recognize failures as soon as they complete, while manifests are decoded in discovery order.
- A discovery-local cancellation token owns transport cancellation callbacks through response body consumption. Cleanup cancels owned calls and futures, shuts down daemon workers, and waits at most 100 ms.
- Only manifest decoding is caught as `invalid manifest`. Only ZIP length/verification failures are caught as `invalid object`. DEX exception-table inspection confirms transport failures, HTTP401, known-manifest mutation, interrupted waits, and scan-budget failures escape those handlers.
- The publication `withFreshReads` boundary wraps the two exact-object/readback checks. Initial graph discovery and final local verified observation bookkeeping retain their original positions.

## Newly reconstructed regression coverage

The focused test class covers metadata zero-object reads, verified-proof isolation, full-discovery object checks, repository isolation, metadata mutation detection, ancestry closure, cycles, path/identity/package/run metadata rejection, deterministic four-way concurrency, prompt HTTP401 failure propagation, caller interruption and owned-call cleanup, diagnostic trace propagation, token lifecycle, legacy/listing limits, and total manifest scan bytes.

The first integrated JVM run on 2026-10-20 executed all 19 tests in `ReconstructedMetadataGraphTest`: 19 passed, 0 failures, 0 errors, 0 skipped (JUnit suite time 1.115 seconds). Its report is `core/build/test-results/test/TEST-pdsync.ReconstructedMetadataGraphTest.xml`. The complete application run also contained failures in other suites; this focused pass is not a claim that the full integration passed. The final integration test report remains authoritative.

These tests use temporary files and synthetic stores only; they do not access a device, credentials, or production server.
