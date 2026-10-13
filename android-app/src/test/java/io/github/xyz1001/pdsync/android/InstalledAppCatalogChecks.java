package io.github.xyz1001.pdsync.android;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Host boundary checks using actual catalog and picker model; no device or APK reads. */
public final class InstalledAppCatalogChecks {
    private static final String SELF = "io.github.xyz1001.pdsync.android";
    private static int checks;

    public static void main(String[] args) throws Exception {
        unionAndClassification();
        invalidMetadataAndExactSelf();
        launcherEnabledEvidence();
        labelFallbackAndImmutableSnapshot();
        visibilityRacesAndFailures();
        System.out.println("Installed-app catalog checks passed: " + checks
                + " assertions; metadata doubles only, no SDK, APK reads, su, or device execution.");
    }

    private static void unionAndClassification() throws Exception {
        FakePm pm = new FakePm();
        PackageInfo regular = pkg("com.example.user", "Regular", 0);
        PackageInfo system = pkg("com.example.system", "System", ApplicationInfo.FLAG_SYSTEM);
        PackageInfo updated = pkg("com.example.updated", "Updated", ApplicationInfo.FLAG_UPDATED_SYSTEM_APP);
        PackageInfo headless = pkg("com.example.headless", "Headless", 0);
        PackageInfo launcherOnly = pkg("com.example.launcheronly", "Launcher", 0);
        PackageInfo selected = pkg("com.example.selected", "Selected", ApplicationInfo.FLAG_SYSTEM);
        pm.install(regular, system, updated, headless);
        pm.lookup(launcherOnly, selected);
        pm.launchers.addAll(Arrays.asList(launcher(regular), launcher(system), launcher(updated),
                launcher(launcherOnly), launcher(launcherOnly)));

        List<InstalledAppPickerModel.Entry> entries = InstalledAppCatalog.load(context(pm), selected.packageName);
        check(entries.size() == 6, "Union contains every installed/launcher/selected package once");
        check(entry(entries, headless.packageName) != null, "Non-launcher metadata stays in the catalog");
        check(!entry(entries, headless.packageName).launchable, "No launcher evidence means not launchable");
        check(entry(entries, launcherOnly.packageName).launchable, "Launcher-only package joins installed candidates");
        check(entry(entries, system.packageName).systemApp, "System flag is classified");
        check(entry(entries, updated.packageName).systemApp, "Updated-system-only flag is classified as system");
        check(!entry(entries, regular.packageName).systemApp, "Ordinary app remains non-system");
        check(entry(entries, selected.packageName).systemApp && !entry(entries, selected.packageName).launchable,
                "Selected system app is included without launcher evidence");
        check(pm.lookupCalls.get(launcherOnly.packageName) == 1, "Repeated launcher activities do not repeat successful metadata lookup");
        check(!pm.lookupCalls.containsKey(regular.packageName), "Installed candidates need no extra package query");
        check(pm.lookupCalls.get(selected.packageName) == 1, "Missing selected package receives explicit lookup");
        check(pm.installedCalls == 1 && pm.launcherCalls == 1, "Discovery queries are each issued once");
        check(pm.lastFlags == 0 && pm.zeroFlagsOnly, "Only cheap metadata flags are requested");
        check(Intent.ACTION_MAIN.equals(pm.lastIntent.getAction())
                        && pm.lastIntent.hasCategory(Intent.CATEGORY_LAUNCHER),
                "Launcher discovery queries MAIN with LAUNCHER category");

        InstalledAppPickerModel.State state = new InstalledAppPickerModel.State();
        List<InstalledAppPickerModel.Entry> defaults = state.visible(entries, null);
        check(defaults.size() == 2, "Model default omits system, updated-system, and headless apps");
        check(entry(defaults, updated.packageName) == null, "Updated system app never leaks into ordinary default view");
        state.setShowAll(true);
        check(state.visible(entries, null).size() == entries.size(), "All-app mode exposes full collected metadata");
        state.setShowAll(false);
        state.setQuery("cannot match any package");
        List<InstalledAppPickerModel.Entry> filtered = state.visible(entries, selected.packageName);
        check(filtered.size() == 1 && filtered.get(0).packageName.equals(selected.packageName),
                "Selection priority belongs to model and survives filters");
        check(!entries.get(0).packageName.equals(selected.packageName), "Catalog does not reorder selection to imitate model sorting");
    }

