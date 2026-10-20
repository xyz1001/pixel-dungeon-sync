package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable PM binding. It preserves embedded provenance; it never invents an original signer. */
public final class InstalledBridgeBinding {
    public final String gamePackage, gameLabel, gameVersionName, originIdentity, namespaceId;
    public final String sourceApkSetSha256, authority, bridgeRuntimeVersion, accessProtocol, signingCertificateSha256, dataDir;
    public final long gameVersionCode, versionCode, firstInstallTime, lastUpdateTime;
    public final int uid;
    public final List<String> apkPaths;

    InstalledBridgeBinding(String gamePackage, String gameLabel, String versionName, long versionCode,
            String origin, String namespace, String sourceHash, String authority, String runtime, String accessProtocol,
            String signer, int uid, String dataDir, long firstInstall, long lastUpdate, List<String> apkPaths) throws IOException {
        if (gamePackage == null || !gamePackage.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
                || "io.github.xyz1001.pdsync.android".equals(gamePackage)
                || !hash(origin) || !hash(sourceHash) || !hash(signer) || uid < 0 || versionCode < 0
                || firstInstall < 0 || lastUpdate < 0 || !"1".equals(runtime) || accessProtocol == null
                || (!accessProtocol.isEmpty() && !"1".equals(accessProtocol))
                || !("android-" + origin).equals(namespace)
                || !("io.github.xyz1001.pdsync.embedded.g" + origin.substring(0, 32) + ".files").equals(authority)
                || dataDir == null || !new java.io.File(dataDir).isAbsolute()
                || apkPaths == null || apkPaths.isEmpty() || apkPaths.size() > 64)
            throw new IOException(AndroidMessages.INSTALLED_BRIDGE_BINDING_01.text());
        for (String path : apkPaths) if (path == null || !new java.io.File(path).isAbsolute())
            throw new IOException(AndroidMessages.INSTALLED_BRIDGE_BINDING_02.text());
        if (new java.util.HashSet<>(apkPaths).size() != apkPaths.size()) throw new IOException(AndroidMessages.INSTALLED_BRIDGE_BINDING_03.text());
        this.gamePackage = gamePackage; this.gameLabel = label(gameLabel, 512);
        this.gameVersionName = label(versionName == null ? "" : versionName, 1024);
        this.gameVersionCode = versionCode; this.versionCode = versionCode;
        this.originIdentity = origin; this.namespaceId = namespace; this.sourceApkSetSha256 = sourceHash;
        this.authority = authority; this.bridgeRuntimeVersion = runtime; this.accessProtocol = accessProtocol; this.signingCertificateSha256 = signer;
        this.uid = uid; this.dataDir = dataDir; this.firstInstallTime = firstInstall; this.lastUpdateTime = lastUpdate;
        this.apkPaths = Collections.unmodifiableList(new ArrayList<>(apkPaths));
    }
    public String selectionToken() {
        return "installed-bridge-v1:" + gamePackage + ":" + originIdentity + ":" + sourceApkSetSha256
                + ":" + gameVersionCode + ":" + uid + ":" + firstInstallTime + ":" + lastUpdateTime
                + ":" + signingCertificateSha256;
    }
    public boolean matches(InstalledBridgeBinding other) {
        return other != null && selectionToken().equals(other.selectionToken())
                && authority.equals(other.authority) && namespaceId.equals(other.namespaceId)
                && bridgeRuntimeVersion.equals(other.bridgeRuntimeVersion) && accessProtocol.equals(other.accessProtocol) && dataDir.equals(other.dataDir)
                && gameVersionName.equals(other.gameVersionName) && apkPaths.equals(other.apkPaths);
    }
    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static String label(String value, int limit) throws IOException {
        if (value == null || value.length() > limit) throw new IOException(AndroidMessages.INSTALLED_BRIDGE_BINDING_04.text());
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i)))
            throw new IOException(AndroidMessages.INSTALLED_BRIDGE_BINDING_04.text());
        return value;
    }
}
