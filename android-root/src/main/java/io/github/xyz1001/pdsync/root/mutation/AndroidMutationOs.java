package io.github.xyz1001.pdsync.root.mutation;

import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import io.github.xyz1001.pdsync.root.RootFileSystem.Stat;
import io.github.xyz1001.pdsync.root.RootAtomicRename;
import io.github.xyz1001.pdsync.root.RootOpenFlags;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Android-only implementation. No shell, JNI, device probing or unanchored fallback. */
public final class AndroidMutationOs implements MutationOs {
    private final RootAtomicRename atomicRename;
    public AndroidMutationOs() { this(null); }
    public AndroidMutationOs(RootAtomicRename atomicRename) { this.atomicRename = atomicRename; }
    private final class Fd implements Handle {
        final ParcelFileDescriptor pfd;
        Fd(ParcelFileDescriptor pfd) { this.pfd = pfd; }
        @Override public void close() throws IOException { pfd.close(); }
        AndroidMutationOs owner() { return AndroidMutationOs.this; }
        String path() { return "/proc/self/fd/" + pfd.getFd(); }
    }
    private Fd fd(Handle h) throws IOException {
        if (!(h instanceof AndroidMutationOs.Fd)) throw new Failure(Error.IO);
        if (((Fd) h).owner() != this) throw new Failure(Error.IO);
        return (Fd) h;
    }
    private String child(Handle parent, String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.length() > 255 || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.indexOf('\0') >= 0) throw new Failure(Error.IO);
        return fd(parent).path() + "/" + name;
    }
    private Fd open(String path, int flags, int mode, Operation operation) throws IOException {
        FileDescriptor raw = null;
        try {
            raw = Os.open(path, flags | OsConstants.O_NOFOLLOW | RootOpenFlags.CLOEXEC | OsConstants.O_NONBLOCK, mode);
            return new Fd(duplicate(raw));
        } catch (ErrnoException e) { throw failure(operation, e); }
        finally { if (raw != null) try { Os.close(raw); } catch (ErrnoException ignored) { } }
    }
    private static ParcelFileDescriptor duplicate(FileDescriptor fd) throws IOException {
        ParcelFileDescriptor result;
        try { result = ParcelFileDescriptor.dup(fd); }
        catch (IOException e) { throw failure(Operation.DUP, e); }
        try { Os.fcntlInt(result.getFileDescriptor(), OsConstants.F_SETFD, OsConstants.FD_CLOEXEC); return result; }
        catch (ErrnoException e) {
            Failure failure = failure(Operation.FCNTL, e);
            try { result.close(); } catch (IOException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }
    @Override public int effectiveUid() { return Os.geteuid(); }
    @Override public String canonicalSystemParent(String parent) throws IOException { return new File(parent).getCanonicalPath(); }
    @Override public Handle openRoot() throws IOException {
        return open("/", OsConstants.O_RDONLY | RootOpenFlags.directory(OsConstants.O_NOFOLLOW), 0, Operation.OPEN_DIRECTORY);
    }
    @Override public Handle openDirectory(Handle parent, String name) throws IOException {
        return open(child(parent, name), OsConstants.O_RDONLY | RootOpenFlags.directory(OsConstants.O_NOFOLLOW), 0, Operation.OPEN_DIRECTORY);
    }
    @Override public Handle openRegular(Handle parent, String name, boolean writable) throws IOException {
        Fd value = open(child(parent, name), writable ? OsConstants.O_RDWR : OsConstants.O_RDONLY, 0, Operation.OPEN_REGULAR);
        try { if (!stat(value).regular()) throw new Failure(Error.IO); return value; }
        catch (IOException e) { value.close(); throw e; }
    }
    @Override public Stat stat(Handle h) throws IOException {
        try { return convert(Os.fstat(fd(h).pfd.getFileDescriptor())); } catch (ErrnoException e) { throw failure(Operation.FSTAT, e); }
    }
    @Override public Stat childStat(Handle parent, String name) throws IOException {
        try { return convert(Os.lstat(child(parent, name))); }
        catch (ErrnoException e) {
            // ENOENT can also mean a vanished /proc/self/fd parent. Only a live directory
            // descriptor may establish an optional child's absence; preserve FSTAT errors.
            if (e.errno == OsConstants.ENOENT && !stat(parent).directory()) throw new Failure(Error.IO, Operation.FSTAT, 0, e);
            throw failure(Operation.LSTAT, e);
        }
    }
    @Override public List<String> children(Handle h, int limit) throws IOException {
        if (limit < 0 || limit > 16384) throw new Failure(Error.IO);
        List<String> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(new File(fd(h).path() + "/.").toPath())) {
            for (Path path : stream) {
                if (result.size() == limit) throw new Failure(Error.IO);
                result.add(path.getFileName().toString());
            }
        } catch (IOException e) { throw failure(Operation.READDIR, e); }
        return result;
    }
    @Override public io.github.xyz1001.pdsync.bridge.SaveDirectoryPolicy.Content content(Handle files) throws IOException {
        return io.github.xyz1001.pdsync.bridge.SaveDirectoryPolicy.content(new File(fd(files).path() + "/."));
    }
    @Override public io.github.xyz1001.pdsync.bridge.SaveArchive.Inventory extractArchive(Handle parent,
            String name, java.io.InputStream input) throws IOException {
        if (effectiveUid() != 0 || input == null) throw new Failure(Error.IO);
        Stat parentBefore = stat(parent); requirePrivateDirectory(parentBefore);
        Stat before = childStat(parent, name); requirePrivateDirectory(before);
        if (before.device != parentBefore.device) throw new Failure(Error.IO);
        try (Handle pinned = openDirectory(parent, name)) {
            if (!before.identity().equals(stat(pinned).identity()) || !children(pinned, 1).isEmpty()) throw new Failure(Error.IO);
            // Keep an actual final child component: SaveArchive.ensureParents normalizes a trailing '/.'.
            // Every path reopened by the unchanged parser is below this empty root-owned 0700 directory.
            io.github.xyz1001.pdsync.bridge.SaveArchive.Inventory result =
                    io.github.xyz1001.pdsync.bridge.SaveArchive.extract(input, new File(child(parent, name)));
            Stat after = childStat(parent, name), descriptor = stat(pinned), parentAfter = stat(parent);
            requirePrivateDirectory(after); requirePrivateDirectory(parentAfter);
            if (!sameDirectory(before, after) || !sameDirectory(before, descriptor)
                    || !sameDirectory(parentBefore, parentAfter)) throw new Failure(Error.IO);
            sync(pinned); sync(parent); return result;
        }
    }
    private static boolean sameDirectory(Stat a, Stat b) {
        return b.directory() && a.device == b.device && a.inode == b.inode && a.mode == b.mode && a.uid == b.uid && a.gid == b.gid;
    }
    private static void requirePrivateDirectory(Stat s) throws IOException {
        if (!s.directory() || s.uid != 0 || s.gid != 0 || (s.mode & 07777) != 0700) throw new Failure(Error.IO);
    }
    @Override public void mkdirExclusive(Handle parent, String name, int mode) throws IOException {
        try { Os.mkdir(child(parent, name), mode); } catch (ErrnoException e) { throw failure(Operation.MKDIR, e); }
    }
    @Override public Handle createExclusive(Handle parent, String name, int mode) throws IOException {
        return open(child(parent, name), OsConstants.O_RDWR | OsConstants.O_CREAT | OsConstants.O_EXCL, mode, Operation.OPEN_REGULAR);
    }
    @Override public int read(Handle h, byte[] b, int offset, int length, long position) throws IOException {
        try { return Os.pread(fd(h).pfd.getFileDescriptor(), b, offset, length, position); }
        catch (ErrnoException e) { throw failure(Operation.PREAD, e); }
    }
    @Override public int write(Handle h, byte[] b, int offset, int length, long position) throws IOException {
        try { return Os.pwrite(fd(h).pfd.getFileDescriptor(), b, offset, length, position); }
        catch (ErrnoException e) { throw failure(Operation.PWRITE, e); }
    }
    @Override public void chmod(Handle h, int mode) throws IOException {
        try { Os.fchmod(fd(h).pfd.getFileDescriptor(), mode); } catch (ErrnoException e) { throw failure(Operation.FCHMOD, e); }
    }
    @Override public void chown(Handle h, int uid, int gid) throws IOException {
        try { Os.fchown(fd(h).pfd.getFileDescriptor(), uid, gid); } catch (ErrnoException e) { throw failure(Operation.FCHOWN, e); }
    }
    @Override public void setModifiedTime(Handle h, long millis) throws IOException {
        if (millis < 0) throw new Failure(Error.IO);
        try { Files.setLastModifiedTime(new File(fd(h).path()).toPath(), java.nio.file.attribute.FileTime.fromMillis(millis)); }
        catch (IOException e) { throw failure(Operation.TIMESTAMP, e); }
    }
    @Override public void sync(Handle h) throws IOException {
        try { Os.fsync(fd(h).pfd.getFileDescriptor()); } catch (ErrnoException e) { throw failure(Operation.FSYNC, e); }
    }
    @Override public byte[] selinux(Handle h) throws IOException {
        try { return Os.getxattr(fd(h).path(), "security.selinux"); } catch (ErrnoException e) { throw failure(Operation.GETXATTR, e); }
    }
    @Override public void selinux(Handle h, byte[] value) throws IOException {
        try { Os.setxattr(fd(h).path(), "security.selinux", value.clone(), 0); } catch (ErrnoException e) { throw failure(Operation.SETXATTR, e); }
    }
    @Override public void rename(Handle from, String a, Handle to, String b) throws IOException {
        try { Os.rename(child(from, a), child(to, b)); } catch (ErrnoException e) { throw failure(Operation.RENAME, e); }
    }
    @Override public void renameNoReplace(Handle from, String a, Handle to, String b) throws IOException {
        child(from, a); child(to, b); // Validate basenames before passing only pinned descriptors to native code.
        if (atomicRename == null) throw new Failure(Error.UNSUPPORTED);
        RootAtomicRename.Result result = atomicRename.renameNoReplace(fd(from).pfd.getFd(), a, fd(to).pfd.getFd(), b);
        if (result == RootAtomicRename.Result.OK) return;
        if (result == RootAtomicRename.Result.EXISTS) throw new Failure(Error.EXISTS, Operation.RENAME, result.errno, null);
        if (result == RootAtomicRename.Result.MISSING) throw new Failure(Error.MISSING, Operation.RENAME, result.errno, null);
        if (result == RootAtomicRename.Result.PERMISSION) throw new Failure(Error.PERMISSION, Operation.RENAME, result.errno, null);
        if (result == RootAtomicRename.Result.CROSS_DEVICE) throw new Failure(Error.CROSS_DEVICE, Operation.RENAME, result.errno, null);
        if (result == RootAtomicRename.Result.NOT_IMPLEMENTED
                || result == RootAtomicRename.Result.UNSUPPORTED || result == RootAtomicRename.Result.INVALID_ARGUMENT)
            throw new Failure(Error.UNSUPPORTED, Operation.RENAME, result.errno, null);
        throw new Failure(Error.IO, Operation.RENAME, result.errno, null);
    }
    @Override public void link(Handle from, String a, Handle to, String b) throws IOException {
        try { Os.link(child(from, a), child(to, b)); } catch (ErrnoException e) { throw failure(Operation.LINK, e); }
    }
    @Override public void unlink(Handle parent, String name) throws IOException {
        if (!childStat(parent, name).regular()) throw new Failure(Error.IO);
        try { Os.remove(child(parent, name)); } catch (ErrnoException e) { throw failure(Operation.REMOVE, e); }
    }
    @Override public void rmdir(Handle parent, String name) throws IOException {
        if (!childStat(parent, name).directory()) throw new Failure(Error.IO);
        try { Os.remove(child(parent, name)); } catch (ErrnoException e) { throw failure(Operation.REMOVE, e); }
    }
    @Override public Lock exclusiveLock(Handle h) throws IOException {
        ParcelFileDescriptor.AutoCloseOutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(
                duplicate(fd(h).pfd.getFileDescriptor()));
        try {
            final FileLock lock = stream.getChannel().tryLock();
            if (lock == null) throw new Failure(Error.BUSY);
            return () -> { try { lock.release(); } finally { stream.close(); } };
        } catch (OverlappingFileLockException e) { stream.close(); throw new Failure(Error.BUSY, e); }
        catch (IOException e) {
            Failure failure = failure(Operation.LOCK, e);
            try { stream.close(); } catch (IOException close) { failure.addSuppressed(close); }
            throw failure;
        } catch (RuntimeException e) { stream.close(); throw e; }
    }
    private static Stat convert(StructStat s) {
        String times = Build.VERSION.SDK_INT >= 27 ? Times.value(s) : s.st_mtime + ":0:" + s.st_ctime + ":0";
        return new Stat(s.st_dev, s.st_ino, s.st_mode, s.st_uid, s.st_gid, s.st_nlink, s.st_size, s.st_mtime, times);
    }
    private static final class Times {
        static String value(StructStat s) { return s.st_mtim.tv_sec + ":" + s.st_mtim.tv_nsec + ":" + s.st_ctim.tv_sec + ":" + s.st_ctim.tv_nsec; }
    }
    private static Failure failure(Operation operation, IOException e) {
        if (e instanceof Failure) return (Failure) e;
        Throwable cause = e.getCause();
        for (int i = 0; cause != null && i < 8; i++, cause = cause.getCause()) {
            if (cause instanceof ErrnoException) return failure(operation, (ErrnoException) cause);
        }
        return new Failure(Error.IO, operation, 0, e);
    }
    private static Failure failure(Operation operation, ErrnoException e) {
        Error code = e.errno == OsConstants.ENOENT ? Error.MISSING : e.errno == OsConstants.EEXIST ? Error.EXISTS
                : e.errno == OsConstants.EACCES || e.errno == OsConstants.EPERM ? Error.PERMISSION
                : e.errno == OsConstants.EXDEV ? Error.CROSS_DEVICE
                : e.errno == OsConstants.ENOSYS || e.errno == OsConstants.EOPNOTSUPP ? Error.UNSUPPORTED : Error.IO;
        return new Failure(code, operation, e.errno, e);
    }
}
