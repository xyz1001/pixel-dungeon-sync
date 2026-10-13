package io.github.xyz1001.pdsync.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Pure presentation filtering shared by installed-app pickers; never validates game identity. */
public final class InstalledAppPickerModel {
    private InstalledAppPickerModel() { }

    /** Immutable metadata snapshot. Android callers supply launchability and system flags. */
    public static final class Entry {
        public final String packageName;
        public final String label;
        public final String versionName;
        public final boolean launchable;
        public final boolean systemApp;

        /** systemApp must include both FLAG_SYSTEM and FLAG_UPDATED_SYSTEM_APP. */
        public Entry(String packageName, String label, String versionName,
                     boolean launchable, boolean systemApp) {
            this.packageName = Objects.requireNonNull(packageName, "packageName");
            this.label = label == null || label.trim().isEmpty() ? packageName : label;
            this.versionName = versionName == null ? "" : versionName;
            this.launchable = launchable;
            this.systemApp = systemApp;
        }
    }

    /** Retain the same state when a picker closes/reopens, or when its candidates refresh. */
    public static final class State {
        private String query = "";
        private boolean showAll;

        public String query() { return query; }
        public boolean showAll() { return showAll; }
        public void setQuery(String query) { this.query = query == null ? "" : query; }
        public void setShowAll(boolean showAll) { this.showAll = showAll; }

        /**
         * Returns an independent, immutable result without changing the supplied candidates.
         * The selected package, if present, bypasses search/default filters and appears first.
         * No entry is synthesized for an uninstalled or otherwise missing selected package.
         */
        public List<Entry> visible(List<Entry> candidates, String selectedPackage) {
            String search = query.trim().toLowerCase(Locale.ROOT);
            List<Entry> visible = new ArrayList<>();
            for (Entry entry : new ArrayList<>(candidates)) {
                boolean selected = entry.packageName.equals(selectedPackage);
                boolean included = showAll || (entry.launchable && !entry.systemApp);
                boolean matches = search.isEmpty()
                        || normalized(entry.label).contains(search)
                        || normalized(entry.packageName).contains(search);
                if (selected || (included && matches)) visible.add(entry);
            }
            visible.sort((left, right) -> {
                int order = Boolean.compare(right.packageName.equals(selectedPackage),
                        left.packageName.equals(selectedPackage));
                if (order != 0) return order;
                order = Boolean.compare(dungeonHint(right), dungeonHint(left));
                if (order != 0) return order;
                order = normalized(left.label).compareTo(normalized(right.label));
                return order != 0 ? order : left.packageName.compareTo(right.packageName);
            });
            return Collections.unmodifiableList(visible);
        }
    }

    private static boolean dungeonHint(Entry entry) {
        return containsDungeon(entry.label) || containsDungeon(entry.packageName);
    }

    private static boolean containsDungeon(String text) {
        String normalized = normalized(text);
        return normalized.contains("地牢") || normalized.contains("dungeon");
    }

    private static String normalized(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
