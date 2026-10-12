# Package trust host fixtures

These deliberately small test doubles let the real `PackageTrust` Java class inspect adversarial package-manager responses on a host JVM. They do not emulate Android or establish anything about Binder, platform signature verification, installed package visibility, or runtime permissions. The production build never includes this directory. The tests exercise the reconstructed policy using supplied metadata only.
