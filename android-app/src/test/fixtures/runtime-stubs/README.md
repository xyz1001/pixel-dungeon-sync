# Package trust host fixtures

These deliberately small test doubles let the real `PackageTrust` Java class inspect adversarial package-manager responses on a host JVM. They do not emulate Android or establish anything about Binder, platform signature verification, installed package visibility, or runtime permissions. The production build never includes this directory. The tests exercise the reconstructed policy using supplied metadata only.

Minimal Intent, PendingIntent and preference doubles additionally check application-level confirmation retention and receiver correlation. Their URI encoding and token registry are test models, not Android implementations. Real Android parceling, token persistence, OS lifecycle, package-install permissions and confirmation UI still require device tests.

SDK-selection doubles exercise production compatibility branches from API 26 through 36. Methods introduced after API 26 throw `NoSuchMethodError` when selected below their introduction level. Insets, notifications, install parameters and receiver calls are recorded for assertions. This is branch/policy coverage, not an Android emulator or proof of ART linkage, insets delivery, OS installer confirmation or service lifecycle behavior.
