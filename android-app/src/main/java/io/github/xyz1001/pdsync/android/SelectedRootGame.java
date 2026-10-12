package io.github.xyz1001.pdsync.android;

import io.github.xyz1001.pdsync.android.prepare.GameSourceIdentity;
import io.github.xyz1001.pdsync.root.RootBinding;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/** Immutable PM snapshot. A saved selection is never evidence of current root authorization. */
public final class SelectedRootGame {
    public final String gamePackage, gameLabel, gameVersionName, dataDir;
    public final long gameVersionCode, firstInstallTime, lastUpdateTime;
    public final int userId, uid;
    public final List<String> currentSignerCertificateSha256, sourceSignerCertificateSha256;
    public final String originIdentity, namespaceId, sourceApkSetSha256, installedApkSetSha256;
    public final SortedMap<String, String> apkHashes;
    public final boolean retainedProvenance;
    private final RootBinding binding;

    public SelectedRootGame(String gamePackage, String gameLabel, String gameVersionName,
            long gameVersionCode, long firstInstallTime, long lastUpdateTime, int userId, int uid,
            String dataDir, List<String> currentSigners, List<String> sourceSigners,
            String sourceApkSetSha256, Map<String, String> apkHashes, boolean retainedProvenance) throws IOException {
        GameSourceIdentity source = new GameSourceIdentity(gamePackage, sourceSigners);
        GameSourceIdentity current = new GameSourceIdentity(gamePackage, currentSigners);
        this.binding = new RootBinding(gamePackage, userId, uid, dataDir, source.originIdentity, apkHashes);
        if (gameVersionCode < 0 || firstInstallTime < 0 || lastUpdateTime < 0
                || !RootBinding.isHash(sourceApkSetSha256)) throw new IOException("Invalid root selection identity");
        this.gamePackage = gamePackage;
        this.gameLabel = label(gameLabel, 512);
        this.gameVersionName = label(gameVersionName, 256);
        this.gameVersionCode = gameVersionCode;
        this.firstInstallTime = firstInstallTime;
        this.lastUpdateTime = lastUpdateTime;
        this.userId = userId; this.uid = uid; this.dataDir = dataDir;
        this.currentSignerCertificateSha256 = current.signerCertificateSha256;
        this.sourceSignerCertificateSha256 = source.signerCertificateSha256;
        this.originIdentity = source.originIdentity; this.namespaceId = source.namespaceId;
        this.apkHashes = binding.apkHashes;
        this.sourceApkSetSha256 = sourceApkSetSha256;
        this.installedApkSetSha256 = ApkSetIdentity.digest(new ArrayList<>(apkHashes.values()));
        this.retainedProvenance = retainedProvenance;
        if (!retainedProvenance && !current.signerCertificateSha256.equals(source.signerCertificateSha256))
            throw new IOException("Original publisher requires validated provenance");
    }

    public RootBinding binding() { return binding; }
    public String selectionToken() throws IOException { return "root-v1:" + binding.fingerprint() + ":" + sourceApkSetSha256 + ":" + gameVersionCode
            + ":" + firstInstallTime + ":" + lastUpdateTime + ":" + String.join(",",currentSignerCertificateSha256); }
    public boolean matches(SelectedRootGame other) {
        return other != null && binding.matchesInstalled(other.binding)
                && originIdentity.equals(other.originIdentity) && sourceApkSetSha256.equals(other.sourceApkSetSha256)
                && currentSignerCertificateSha256.equals(other.currentSignerCertificateSha256)
                && sourceSignerCertificateSha256.equals(other.sourceSignerCertificateSha256)
                && gameVersionCode == other.gameVersionCode && gameVersionName.equals(other.gameVersionName)
                && firstInstallTime == other.firstInstallTime && lastUpdateTime == other.lastUpdateTime
                && retainedProvenance == other.retainedProvenance;
    }
    private static String label(String value, int max) throws IOException {
        if (value == null || value.length() > max) throw new IOException("Invalid selection label");
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i)))
            throw new IOException("Invalid selection label");
        return value;
    }
}
