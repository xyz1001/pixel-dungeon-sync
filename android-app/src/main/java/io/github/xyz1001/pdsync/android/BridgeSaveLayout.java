package io.github.xyz1001.pdsync.android;

import android.os.Bundle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Optional, untrusted provider metadata. It never grants permission to transfer a save. */
final class BridgeSaveLayout {
    // Keep in sync with SaveDirectoryPolicy's diagnostic limits, not archive limits.
    static final int MAX_ENTRIES = 64;
    static final int MAX_NAME_LENGTH = 96;
    static final int MAX_BYTES = 4096;
    private static final int MAX_ROOT_ENTRIES = 16384;
    private enum State { MISSING_DIRECTORY, EMPTY_DIRECTORY, NO_TRANSFERABLE_FILES, ALLOWLISTED_FILES_PRESENT, UNRECOGNIZED_LAYOUT }
    private enum Type { REGULAR_FILE, DIRECTORY, SYMLINK, OTHER, UNKNOWN }
    private enum Classification { INCLUDED, EXCLUDED, UNRECOGNIZED }

    static final class Entry {
        final String name;
        final Type type;
        final Classification classification;

        Entry(String name, Type type, Classification classification) {
            this.name = name;
            this.type = type;
            this.classification = classification;
        }
    }

    final String state;
    final int included, excluded, unrecognized;
    final List<Entry> entries;
    final boolean truncated, valid;

    private BridgeSaveLayout(String state, int included, int excluded, int unrecognized,
            List<Entry> entries, boolean truncated, boolean valid) {
        this.state = state;
        this.included = included;
        this.excluded = excluded;
        this.unrecognized = unrecognized;
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.truncated = truncated;
        this.valid = valid;
    }

    private static BridgeSaveLayout invalid() {
        return new BridgeSaveLayout("UNKNOWN", -1, -1, -1, Collections.emptyList(), true, false);
    }

    static BridgeSaveLayout fromReply(Bundle reply) {
        if (reply == null) return null;
        try {
            Object version = reply.get("saveLayoutDiagnosticVersion");
            if (version == null) return null; // Older bridges do not implement this optional extension.
            if (!(version instanceof Integer) || ((Integer) version) != 1) return invalid();
            Object rawState = reply.get("saveContentState");
            if (!(rawState instanceof String) || ((String) rawState).length() > 32) return invalid();
            String state = State.valueOf((String) rawState).name();
            int included = count(reply.get("includedRootEntries"));
            int excluded = count(reply.get("excludedRootEntries"));
            int unrecognized = count(reply.get("unrecognizedRootEntries"));
            if (included < 0 || excluded < 0 || unrecognized < 0
                    || (long) included + excluded + unrecognized > MAX_ROOT_ENTRIES) return invalid();
            Object rawNames = reply.get("rootEntryNames"), rawTypes = reply.get("rootEntryTypes");
            Object rawClasses = reply.get("rootEntryClassifications"), rawTruncated = reply.get("rootEntriesTruncated");
            if (!(rawNames instanceof String[]) || !(rawTypes instanceof String[])
                    || !(rawClasses instanceof String[]) || !(rawTruncated instanceof Boolean)) return invalid();
            String[] names = (String[]) rawNames, types = (String[]) rawTypes, classes = (String[]) rawClasses;
            if (names.length != types.length || names.length != classes.length) return invalid();
            int total = included + excluded + unrecognized;
            if (names.length > total) return invalid();
            boolean truncated = (Boolean) rawTruncated || names.length < total || names.length > MAX_ENTRIES;
            List<Entry> entries = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int bytes = 0;
            for (int i = 0; i < names.length && i < MAX_ENTRIES; i++) {
                String name = names[i], type = types[i], classification = classes[i];
                if (!safeName(name) || type == null || type.length() > 16
                        || classification == null || classification.length() > 16) {
                    truncated = true;
                    continue;
                }
                Type parsedType;
                Classification parsedClassification;
                try {
                    parsedType = Type.valueOf(type);
                    parsedClassification = Classification.valueOf(classification);
                } catch (IllegalArgumentException unknown) {
                    truncated = true;
                    continue;
                }
                int entryBytes = name.length() + type.length() + classification.length();
                if (entryBytes > MAX_BYTES - bytes || !seen.add(name)) {
                    truncated = true;
                    continue;
                }
                entries.add(new Entry(name, parsedType, parsedClassification));
                bytes += entryBytes;
            }
            return new BridgeSaveLayout(state, included, excluded, unrecognized, entries, truncated, true);
        } catch (RuntimeException malformed) {
            // Bad parcel types or unreadable optional data cannot replace the real readiness error.
            return invalid();
        }
    }

    private static int count(Object value) {
        if (!(value instanceof Integer)) return -1;
        int count = (Integer) value;
        return count >= 0 && count <= MAX_ROOT_ENTRIES ? count : -1;
    }

    private static boolean safeName(String name) {
        return name != null && name.length() <= MAX_NAME_LENGTH && !".".equals(name)
                && !"..".equals(name) && name.matches("[A-Za-z0-9_.$-]+");
    }

    void appendTo(StringBuilder report) {
        report.append("\nsave_layout_diagnostic=").append(valid ? "AVAILABLE" : "INVALID")
                .append("\nsave_content_state=").append(state)
                .append("\nincluded_root_entries=").append(included)
                .append("\nexcluded_root_entries=").append(excluded)
                .append("\nunrecognized_root_entries=").append(unrecognized)
                .append("\nroot_entries_reported=").append(entries.size())
                .append("\nroot_entries_truncated=").append(truncated)
                .append("\nroot_entry_scope=TOP_LEVEL_RELATIVE_NAMES_NOFOLLOW_METADATA_ONLY");
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            report.append("\nroot_entry[").append(i).append("]=").append(entry.name)
                    .append("; type=").append(entry.type.name())
                    .append("; classification=").append(entry.classification.name());
        }
    }
}
