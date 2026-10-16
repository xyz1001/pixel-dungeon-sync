package io.github.xyz1001.pdsync.root;

import io.github.xyz1001.pdsync.bridge.SaveArchive;
import io.github.xyz1001.pdsync.bridge.SaveDirectoryPolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Read-only save snapshot. Never creates files or directories in a game's tree. */
public final class RootSaveTree {
    public static final int MAX_DEPTH = 16;
    private final RootFileSystem fs;
    private final RootBinding binding;
    private final Limits limits;
    public static final class Limits {
        final long bytes; final int files, entries, depth;
        public Limits(long bytes, int files, int entries, int depth) {
            if (bytes < 0 || bytes > SaveArchive.MAX_BYTES || files < 1 || files > SaveArchive.MAX_FILES
                    || entries < 1 || entries > SaveArchive.MAX_ENTRIES || depth < 1 || depth > MAX_DEPTH)
                throw new IllegalArgumentException("Invalid bounded limits");
            this.bytes = bytes; this.files = files; this.entries = entries; this.depth = depth;
        }
    }
    public static final class Snapshot {
        public final SaveArchive.Inventory inventory;
        public final String contentState;
        final Map<String, String> metadata;
        final Map<String, byte[]> bytes;
        final Map<String, Long> modified;
        Snapshot(Map<String, String> hashes, long count, String contentState, Map<String, String> metadata,
                Map<String, byte[]> bytes, Map<String, Long> modified) {
            this.inventory = new SaveArchive.Inventory(hashes, count); this.contentState = contentState;
            this.metadata = Collections.unmodifiableMap(new TreeMap<>(metadata));
            this.bytes = bytes; this.modified = modified;
        }
        public boolean same(Snapshot other) {
            return contentState.equals(other.contentState) && inventory.bytes == other.inventory.bytes
                    && inventory.hashes.equals(other.inventory.hashes) && metadata.equals(other.metadata);
        }
    }
    public RootSaveTree(RootFileSystem fs, RootBinding binding) {
        this(fs, binding, new Limits(SaveArchive.MAX_BYTES, SaveArchive.MAX_FILES, SaveArchive.MAX_ENTRIES, MAX_DEPTH));
    }
    public RootSaveTree(RootFileSystem fs, RootBinding binding, Limits limits) {
        this.fs = fs; this.binding = binding; this.limits = limits;
    }
    public void probe() throws IOException {
        try (RootFileSystem.Directory data = fs.openData(binding)) {
            requireDirectory(data.stat());
            try (RootFileSystem.Directory files = fs.openDirectory(data, "files")) {
                requireDirectory(files.stat()); fs.revalidateRoot(binding, data, files);
            }
        }
    }
    /** Final original-path identity check after potentially slow PM/lifecycle calls. */
    public void requireRootIdentity(Snapshot expected) throws IOException {
        try (RootFileSystem.Directory data = fs.openData(binding)) {
            requireDirectory(data.stat());
            if ("<missing>".equals(expected.metadata.get(""))) {
                if (!data.stat().identity().equals(expected.metadata.get("<data>")))
                    throw failure(RootProtocol.Status.CHANGED);
                requireFilesAbsent(data);
                return;
            }
            try (RootFileSystem.Directory files = fs.openDirectory(data, "files")) {
                requireDirectory(files.stat());
                if (!data.stat().identity().equals(expected.metadata.get("<data>"))
                        || !files.stat().identity().equals(expected.metadata.get("")))
                    throw failure(RootProtocol.Status.CHANGED);
                fs.revalidateRoot(binding, data, files);
            }
        }
    }
    public Snapshot scan(boolean capture) throws IOException {
        Map<String, String> hashes = new TreeMap<>(), metadata = new TreeMap<>();
        Map<String, byte[]> bytes = capture ? new TreeMap<>() : null;
        Map<String, Long> modified = new TreeMap<>();
        try (RootFileSystem.Directory data = fs.openData(binding)) {
            RootFileSystem.Stat dataBefore = data.stat(); requireDirectory(dataBefore);
            RootFileSystem.Stat filesBefore;
            try { filesBefore = fs.childStat(data, "files"); }
            catch (RootFailure missing) {
                if (missing.status != RootProtocol.Status.MISSING_DIRECTORY) throw missing;
                metadata.put("<data>", dataBefore.identity()); metadata.put("", "<missing>");
                Snapshot empty = new Snapshot(hashes, 0, "EMPTY_DIRECTORY", metadata, bytes, modified);
                requireFilesAbsent(data);
                same(dataBefore, data.stat());
                requireRootIdentity(empty);
                return empty;
            }
            requireDirectory(filesBefore);
            try (RootFileSystem.Directory files = fs.openDirectory(data, "files")) {
                same(filesBefore, files.stat());
                SaveDirectoryPolicy.Content content = fs.content(files);
                if (content.state == SaveDirectoryPolicy.ContentState.MISSING_DIRECTORY)
                    throw failure(RootProtocol.Status.MISSING_DIRECTORY);
                if (content.state == SaveDirectoryPolicy.ContentState.UNRECOGNIZED_LAYOUT)
                    throw failure(RootProtocol.Status.UNRECOGNIZED_LAYOUT);
                metadata.put("<data>", dataBefore.identity()); metadata.put("", filesBefore.identity());
                long[] total = {0}; int[] count = {0};
                walk(files, "", 0, hashes, metadata, bytes, modified, new HashSet<>(), total, count);
                same(filesBefore, files.stat()); same(dataBefore, data.stat());
                same(filesBefore, fs.childStat(data, "files"));
                fs.revalidateRoot(binding, data, files);
                try { SaveArchive.requireCompleteRuns(hashes.keySet()); }
                catch (IOException incomplete) { throw failure(RootProtocol.Status.CHANGED); }
                return new Snapshot(hashes, total[0], content.state.name(), metadata, bytes, modified);
            }
        }
    }
    private void requireFilesAbsent(RootFileSystem.Directory data) throws IOException {
        try { fs.childStat(data, "files"); }
        catch (RootFailure missing) {
            if (missing.status == RootProtocol.Status.MISSING_DIRECTORY) return;
            throw missing;
        }
        throw failure(RootProtocol.Status.CHANGED);
    }
    private void walk(RootFileSystem.Directory parent, String prefix, int depth,
            Map<String, String> hashes, Map<String, String> metadata, Map<String, byte[]> bytes,
            Map<String, Long> modified, Set<String> names, long[] total, int[] count) throws IOException {
        List<String> children = fs.children(parent, limits.entries);
        Collections.sort(children);
        for (String basename : children) {
            if (++count[0] > limits.entries) throw failure(RootProtocol.Status.TOO_LARGE);
            if (basename == null || basename.isEmpty() || basename.equals(".") || basename.equals("..")
                    || basename.indexOf('/') >= 0 || basename.indexOf('\\') >= 0)
                throw failure(RootProtocol.Status.UNSAFE_PATH);
            String name = prefix.isEmpty() ? basename : prefix + "/" + basename;
            if (!SaveArchive.included(name)) continue;
            if (depth + 1 > limits.depth || !SaveArchive.safeName(name)
                    || !names.add(name.toLowerCase(Locale.ROOT))) throw failure(RootProtocol.Status.UNSAFE_PATH);
            RootFileSystem.Stat before = fs.childStat(parent, basename); requireOwner(before);
            if (before.directory()) {
                if (prefix.isEmpty() && !basename.matches("game[0-9]+")) throw failure(RootProtocol.Status.UNSAFE_PATH);
                try (RootFileSystem.Directory directory = fs.openDirectory(parent, basename)) {
                    same(before, directory.stat());
                    metadata.put(name, before.identity());
                    walk(directory, name, depth + 1, hashes, metadata, bytes, modified, names, total, count);
                    same(before, directory.stat());
                }
            } else {
                requireRegular(before);
                if (hashes.size() >= limits.files || before.size < 0 || before.size > limits.bytes - total[0])
                    throw failure(RootProtocol.Status.TOO_LARGE);
                MessageDigest hash = SaveArchive.digest(); long size = 0;
                ByteArrayOutputStream copy = bytes == null ? null : new ByteArrayOutputStream((int) before.size);
                try (RootFileSystem.RegularFile file = fs.openFile(parent, basename)) {
                    same(before, file.stat()); requireRegular(file.stat());
                    try (InputStream input = file.input()) {
                        byte[] buffer = new byte[32768]; int n;
                        while ((n = input.read(buffer)) != -1) {
                            if (n == 0) throw failure(RootProtocol.Status.IO_ERROR);
                            size += n;
                            if (size > before.size || size > limits.bytes - total[0]) throw failure(RootProtocol.Status.CHANGED);
                            hash.update(buffer, 0, n); if (copy != null) copy.write(buffer, 0, n);
                        }
                        if (size != before.size) throw failure(RootProtocol.Status.CHANGED);
                        same(before, file.stat());
                    }
                }
                total[0] += size; hashes.put(name, SaveArchive.hex(hash.digest()));
                metadata.put(name, before.identity()); modified.put(name, before.modifiedSeconds);
                if (copy != null) bytes.put(name, copy.toByteArray());
            }
            same(before, fs.childStat(parent, basename));
        }
    }
    public byte[] archive(Snapshot snapshot) throws IOException {
        if (snapshot.bytes == null || !snapshot.bytes.keySet().equals(snapshot.inventory.hashes.keySet()))
            throw new IOException("Snapshot content was not captured");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (snapshot.bytes.isEmpty()) {
            byte[] empty = new byte[22]; empty[0] = 0x50; empty[1] = 0x4b; empty[2] = 0x05; empty[3] = 0x06;
            return empty;
        }
        try (ZipOutputStream zip = new ZipOutputStream(new SaveArchive.LimitedOutputStream(output, SaveArchive.MAX_BYTES))) {
            for (Map.Entry<String, byte[]> item : snapshot.bytes.entrySet()) {
                if (!SaveArchive.sha256(item.getValue()).equals(snapshot.inventory.hashes.get(item.getKey())))
                    throw failure(RootProtocol.Status.CHANGED);
                ZipEntry entry = new ZipEntry(item.getKey());
                // Standard ZIP DOS timestamp, bounded to avoid overflow from untrusted metadata.
                long seconds = snapshot.modified.get(item.getKey());
                entry.setTime(seconds >= 0 && seconds <= 4102444800L ? seconds * 1000 : 0);
                zip.putNextEntry(entry); zip.write(item.getValue()); zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
    private void requireOwner(RootFileSystem.Stat stat) throws IOException {
        if (stat.uid != binding.uid || (stat.mode & 0002) != 0 || (stat.mode & 07000) != 0
                || ((stat.mode & 0020) != 0 && stat.gid != binding.uid)) throw failure(RootProtocol.Status.UNSAFE_PATH);
    }
    private void requireDirectory(RootFileSystem.Stat stat) throws IOException {
        requireOwner(stat); if (!stat.directory()) throw failure(RootProtocol.Status.UNSAFE_PATH);
    }
    private void requireRegular(RootFileSystem.Stat stat) throws IOException {
        requireOwner(stat); if (!stat.regular() || stat.links != 1) throw failure(RootProtocol.Status.UNSAFE_PATH);
    }
    private static void same(RootFileSystem.Stat first, RootFileSystem.Stat second) throws IOException {
        if (!first.identity().equals(second.identity())) throw failure(RootProtocol.Status.CHANGED);
    }
    private static RootFailure failure(RootProtocol.Status status) { return new RootFailure(status); }
}
