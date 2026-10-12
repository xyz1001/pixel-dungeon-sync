package io.github.xyz1001.pdsync.android.prepare;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Pure host tests: all input APK/DEX bytes remain inert. */
public final class ProviderInjectionTest {
    private static int checks;
    private static final String ORIGIN = "a".repeat(64), PIN = "b".repeat(64), SOURCE = "c".repeat(64);
    private static final String AUTHORITY = "io.github.xyz1001.pdsync.embedded.g" + ORIGIN.substring(0, 32) + ".files";
    interface Checked { void run() throws Exception; }
    private static void check(boolean result, String label) { checks++; if (!result) throw new AssertionError(label); }
    private static void rejects(Checked action, String label) throws Exception {
        checks++;
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Accepted unsafe case: " + label);
    }
    private static BinaryManifestPatch.BridgeBinding binding(boolean activation) throws IOException {
        return new BinaryManifestPatch.BridgeBinding(AUTHORITY, "com.example.game", ORIGIN, "android-" + ORIGIN,
                SOURCE, 123, "test", PIN, "1", activation);
    }
    private static File archive(Path path, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> input : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(input.getKey());
                if (input.getKey().endsWith(".so") || input.getKey().endsWith(".arsc") || input.getKey().endsWith("/")) {
                    entry.setMethod(ZipEntry.STORED); entry.setSize(input.getValue().length);
                    CRC32 crc = new CRC32(); crc.update(input.getValue()); entry.setCrc(crc.getValue());
                }
                output.putNextEntry(entry); output.write(input.getValue()); output.closeEntry();
            }
        }
        return path.toFile();
    }
    private static Map<String, byte[]> entries(File input) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(input)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                try (InputStream data = zip.getInputStream(entry)) { result.put(entry.getName(), data.readAllBytes()); }
            }
        }
        return result;
    }
    private static File changed(Path temp, File source, String path, byte[] content, String output) throws IOException {
        Map<String, byte[]> entries = entries(source);
        if (content == null) entries.remove(path); else entries.put(path, content);
        return archive(temp.resolve(output), entries);
    }
    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of(args[0]), temp = Files.createTempDirectory("pdsync-provider-host-");
        byte[] gameDex = Files.readAllBytes(Path.of(args[1]));
        File runtime = new File(args[2]); byte[] runtimeDex = Files.readAllBytes(runtime.toPath());
        BinaryManifestPatch.BridgeBinding binding = binding(true);
        try {
            byte[] originalManifest = ApkArchive.manifest(fixtures.resolve("base.apk").toFile());
            byte[] patched = BinaryManifestPatch.patchProvider(originalManifest, binding);
            check(Arrays.equals(patched, BinaryManifestPatch.patchProvider(originalManifest, binding)), "deterministic manifest patch");
            BinaryManifestPatch.ManifestInfo info = BinaryManifestPatch.inspect(patched);
            check(info.packageName.equals("com.example.game") && info.versionCode == 123 && info.versionName.equals("test"), "manifest game identity remains original");
            check(info.sharedUserId == null && !info.hasSharedUserMaxSdkVersion && info.targetSandboxVersion == 1, "no shared UID and sandbox one");
            check(info.applicationLabel.equals("Original game") && info.minSdkVersion == 26, "original app label and minimum SDK retained");
            BinaryManifestPatch.verifyProviderManifest(patched, binding); checks++;
            byte[] noActivation = BinaryManifestPatch.patchProvider(originalManifest, binding(false));
            BinaryManifestPatch.verifyProviderManifest(noActivation, binding(false)); checks++;
            rejects(() -> BinaryManifestPatch.verifyProviderManifest(noActivation, binding), "missing requested activation");
            rejects(() -> BinaryManifestPatch.patchProvider(patched, binding), "blind reinjection");
            check(binding.metadata().size() == 9 && binding.metadata().get("io.github.xyz1001.pdsync.MAIN_APP_CERT_SHA256").equals(PIN), "exact nine direct-string metadata bindings");
            rejects(() -> new BinaryManifestPatch.BridgeBinding(AUTHORITY + ";other", "com.example.game", ORIGIN, "android-"+ORIGIN,SOURCE,123,"test",PIN,"1"), "authority list injection");
            rejects(() -> new BinaryManifestPatch.BridgeBinding(AUTHORITY, "com.example.game", ORIGIN, "wrong",SOURCE,123,"test",PIN,"1"), "namespace mismatch");
            rejects(() -> new BinaryManifestPatch.BridgeBinding(AUTHORITY, "com.example.game", ORIGIN, "android-"+ORIGIN,SOURCE,123,"test","not-a-cert","1"), "invalid main certificate pin");
            rejects(() -> BinaryManifestPatch.patchProvider(originalManifest,new BinaryManifestPatch.BridgeBinding(AUTHORITY,"com.example.other",ORIGIN,"android-"+ORIGIN,SOURCE,123,"test",PIN,"1")), "wrong bound package");
            for (String conflict : new String[]{"conflict-class", "conflict-authority", "conflict-metadata", "duplicate-query"})
                rejects(() -> BinaryManifestPatch.patchProvider(ApkArchive.manifest(fixtures.resolve(conflict + ".apk").toFile()), binding), conflict);
            BinaryManifestPatch.verifyProviderManifest(BinaryManifestPatch.patchProvider(ApkArchive.manifest(fixtures.resolve("existing-query.apk").toFile()), binding), binding); checks++;

            for (String sdkFixture : new String[]{"sdk-old", "sdk-26", "sdk-new", "sdk-no-min", "sdk-no-target", "sdk-absent", "sdk-split-old"}) {
                File sdkSource = fixtures.resolve(sdkFixture + ".apk").toFile();
                byte[] sdkManifest = ApkArchive.manifest(sdkSource);
                byte[] sdkPatched = sdkFixture.startsWith("sdk-split") ? BinaryManifestPatch.patchProviderSplit(sdkManifest)
                        : BinaryManifestPatch.patchProvider(sdkManifest, binding);
                check(BinaryManifestPatch.inspect(sdkPatched).minSdkVersion == Math.max(26, BinaryManifestPatch.inspect(sdkManifest).minSdkVersion), "runtime minimum SDK floor: " + sdkFixture);
                Map<String, byte[]> sdkEntries = entries(sdkSource); sdkEntries.put("AndroidManifest.xml", sdkPatched);
                archive(Path.of(args[3], sdkFixture + "-patched.apk"), sdkEntries);
            }

            Map<String, byte[]> sourceEntries = new LinkedHashMap<>();
            sourceEntries.put("AndroidManifest.xml", originalManifest); sourceEntries.put("classes.dex", gameDex);
            sourceEntries.put("classes2.dex", gameDex); sourceEntries.put("lib/arm64-v8a/libgame.so", new byte[]{1,2,3,4,5,6});
            sourceEntries.put("resources.arsc", entries(fixtures.resolve("base.apk").toFile()).get("resources.arsc")); sourceEntries.put("res/drawable/original.bin", new byte[]{7,8,9});
            sourceEntries.put("assets/original.dat", new byte[]{0,-1,3,4}); sourceEntries.put("assets/empty/", new byte[0]);
            sourceEntries.put("META-INF/services/original", new byte[]{11,12}); sourceEntries.put("META-INF/CERT.RSA", new byte[]{99});
            File source = archive(temp.resolve("source.apk"), sourceEntries);
            byte[] originalApkBytes = Files.readAllBytes(source.toPath());
            File output = temp.resolve("injected.apk").toFile();
            ApkArchive.validateBridgeRuntime(List.of(source), runtime, binding);
            rejects(() -> ApkArchive.validateBridgeRuntime(List.of(source,fixtures.resolve("conflict-authority.apk").toFile()),runtime,binding), "conflicting authority in complete source APK set");
            ApkArchive.InjectedDex injected = ApkArchive.injectProvider(source, output, runtime, binding);
            check(injected.name.equals("classes3.dex") && injected.sha256.equals(ApkArchive.sha256(runtime)), "runtime appended as exact next DEX with independent hash");
            check(ApkArchive.verifyProviderPayload(source, output, binding, injected) == 8, "exact original protected payload count");
            check(Arrays.equals(originalApkBytes, Files.readAllBytes(source.toPath())), "original APK container unchanged");
            Map<String, byte[]> actual = entries(output);
            for (Map.Entry<String, byte[]> original : sourceEntries.entrySet()) {
                if (original.getKey().equals("AndroidManifest.xml") || ApkArchive.isSignature(original.getKey())) continue;
                check(Arrays.equals(original.getValue(), actual.get(original.getKey())), "original decompressed bytes retained: " + original.getKey());
            }
            check(!actual.containsKey("META-INF/CERT.RSA"), "original signature entry removed");
            rejects(() -> ApkArchive.verifyProtectedPayload(source, output), "old strict validator does not broadly permit additions");
            rejects(() -> ApkArchive.injectProvider(source, output, runtime, binding), "existing output cannot be overwritten");
            check(Arrays.equals(actual.get("classes3.dex"), runtimeDex), "injected runtime bytes unchanged");
            rejects(() -> ApkArchive.verifyProviderPayload(source, output, binding, new ApkArchive.InjectedDex("classes4.dex", injected.sha256)), "wrong injected DEX name");
            rejects(() -> ApkArchive.verifyProviderPayload(source, output, binding, new ApkArchive.InjectedDex(injected.name, PIN)), "wrong injected DEX hash");
            rejects(() -> ApkArchive.verifyProviderPayload(source, changed(temp,output,"assets/unexpected",new byte[]{1},"extra.apk"),binding,injected), "arbitrary extra entry");
            rejects(() -> ApkArchive.verifyProviderPayload(source, changed(temp,output,"classes4.dex",runtimeDex,"extra-dex.apk"),binding,injected), "undeclared extra DEX");
            rejects(() -> ApkArchive.verifyProviderPayload(source, changed(temp,output,"classes.dex",runtimeDex,"tampered-original.apk"),binding,injected), "changed original DEX");
            rejects(() -> ApkArchive.verifyProviderPayload(source, changed(temp,output,"assets/original.dat",null,"missing.apk"),binding,injected), "removed original asset");
            rejects(() -> ApkArchive.verifyProviderPayload(source, changed(temp,output,"classes3.dex",gameDex,"tampered-runtime.apk"),binding,injected), "changed runtime DEX");
            rejects(() -> ApkArchive.verifyProviderPayload(source, changed(temp,output,"AndroidManifest.xml",noActivation,"tampered-manifest.apk"),binding,injected), "unapproved manifest change");
            File gap = changed(temp, source, "classes.dex", null, "gap.apk");
            rejects(() -> ApkArchive.injectProvider(gap,temp.resolve("gap-output.apk").toFile(),runtime,binding), "source DEX gap");
            File collision = changed(temp,source,"classes2.dex",runtimeDex,"collision.apk");
            rejects(() -> ApkArchive.validateBridgeRuntime(List.of(source,collision),runtime), "collision anywhere in complete source APK set");
            rejects(() -> ApkArchive.injectProvider(collision,temp.resolve("collision-output.apk").toFile(),runtime,binding), "collision in base");
            byte[] invalidDex = runtimeDex.clone(); invalidDex[invalidDex.length - 1] ^= 1;
            Path badRuntime = temp.resolve("bad.dex"); Files.write(badRuntime,invalidDex);
            rejects(() -> ApkArchive.injectProvider(source,temp.resolve("bad-runtime.apk").toFile(),badRuntime.toFile(),binding), "malformed runtime checksum");
            check(!temp.resolve("bad-runtime.apk").toFile().exists(), "invalid runtime produces no output");
            rejects(() -> PayloadInventory.classNames(new byte[112]), "invalid DEX magic");
            check(PayloadInventory.classNames(gameDex).contains("Lcom/example/game/OriginalGame;"), "DEX definitions read without loading code");
            check(!PayloadInventory.isDexEntry("assets/classes.dex") && !PayloadInventory.isDexEntry("classes1.dex")
                    && PayloadInventory.isDexEntry("classes10.dex") && PayloadInventory.isDexEntry("classes100.dex"), "only canonical Android dex names");

            File noCode = fixtures.resolve("no-code.apk").toFile(), noCodeOutput = temp.resolve("no-code-output.apk").toFile();
            ApkArchive.InjectedDex firstDex = ApkArchive.injectProvider(noCode,noCodeOutput,runtime,binding);
            check(firstDex.name.equals("classes.dex"), "resource-only base receives its first DEX");
            ApkArchive.verifyProviderPayload(noCode,noCodeOutput,binding,firstDex); checks++;
            File split = fixtures.resolve("split.apk").toFile(), splitOutput = temp.resolve("split-output.apk").toFile();
            ApkArchive.patchProviderSplit(split,splitOutput);
            ApkArchive.verifyProviderSplitPayload(split,splitOutput); checks++;
            BinaryManifestPatch.ManifestInfo splitInfo = BinaryManifestPatch.inspect(ApkArchive.manifest(splitOutput));
            check(splitInfo.splitName.equals("config.arm64") && splitInfo.sharedUserId == null && splitInfo.targetSandboxVersion == 1, "split identity retained with UID migration only");
            check(ApkArchive.entryHashes(split).keySet().equals(ApkArchive.entryHashes(splitOutput).keySet()), "split receives no extra entry");
            rejects(() -> ApkArchive.injectProvider(split,temp.resolve("inject-split.apk").toFile(),runtime,binding), "runtime forbidden in split");
            rejects(() -> ApkArchive.patchProviderSplit(source,temp.resolve("split-base.apk").toFile()), "base cannot enter split-only patch path");
            Files.copy(output.toPath(), Path.of(args[3],"injected-fixture.apk"),StandardCopyOption.REPLACE_EXISTING);
            Files.copy(noCodeOutput.toPath(), Path.of(args[3],"no-code-injected-fixture.apk"),StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Provider injection host checks passed: " + checks + "; no APK/DEX code executed");
        } finally {
            try (var files = Files.walk(temp)) { files.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.delete(path); } catch (IOException ignored) { } }); }
        }
    }
}
