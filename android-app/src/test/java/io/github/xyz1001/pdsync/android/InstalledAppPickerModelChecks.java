package io.github.xyz1001.pdsync.android;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import io.github.xyz1001.pdsync.android.InstalledAppPickerModel.Entry;
import io.github.xyz1001.pdsync.android.InstalledAppPickerModel.State;

/** Runs the production model directly; input flags are snapshots, never Android API calls. */
public final class InstalledAppPickerModelChecks {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static Entry app(String packageName, String label, boolean launchable, boolean systemApp) {
        return new Entry(packageName, label, "1.2.3", launchable, systemApp);
    }

    private static void packages(List<Entry> actual, String... expected) {
        List<String> names = new ArrayList<>();
        for (Entry entry : actual) names.add(entry.packageName);
        check(names.equals(Arrays.asList(expected)), "Expected " + Arrays.toString(expected) + ", got " + names);
    }

    private static void immutable(Runnable mutation, String message) {
        try {
            mutation.run();
        } catch (UnsupportedOperationException expected) {
            checks++;
            return;
        }
        throw new AssertionError(message);
    }

    private static void defaultsAndToggles() {
        State state = new State();
        check(state.query().isEmpty(), "Initial search is empty");
        check(!state.showAll(), "Initial view hides non-launchable and system apps");
        List<Entry> entries = Arrays.asList(
                app("org.arbitrary.fork", "Custom game", true, false),
                app("org.random.utility", "Alpha utility", true, false),
                app("org.system.launcher", "System launcher", true, true),
                // An updated system app is already classified as system by the Android caller.
                app("org.updated.system", "Updated system app", true, true),
                app("org.background.game", "Dungeon background", false, false),
                app("org.system.service", "System service", false, true));
        packages(state.visible(entries, null), "org.random.utility", "org.arbitrary.fork");
        state.setShowAll(true);
        check(state.showAll(), "Show-all state is retained");
        packages(state.visible(entries, null), "org.background.game", "org.random.utility",
                "org.arbitrary.fork", "org.system.launcher", "org.system.service", "org.updated.system");
        state.setShowAll(false);
        packages(state.visible(entries, null), "org.random.utility", "org.arbitrary.fork");
        check(entries.get(3).systemApp, "Updated-system metadata remains system metadata");
        check(entries.get(0).versionName.equals("1.2.3"), "Version metadata is carried unchanged");
    }

