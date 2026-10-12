package io.github.xyz1001.pdsync.bridge;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;


public final class SaveDirectoryPolicy {
    private static final Set<String> EXCLUDED = new HashSet(Arrays.asList("settings.dat", "settings.xml", "preferences.dat", "preferences.xml", "ShatteredPixelDungeon.xml", "bones.dat", ".nomedia", ".pdsync-meta.json"));

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

        private Content(ContentState contentState, int i, int i2, int i3) {
            this.state = contentState;
            this.includedEntries = i;
            this.excludedEntries = i2;
            this.unrecognizedEntries = i3;
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
        if (!present(root)) return new Content(ContentState.MISSING_DIRECTORY, 0, 0, 0);
        int count = 0, included = 0, excluded = 0, unrecognized = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root.toPath())) {
            for (Path entry : entries) {
                if (++count > SaveArchive.MAX_ENTRIES)
                    throw new BridgeFailure("SAVE_TOO_LARGE", "Too many entries in the selected game's files directory");
                String name = entry.getFileName().toString();
                if (SaveArchive.included(name)) {
                    BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    boolean expected = name.matches("game[0-9]+") ? attrs.isDirectory() : attrs.isRegularFile();
                    if (attrs.isSymbolicLink() || !expected)
                        throw new BridgeFailure("UNSAFE_SAVE_PATH", "A supported save entry is linked or has an unexpected file type");
                    included++;
                } else if ("profileInstalled".equals(name)) {
                    BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attrs.isRegularFile() && !attrs.isSymbolicLink()) excluded++; else unrecognized++;
                } else if (EXCLUDED.contains(name)) excluded++;
                else unrecognized++;
            }
        }
        ContentState state = unrecognized > 0 ? ContentState.UNRECOGNIZED_LAYOUT
                : included > 0 ? ContentState.ALLOWLISTED_FILES_PRESENT
                : excluded > 0 ? ContentState.NO_TRANSFERABLE_FILES : ContentState.EMPTY_DIRECTORY;
        return new Content(state, included, excluded, unrecognized);
    }
}
