package io.github.xyz1001.pdsync.bridge;

import android.content.pm.PackageManager;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import io.github.xyz1001.pdsync.bridge.SaveArchive;
import io.github.xyz1001.pdsync.bridge.SaveDirectoryPolicy;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Set;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;


final class SafeSaveTree {
    private final GameGuard guard;
    final File root;

    SafeSaveTree(File file, GameGuard gameGuard) {
        this.root = file;
        this.guard = gameGuard;
    }

    static final class State {
        final SaveDirectoryPolicy.Content content;
        final SaveArchive.Inventory inventory;
        final Map<String, String> metadata;

        State(Map<String, String> map, long j, Map<String, String> map2, SaveDirectoryPolicy.Content content) {
            this.inventory = new SaveArchive.Inventory(map, j);
            this.metadata = map2;
            this.content = content;
        }

        boolean same(State state) {
            return this.inventory.hashes.equals(state.inventory.hashes) && this.metadata.equals(state.metadata);
        }
    }

    State stable(File file, boolean z) throws IOException {
        for (int i = 0; i < 3; i++) {
            try {
                this.guard.requireQuiescent();
                State stateScan = scan(null, z);
                this.guard.requireQuiescent();
                try {
                    Thread.sleep(2000L);
                    this.guard.requireQuiescent();
                    State stateScan2 = scan(file, z);
                    this.guard.requireQuiescent();
                    if (!stateScan.same(stateScan2)) {
                        throw new BridgeFailure("SAVE_CHANGED", "Save changed during the quiet observation");
                    }
                    State stateScan3 = scan(null, z);
                    this.guard.requireQuiescent();
                    if (stateScan2.same(stateScan3)) {
                        return stateScan2;
                    }
                    throw new BridgeFailure("SAVE_CHANGED", "Save changed while it was read");
                } catch (InterruptedException unused) {
                    Thread.currentThread().interrupt();
                    throw new BridgeFailure("INTERRUPTED", "Save operation interrupted");
                }
            } catch (BridgeFailure e) {
                if (!"SAVE_CHANGED".equals(e.code) || i == 2) {
                    throw e;
                }
                if (file != null) {
                    clearCopy(file);
                }
            }
        }
        throw new AssertionError("Unreachable retry limit");
    }

