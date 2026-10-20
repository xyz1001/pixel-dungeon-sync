# Preparation-service host boundaries

These test-only Android framework stand-ins let the actual `GamePreparationService`
entry points execute against host files. They are never packaged into an APK.
Production APK parsing, DEX validation, signing, payload verification, receipts, and
namespace decisions use the unmodified production classes. The stubs provide only
file-backed content reads, declared package metadata, and atomic receipt files.
No APK or DEX code is installed, launched, or executed.
