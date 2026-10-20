package io.github.xyz1001.pdsync.android.prepare;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded facts from one actual intake read. No source URI, path, content or exception text. */
public final class PreparationInputEvidence {
    public final int index;
    public long bytes, cacheBytes = -1, copyMillis;
    public String sha256 = "unknown", cacheSha256 = "unknown", metadataStatus = "NOT_CHECKED";
    public boolean eof, cacheMatch;
    public String gamePackage = "unknown";
    public long versionCode = -1;
    public int minSdk = -1, targetSdk = -1;
    PreparationInputEvidence(int index) { this.index = index; }
    void metadata(BinaryManifestPatch.ManifestInfo info) {
        gamePackage = info.packageName; versionCode = info.versionCode;
        minSdk = info.minSdkVersion; targetSdk = info.targetSdkVersion; metadataStatus = "PARSED";
    }
    Map<String, String> details() {
        Map<String, String> result = new LinkedHashMap<>(); String prefix = "input." + index + ".";
        result.put(prefix + "bytes", Long.toString(bytes)); result.put(prefix + "sha256", sha256);
        result.put(prefix + "eof", Boolean.toString(eof)); result.put(prefix + "copy_ms", Long.toString(copyMillis));
        result.put(prefix + "cache_bytes", Long.toString(cacheBytes)); result.put(prefix + "cache_sha256", cacheSha256);
        result.put(prefix + "cache_match", Boolean.toString(cacheMatch)); result.put(prefix + "metadata_status", metadataStatus);
        result.put(prefix + "package", gamePackage.length() <= 255 && gamePackage.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+") ? gamePackage : "unknown");
        result.put(prefix + "version_code", Long.toString(versionCode)); result.put(prefix + "min_sdk", Integer.toString(minSdk));
        result.put(prefix + "target_sdk", Integer.toString(targetSdk)); return result;
    }
}
