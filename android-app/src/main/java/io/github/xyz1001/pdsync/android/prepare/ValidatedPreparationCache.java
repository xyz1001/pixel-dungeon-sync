package io.github.xyz1001.pdsync.android.prepare;

import pdsync.i18n.PrepareMessages;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Process-memory proof reuse only. A cache miss always requires the production full validator. */
public final class ValidatedPreparationCache {
    private static final Map<GamePreparationService.PreparedPair, List<Stamp>> VALIDATED = new IdentityHashMap<>();
    private static final List<GamePreparationService.PreparedPair> RECENT = new ArrayList<>();
    private ValidatedPreparationCache() { }

    private static final class Stamp {
        final File file;
        final String canonical;
        final Object key;
        final long length;
        final java.nio.file.attribute.FileTime modified, created;
        Stamp(File file) throws IOException {
            this.file = file;
            canonical = file.getCanonicalPath();
            if (!file.getAbsolutePath().equals(canonical)) throw new IOException(PrepareMessages.FILE_PATH_CHANGED.text());
            BasicFileAttributes attrs = Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile() || attrs.isSymbolicLink()) throw new IOException(PrepareMessages.FILE_UNAVAILABLE.text());
            key = attrs.fileKey(); length = attrs.size(); modified = attrs.lastModifiedTime(); created = attrs.creationTime();
        }
        boolean same(Stamp other) {
            return canonical.equals(other.canonical) && length == other.length && modified.equals(other.modified)
                    && created.equals(other.created) && java.util.Objects.equals(key, other.key);
        }
    }

    /** Call only after every content, signature, payload and receipt validation has succeeded. */
    static synchronized void remember(GamePreparationService.PreparedPair pair) throws IOException {
        if (pair == null) throw new IOException(PrepareMessages.VERIFIED_PACKAGE_MISSING.text());
        List<Stamp> stamps = new ArrayList<>();
        for (File file : pair.validationFiles()) stamps.add(new Stamp(file));
        if (stamps.isEmpty()) throw new IOException(PrepareMessages.VERIFIED_FILES_MISSING.text());
        VALIDATED.put(pair, stamps);
        touch(pair);
        // Evict only the least-recent session, never all live hot-path proofs at once.
        while (RECENT.size() > 8) VALIDATED.remove(RECENT.remove(0));
    }

    private static void touch(GamePreparationService.PreparedPair pair) {
        RECENT.removeIf(value -> value == pair);
        RECENT.add(pair);
    }

    public static synchronized boolean isUnchanged(GamePreparationService.PreparedPair pair) {
        List<Stamp> stamps = VALIDATED.get(pair);
        if (stamps == null) return false;
        try {
            List<File> files = pair.validationFiles();
            if (files.size() != stamps.size()) return false;
            for (int i = 0; i < files.size(); i++)
                if (!files.get(i).equals(stamps.get(i).file) || !stamps.get(i).same(new Stamp(files.get(i)))) return false;
            touch(pair);
            return true;
        } catch (IOException | RuntimeException invalid) { return false; }
    }

    public static void requireUnchanged(GamePreparationService.PreparedPair pair) throws IOException {
        if (!isUnchanged(pair)) throw new IOException(PrepareMessages.FILES_CHANGED.text());
    }
}
