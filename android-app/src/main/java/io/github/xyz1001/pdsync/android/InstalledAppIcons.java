package io.github.xyz1001.pdsync.android;

import android.content.pm.PackageManager;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import java.util.LinkedHashMap;
import java.util.Map;

/** A picker-local bounded cache; an unavailable app resource must never break selection. */
final class InstalledAppIcons {
    private final PackageManager packages;
    private final Map<String, Drawable> cache = new LinkedHashMap<String, Drawable>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Drawable> entry) { return size() > 32; }
    };
    InstalledAppIcons(PackageManager packages) { this.packages = packages; }
    Drawable load(String packageName) {
        Drawable icon = cache.get(packageName);
        if (icon != null) return icon;
        try { icon = packages.getApplicationIcon(packageName); }
        catch (PackageManager.NameNotFoundException | RuntimeException | OutOfMemoryError unavailable) { }
        if (icon == null) {
            try { icon = packages.getDefaultActivityIcon(); }
            catch (RuntimeException | OutOfMemoryError unavailable) { }
        }
        if (icon == null) icon = new ColorDrawable(0xffd9ddd6);
        cache.put(packageName, icon);
        return icon;
    }
}
