# Package trust host fixtures

These deliberately small test doubles let the real `PackageTrust` Java class inspect adversarial package-manager responses on a host JVM. They do not emulate Android or establish anything about Binder, platform signature verification, installed package visibility, or runtime permissions. The production build never includes this directory. The tests exercise the reconstructed policy using supplied metadata only.

Minimal Intent, PendingIntent and preference doubles additionally check application-level confirmation retention and receiver correlation. Their URI encoding and token registry are test models, not Android implementations. Real Android parceling, token persistence, OS lifecycle, package-install permissions and confirmation UI still require device tests.