    private static void invalidMetadataAndExactSelf() throws Exception {
        FakePm pm = new FakePm();
        PackageInfo self = pkg(SELF, "Self", 0);
        PackageInfo prefix = pkg(SELF + ".customgame", "Unrecognized fork", 0);
        PackageInfo noApplication = pkg("com.example.noapplication", "No app", 0);
        noApplication.applicationInfo = null;
        PackageInfo noSource = pkg("com.example.nosource", "No source", 0);
        noSource.applicationInfo.sourceDir = null;
        PackageInfo emptySource = pkg("com.example.emptysource", "Empty source", 0);
        emptySource.applicationInfo.sourceDir = "  ";
        PackageInfo noName = pkg("com.example.noname", "No name", 0);
        noName.packageName = null;
        PackageInfo blankName = pkg("com.example.blankname", "Blank name", 0);
        blankName.packageName = " ";
        pm.install(self, prefix, noApplication, noSource, emptySource, noName, blankName, prefix);
        pm.installed.add(null);
        pm.launchers.add(launcher(self));
        List<InstalledAppPickerModel.Entry> entries = InstalledAppCatalog.load(context(pm), SELF);
        check(entries.size() == 1 && entries.get(0).packageName.equals(prefix.packageName),
                "Only exact self and unusable metadata are excluded; prefix-sharing fork remains");
        check(!pm.lookupCalls.containsKey(SELF), "Self launcher and self selection never trigger package lookup");
        check(entries.get(0).label.equals("Unrecognized fork"), "No official-name or package-prefix restriction");
        check(entries.get(0).versionName.equals("v-Unrecognized fork"), "Version name is copied cheaply");
    }

    private static void launcherEnabledEvidence() throws Exception {
        FakePm pm = new FakePm();
        PackageInfo disabledActivity = pkg("com.example.disabledactivity", "Activity disabled", 0);
        PackageInfo disabledApp = pkg("com.example.disabledapp", "App disabled", 0);
        disabledApp.applicationInfo.enabled = false;
        PackageInfo noLauncherApp = pkg("com.example.launcherappmissing", "Missing launcher app", 0);
        PackageInfo mixedActivities = pkg("com.example.mixed", "Mixed activities", 0);
        PackageInfo disabledSnapshot = pkg("com.example.disabledsnapshot", "Disabled snapshot", 0);
        disabledSnapshot.applicationInfo.enabled = false;
        pm.install(disabledActivity, disabledApp, noLauncherApp, mixedActivities, disabledSnapshot);
        ResolveInfo activityOff = launcher(disabledActivity);
        activityOff.activityInfo.enabled = false;
        ResolveInfo noApp = launcher(noLauncherApp);
        noApp.activityInfo.applicationInfo = null;
        ResolveInfo mixedOff = launcher(mixedActivities);
        mixedOff.activityInfo.enabled = false;
        ResolveInfo staleOn = launcher(pkg(disabledSnapshot.packageName, "Old enabled record", 0));
        pm.launchers.addAll(Arrays.asList(activityOff, launcher(disabledApp), noApp, mixedOff,
                launcher(mixedActivities), staleOn, null, new ResolveInfo()));
        List<InstalledAppPickerModel.Entry> entries = InstalledAppCatalog.load(context(pm), null);
        check(entries.size() == 5, "Disabled apps remain available in all-app catalog");
        check(!entry(entries, disabledActivity.packageName).launchable, "Disabled activity is not launcher evidence");
        check(!entry(entries, disabledApp.packageName).launchable, "Disabled launcher application is not launcher evidence");
        check(!entry(entries, noLauncherApp.packageName).launchable, "Incomplete launcher app metadata is not launcher evidence");
        check(entry(entries, mixedActivities.packageName).launchable, "One enabled launcher activity is sufficient");
        check(!entry(entries, disabledSnapshot.packageName).launchable, "Disabled installed metadata overrides stale enabled launcher record");
    }

    private static void labelFallbackAndImmutableSnapshot() throws Exception {
        FakePm pm = new FakePm();
        PackageInfo nullLabel = pkg("com.example.nulllabel", "Unused", 0);
        PackageInfo emptyLabel = pkg("com.example.emptylabel", "Unused", 0);
        PackageInfo failingLabel = pkg("com.example.failinglabel", "Unused", 0);
        nullLabel.versionName = null;
        pm.install(nullLabel, emptyLabel, failingLabel);
        pm.labels.put(nullLabel.packageName, null);
        pm.labels.put(emptyLabel.packageName, " \t ");
        pm.failingLabel = failingLabel.packageName;
        List<InstalledAppPickerModel.Entry> entries = InstalledAppCatalog.load(context(pm), null);
        for (InstalledAppPickerModel.Entry value : entries) {
            check(value.label.equals(value.packageName), "Missing, blank or unavailable labels fall back to package");
        }
        check(entry(entries, nullLabel.packageName).versionName.equals(""), "Missing version is optional");
        try {
            entries.clear();
            throw new AssertionError("Catalog list must be immutable");
        } catch (UnsupportedOperationException expected) { checks++; }
        nullLabel.packageName = "changed";
        nullLabel.versionName = "changed";
        nullLabel.applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM;
        pm.installed.clear();
        check(entries.size() == 3 && entry(entries, "com.example.nulllabel") != null,
                "List and entry fields are snapshots independent of later PM mutations");
        check(entry(entries, "com.example.nulllabel").versionName.isEmpty()
                        && !entry(entries, "com.example.nulllabel").systemApp,
                "Entry version and flags are immutable metadata values");
    }

