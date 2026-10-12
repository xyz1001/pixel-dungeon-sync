package io.github.xyz1001.pdsync.bridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import java.util.HashMap;
import java.util.Map;

/** Injected string metadata; no game resource IDs or helper resources are required. */
final class BridgeMetadata {
    private BridgeMetadata() { }

    static ProviderInfo provider(Context context) throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getProviderInfo(new ComponentName(context.getPackageName(),
                SaveBridgeProvider.class.getName()), PackageManager.GET_META_DATA);
    }

    static Map<String, String> read(Context context) throws PackageManager.NameNotFoundException {
        ApplicationInfo application = context.getPackageManager().getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
        ProviderInfo provider = provider(context);
        Map<String, String> metadata = new HashMap<>();
        copy(application.metaData, metadata);
        copy(provider.metaData, metadata);
        return metadata;
    }

    private static void copy(Bundle source, Map<String, String> target) {
        if (source == null) return;
        for (String key : source.keySet()) {
            if (!key.startsWith(GameBinding.PREFIX)) continue;
            Object value = source.get(key);
            // Legacy certificate metadata was a string resource ID; it is never
            // used by the embedded path and cannot select a different trust root.
            if (key.equals(GameBinding.PREFIX + "EXPECTED_MAIN_CERT_SHA256")) continue;
            if (!(value instanceof String)) throw new SecurityException("Bridge metadata must use literal strings");
            String existing = target.put(key, (String) value);
            if (existing != null && !existing.equals(value)) throw new SecurityException("Conflicting bridge metadata");
        }
    }
}