    private static void clearCopy(File file) throws IOException {
        File[] fileArrListFiles = file.listFiles();
        if (fileArrListFiles == null) {
            throw new IOException("Cannot clear retry staging directory");
        }
        for (File file2 : fileArrListFiles) {
            if (Files.isDirectory(file2.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                clearCopy(file2);
            }
            Files.delete(file2.toPath());
        }
    }

    void unchanged(State state, boolean z) throws IOException {
        this.guard.requireQuiescent();
        if (!state.same(scan(null, z))) {
            throw new BridgeFailure("SAVE_CHANGED", "Save changed after export or backup; operation cancelled");
        }
        this.guard.requireQuiescent();
    }

    State scan(File file, boolean z) throws IOException {
        checkDirectory(this.root.getParentFile());
        String strIdentity = identity(checkDirectory(this.root));
        SaveDirectoryPolicy.Content content = SaveDirectoryPolicy.content(this.root);
        content.requireTransferable();
        TreeMap treeMap = new TreeMap();
        TreeMap treeMap2 = new TreeMap();
        treeMap2.put("", strIdentity);
        long[] jArr = {0};
        walk(this.root, "", file, treeMap, treeMap2, new HashSet<>(), jArr, new int[]{0});
        if (!strIdentity.equals(identity(checkDirectory(this.root)))) {
            throw new BridgeFailure("SAVE_CHANGED", "Save root changed while reading");
        }
        if (z) {
            try {
                SaveArchive.requireCompleteRuns(treeMap.keySet());
            } catch (IOException e) {
                throw new BridgeFailure("SAVE_CHANGED", "A save slot is incomplete; wait for the game to finish saving", e);
            }
        }
        return new State(treeMap, jArr[0], treeMap2, content);
    }

    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    private void walk(File directory, String prefix, File copy, Map<String,String> hashes,
                      Map<String,String> metadata, Set<String> names, long[] total, int[] count) throws IOException {
        File[] files = directory.listFiles();
        if (files == null) throw new BridgeFailure("SAVE_UNREADABLE", "Cannot list the private save directory");
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            String name = prefix.isEmpty() ? file.getName() : prefix + "/" + file.getName();
            if (!SaveArchive.included(name)) continue;
            if (!SaveArchive.safeName(name) || !names.add(name.toLowerCase(Locale.ROOT)) || ++count[0] > SaveArchive.MAX_ENTRIES)
                throw new BridgeFailure("UNSAFE_SAVE_PATH", "Unsafe, aliased, or excessive save path");
            StructStat before = stat(file); requireOwner(before);
            if (OsConstants.S_ISDIR(before.st_mode)) {
                metadata.put(name, identity(before));
                walk(file, name, copy, hashes, metadata, names, total, count);
                if (!identity(before).equals(identity(stat(file)))) throw new BridgeFailure("SAVE_CHANGED", "Save directory changed during enumeration");
                continue;
            }
            requireRegular(before);
            if (hashes.size() >= SaveArchive.MAX_FILES || before.st_size < 0 || before.st_size > SaveArchive.MAX_BYTES-total[0])
                throw new BridgeFailure("SAVE_TOO_LARGE", "Save exceeds the file count or 64 MiB limit");
            FileDescriptor fd = null;
            try {
                fd = Os.open(file.getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0);
                StructStat opened = Os.fstat(fd); requireRegular(opened);
                if (!identity(before).equals(identity(opened))) throw new BridgeFailure("SAVE_CHANGED", "Save file changed before opening");
                File target = copy == null ? null : new File(copy, name);
                if (target != null) SaveArchive.ensureParents(copy, target.getParentFile());
                MessageDigest hash = SaveArchive.digest(); long size = 0;
                try (FileInputStream input = new FileInputStream(fd);
                     OutputStream output = target == null ? new Sink() : Files.newOutputStream(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    byte[] buffer = new byte[32768]; int n;
                    while ((n = input.read(buffer)) != -1) {
                        size += n;
                        if (size > before.st_size || total[0]+size > SaveArchive.MAX_BYTES) throw new BridgeFailure("SAVE_CHANGED", "Save file grew while reading");
                        hash.update(buffer,0,n); output.write(buffer,0,n);
                    }
                    if (size != before.st_size || !identity(before).equals(identity(Os.fstat(input.getFD()))))
                        throw new BridgeFailure("SAVE_CHANGED", "Save file changed during reading");
                } finally { fd = null; }
                if (!identity(before).equals(identity(stat(file)))) throw new BridgeFailure("SAVE_CHANGED", "Save file was replaced during reading");
                if (target != null) Files.setLastModifiedTime(target.toPath(), Files.getLastModifiedTime(file.toPath(), LinkOption.NOFOLLOW_LINKS));
                total[0] += size; hashes.put(name,SaveArchive.hex(hash.digest())); metadata.put(name,identity(before));
            } catch (ErrnoException error) {
                throw new BridgeFailure("UNSAFE_SAVE_PATH", "Cannot safely open a private save file", error);
            } finally {
                if (fd != null) try { Os.close(fd); } catch (ErrnoException ignored) { }
            }
        }
    }

    static StructStat stat(File file) throws IOException {
        try {
            return Os.lstat(file.getPath());
        } catch (ErrnoException e) {
            throw new BridgeFailure("SAVE_UNREADABLE", "Cannot stat a private save path", e);
        }
    }

    static StructStat checkDirectory(File file) throws IOException {
        StructStat structStatStat = stat(file);
        requireOwner(structStatStat);
        if (OsConstants.S_ISDIR(structStatStat.st_mode)) {
            return structStatStat;
        }
        throw new BridgeFailure("UNSAFE_SAVE_PATH", "Save directory is missing, linked, or not a directory");
    }

    static void requireOwner(StructStat structStat) throws IOException {
        if (structStat.st_uid != Process.myUid()) {
            throw new BridgeFailure("UNSAFE_SAVE_PATH", "Save path is owned by an unexpected UID");
        }
    }

    static void requireRegular(StructStat structStat) throws IOException {
        requireOwner(structStat);
        if (!OsConstants.S_ISREG(structStat.st_mode) || structStat.st_nlink != 1) {
            throw new BridgeFailure("UNSAFE_SAVE_PATH", "Save files must be regular, non-linked files");
        }
    }

    static String identity(StructStat structStat) {
        return structStat.st_dev + ":" + structStat.st_ino + ":" + structStat.st_mode + ":" + structStat.st_uid + ":" + structStat.st_nlink + ":" + structStat.st_size + ":" + structStat.st_mtim.tv_sec + ":" + structStat.st_mtim.tv_nsec + ":" + structStat.st_ctim.tv_sec + ":" + structStat.st_ctim.tv_nsec;
    }

    private static final class Sink extends OutputStream {
        @Override // java.io.OutputStream
        public void write(int i) {
        }

        @Override // java.io.OutputStream
        public void write(byte[] bArr, int i, int i2) {
        }

        private Sink() {
        }
    }
}
