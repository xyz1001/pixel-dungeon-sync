package io.github.xyz1001.pdsync.bridge;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;


public final class SaveDirectoryPolicy {
    private static final Set<String> EXCLUDED = new HashSet(Arrays.asList("settings.dat", "settings.xml", "preferences.dat", "preferences.xml", "ShatteredPixelDungeon.xml", "bones.dat", ".nomedia", ".pdsync-meta.json"));

    // These are diagnostic payload limits, independent of the archive/transfer limits.
    public static final int MAX_DIAGNOSTIC_ENTRIES = 64;
    public static final int MAX_DIAGNOSTIC_NAME_LENGTH = 96;
    public static final int MAX_DIAGNOSTIC_BYTES = 4096;

    public enum EntryType { REGULAR_FILE, DIRECTORY, SYMLINK, OTHER, UNKNOWN }
    public enum Classification { INCLUDED, EXCLUDED, UNRECOGNIZED }

    public static final class RootEntry {
        public final String name;
        public final EntryType type;
        public final Classification classification;

        private RootEntry(String name, EntryType type, Classification classification) {
            this.name = name;
            this.type = type;
            this.classification = classification;
        }
    }

    public enum ContentState {
        MISSING_DIRECTORY,
        EMPTY_DIRECTORY,
        NO_TRANSFERABLE_FILES,
        ALLOWLISTED_FILES_PRESENT,
        UNRECOGNIZED_LAYOUT
    }

    public static final class Content {
        public final int excludedEntries;
        public final int includedEntries;
        public final ContentState state;
        public final int unrecognizedEntries;

        public final List<RootEntry> rootEntries;
        public final boolean rootEntriesTruncated;

        private Content(ContentState contentState, int i, int i2, int i3,
                List<RootEntry> entries, boolean truncated) {
            this.state = contentState;
            this.includedEntries = i;
            this.excludedEntries = i2;
            this.unrecognizedEntries = i3;
            this.rootEntries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.rootEntriesTruncated = truncated;
        }

        public boolean transferable() {
            return (this.state == ContentState.MISSING_DIRECTORY || this.state == ContentState.UNRECOGNIZED_LAYOUT) ? false : true;
        }

        public void requireTransferable() throws IOException {
            if (this.state == ContentState.MISSING_DIRECTORY) {
                throw new BridgeFailure("FIRST_LAUNCH_NEEDED", "Open the selected game once to create its save directory");
            }
            if (this.state == ContentState.UNRECOGNIZED_LAYOUT) {
                throw new BridgeFailure("UNRECOGNIZED_SAVE_LAYOUT", "The selected game has files outside the supported save layout; it cannot be treated as an empty save");
            }
        }
    }

    private SaveDirectoryPolicy() {
    }

    public static boolean present(File file) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file.toPath(), (Class<BasicFileAttributes>) BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
                throw new IOException("Save root is not a real directory");
            }
            return true;
        } catch (NoSuchFileException unused) {
            return false;
        }
    }

    public static void requirePresent(File file) throws IOException {
        if (!present(file)) {
            throw new BridgeFailure("FIRST_LAUNCH_NEEDED", "Open the game once so it can create its save directory, then return here to sync");
        }
    }

    public static Content content(File root) throws IOException {
        if (!present(root)) return new Content(ContentState.MISSING_DIRECTORY, 0, 0, 0, Collections.emptyList(), false);
        int count = 0, included = 0, excluded = 0, unrecognized = 0, diagnosticBytes = 0;
        List<RootEntry> diagnosticEntries = new ArrayList<>();
        boolean truncated = false;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root.toPath())) {
            for (Path entry : entries) {
                if (++count > SaveArchive.MAX_ENTRIES)
                    throw new BridgeFailure("SAVE_TOO_LARGE", "Too many entries in the selected game's files directory");
                String name = entry.getFileName().toString();
                boolean supported = SaveArchive.included(name);
                boolean profileMarker = "profileInstalled".equals(name);
                BasicFileAttributes attrs = null;
                try {
                    // Metadata only. Never open a file, descend into a directory or resolve a link target.
                    attrs = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException unavailable) {
                    // Preserve the existing policy: included entries and the exact runtime marker
                    // require a verified type. Optional diagnostics must not change excluded/unknown handling.
                    if (supported || profileMarker) throw unavailable;
                }
                Classification classification;
                if (supported) {
                    boolean expected = name.matches("game[0-9]+") ? attrs.isDirectory() : attrs.isRegularFile();
                    if (attrs.isSymbolicLink() || !expected)
                        throw new BridgeFailure("UNSAFE_SAVE_PATH", "A supported save entry is linked or has an unexpected file type");
                    included++;
                    classification = Classification.INCLUDED;
                } else if (profileMarker) {
                    if (attrs.isRegularFile() && !attrs.isSymbolicLink()) {
                        excluded++;
                        classification = Classification.EXCLUDED;
                    } else {
                        unrecognized++;
                        classification = Classification.UNRECOGNIZED;
                    }
                } else if (EXCLUDED.contains(name)) {
                    excluded++;
                    classification = Classification.EXCLUDED;
                } else {
                    unrecognized++;
                    classification = Classification.UNRECOGNIZED;
                }
                EntryType type = attrs == null ? EntryType.UNKNOWN
                        : attrs.isSymbolicLink() ? EntryType.SYMLINK
                        : attrs.isRegularFile() ? EntryType.REGULAR_FILE
                        : attrs.isDirectory() ? EntryType.DIRECTORY : EntryType.OTHER;
                // Only complete, plain relative basenames are reportable. Omit unsafe or long
                // names instead of turning paths/control characters into misleading partial names.
                boolean safeName = name.length() <= MAX_DIAGNOSTIC_NAME_LENGTH
                        && !".".equals(name) && !"..".equals(name) && name.matches("[A-Za-z0-9_.$-]+");
                int bytes = safeName ? name.length() + type.name().length() + classification.name().length() : 0;
                if (!safeName || diagnosticEntries.size() >= MAX_DIAGNOSTIC_ENTRIES
                        || bytes > MAX_DIAGNOSTIC_BYTES - diagnosticBytes) {
                    truncated = true;
                } else {
                    diagnosticEntries.add(new RootEntry(name, type, classification));
                    diagnosticBytes += bytes; // Every accepted character is ASCII, hence one UTF-8 byte.
                }
            }
        }
        ContentState state = unrecognized > 0 ? ContentState.UNRECOGNIZED_LAYOUT
                : included > 0 ? ContentState.ALLOWLISTED_FILES_PRESENT
                : excluded > 0 ? ContentState.NO_TRANSFERABLE_FILES : ContentState.EMPTY_DIRECTORY;
        return new Content(state, included, excluded, unrecognized, diagnosticEntries, truncated);
    }
}
