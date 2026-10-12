package io.github.xyz1001.pdsync.root.mutation;

import io.github.xyz1001.pdsync.bridge.SaveDirectoryPolicy;
import io.github.xyz1001.pdsync.root.RootFileSystem.Stat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory-only injected kernel model. It never invokes a host filesystem, Android API or command. */
public final class FakeMutationOs implements MutationOs {
    public static final byte[] LABEL = "u:object_r:app_data_file:s0:c1,c2\0".getBytes(StandardCharsets.US_ASCII);
    public static final class Node {
        public long inode, device = 1, links = 1, mtime = 100, ctime = 100;
        public int mode, uid, gid;
        public byte[] bytes = new byte[0], label = LABEL.clone();
        public final Map<String, Node> children = new LinkedHashMap<>();
        public boolean locked;
        Node(long inode, int mode, int uid, int gid) { this.inode = inode; this.mode = mode; this.uid = uid; this.gid = gid; }
        public boolean directory() { return (mode & 0170000) == Stat.DIRECTORY; }
    }
    private static final class H implements Handle {
        final Node node; boolean closed;
        H(Node node) { this.node = node; }
        @Override public void close() { closed = true; }
    }
    public int uid = 0, touches, mutations, maxWrite = Integer.MAX_VALUE, maxRead = Integer.MAX_VALUE;
    public boolean noXattr, noSync, noLink, noRename, zeroWrite, zeroRead;
    public Runnable beforeRename, beforeLink, beforeRead;
    public SaveDirectoryPolicy.Content contentResult;
    public String canonical = "/data/user/0";
    public final List<String> log = new ArrayList<>();
    private static final java.util.concurrent.atomic.AtomicLong NEXT_INODE = new java.util.concurrent.atomic.AtomicLong(1000);
    public final Node root = fresh(Stat.DIRECTORY | 0755, 0, 0);
    public final Node data, files;
    public FakeMutationOs() {
        Node d = dir(root, "data", 0771, 0, 1000);
        Node users = dir(d, "user", 0711, 0, 1000);
        Node user = dir(users, "0", 0711, 0, 1000);
        data = dir(user, "com.example.game", 0700, 10001, 10001);
        files = dir(data, "files", 0700, 10001, 10001);
    }
    public Node fresh(int mode, int uid, int gid) { return new Node(NEXT_INODE.getAndIncrement(), mode, uid, gid); }
    public Node dir(Node parent, String name, int mode, int uid, int gid) {
        Node n = fresh(Stat.DIRECTORY | mode, uid, gid); parent.children.put(name, n); return n;
    }
    public Node file(Node parent, String name, String value) {
        Node n = fresh(Stat.REGULAR | 0600, 10001, 10001);
        n.bytes = value.getBytes(StandardCharsets.US_ASCII); parent.children.put(name, n); return n;
    }
    public Node workspace() { return data.children.get(RootMutationSession.WORKSPACE); }
    private Node n(Handle h) throws IOException { touches++; H value = (H) h; if (value.closed) throw new Failure(Error.IO); return value.node; }
    private Node child(Handle h, String name) throws IOException {
        safeName(name); Node parent = n(h); if (!parent.directory()) throw new Failure(Error.IO);
        Node child = parent.children.get(name); if (child == null) throw new Failure(Error.MISSING); return child;
    }
    private static void safeName(String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\") || name.indexOf('\0') >= 0)
            throw new Failure(Error.IO);
    }
    private void exclusive(Node p, String name) throws IOException { safeName(name); if (p.children.containsKey(name)) throw new Failure(Error.EXISTS); }
    private void mutation(String op, Node parent, String name) {
        mutations++; log.add(op + ":" + parent.inode + ":" + name); parent.ctime++;
    }
    @Override public int effectiveUid() { return uid; }
    @Override public String canonicalSystemParent(String parent) { touches++; return canonical; }
    @Override public Handle openRoot() { touches++; return new H(root); }
    @Override public Handle openDirectory(Handle parent, String name) throws IOException {
        Node child = child(parent, name); if (!child.directory()) throw new Failure(Error.IO); return new H(child);
    }
    @Override public Handle openRegular(Handle parent, String name, boolean writable) throws IOException {
        Node child = child(parent, name); if ((child.mode & 0170000) != Stat.REGULAR) throw new Failure(Error.IO); return new H(child);
    }
    private Stat stat(Node n) { return new Stat(n.device, n.inode, n.mode, n.uid, n.gid, n.links, n.directory() ? 0 : n.bytes.length, n.mtime, n.mtime + ":0:" + n.ctime + ":0"); }
    @Override public Stat stat(Handle h) throws IOException { return stat(n(h)); }
    @Override public Stat childStat(Handle parent, String name) throws IOException { return stat(child(parent, name)); }
    @Override public List<String> children(Handle h, int limit) throws IOException {
        List<String> result = new ArrayList<>(n(h).children.keySet()); if (result.size() > limit) throw new Failure(Error.IO); return result;
    }
    /** Explicit policy fixture; use contentFixture to avoid any real host filesystem reads. */
    @Override public SaveDirectoryPolicy.Content content(Handle h) throws IOException {
        n(h); if (contentResult == null) throw new Failure(Error.UNSUPPORTED); return contentResult;
    }
    public static SaveDirectoryPolicy.Content contentFixture(SaveDirectoryPolicy.ContentState state) {
        try {
            java.lang.reflect.Constructor<SaveDirectoryPolicy.Content> constructor = SaveDirectoryPolicy.Content.class.getDeclaredConstructor(
                    SaveDirectoryPolicy.ContentState.class, int.class, int.class, int.class, List.class, boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance(state, state == SaveDirectoryPolicy.ContentState.ALLOWLISTED_FILES_PRESENT ? 1 : 0,
                    state == SaveDirectoryPolicy.ContentState.NO_TRANSFERABLE_FILES ? 1 : 0,
                    state == SaveDirectoryPolicy.ContentState.UNRECOGNIZED_LAYOUT ? 1 : 0, java.util.Collections.emptyList(), false);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    @Override public void mkdirExclusive(Handle parent, String name, int mode) throws IOException {
        Node p = n(parent); exclusive(p, name); dir(p, name, mode, 0, 0); mutation("mkdir", p, name);
    }
    @Override public Handle createExclusive(Handle parent, String name, int mode) throws IOException {
        Node p = n(parent); exclusive(p, name); Node child = fresh(Stat.REGULAR | mode, 0, 0); p.children.put(name, child);
        mutation("create", p, name); return new H(child);
    }
    @Override public int read(Handle h, byte[] bytes, int offset, int length, long position) throws IOException {
        if (beforeRead != null) { Runnable hook = beforeRead; beforeRead = null; hook.run(); }
        Node n = n(h); if (zeroRead || position >= n.bytes.length) return 0;
        int size = Math.min(Math.min(length, n.bytes.length - (int) position), maxRead);
        System.arraycopy(n.bytes, (int) position, bytes, offset, size); return size;
    }
    @Override public int write(Handle h, byte[] bytes, int offset, int length, long position) throws IOException {
        Node n = n(h); if (zeroWrite) return 0; int size = Math.min(length, maxWrite);
        n.bytes = Arrays.copyOf(n.bytes, Math.max(n.bytes.length, (int) position + size));
        System.arraycopy(bytes, offset, n.bytes, (int) position, size); n.ctime++; n.mtime++; mutations++; return size;
    }
    @Override public void chmod(Handle h, int mode) throws IOException { Node n = n(h); n.mode = (n.mode & 0170000) | mode; n.ctime++; mutations++; }
    @Override public void chown(Handle h, int uid, int gid) throws IOException { Node n = n(h); n.uid = uid; n.gid = gid; n.ctime++; mutations++; }
    @Override public void setModifiedTime(Handle h, long value) throws IOException { Node n = n(h); n.mtime = value / 1000; n.ctime++; mutations++; }
    @Override public void sync(Handle h) throws IOException { n(h); if (noSync) throw new Failure(Error.UNSUPPORTED); }
    @Override public byte[] selinux(Handle h) throws IOException { if (noXattr) throw new Failure(Error.UNSUPPORTED); return n(h).label.clone(); }
    @Override public void selinux(Handle h, byte[] value) throws IOException { if (noXattr) throw new Failure(Error.UNSUPPORTED); Node n = n(h); n.label = value.clone(); n.ctime++; mutations++; }
    @Override public void rename(Handle from, String a, Handle to, String b) throws IOException {
        if (beforeRename != null) { Runnable hook = beforeRename; beforeRename = null; hook.run(); }
        move(from, a, to, b);
    }
    private void move(Handle from, String a, Handle to, String b) throws IOException {
        Node source = child(from, a), p = n(from), q = n(to);
        Node previous = q.children.put(b, source); if (previous != null) previous.links--;
        p.children.remove(a); source.ctime++; mutation("rename", q, b);
    }
    @Override public void renameNoReplace(Handle from, String a, Handle to, String b) throws IOException {
        if (noRename) throw new Failure(Error.UNSUPPORTED);
        if (beforeRename != null) { Runnable hook = beforeRename; beforeRename = null; hook.run(); }
        exclusive(n(to), b); move(from, a, to, b);
    }
    @Override public void link(Handle from, String a, Handle to, String b) throws IOException {
        if (noLink) throw new Failure(Error.UNSUPPORTED);
        if (beforeLink != null) { Runnable hook = beforeLink; beforeLink = null; hook.run(); }
        Node p = n(to); exclusive(p, b); Node source = child(from, a);
        if (source.directory()) throw new Failure(Error.IO);
        p.children.put(b, source); source.links++; source.ctime++; mutation("link", p, b);
    }
    @Override public void unlink(Handle parent, String name) throws IOException {
        Node p = n(parent), c = child(parent, name); if (c.directory()) throw new Failure(Error.IO);
        p.children.remove(name); c.links--; c.ctime++; mutation("unlink", p, name);
    }
    @Override public void rmdir(Handle parent, String name) throws IOException {
        Node p = n(parent), c = child(parent, name); if (!c.directory() || !c.children.isEmpty()) throw new Failure(Error.IO);
        p.children.remove(name); mutation("rmdir", p, name);
    }
    @Override public Lock exclusiveLock(Handle h) throws IOException {
        Node n = n(h); if (n.locked) throw new Failure(Error.BUSY); n.locked = true; return () -> n.locked = false;
    }
}
