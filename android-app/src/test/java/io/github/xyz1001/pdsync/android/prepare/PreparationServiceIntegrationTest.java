package io.github.xyz1001.pdsync.android.prepare;

import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Real production SAF -> service -> receipt -> signature/payload paths, with inert host framework adapters. */
public final class PreparationServiceIntegrationTest {
    static int checks;
    interface Action { void run() throws Exception; }
    static void check(boolean result, String message) { checks++; if (!result) throw new AssertionError(message); }
    static Exception rejects(Action action, String message) throws Exception {
        checks++; try { action.run(); } catch (Exception expected) { return expected; }
        throw new AssertionError("Accepted unsafe source: " + message);
    }
    static final class Resolver extends ContentResolver {
        final Map<Uri, byte[]> contents = new HashMap<>(); int opens; int failAfter = -1;
        public InputStream openInputStream(Uri uri) throws FileNotFoundException {
            byte[] data = contents.get(uri); if (data == null) throw new FileNotFoundException(); opens++;
            if (failAfter < 0) return new ByteArrayInputStream(data);
            final int limit = failAfter;
            return new InputStream() {
                int at;
                public int read() throws IOException {
                    if (at >= limit) throw new IOException("https://secret.example/?password=credential");
                    return at < data.length ? data[at++] & 255 : -1;
                }
                public int read(byte[] out, int offset, int length) throws IOException {
                    if (at >= limit) throw new IOException("https://secret.example/?password=credential");
                    int amount = Math.min(length, Math.min(limit - at, data.length - at));
                    if (amount == 0) return -1; System.arraycopy(data, at, out, offset, amount); at += amount; return amount;
                }
            };
        }
    }
    static final class Packages extends PackageManager {
        final Map<String, PackageInfo> infos = new HashMap<>();
        public PackageInfo getPackageInfo(String name, int flags) throws NameNotFoundException {
            PackageInfo info = infos.get(name); if (info == null) throw new NameNotFoundException(); return info;
        }
    }
    static final class TestContext extends Context {
        final File root; final Packages packages = new Packages(); final Resolver resolver = new Resolver();
        TestContext(File root) throws Exception {
            this.root = root; root.mkdirs();
            PackageInfo main = new PackageInfo(); main.packageName = getPackageName(); main.versionCode = 46; main.versionName = "0.5.25";
            main.signatures = new android.content.pm.Signature[]{new android.content.pm.Signature(PublicGameSigningIdentity.load().getCertificate().getEncoded())};
            main.signingInfo = new SigningInfo(main.signatures); packages.infos.put(main.packageName, main);
        }
        public PackageManager getPackageManager() { return packages; }
        public String getPackageName() { return "io.github.xyz1001.pdsync.android"; }
        public File getNoBackupFilesDir() { return root; }
        public ContentResolver getContentResolver() { return resolver; }
        Uri source(String name, File file) throws IOException { Uri uri = Uri.parse("content://test/" + name); resolver.contents.put(uri, Files.readAllBytes(file.toPath())); return uri; }
    }
    static File archive(Path path, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> item : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(item.getKey()); entry.setTime(0);
                zip.putNextEntry(entry); zip.write(item.getValue()); zip.closeEntry();
            }
        }
        return path.toFile();
    }
    static Map<String, byte[]> contents(File file) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(file)) { Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) { ZipEntry entry = entries.nextElement();
                if (!ApkArchive.isSignature(entry.getName())) try (InputStream input = zip.getInputStream(entry)) { result.put(entry.getName(), input.readAllBytes()); }
            }
        }
        return result;
    }
    static GamePreparationService.PreparedPair prepare(TestContext context, File source, File runtime) throws Exception {
        return new GamePreparationService(context).prepareSelectedApks(List.of(context.source("source" + context.resolver.contents.size(), source)), false, new FileInputStream(runtime));
    }
    static int dexCount(File file) throws IOException { int count = 0; for (String name : ApkArchive.entryHashes(file).keySet()) if (PayloadInventory.isDexEntry(name)) count++; return count; }
    static void checkPickedPayload(GamePreparationService.PreparedPair pair, File selected,
            Map<String, byte[]> selectedEntries, long version, String versionName, String sourceSetHash,
            String label) throws Exception {
        check(pair.selectedFileInstallation, label + " retains explicit file installation intent in the receipt");
        check(pair.gamePackage.equals("com.example.game") && pair.gameVersionCode == version
                && pair.gameVersionName.equals(versionName), label + " retains the picked identity/version");
        BinaryManifestPatch.ManifestInfo output = BinaryManifestPatch.inspect(ApkArchive.manifest(pair.gameApk));
        check(output.versionCode == version && output.versionName.equals(versionName),
                label + " writes the picked version to the actual binary output manifest");
        check(pair.originalSha256.equals(ApkArchive.sha256(selected)) && pair.sourceApkSetSha256.equals(sourceSetHash),
                label + " binds the receipt to the picked bytes");
        check(!pair.adoptedEmbedded && pair.sourceProvenance == GameSourceIdentity.Provenance.UNSIGNED,
                label + " does not substitute the installed embedded source");
        Map<String, byte[]> outputEntries = contents(pair.gameApk);
        for (Map.Entry<String, byte[]> entry : selectedEntries.entrySet()) {
            if (!entry.getKey().equals("AndroidManifest.xml"))
                check(Arrays.equals(entry.getValue(), outputEntries.get(entry.getKey())),
                        label + " retains picked payload " + entry.getKey());
        }
        check(dexCount(pair.gameApk) == 2, label + " injects exactly one bridge runtime");
        BinaryManifestPatch.BridgeBinding binding = BinaryManifestPatch.readProviderBinding(ApkArchive.manifest(pair.gameApk));
        check(binding.gameVersionCode == version && binding.gameVersionName.equals(versionName)
                && binding.sourceApkSetSha256.equals(sourceSetHash), label + " binds the bridge to the picked source");
    }
    static void pickedSourceIsNotReplacedByInstalledBridge(Path fixtures, Path temp, File gameDex,
            File runtime, Map<String, byte[]> originalEntries) throws Exception {
        Path caseRoot = Files.createDirectory(temp.resolve("picked-with-installed-bridge"));
        TestContext context = new TestContext(caseRoot.resolve("private").toFile());
        GamePreparationService service = new GamePreparationService(context);
        Map<String, byte[]> installedEntries = new LinkedHashMap<>(originalEntries);
        installedEntries.put("assets/preserved.bin", new byte[]{10,20,30,40});
        File installedOriginal = archive(caseRoot.resolve("installed-original.apk"), installedEntries);
        GamePreparationService.PreparedPair retained = prepare(context, installedOriginal, runtime);
        Path installedPath = Files.copy(retained.gameApk.toPath(), caseRoot.resolve("installed-base.apk"));
        String installedHash = ApkArchive.sha256(installedPath.toFile());
        long installedModified = Files.getLastModifiedTime(installedPath).toMillis();
        PackageInfo installed = new PackageInfo();
        installed.packageName = retained.gamePackage; installed.versionCode = (int) retained.gameVersionCode;
        installed.versionName = retained.gameVersionName; installed.firstInstallTime = 1000; installed.lastUpdateTime = 2000;
        installed.signatures = new android.content.pm.Signature[]{new android.content.pm.Signature(PublicGameSigningIdentity.load().getCertificate().getEncoded())};
        installed.signingInfo = new SigningInfo(installed.signatures);
        installed.applicationInfo = new ApplicationInfo();
        installed.applicationInfo.packageName = installed.packageName; installed.applicationInfo.uid = 10042;
        installed.applicationInfo.sourceDir = installedPath.toString();
        installed.applicationInfo.publicSourceDir = installedPath.toString();
        context.packages.infos.put(installed.packageName, installed);
        check(BinaryManifestPatch.readProviderBinding(ApkArchive.manifest(installedPath.toFile())) != null,
                "Installed competitor contains a real prepared embedded bridge");
        LocalApkSigner.verify(installedPath.toFile(), PublicGameSigningIdentity.CERTIFICATE_SHA256, 36); checks++;

        String[] fixtureNames = {"picked-older", "base", "picked-newer"};
        String[] versionNames = {"picked-older", "test", "picked-newer"};
        for (int index = 0; index < fixtureNames.length; index++) {
            long version = 122 + index;
            String label = "Picked version " + version + " with installed bridge version 123";
            Map<String, byte[]> selectedEntries = contents(fixtures.resolve(fixtureNames[index] + ".apk").toFile());
            selectedEntries.put("classes.dex", Files.readAllBytes(gameDex.toPath()));
            selectedEntries.put("assets/preserved.bin", new byte[]{50,60,(byte) version,70});
            File selected = archive(caseRoot.resolve("picked-" + version + ".apk"), selectedEntries);
            BinaryManifestPatch.ManifestInfo selectedManifest = BinaryManifestPatch.inspect(ApkArchive.manifest(selected));
            check(selectedManifest.versionCode == version && selectedManifest.versionName.equals(versionNames[index]),
                    label + " fixture has the requested version in its binary manifest");
            check(!Arrays.equals(selectedEntries.get("assets/preserved.bin"), installedEntries.get("assets/preserved.bin")),
                    label + " selected payload differs even when the version is equal");
            String selectedHash = ApkArchive.sha256(selected);
            String sourceSetHash = ApkSetValidator.sourceSetSha256(ApkSetValidator.inspect(List.of(selected), false));
            int previousOpens = context.resolver.opens;
            GamePreparationService.PreparedPair picked = prepare(context, selected, runtime);
            check(context.resolver.opens == previousOpens + 1, label + " reads the selected SAF source once");
            check(!picked.gameApk.equals(retained.gameApk) && !picked.sourceApkSetSha256.equals(retained.sourceApkSetSha256),
                    label + " does not reuse the other installed bridge's retained receipt");
            checkPickedPayload(picked, selected, selectedEntries, version, versionNames[index], sourceSetHash, label);
            check(picked.gameStrategy == (version < 123 ? GamePreparationService.InstallStrategy.REQUIRES_UNINSTALL
                    : GamePreparationService.InstallStrategy.COMPATIBLE_UPDATE),
                    label + " reports installed compatibility without replacing the selected version");
            checkPickedPayload(service.reloadPreparedForInstallation(picked), selected, selectedEntries,
                    version, versionNames[index], sourceSetHash, label + " after hot reload");
            checkPickedPayload(service.reloadPrepared(), selected, selectedEntries,
                    version, versionNames[index], sourceSetHash, label + " after cold reload");
            check(context.resolver.opens == previousOpens + 1, label + " reloads never reopen SAF");
            check(selectedHash.equals(ApkArchive.sha256(selected)), label + " leaves the selected original unchanged");
            check(installedHash.equals(ApkArchive.sha256(installedPath.toFile()))
                    && installedModified == Files.getLastModifiedTime(installedPath).toMillis(),
                    label + " never writes the installed bridge APK");
            check(context.packages.infos.get(installed.packageName) == installed && installed.versionCode == 123
                    && installed.versionName.equals("test") && installed.sharedUserId == null
                    && installed.firstInstallTime == 1000 && installed.lastUpdateTime == 2000
                    && installed.applicationInfo.uid == 10042 && installed.applicationInfo.sourceDir.equals(installedPath.toString())
                    && installed.applicationInfo.publicSourceDir.equals(installedPath.toString())
                    && installed.applicationInfo.splitSourceDirs == null,
                    label + " leaves installed package metadata unchanged");
            check(installedHash.equals(ApkArchive.sha256(retained.gameApk)),
                    label + " leaves the installed bridge's retained preparation unchanged");
        }
    }
    static void rejectChildSymlink(Path path, Path moved, GamePreparationService.PreparedPair pair,
            GamePreparationService service, String label) throws Exception {
        Files.move(path, moved);
        Files.createSymbolicLink(path, moved.toAbsolutePath());
        try {
            check(!ValidatedPreparationCache.isUnchanged(pair), label + " invalidates cached proof");
            rejects(() -> ValidatedPreparationCache.requireUnchanged(pair), label + " cannot authorize a hot installation");
            rejects(() -> service.reloadPreparedForInstallation(pair), label + " also fails full installation revalidation");
        } finally {
            Files.delete(path); Files.move(moved, path);
        }
    }

    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of(args[0]); File gameDex = new File(args[1]), runtime = new File(args[2]);
        Path output = Path.of(args[3]); Files.createDirectories(output); File historicalRuntime = args[4].isEmpty() ? null : new File(args[4]);
        Path temp = Files.createTempDirectory("pdsync-service-");
        try {
            Map<String, byte[]> originalEntries = contents(fixtures.resolve("base.apk").toFile());
            originalEntries.put("classes.dex", Files.readAllBytes(gameDex.toPath()));
            originalEntries.put("assets/preserved.bin", new byte[]{0,1,2,-1,7});
            File source = archive(temp.resolve("source.apk"), originalEntries);
            TestContext pickedRoot = new TestContext(temp.resolve("picked-root").toFile());
            GamePreparationService rootInspector = new GamePreparationService(pickedRoot);
            Uri rootBase = pickedRoot.source("base", source);
            Uri rootSplit = pickedRoot.source("split", fixtures.resolve("split.apk").toFile());
            String sourceHash = ApkArchive.sha256(source);
            check(rootInspector.inspectSelectedApkPackage(List.of(rootBase)).equals("com.example.game"),
                    "Picked Root resolves real binary manifest package without requiring source signature or installation");
            check(pickedRoot.resolver.opens == 1, "Picked Root manifest inspection opens the source only once");
            check(pickedRoot.root.list().length == 0, "Picked Root inspection leaves no receipt, staging, selection or cached input");
            check(sourceHash.equals(ApkArchive.sha256(source)), "Picked Root inspection does not modify the source APK");
            check(rootInspector.inspectSelectedApkPackage(List.of(rootSplit, rootBase)).equals("com.example.game"),
                    "Picked Root resolves same package from base plus split in either order");
            rejects(() -> rootInspector.inspectSelectedApkPackage(List.of(rootSplit)), "Split-only selection is not a game base");
            rejects(() -> rootInspector.inspectSelectedApkPackage(List.of(rootBase, rootBase)), "Duplicate URI rejected");
            rejects(() -> rootInspector.inspectSelectedApkPackage(List.of(rootBase, pickedRoot.source("base-copy", source))),
                    "Two base APKs are not one installed target");
            rejects(() -> rootInspector.inspectSelectedApkPackage(List.of(rootBase, rootSplit,
                    pickedRoot.source("split-copy", fixtures.resolve("split.apk").toFile()))), "Duplicate split identity rejected");
            rejects(() -> rootInspector.inspectSelectedApkPackage(List.of()), "Empty selection rejected");
            rejects(() -> rootInspector.inspectSelectedApkPackage(List.of(Uri.fromFile(source))), "Only SAF content sources accepted");
            Uri broken = Uri.parse("content://test/broken"); pickedRoot.resolver.contents.put(broken, new byte[]{1,2,3});
            rejects(() -> rootInspector.inspectSelectedApkPackage(List.of(broken)), "Invalid manifest cannot select a package");
            check(pickedRoot.root.list().length == 0, "Invalid picked identity also cleans temporary inputs");
            pickedRoot.resolver.failAfter = 100;
            Exception rootReadFailure = rejects(() -> rootInspector.inspectSelectedApkPackage(List.of(rootBase)), "SAF failure is not installed-target success");
            check("100".equals(PreparationDiagnostics.details(rootReadFailure).get("input.0.bytes")),
                    "Root identity inspection failure retains bounded SAF copy evidence");
            check(pickedRoot.root.list().length == 0, "Interrupted Root identity read cleans temporary inputs");
            pickedRoot.resolver.failAfter = -1;
            TestContext first = new TestContext(temp.resolve("first").toFile()); GamePreparationService service = new GamePreparationService(first);
            GamePreparationService.PreparedPair prepared = service.prepareSelectedApks(List.of(first.source("unsigned", source)), false, new FileInputStream(runtime));
            check(prepared.sourceProvenance == GameSourceIdentity.Provenance.UNSIGNED && prepared.sourceSignerCertificateSha256.isEmpty(), "Unsigned input has explicit no-original-signer provenance");
            check(first.resolver.opens == 1, "SAF source is opened once");
            check(prepared.gamePackage.equals("com.example.game") && prepared.gameVersionCode == 123, "Actual manifest identity retained");
            check(BinaryManifestPatch.inspect(ApkArchive.manifest(prepared.gameApk)).targetSandboxVersion == 2, "Sandbox two retained");
            check(dexCount(prepared.gameApk) == 2, "Exactly one appended runtime");
            check(prepared.signingCertificateSha256.equals(PublicGameSigningIdentity.CERTIFICATE_SHA256), "Fixed public compatibility signer");
            LocalApkSigner.verify(prepared.gameApk, prepared.signingCertificateSha256, 26); checks++;
            LocalApkSigner.verify(prepared.gameApk, prepared.signingCertificateSha256, 36); checks++;
            check(ValidatedPreparationCache.isUnchanged(prepared), "Full production validation captures session");
            GamePreparationService.PreparedPair hot = service.reloadPreparedForInstallation(prepared);
            check(first.resolver.opens == 1 && ValidatedPreparationCache.isUnchanged(hot), "Installation reload reuses unchanged private session");
            check(service.reloadPrepared().namespaceId.equals(prepared.namespaceId), "Cold reload verifies same namespace");
            pickedSourceIsNotReplacedByInstalledBridge(fixtures, temp, gameDex, runtime, originalEntries);

            Path actualFrameworkRoot = Files.createDirectory(temp.resolve("framework-real"));
            Path frameworkAlias = temp.resolve("framework-alias");
            Files.createSymbolicLink(frameworkAlias, actualFrameworkRoot);
            TestContext aliasedContext = new TestContext(frameworkAlias.toFile());
            GamePreparationService aliasedService = new GamePreparationService(aliasedContext);
            GamePreparationService.PreparedPair aliasedPair = aliasedService.prepareSelectedApks(
                    List.of(aliasedContext.source("aliased-parent", source)), false, new FileInputStream(runtime));
            check(aliasedPair.gameApk.getAbsoluteFile().equals(aliasedPair.gameApk.getCanonicalFile()),
                    "Trusted framework parent alias is normalized before constructing preparation paths");
            check(aliasedPair.gameApk.toPath().startsWith(actualFrameworkRoot), "Prepared files use the real framework parent");
            check(ValidatedPreparationCache.isUnchanged(aliasedPair), "Framework parent alias succeeds through full production prepare and cache capture");
            GamePreparationService.PreparedPair aliasedHot = aliasedService.reloadPreparedForInstallation(aliasedPair);
            check(ValidatedPreparationCache.isUnchanged(aliasedHot) && aliasedContext.resolver.opens == 1,
                    "Framework parent alias succeeds through hot reload without reopening SAF");
            Path receiptDirectory = aliasedHot.gameApk.getParentFile().toPath();
            Path stagingRoot = receiptDirectory.getParent();
            rejectChildSymlink(stagingRoot, actualFrameworkRoot.resolve("relocated-staging"), aliasedHot, aliasedService, "Staging-root symlink");
            rejectChildSymlink(receiptDirectory, stagingRoot.resolve("relocated-receipt"), aliasedHot, aliasedService, "Receipt-directory symlink");
            rejectChildSymlink(aliasedHot.gameApk.toPath(), receiptDirectory.resolve("relocated-game.apk"), aliasedHot, aliasedService, "APK-leaf symlink");
            rejectChildSymlink(receiptDirectory.resolve("receipt.properties"), receiptDirectory.resolve("relocated-receipt.properties"),
                    aliasedHot, aliasedService, "Receipt-file symlink");
            check(ValidatedPreparationCache.isUnchanged(aliasedService.reloadPreparedForInstallation(aliasedHot)),
                    "Restored canonical files regain validated installation state");

            TestContext second = new TestContext(temp.resolve("no-receipt").toFile());
            GamePreparationService.PreparedPair adopted = prepare(second, prepared.gameApk, runtime);
            check(adopted.adoptedEmbedded && adopted.sourceProvenance == GameSourceIdentity.Provenance.INHERITED_EMBEDDED, "Receipt-less input adopted through production path");
            check(adopted.sourceSignerCertificateSha256.isEmpty(), "Public output signer is never called original signer");
            check(adopted.namespaceId.equals(prepared.namespaceId) && adopted.sourceApkSetSha256.equals(prepared.sourceApkSetSha256), "Inherited source identity and namespace retained");
            check(dexCount(adopted.gameApk) == dexCount(prepared.gameApk) && adopted.runtimeDexName.equals(prepared.runtimeDexName), "No duplicate injection");
            check(ApkArchive.entryHashes(adopted.gameApk).get("classes.dex").equals(ApkArchive.sha256(gameDex)), "Original game DEX retained");
            check(Arrays.equals(contents(adopted.gameApk).get("assets/preserved.bin"), originalEntries.get("assets/preserved.bin")), "Assets retained exactly");
            GamePreparationService.PreparedPair recovered = prepare(first, prepared.gameApk, runtime);
            check(!recovered.adoptedEmbedded && recovered.sourceProvenance == GameSourceIdentity.Provenance.UNSIGNED && recovered.namespaceId.equals(prepared.namespaceId), "Retained original receipt recovery wins over adopted binding");

            BinaryManifestPatch.BridgeBinding binding = BinaryManifestPatch.readProviderBinding(ApkArchive.manifest(prepared.gameApk));
            if (historicalRuntime != null) {
            BinaryManifestPatch.BridgeBinding legacyBinding = new BinaryManifestPatch.BridgeBinding(binding.authority,
                    binding.gamePackage, binding.originIdentity, binding.namespaceId, binding.sourceApkSetSha256,
                    binding.gameVersionCode, binding.gameVersionName, binding.mainAppCertSha256, binding.bridgeRuntimeVersion, true, null);
            File oldUnsigned = temp.resolve("old-unsigned.apk").toFile();
            ApkArchive.InjectedDex oldDex = ApkArchive.injectProvider(source, oldUnsigned, historicalRuntime, legacyBinding);
            check(BinaryManifestPatch.readProviderBinding(ApkArchive.manifest(oldUnsigned)).accessProtocol == null, "Historical runtime manifest has no invented handshake marker");
            File oldSigned = temp.resolve("old-signed.apk").toFile(); LocalApkSigner.sign(oldUnsigned, oldSigned, LocalApkSigner.existing());
            TestContext oldContext = new TestContext(temp.resolve("old-no-receipt").toFile());
            GamePreparationService.PreparedPair upgraded = prepare(oldContext, oldSigned, runtime);
            check(upgraded.adoptedEmbedded && upgraded.runtimeDexName.equals(oldDex.name) && upgraded.namespaceId.equals(prepared.namespaceId), "Published code45 runtime upgrades without receipt");
            check(upgraded.runtimeDexSha256.equals(ApkArchive.sha256(runtime)) && dexCount(upgraded.gameApk) == 2, "Only recognized runtime is replaced");
            check("1".equals(BinaryManifestPatch.readProviderBinding(ApkArchive.manifest(upgraded.gameApk)).accessProtocol), "Upgrade installs explicit pure-handshake protocol marker");

            } else System.out.println("SKIP: code45 historical runtime replacement; no verified reference supplied");

            Map<String, byte[]> unknownEntries = contents(prepared.gameApk);
            byte[] unknownRuntime = Files.readAllBytes(runtime.toPath()); unknownRuntime[44] ^= 1;
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1"); sha1.update(unknownRuntime,32,unknownRuntime.length-32); System.arraycopy(sha1.digest(),0,unknownRuntime,12,20);
            Adler32 adler = new Adler32(); adler.update(unknownRuntime,12,unknownRuntime.length-12); BinaryManifestPatch.put32(unknownRuntime,8,adler.getValue());
            unknownEntries.put(prepared.runtimeDexName, unknownRuntime);
            File unknownUnsigned = archive(temp.resolve("unknown-runtime.apk"), unknownEntries);
            Exception unknown = rejects(() -> prepare(new TestContext(temp.resolve("unknown").toFile()), unknownUnsigned, runtime), "Unknown runtime cannot claim ownership by class names");
            check(PreparationDiagnostics.details(unknown).containsValue("EMBEDDED_RUNTIME_NOT_RECOGNIZED"), "Unknown runtime has specific diagnostic reason");

            Map<String, byte[]> orphanEntries = new LinkedHashMap<>(originalEntries); orphanEntries.put("META-INF/CERT.SF",new byte[]{1,2,3});
            File orphan = archive(temp.resolve("orphan.apk"), orphanEntries); TestContext rejected = new TestContext(temp.resolve("rejected").toFile());
            Exception badSignature = rejects(() -> prepare(rejected, orphan, runtime), "Orphan signature material is not unsigned");
            Map<String, String> evidence = PreparationDiagnostics.details(badSignature);
            check("true".equals(evidence.get("input.0.eof")) && "true".equals(evidence.get("input.0.cache_match")), "Failure preserves actual input/cache proof after cleanup");
            check(ApkArchive.sha256(orphan).equals(evidence.get("input.0.sha256")) && "PARSED".equals(evidence.get("input.0.metadata_status")), "Failure retains input hash and parsed metadata");
            check("36".equals(evidence.get("input.0.target_sdk")) && "123".equals(evidence.get("input.0.version_code")), "Failure retains target/package version");
            check(evidence.containsKey("verifier.issue.0") && "36".equals(evidence.get("verifier.api")), "Failure retains verifier issue/API");

            Map<String, byte[]> missingManifest = new LinkedHashMap<>(originalEntries); missingManifest.remove("AndroidManifest.xml");
            File noManifest = archive(temp.resolve("no-manifest.apk"), missingManifest);
            Exception noManifestFailure = rejects(() -> prepare(new TestContext(temp.resolve("no-manifest").toFile()),noManifest,runtime), "Missing manifest rejected in production intake");
            check("FAILED".equals(PreparationDiagnostics.details(noManifestFailure).get("input.0.metadata_status")), "Failed metadata status survives cleanup");
            byte[] truncatedBytes = Files.readAllBytes(source.toPath());
            File truncated = temp.resolve("truncated.apk").toFile(); Files.write(truncated.toPath(),Arrays.copyOf(truncatedBytes,truncatedBytes.length-8));
            rejects(() -> prepare(new TestContext(temp.resolve("truncated").toFile()),truncated,runtime), "Truncated ZIP rejected in production intake");
            byte[] badCrcBytes = Files.readAllBytes(source.toPath());
            for (int offset=0;offset<badCrcBytes.length-46;offset++) if (BinaryManifestPatch.unchecked32(badCrcBytes,offset)==0x02014b50L) {
                badCrcBytes[offset+16]^=1; break;
            }
            File badCrc = temp.resolve("bad-crc.apk").toFile(); Files.write(badCrc.toPath(),badCrcBytes);
            Exception crcFailure = rejects(() -> prepare(new TestContext(temp.resolve("crc").toFile()),badCrc,runtime), "Wrong central CRC rejected in production intake");
            check(PreparationDiagnostics.details(crcFailure).containsValue("ZIP_CRC_MISMATCH"), "CRC rejection has stable exact reason");

            Map<String, byte[]> wrongBindingEntries = contents(prepared.gameApk);
            byte[] wrongBinding = wrongBindingEntries.get("AndroidManifest.xml").clone(); boolean changedOrigin = false;
            for (java.nio.charset.Charset charset : new java.nio.charset.Charset[]{java.nio.charset.StandardCharsets.UTF_8,java.nio.charset.StandardCharsets.UTF_16LE}) {
                byte[] originBytes = prepared.originIdentity.getBytes(charset);
                for (int offset=0;offset<=wrongBinding.length-originBytes.length;offset++) {
                    if (Arrays.equals(originBytes,Arrays.copyOfRange(wrongBinding,offset,offset+originBytes.length))) {
                        wrongBinding[offset]=(byte)(wrongBinding[offset]=='a'?'b':'a'); changedOrigin=true; break;
                    }
                }
                if (changedOrigin) break;
            }
            check(changedOrigin,"Fixture changes a real bound origin string");
            wrongBindingEntries.put("AndroidManifest.xml",wrongBinding);
            File wrongBound = archive(temp.resolve("wrong-bound.apk"),wrongBindingEntries);
            rejects(() -> prepare(new TestContext(temp.resolve("wrong-bound").toFile()),wrongBound,runtime), "Tampered origin/namespace binding rejected");

            TestContext shortRead = new TestContext(temp.resolve("short").toFile()); shortRead.resolver.failAfter = 100;
            Exception failedRead = rejects(() -> prepare(shortRead, source, runtime), "Truncated SAF stream fails");
            Map<String, String> readEvidence = PreparationDiagnostics.details(failedRead);
            check("100".equals(readEvidence.get("input.0.bytes")) && "false".equals(readEvidence.get("input.0.eof")), "Partial read bytes/EOF survive failure");
            check(!readEvidence.toString().contains("secret.example") && !readEvidence.toString().contains("credential"), "Raw URL and credentials omitted");
            check(shortRead.resolver.opens == 1, "Failed nonrepeatable source never reopened");

            File splitSource = fixtures.resolve("split.apk").toFile(); TestContext splits = new TestContext(temp.resolve("splits").toFile());
            GamePreparationService.PreparedPair splitPair = new GamePreparationService(splits).prepareSelectedApks(List.of(splits.source("split",splitSource),splits.source("base",source)),false,new FileInputStream(runtime));
            check(splitPair.gameApks.size() == 2 && splitPair.sourceProvenance == GameSourceIdentity.Provenance.UNSIGNED, "Complete unsigned split set is sorted and prepared");
            TestContext splitAdopt = new TestContext(temp.resolve("split-adopt").toFile());
            GamePreparationService.PreparedPair adoptedSplits = new GamePreparationService(splitAdopt).prepareSelectedApks(List.of(splitAdopt.source("split",splitPair.gameApks.get(1)),splitAdopt.source("base",splitPair.gameApks.get(0))),false,new FileInputStream(runtime));
            check(adoptedSplits.gameApks.size()==2 && adoptedSplits.namespaceId.equals(splitPair.namespaceId), "Complete no-receipt embedded split set retains binding");
            check(Arrays.equals(ApkArchive.manifest(splitPair.gameApks.get(1)),ApkArchive.manifest(adoptedSplits.gameApks.get(1))), "Adopted split manifest unchanged");
            TestContext mixed = new TestContext(temp.resolve("mixed").toFile());
            rejects(() -> new GamePreparationService(mixed).prepareSelectedApks(List.of(mixed.source("unsigned",source),mixed.source("signed",splitPair.gameApks.get(1))),false,new FileInputStream(runtime)), "Mixed signed/unsigned split set rejected");

            Files.write(prepared.gameApk.toPath(), new byte[]{1}, StandardOpenOption.APPEND);
            check(!ValidatedPreparationCache.isUnchanged(prepared), "Output mutation invalidates hot-session stamp");
            rejects(() -> service.reloadPreparedForInstallation(prepared), "Mutated prepared output re-enters full validation and rejects");
            Files.copy(adopted.gameApk.toPath(),output.resolve("service-unsigned-adopted.apk"),StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(output.resolve("service-diagnostics.txt"),evidence.toString());
            System.out.println("Preparation service production-path checks passed: " + checks + "; Android framework stubbed; no APK executed");
        } finally {
            try (java.util.stream.Stream<Path> files=Files.walk(temp)) { files.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.delete(path);}catch(IOException ignored){}}); }
        }
    }
}