    private static void searchAndLocales() {
        State state = new State();
        List<Entry> entries = Arrays.asList(
                app("org.fork.custom", "PIXEL Adventure", true, false),
                app("COM.FORK.PIXEL", "Renamed adventure", true, false),
                app("org.mod.chinese", "地牢测试", true, false),
                app("org.other.tool", "Utility", true, false),
                app("org.hidden.pixel", "Pixel service", false, false));
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            state.setQuery(" \tPiXeL\n ");
            check(state.query().equals(" \tPiXeL\n "), "Keep typed query for reopening/editing");
            packages(state.visible(entries, null), "org.fork.custom", "COM.FORK.PIXEL");
            state.setShowAll(true);
            packages(state.visible(entries, null), "org.fork.custom", "org.hidden.pixel", "COM.FORK.PIXEL");
            state.setQuery(" 地牢 ");
            packages(state.visible(entries, null), "org.mod.chinese");
            state.setQuery("oRg.OtHeR");
            packages(state.visible(entries, null), "org.other.tool");
            state.setQuery("nonexistent app");
            packages(state.visible(entries, null));
            state.setQuery(" \t\n ");
            check(state.visible(entries, null).size() == entries.size(), "Whitespace-only search matches all");
            state.setQuery(null);
            check(state.query().isEmpty(), "Null search resets to empty");
        } finally {
            Locale.setDefault(previous);
        }
    }

    private static void orderingAndSelection() {
        State state = new State();
        List<Entry> entries = Arrays.asList(
                app("org.z.tie", "Alpha", true, false),
                app("org.a.tie", "ALPHA", true, false),
                app("org.z.dungeon.mod", "Zulu mod", true, false),
                app("org.b.地牢", "Beta mod", true, false),
                app("org.d.label", "DUNGEON Remix", true, false),
                app("org.c.label", "地牢变体", true, false),
                app("org.selected.hidden", "Selected service", false, true));
        packages(state.visible(entries, null), "org.b.地牢", "org.d.label", "org.z.dungeon.mod",
                "org.c.label", "org.a.tie", "org.z.tie");
        packages(state.visible(entries, "org.selected.hidden"), "org.selected.hidden", "org.b.地牢",
                "org.d.label", "org.z.dungeon.mod", "org.c.label", "org.a.tie", "org.z.tie");
        state.setQuery("unmatched search");
        packages(state.visible(entries, "org.selected.hidden"), "org.selected.hidden");
        packages(state.visible(entries, "org.a.tie"), "org.a.tie");
        packages(state.visible(entries, "missing.package"));
        state.setShowAll(true);
        packages(state.visible(entries, "org.selected.hidden"), "org.selected.hidden");
        state.setQuery("");
        packages(state.visible(entries, "org.z.tie"), "org.z.tie", "org.b.地牢", "org.d.label",
                "org.z.dungeon.mod", "org.c.label", "org.a.tie", "org.selected.hidden");
        packages(state.visible(Collections.emptyList(), "org.selected.hidden"));
    }

    private static void snapshotsAndReopening() {
        State state = new State();
        Entry first = app("org.one", "Zulu", true, false);
        Entry second = app("org.two", "Alpha", true, false);
        List<Entry> candidates = new ArrayList<>(Arrays.asList(first, second));
        List<Entry> result = state.visible(candidates, null);
        packages(candidates, "org.one", "org.two");
        packages(result, "org.two", "org.one");
        candidates.clear();
        packages(result, "org.two", "org.one");
        immutable(() -> result.add(first), "Visible list must reject additions");
        immutable(() -> result.remove(0), "Visible list must reject removals");
        immutable(() -> result.set(0, first), "Visible list must reject replacements");
        check(Modifier.isFinal(Entry.class.getModifiers()), "Entry cannot be subclassed with mutable metadata");
        for (Field field : Entry.class.getDeclaredFields()) {
            check(Modifier.isFinal(field.getModifiers()), "Entry field is final: " + field.getName());
        }
        Entry fallback = new Entry("org.fallback", null, null, true, false);
        check(fallback.label.equals("org.fallback"), "Missing label falls back to package");
        check(fallback.versionName.isEmpty(), "Missing version is represented without null");
        check(new Entry("org.blank", " \t ", "v2", true, false).label.equals("org.blank"),
                "Whitespace-only label falls back to package");

        state.setQuery("  hidden  ");
        state.setShowAll(true);
        List<Entry> initialCandidates = Collections.singletonList(app("org.hidden", "Hidden service", false, true));
        List<Entry> firstOpen = state.visible(initialCandidates, null);
        packages(firstOpen, "org.hidden");
        // Dismissal does not reset State. A new package-manager snapshot may be supplied on reopen.
        List<Entry> refreshedCandidates = Arrays.asList(app("org.hidden.new", "Hidden new", false, false),
                app("org.hidden", "Hidden service", false, true), app("org.unrelated", "Other", true, false));
        packages(state.visible(refreshedCandidates, null), "org.hidden.new", "org.hidden");
        check(state.query().equals("  hidden  ") && state.showAll(), "Reopening retains query and show-all");
        packages(firstOpen, "org.hidden");
        state.setShowAll(false);
        packages(state.visible(refreshedCandidates, null));
        packages(state.visible(refreshedCandidates, "org.hidden"), "org.hidden");
        packages(firstOpen, "org.hidden");
        List<Entry> empty = state.visible(Collections.emptyList(), null);
        immutable(() -> empty.add(first), "Empty result must also be immutable");
    }

    public static void main(String[] args) {
        defaultsAndToggles();
        searchAndLocales();
        orderingAndSelection();
        snapshotsAndReopening();
        System.out.println("Installed-app picker checks passed: " + checks
                + " assertions; pure host model, no Android SDK or device execution.");
    }
}
