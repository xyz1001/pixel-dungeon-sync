package io.github.xyz1001.pdsync.android.prepare;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Constrained replacement of an independently recognized runtime, preserving all other source bytes. */
final class EmbeddedBridgeUpgrade {
    // Extracted from published code45 APK aa839df6...311e5, never inferred from a signing certificate.
    static final String CODE45_RUNTIME = "92aa07a4ebc64ccc9f78d3dae160afe191a342e7596c5e4b26cf56eb3699893d";
    final BinaryManifestPatch.BridgeBinding binding;
    final ApkArchive.InjectedDex runtime;
    private EmbeddedBridgeUpgrade(BinaryManifestPatch.BridgeBinding binding, ApkArchive.InjectedDex runtime) {
        this.binding = binding; this.runtime = runtime;
    }

    static EmbeddedBridgeUpgrade inspect(List<File> sources, File currentRuntime) throws IOException {
        if (sources.isEmpty()) throw new IOException("Missing embedded APK set");
        BinaryManifestPatch.BridgeBinding binding = BinaryManifestPatch.readProviderBinding(ApkArchive.manifest(sources.get(0)));
        if (binding == null) return null;
        String currentHash = ApkArchive.sha256(currentRuntime); ApkArchive.InjectedDex recognized = null;
        Set<String> bridgeNames = PayloadInventory.classNames(PayloadInventory.readRuntime(currentRuntime));
        for (int index = 0; index < sources.size(); index++) {
            File source = sources.get(index);
            if (index > 0 && BinaryManifestPatch.hasEmbeddedPreparationMarkers(ApkArchive.manifest(source)))
                throw new IOException("Embedded bridge components must occur only in the base APK");
            Map<String, String> hashes = ApkArchive.entryHashes(source);
            int dexCount = 0; for (String name : hashes.keySet()) if (PayloadInventory.isDexEntry(name)) dexCount++;
            try (ZipFile zip = new ZipFile(source)) {
                for (Map.Entry<String, String> entry : hashes.entrySet()) {
                    if (!PayloadInventory.isDexEntry(entry.getKey())) continue;
                    byte[] bytes;
                    try (InputStream input = zip.getInputStream(zip.getEntry(entry.getKey()))) { bytes = ApkArchive.readLimited(input, PayloadInventory.MAX_DEX_BYTES); }
                    Set<String> classes = PayloadInventory.classNames(bytes); boolean bridge = false;
                    for (String name : classes) if (name.startsWith("Lio/github/xyz1001/pdsync/bridge/") || bridgeNames.contains(name)) bridge = true;
                    if (!bridge) continue;
                    if (index != 0 || recognized != null) throw new IOException("Ambiguous embedded runtime DEX ownership");
                    if (!entry.getValue().equals(currentHash) && !entry.getValue().equals(CODE45_RUNTIME))
                        throw new IOException("Unknown historical embedded runtime; original-source receipt is required");
                    String last = dexCount == 1 ? "classes.dex" : "classes" + dexCount + ".dex";
                    if (!last.equals(entry.getKey())) throw new IOException("Embedded runtime is not the isolated appended DEX boundary");
                    for (String name : classes) if (!name.startsWith("Lio/github/xyz1001/pdsync/bridge/"))
                        throw new IOException("Embedded runtime contains unowned original game classes");
                    if (!classes.contains("Lio/github/xyz1001/pdsync/bridge/SaveBridgeProvider;")) throw new IOException("Embedded runtime provider is missing");
                    recognized = new ApkArchive.InjectedDex(entry.getKey(), entry.getValue());
                }
            }
        }
        if (recognized == null) throw new IOException("Embedded binding has no recognized runtime DEX");
        return new EmbeddedBridgeUpgrade(binding, recognized);
    }

    ApkArchive.InjectedDex replaceBase(File source, File output, File runtimeFile, BinaryManifestPatch.BridgeBinding replacement) throws IOException {
        byte[] bytes = PayloadInventory.readRuntime(runtimeFile);
        ApkArchive.InjectedDex result = new ApkArchive.InjectedDex(runtime.name, ApkArchive.sha256(bytes));
        ApkArchive.replaceEmbedded(source, output, BinaryManifestPatch.replaceProviderBinding(ApkArchive.manifest(source), binding, replacement), runtime.name, bytes);
        verifyBasePayload(source, output, replacement, result); return result;
    }

    int verifyBasePayload(File source, File output, BinaryManifestPatch.BridgeBinding replacement, ApkArchive.InjectedDex updated) throws IOException {
        Map<String, String> expected = ApkArchive.protectedEntries(ApkArchive.entryHashes(source));
        if (!runtime.sha256.equals(expected.get(runtime.name)) || !runtime.name.equals(updated.name)) throw new IOException("Inherited runtime receipt mismatch");
        expected.put(runtime.name, updated.sha256);
        if (!expected.equals(ApkArchive.protectedEntries(ApkArchive.entryHashes(output)))) throw new IOException("Embedded upgrade changed original game payload");
        if (!Arrays.equals(BinaryManifestPatch.replaceProviderBinding(ApkArchive.manifest(source), binding, replacement), ApkArchive.manifest(output)))
            throw new IOException("Embedded upgrade changed unapproved manifest fields");
        return expected.size() - 1;
    }

    static int verifySplitPayload(File source, File output) throws IOException {
        int count = ApkArchive.verifyProtectedPayload(source, output);
        if (!Arrays.equals(ApkArchive.manifest(source), ApkArchive.manifest(output))) throw new IOException("Embedded upgrade changed split manifest");
        return count;
    }
}
