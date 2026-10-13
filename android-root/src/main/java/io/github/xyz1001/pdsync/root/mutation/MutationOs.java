package io.github.xyz1001.pdsync.root.mutation;

import io.github.xyz1001.pdsync.root.RootFileSystem.Stat;
import java.io.IOException;
import java.util.List;

/** Injectable, deliberately narrow kernel boundary. Paths above this layer are session capabilities. */
public interface MutationOs {
    interface Handle extends AutoCloseable { @Override void close() throws IOException; }
    interface Lock extends AutoCloseable { @Override void close() throws IOException; }
    enum Error { MISSING, EXISTS, BUSY, PERMISSION, CROSS_DEVICE, UNSUPPORTED, IO }
    /** Fixed diagnostics only; never carries a path, basename, or descriptor number. */
    enum Operation { NONE, OPEN_DIRECTORY, OPEN_REGULAR, DUP, FCNTL, FSTAT, LSTAT, READDIR,
        MKDIR, PREAD, PWRITE, FCHMOD, FCHOWN, FSYNC, GETXATTR, SETXATTR, RENAME, LINK, REMOVE,
        LOCK, TIMESTAMP, LSTAT_DATA_PACKAGE, LSTAT_SAVE_FILES, LSTAT_WORKSPACE }
    final class Failure extends IOException {
        public final Error error;
        public final Operation operation;
        /** Bounded Linux errno, or zero when this failure has no trustworthy kernel errno. */
        public final int errno;
        public Failure(Error error) { this(error, 0, null); }
        public Failure(Error error, Throwable cause) { this(error, 0, cause); }
        public Failure(Error error, int errno) { this(error, errno, null); }
        public Failure(Error error, int errno, Throwable cause) {
            this(error, Operation.NONE, errno, cause);
        }
        public Failure(Error error, Operation operation, int errno, Throwable cause) {
            super(error.name(), cause); this.error = error;
            this.operation = operation == null ? Operation.NONE : operation;
            this.errno = errno >= 1 && errno <= 4095 ? errno : 0;
        }
    }
    int effectiveUid() throws IOException;
    String canonicalSystemParent(String fixedParent) throws IOException;
    Handle openRoot() throws IOException;
    /** Open exactly one child O_NOFOLLOW; files are never opened for writing here. */
    Handle openDirectory(Handle parent, String name) throws IOException;
    Handle openRegular(Handle parent, String name, boolean writable) throws IOException;
    Stat stat(Handle handle) throws IOException;
    /** lstat, never follows the child. Missing is an explicit Failure(MISSING). */
    Stat childStat(Handle parent, String name) throws IOException;
    List<String> children(Handle directory, int limit) throws IOException;
    io.github.xyz1001.pdsync.bridge.SaveDirectoryPolicy.Content content(Handle files) throws IOException;
    /** Existing unchanged parser, confined to an empty root-private child of a root-private pinned parent. */
    default io.github.xyz1001.pdsync.bridge.SaveArchive.Inventory extractArchive(Handle privateParent,
            String privateChild, java.io.InputStream input) throws IOException {
        throw new Failure(Error.UNSUPPORTED);
    }
    /** mkdir is exclusive. All directory creation is confined to the session's private namespace. */
    void mkdirExclusive(Handle parent, String name, int mode) throws IOException;
    Handle createExclusive(Handle parent, String name, int mode) throws IOException;
    int read(Handle file, byte[] bytes, int offset, int length, long position) throws IOException;
    int write(Handle file, byte[] bytes, int offset, int length, long position) throws IOException;
    void chmod(Handle file, int mode) throws IOException;
    void chown(Handle file, int uid, int gid) throws IOException;
    void setModifiedTime(Handle privateFile, long modifiedMillis) throws IOException;
    void sync(Handle handle) throws IOException;
    /** The only accessible extended attribute is security.selinux, addressed through the pinned FD. */
    byte[] selinux(Handle handle) throws IOException;
    void selinux(Handle handle, byte[] trustedLocalValue) throws IOException;
    void rename(Handle fromParent, String fromName, Handle toParent, String toName) throws IOException;
    /** Atomic kernel no-replace rename, including directories; unsupported must never fall back. */
    void renameNoReplace(Handle fromParent, String fromName, Handle toParent, String toName) throws IOException;
    /** Kernel link is atomic no-replace. EXISTS must not alter the destination. */
    void link(Handle fromParent, String fromName, Handle toParent, String toName) throws IOException;
    void unlink(Handle privateParent, String privateName) throws IOException;
    void rmdir(Handle privateParent, String privateName) throws IOException;
    Lock exclusiveLock(Handle rootPrivateFile) throws IOException;
}
