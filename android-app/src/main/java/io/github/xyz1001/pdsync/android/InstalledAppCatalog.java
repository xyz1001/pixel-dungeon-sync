package io.github.xyz1001.pdsync.android;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Cheap, platform-visible installed metadata. Selection performs identity checks separately. */
public final class InstalledAppCatalog {
    private InstalledAppCatalog() { }

    public static List<InstalledAppPickerModel.Entry> load(Context context, String selectedPackage)
            throws Exception {
        PackageManager pm = context.getPackageManager();
        String self = context.getPackageName();
        Map<String, PackageInfo> packages = new LinkedHashMap<>();
        for (PackageInfo info : pm.getInstalledPackages(0)) add(packages, info, self);

        Set<String> launchable = new HashSet<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo result : pm.queryIntentActivities(launcher, 0)) {
            ActivityInfo activity = result == null ? null : result.activityInfo;
            if (activity == null || !candidateName(activity.packageName, self)) continue;
            if (activity.enabled && activity.applicationInfo != null && activity.applicationInfo.enabled) {
                launchable.add(activity.packageName);
            }
            addVisiblePackage(pm, packages, activity.packageName, self);
        }
        // Explicit lookup preserves a visible selection even when it has no launcher or is a system app.
        if (candidateName(selectedPackage, self)) addVisiblePackage(pm, packages, selectedPackage, self);

        List<InstalledAppPickerModel.Entry> entries = new ArrayList<>();
        for (PackageInfo info : packages.values()) {
            ApplicationInfo app = info.applicationInfo;
            String label = info.packageName;
            try {
                CharSequence loaded = pm.getApplicationLabel(app);
                if (loaded != null && !loaded.toString().trim().isEmpty()) label = loaded.toString();
            } catch (RuntimeException unavailableLabel) {
                // A missing label resource does not invalidate the installed package metadata.
            }
            boolean system = (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            entries.add(new InstalledAppPickerModel.Entry(info.packageName, label, info.versionName,
                    app.enabled && launchable.contains(info.packageName), system));
        }
        return Collections.unmodifiableList(entries);
    }

    private static void addVisiblePackage(PackageManager pm, Map<String, PackageInfo> packages,
                                          String packageName, String self) throws Exception {
        if (packages.containsKey(packageName)) return;
        try {
            PackageInfo info = pm.getPackageInfo(packageName, 0);
            if (info != null && packageName.equals(info.packageName)) add(packages, info, self);
        } catch (PackageManager.NameNotFoundException removedOrInvisible) {
            // The package may disappear or become invisible between PM queries; never invent an entry.
        }
    }

    private static void add(Map<String, PackageInfo> packages, PackageInfo info, String self) {
        if (info == null || !candidateName(info.packageName, self) || info.applicationInfo == null
                || info.applicationInfo.sourceDir == null || info.applicationInfo.sourceDir.trim().isEmpty()) return;
        packages.putIfAbsent(info.packageName, info);
    }

    private static boolean candidateName(String packageName, String self) {
        return packageName != null && !packageName.trim().isEmpty() && !packageName.equals(self);
    }
}
