package io.github.xyz1001.pdsync.root;

import io.github.xyz1001.pdsync.bridge.SaveDirectoryPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Read-only injectable operations. Android implementation pins every traversed directory. */
public interface RootFileSystem {
    interface Handle extends AutoCloseable {
        Stat stat() throws IOException;
        @Override void close() throws IOException;
    }
    interface Directory extends Handle { }
    interface RegularFile extends Handle { InputStream input() throws IOException; }
    final class Stat {
        public static final int DIRECTORY = 0040000, REGULAR = 0100000, SYMLINK = 0120000;
        public final long device, inode, links, size, modifiedSeconds;
        public final int mode, uid, gid;
        public final String times;
        public Stat(long device, long inode, int mode, int uid, int gid, long links, long size,
                long modifiedSeconds, String times) {
            this.device = device; this.inode = inode; this.mode = mode; this.uid = uid; this.gid = gid;
            this.links = links; this.size = size; this.modifiedSeconds = modifiedSeconds; this.times = times;
        }
        public boolean directory() { return (mode & 0170000) == DIRECTORY; }
        public boolean regular() { return (mode & 0170000) == REGULAR; }
        public String identity() {
            return device + ":" + inode + ":" + mode + ":" + uid + ":" + gid + ":" + links + ":" + size + ":" + times;
        }
    }
    /** Validates only fixed PM dataDir ancestry, then opens the expected app's directory NOFOLLOW. */
    Directory openData(RootBinding binding) throws IOException;
    /** Reopen original trusted path and compare it with the still-pinned data and files handles. */
    void revalidateRoot(RootBinding binding, Directory data, Directory files) throws IOException;
    Stat childStat(Directory parent, String basename) throws IOException;
    Directory openDirectory(Directory parent, String basename) throws IOException;
    RegularFile openFile(Directory parent, String basename) throws IOException;
    List<String> children(Directory directory, int limit) throws IOException;
    SaveDirectoryPolicy.Content content(Directory directory) throws IOException;
}
