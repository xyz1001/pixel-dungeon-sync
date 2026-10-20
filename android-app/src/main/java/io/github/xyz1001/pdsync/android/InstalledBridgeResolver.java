package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Package-manager discovery only. No save/APK contents, receipt, key store, or root probe. */
public final class InstalledBridgeResolver {
    static final String PREFIX = "io.github.xyz1001.pdsync.";
    static final String PROVIDER = "io.github.xyz1001.pdsync.bridge.SaveBridgeProvider";
    static final String MAIN = "io.github.xyz1001.pdsync.android";
    private InstalledBridgeResolver() { }

    /** Null means no own bridge marker/component. Malformed own candidates fail closed. */
    public static InstalledBridgeBinding resolve(Context context, String packageName) throws Exception {
        PackageManager pm = context.getPackageManager();
        PackageInfo game = pm.getPackageInfo(packageName,
                PackageTrust.signingFlags() | PackageManager.GET_META_DATA | PackageManager.GET_PROVIDERS);
        if (game.applicationInfo == null) throw new SecurityException(AndroidMessages.INSTALLED_BRIDGE_RESOLVER_01.text());
        Map<String, String> metadata = new HashMap<>();
        ProviderInfo own = null;
        if (game.providers != null) for (ProviderInfo provider : game.providers) {
            if (provider != null && PROVIDER.equals(provider.name)) {
                if (own != null) throw new SecurityException(AndroidMessages.INSTALLED_BRIDGE_RESOLVER_02.text());
                own = provider;
            }
        }
        Bundle applicationMetadata = game.applicationInfo.metaData;
        boolean marker = applicationMetadata != null && applicationMetadata.containsKey(PREFIX + "BRIDGE_MODE");
        if (own == null && !marker) return null;
        copy(applicationMetadata, metadata);
        if (own != null) copy(own.metaData, metadata);
        if (!"embedded-v1".equals(metadata.get(PREFIX + "BRIDGE_MODE")) || own == null)
            throw new SecurityException(AndroidMessages.INSTALLED_BRIDGE_RESOLVER_03.text());
        PackageInfo main = pm.getPackageInfo(context.getPackageName(), 0);
        if (!MAIN.equals(context.getPackageName()) || main.applicationInfo == null
                || !packageName.equals(game.packageName) || !game.applicationInfo.enabled
                || game.sharedUserId != null || main.sharedUserId != null
                || game.applicationInfo.uid == main.applicationInfo.uid
                || !sole(pm.getPackagesForUid(game.applicationInfo.uid), packageName)
                || !sole(pm.getPackagesForUid(main.applicationInfo.uid), MAIN))
            throw new SecurityException(AndroidMessages.INSTALLED_BRIDGE_RESOLVER_04.text());
        if (!packageName.equals(required(metadata, "BOUND_GAME_PACKAGE"))
                || !Long.toString(PackageTrust.versionCode(game)).equals(required(metadata, "BOUND_GAME_VERSION_CODE"))
                || !(game.versionName == null ? "" : game.versionName).equals(required(metadata, "BOUND_GAME_VERSION_NAME")))
            throw new SecurityException(AndroidMessages.INSTALLED_BRIDGE_RESOLVER_05.text());
        String origin = required(metadata, "BOUND_ORIGIN_IDENTITY");
        if (!origin.matches("[0-9a-f]{64}")) throw new SecurityException(AndroidMessages.INSTALLED_BRIDGE_RESOLVER_06.text());
        String authority = "io.github.xyz1001.pdsync.embedded.g" + origin.substring(0, 32) + ".files";
        requireProvider(own, packageName, authority, game.applicationInfo.uid);
        ProviderInfo resolved = pm.resolveContentProvider(authority, PackageManager.GET_META_DATA);
        requireProvider(resolved, packageName, authority, game.applicationInfo.uid);
        Map<String,String> resolvedMetadata = new HashMap<>();
        copy(applicationMetadata, resolvedMetadata); copy(resolved.metaData, resolvedMetadata);
        if (!metadata.equals(resolvedMetadata)) throw new SecurityException(AndroidMessages.INSTALLED_BRIDGE_RESOLVER_07.text());
        ArrayList<String> paths = new ArrayList<>();
        for (File apk : PackageTrust.installedApks(game)) paths.add(apk.getAbsolutePath());
        CharSequence label = pm.getApplicationLabel(game.applicationInfo);
        return new InstalledBridgeBinding(packageName, label == null ? packageName : label.toString(),
                game.versionName, PackageTrust.versionCode(game), origin, required(metadata,"BOUND_NAMESPACE_ID"),
                required(metadata,"BOUND_SOURCE_APK_SHA256"), authority, required(metadata,"BRIDGE_RUNTIME_VERSION"), metadata.getOrDefault(PREFIX + "BRIDGE_ACCESS_PROTOCOL", ""),
                PackageTrust.signer(game), game.applicationInfo.uid, game.applicationInfo.dataDir,
                game.firstInstallTime, game.lastUpdateTime, paths);
    }

    public static InstalledBridgeBinding requireUnchanged(Context context, InstalledBridgeBinding expected) throws Exception {
        if (expected == null) throw new SecurityException(AndroidMessages.INSTALLED_BRIDGE_RESOLVER_08.text());
        InstalledBridgeBinding current = resolve(context, expected.gamePackage);
        if (!expected.matches(current)) throw new SecurityException(AndroidMessages.INSTALLED_BRIDGE_RESOLVER_09.text());
        return current;
    }

    static void requireProvider(ProviderInfo p, String game, String authority, int uid) {
        if (p == null || !PROVIDER.equals(p.name) || !game.equals(p.packageName) || !authority.equals(p.authority)
                || !(game + ":pdsync_bridge").equals(p.processName) || !p.enabled || !p.exported
                || p.grantUriPermissions || !empty(p.readPermission) || !empty(p.writePermission)
                || p.applicationInfo == null || !p.applicationInfo.enabled || p.applicationInfo.uid != uid)
            throw new SecurityException(AndroidMessages.INSTALLED_BRIDGE_RESOLVER_10.text());
    }
    private static String required(Map<String,String> metadata, String name) throws IOException {
        String value = metadata.get(PREFIX + name);
        if (value == null) throw new IOException(AndroidMessages.INSTALLED_BRIDGE_RESOLVER_11.text() + name);
        return value;
    }
    private static void copy(Bundle source, Map<String,String> target) {
        if (source == null) return;
        for (String key : source.keySet()) {
            if (!key.startsWith(PREFIX) || key.equals(PREFIX + "EXPECTED_MAIN_CERT_SHA256")) continue;
            Object value = source.get(key);
            if (!(value instanceof String)) throw new SecurityException(AndroidMessages.INSTALLED_BRIDGE_RESOLVER_12.text());
            String old = target.put(key, (String)value);
            if (old != null && !old.equals(value)) throw new SecurityException(AndroidMessages.INSTALLED_BRIDGE_RESOLVER_13.text());
        }
    }
    private static boolean sole(String[] names, String name) { return names != null && names.length == 1 && name.equals(names[0]); }
    private static boolean empty(String value) { return value == null || value.isEmpty(); }
}