    private static void visibilityRacesAndFailures() throws Exception {
        FakePm pm = new FakePm();
        PackageInfo retained = pkg("com.example.retained", "Retained", 0);
        pm.install(retained);
        pm.launchers.add(launcher(pkg("com.example.removed", "Removed", 0)));
        List<InstalledAppPickerModel.Entry> entries = InstalledAppCatalog.load(context(pm), "com.example.invisible");
        check(entries.size() == 1 && entries.get(0).packageName.equals(retained.packageName),
                "Launcher/selected NameNotFound races do not synthesize entries or discard others");
        check(pm.lookupCalls.containsKey("com.example.removed") && pm.lookupCalls.containsKey("com.example.invisible"),
                "Both launcher union and explicit selection are actually queried");

        FakePm mismatched = new FakePm();
        mismatched.lookups.put("com.example.requested", pkg("com.example.other", "Other", 0));
        check(InstalledAppCatalog.load(context(mismatched), "com.example.requested").isEmpty(),
                "Mismatched explicit lookup cannot substitute another selected package");

        for (String stage : Arrays.asList("installed", "launcher", "lookup")) {
            FakePm broken = new FakePm();
            broken.failureStage = stage;
            try {
                InstalledAppCatalog.load(context(broken), "com.example.selected");
                throw new AssertionError("PM " + stage + " failure must propagate");
            } catch (SecurityException expected) {
                check(expected == broken.failure, "Original " + stage + " query error propagates without partial success");
            }
        }
        FakePm launcherLookupBroken = new FakePm();
        launcherLookupBroken.failureStage = "lookup";
        launcherLookupBroken.launchers.add(launcher(pkg("com.example.launcher", "Launcher", 0)));
        try {
            InstalledAppCatalog.load(context(launcherLookupBroken), null);
            throw new AssertionError("Unexpected launcher lookup failure must propagate");
        } catch (SecurityException expected) {
            check(expected == launcherLookupBroken.failure, "Only NameNotFound is ignored in launcher lookup");
        }
    }

    private static Context context(FakePm pm) {
        return new Context() {
            @Override public PackageManager getPackageManager() { return pm; }
            @Override public String getPackageName() { return SELF; }
        };
    }

    private static PackageInfo pkg(String name, String label, int flags) {
        PackageInfo info = new PackageInfo();
        info.packageName = name;
        info.versionName = "v-" + label;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = name;
        // Deliberately nonexistent: listing must not stat, read, hash, or parse installed APKs.
        info.applicationInfo.sourceDir = "/not-a-real-device/" + name + "/base.apk";
        info.applicationInfo.flags = flags;
        LABELS.put(info.applicationInfo, label);
        return info;
    }

    private static final Map<ApplicationInfo, String> LABELS = new HashMap<>();

    private static ResolveInfo launcher(PackageInfo info) {
        ResolveInfo result = new ResolveInfo();
        result.activityInfo = new ActivityInfo();
        result.activityInfo.packageName = info.packageName;
        result.activityInfo.applicationInfo = info.applicationInfo;
        return result;
    }

    private static InstalledAppPickerModel.Entry entry(List<InstalledAppPickerModel.Entry> entries, String name) {
        for (InstalledAppPickerModel.Entry value : entries) if (value.packageName.equals(name)) return value;
        return null;
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static final class FakePm extends PackageManager {
        final List<PackageInfo> installed = new ArrayList<>();
        final List<ResolveInfo> launchers = new ArrayList<>();
        final Map<String, PackageInfo> lookups = new HashMap<>();
        final Map<String, Integer> lookupCalls = new HashMap<>();
        final Map<String, CharSequence> labels = new HashMap<>();
        final SecurityException failure = new SecurityException("PM discovery unavailable");
        String failureStage, failingLabel;
        Intent lastIntent;
        int installedCalls, launcherCalls, lastFlags;
        boolean zeroFlagsOnly = true;

        void install(PackageInfo... infos) { installed.addAll(Arrays.asList(infos)); lookup(infos); }
        void lookup(PackageInfo... infos) {
            for (PackageInfo info : infos) lookups.put(info.packageName, info);
        }
        void flags(int value) { lastFlags = value; zeroFlagsOnly &= value == 0; }
        @Override public List<PackageInfo> getInstalledPackages(int flags) {
            flags(flags); installedCalls++;
            if ("installed".equals(failureStage)) throw failure;
            return installed;
        }
        @Override public List<ResolveInfo> queryIntentActivities(Intent intent, int flags) {
            flags(flags); launcherCalls++; lastIntent = intent;
            if ("launcher".equals(failureStage)) throw failure;
            return launchers;
        }
        @Override public PackageInfo getPackageInfo(String name, int flags) throws NameNotFoundException {
            flags(flags); lookupCalls.put(name, lookupCalls.getOrDefault(name, 0) + 1);
            if ("lookup".equals(failureStage)) throw failure;
            if (!lookups.containsKey(name)) throw new NameNotFoundException();
            return lookups.get(name);
        }
        @Override public CharSequence getApplicationLabel(ApplicationInfo info) {
            if (info.packageName.equals(failingLabel)) throw new IllegalStateException("Label resource disappeared");
            return labels.containsKey(info.packageName) ? labels.get(info.packageName) : LABELS.get(info);
        }
    }
}
