package io.github.xyz1001.pdsync.root;

import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import io.github.xyz1001.pdsync.bridge.SaveArchive;
import io.github.xyz1001.pdsync.bridge.SaveDirectoryPolicy;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Android-only descriptor-anchored reader. No unanchored fallback if proc/FD access is denied. */
final class AndroidRootFileSystem implements RootFileSystem {
    private static final int DIRECTORY_FLAG = 00200000; // Linux O_DIRECTORY, absent from public Android stubs.
    private static final int CLOEXEC = 02000000; // Linux flag is valid on API26; Java constant arrived in API27.
    private static final long MAX_APK_BYTES = 536870912L;
    private final class Pinned implements Directory, RegularFile {
        final ParcelFileDescriptor descriptor;
        final boolean directory;
        Pinned(ParcelFileDescriptor descriptor, boolean directory) { this.descriptor = descriptor; this.directory = directory; }
        String path() { return "/proc/self/fd/" + descriptor.getFd(); }
        @Override public Stat stat() throws IOException {
            try { return convert(Os.fstat(descriptor.getFileDescriptor())); }
            catch (ErrnoException error) { throw unsafe(error); }
        }
        @Override public InputStream input() throws IOException {
            if (directory) throw unsafe(null);
            // The returned stream owns a duplicate. The pinned file remains open for fstat checks.
            return new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.getFileDescriptor()));
        }
        @Override public void close() throws IOException { descriptor.close(); }
    }
    private Pinned pin(String path, boolean directory) throws IOException {
        FileDescriptor fd = null;
        try {
            // NONBLOCK ensures a raced FIFO cannot hang before fstat rejects it.
            fd = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | CLOEXEC | OsConstants.O_NONBLOCK
                    | (directory ? DIRECTORY_FLAG : 0), 0);
            StructStat stat = Os.fstat(fd);
            if (directory ? !OsConstants.S_ISDIR(stat.st_mode) : !OsConstants.S_ISREG(stat.st_mode)) throw unsafe(null);
            ParcelFileDescriptor owned = ParcelFileDescriptor.dup(fd);
            return new Pinned(owned, directory);
        } catch (ErrnoException error) {
            if (error.errno == OsConstants.ENOENT) throw new RootFailure(RootProtocol.Status.MISSING_DIRECTORY, error);
            throw unsafe(error);
        } finally { if (fd != null) try { Os.close(fd); } catch (ErrnoException ignored) { } }
    }
    private String child(Directory parent, String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) throw unsafe(null);
        return ((Pinned) parent).path() + "/" + name;
    }
    private Pinned trustedParent(String absolute) throws IOException {
        Pinned current = pin("/", true);
        try {
            trusted(current.stat());
            for (String part : absolute.substring(1).split("/")) {
                if (part.isEmpty()) throw unsafe(null);
                Pinned next = pin(child(current, part), true);
                try { trusted(next.stat()); } catch (IOException error) { next.close(); throw error; }
                current.close(); current = next;
            }
            Pinned result = current; current = null; return result;
        } finally { if (current != null) current.close(); }
    }
    private static void trusted(Stat stat) throws IOException {
        if (!stat.directory() || (stat.uid != 0 && stat.uid != 1000) || (stat.mode & 0002) != 0
                || ((stat.mode & 0020) != 0 && stat.gid != 0 && stat.gid != 1000)) throw unsafe(null);
    }
    private String dataParent(RootBinding binding) throws IOException {
        // Only system-controlled parent alias is canonicalized, never the package or files component.
        String parent = new File(binding.dataDir).getParent();
        String canonical = new File(parent).getCanonicalPath();
        String normal = "/data/user/" + binding.userId;
        if (!canonical.equals(normal) && !(binding.userId == 0 && canonical.equals("/data/data"))) throw unsafe(null);
        return canonical;
    }
    @Override public Directory openData(RootBinding binding) throws IOException {
        try (Pinned parent = trustedParent(dataParent(binding))) {
            Pinned data = pin(child(parent, binding.packageName), true);
            try {
                if (data.stat().uid != binding.uid) throw unsafe(null);
                return data;
            } catch (IOException error) { data.close(); throw error; }
        }
    }
    @Override public void revalidateRoot(RootBinding binding, Directory data, Directory files) throws IOException {
        try (Directory current = openData(binding)) {
            if (!current.stat().identity().equals(data.stat().identity())) throw new RootFailure(RootProtocol.Status.CHANGED);
            Stat currentFiles = childStat(current, "files");
            if (!currentFiles.identity().equals(files.stat().identity())) throw new RootFailure(RootProtocol.Status.CHANGED);
        }
    }
    @Override public Stat childStat(Directory parent, String name) throws IOException {
        try { return convert(Os.lstat(child(parent, name))); }
        catch (ErrnoException error) {
            if (error.errno == OsConstants.ENOENT) throw new RootFailure(RootProtocol.Status.MISSING_DIRECTORY, error);
            throw unsafe(error);
        }
    }
    @Override public Directory openDirectory(Directory parent, String name) throws IOException { return pin(child(parent, name), true); }
    @Override public RegularFile openFile(Directory parent, String name) throws IOException { return pin(child(parent, name), false); }
    @Override public List<String> children(Directory directory, int limit) throws IOException {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(new File(((Pinned) directory).path() + "/.").toPath())) {
            for (Path entry : entries) {
                if (names.size() >= limit) throw new RootFailure(RootProtocol.Status.TOO_LARGE);
                names.add(entry.getFileName().toString());
            }
        }
        return names;
    }
    @Override public SaveDirectoryPolicy.Content content(Directory directory) throws IOException {
        // Final "/." is a real directory; the only followed symlink is our own pinned /proc descriptor.
        return SaveDirectoryPolicy.content(new File(((Pinned) directory).path() + "/."));
    }
    String apkHash(String path, long[] aggregateBytes) throws IOException {
        if (!RootBinding.safeApkPath(path)) throw unsafe(null);
        File file = new File(path);
        // APK parent aliases such as /product -> /system/product are not silently followed.
        try (Pinned parent = trustedParent(file.getParent())) {
            Stat before = childStat(parent, file.getName());
            if (!before.regular() || before.links != 1 || (before.uid != 0 && before.uid != 1000)
                    || (before.mode & 0022) != 0 || before.size < 0 || before.size > MAX_APK_BYTES - aggregateBytes[0]) throw unsafe(null);
            try (RegularFile opened = openFile(parent, file.getName())) {
                if (!before.identity().equals(opened.stat().identity())) throw new RootFailure(RootProtocol.Status.CHANGED);
                MessageDigest digest = SaveArchive.digest(); long size = 0;
                try (InputStream input = opened.input()) {
                    byte[] buffer = new byte[32768]; int n;
                    while ((n = input.read(buffer)) != -1) {
                        size += n;
                        if (n == 0 || size > before.size) throw new RootFailure(RootProtocol.Status.CHANGED);
                        digest.update(buffer, 0, n);
                    }
                }
                if (size != before.size || !before.identity().equals(opened.stat().identity())
                        || !before.identity().equals(childStat(parent, file.getName()).identity()))
                    throw new RootFailure(RootProtocol.Status.CHANGED);
                aggregateBytes[0] += size;
                return SaveArchive.hex(digest.digest());
            }
        }
    }
    private static Stat convert(StructStat stat) {
        String times = Build.VERSION.SDK_INT >= 27 ? NewTimes.get(stat) : stat.st_mtime + ":0:" + stat.st_ctime + ":0";
        return new Stat(stat.st_dev, stat.st_ino, stat.st_mode, stat.st_uid, stat.st_gid, stat.st_nlink,
                stat.st_size, stat.st_mtime, times);
    }
    private static final class NewTimes {
        static String get(StructStat stat) { return stat.st_mtim.tv_sec + ":" + stat.st_mtim.tv_nsec + ":" + stat.st_ctim.tv_sec + ":" + stat.st_ctim.tv_nsec; }
    }
    private static RootFailure unsafe(Throwable cause) { return new RootFailure(RootProtocol.Status.UNSAFE_PATH, cause); }
}
