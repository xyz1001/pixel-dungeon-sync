package io.github.xyz1001.pdsync.root.mutation;

import io.github.xyz1001.pdsync.bridge.SaveArchive;
import io.github.xyz1001.pdsync.bridge.SaveDirectoryPolicy;
import io.github.xyz1001.pdsync.root.RootBinding;
import io.github.xyz1001.pdsync.root.RootFileSystem.Stat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Source-only, root-gated mutation capabilities. Never deletes, overwrites or creates a live directory.
 * Callers must separately check PM/APK binding, reliable cached-or-absent UID observation and journal policy around each action.
 * A pinned FD prevents path redirection, not a game launch or changes through another open file descriptor.
 */
public final class RootMutationSession implements AutoCloseable {
    public static final String WORKSPACE = ".pdsync-root-restore";
    public static final int MAX_BYTES = 64 * 1024 * 1024, MAX_JOURNAL = 16 * 1024 * 1024;
    private static final String LOCK = "lock", JOURNAL = "journal";
    public enum Code { NOT_ROOT, UNSAFE, CHANGED, CONFLICT, UNSUPPORTED, BUSY, TOO_LARGE, CLOSED }
    public static final class Failure extends IOException {
        public final Code code;
        /** Present on a failed quarantine; its unknown contents are deliberately preserved. */
        public final String preservedReservation;
        Failure(Code code) { this(code, null, null); }
        Failure(Code code, String preserved, Throwable cause) {
            super(code.name(), cause); this.code = code; this.preservedReservation = preserved;
        }
    }
    /** Only this session can mint attributes, from a pinned, locally validated live entry. */
    public final class LocalAttributes {
        public final int uid, gid, mode;
        private final byte[] label;
        private LocalAttributes(Stat s, byte[] label, int mode) throws IOException {
            this.uid = s.uid; this.gid = s.gid; this.mode = mode; this.label = validLabel(label);
        }
        public String selinux() {
            int n = label.length; if (label[n - 1] == 0) n--;
            return new String(label, 0, n, StandardCharsets.US_ASCII);
        }
        private RootMutationSession owner() { return RootMutationSession.this; }
    }
    private final class Parent implements AutoCloseable {
        final MutationOs.Handle handle;
        final String relative;
        final boolean owned;
        final Stat original;
        Parent(MutationOs.Handle handle, String relative, boolean owned) throws IOException {
            this.handle = handle; this.relative = relative; this.owned = owned; this.original = os.stat(handle);
        }
        @Override public void close() throws IOException { if (owned) handle.close(); }
    }
    public final class LiveFile implements AutoCloseable {
        public final String relative;
        private final Parent parent;
        private final String name;
        private final MutationOs.Handle handle;
        private final Stat expected;
        private final byte[] label;
        private final byte[] contentHash;
        private boolean closed;
        private LiveFile(String relative, Parent parent, String name, MutationOs.Handle h, Stat expected, byte[] label, byte[] contentHash) {
            this.relative = relative; this.parent = parent; this.name = name; this.handle = h;
            this.expected = expected; this.label = label; this.contentHash = contentHash; liveFiles.add(this);
        }
        public Stat stat() throws IOException { checkLive(this); return os.stat(handle); }
        public LocalAttributes attributes() throws IOException { return localAttributes(this); }
        @Override public void close() throws IOException {
            if (!closed) { closed = true; try { handle.close(); } finally { parent.close(); } }
        }
        private RootMutationSession owner() { return RootMutationSession.this; }
    }
    public final class PrivateFile implements AutoCloseable {
        public final String name;
        private final MutationOs.Handle parent, handle;
        private final Reservation reservation;
        private Stat expected;
        private byte[] label;
        private boolean closed, removed;
        private PrivateFile(String name, MutationOs.Handle parent, MutationOs.Handle handle,
                Reservation reservation, Stat expected, byte[] label) {
            this.name = name; this.parent = parent; this.handle = handle; this.reservation = reservation;
            this.expected = expected; this.label = label;
            privateFiles.add(this);
        }
        /** Includes link-count transitions made by this session, not an unvalidated path lookup. */
        public Stat stat() throws IOException { checkPrivate(this); return os.stat(handle); }
        public String relativeName() { return reservation == null ? name : reservation.name + "/" + name; }
        @Override public void close() throws IOException { if (!closed) { closed = true; handle.close(); } }
        private RootMutationSession owner() { return RootMutationSession.this; }
    }
    public final class Reservation implements AutoCloseable {
        public final String name;
        private final MutationOs.Handle handle;
        private final Stat expected;
        private boolean closed, removed, occupied, unknown;
        private Reservation(String name, MutationOs.Handle handle, Stat expected) {
            this.name = name; this.handle = handle; this.expected = expected; reservations.add(this);
        }
        public boolean preservesUnknownEntry() { return unknown; }
        @Override public void close() throws IOException { if (!closed) { closed = true; handle.close(); } }
        private RootMutationSession owner() { return RootMutationSession.this; }
    }
    private MutationProcessLock.Lease processLock;
    private final MutationOs os;
    private final RootBinding binding;
    private MutationOs.Handle data, files, workspace, lockFile;
    private MutationOs.Lock lock;
    private Stat originalData, originalFiles, originalWorkspace, originalLock;
    private String canonicalParent;
    private final List<PrivateFile> privateFiles = new ArrayList<>();
    private final List<LiveFile> liveFiles = new ArrayList<>();
    private final List<Reservation> reservations = new ArrayList<>();
    private boolean closed, conflicted, preflightPassed;

    private RootMutationSession(MutationOs os, RootBinding binding) { this.os = os; this.binding = binding; }
    public static RootMutationSession open(MutationOs os, RootBinding binding) throws IOException {
        if (os == null || binding == null) throw new Failure(Code.UNSAFE);
        // This MUST precede canonicalization, opening any FD, and all target reads/writes.
        if (os.effectiveUid() != 0) throw new Failure(Code.NOT_ROOT);
        RootMutationSession s = new RootMutationSession(os, binding);
        try { s.initialize(); return s; }
        catch (IOException | RuntimeException e) { try { s.close(); } catch (IOException close) { e.addSuppressed(close); } throw e; }
    }
    private void initialize() throws IOException {
        canonicalParent = os.canonicalSystemParent(binding.dataDir.substring(0, binding.dataDir.lastIndexOf('/')));
        if (!canonicalParent.equals("/data/user/" + binding.userId)
                && !(binding.userId == 0 && canonicalParent.equals("/data/data"))) fail(Code.UNSAFE);
        data = openBoundData(); originalData = os.stat(data); liveDirectory(originalData);
        files = os.openDirectory(data, "files"); originalFiles = os.stat(files); liveDirectory(originalFiles);
        if (originalData.device != originalFiles.device) fail(Code.UNSAFE);
        // mkdir never replaces. A pre-existing directory is accepted only as a root-private recovery workspace.
        try { os.mkdirExclusive(data, WORKSPACE, 0700); }
        catch (MutationOs.Failure e) { if (e.error != MutationOs.Error.EXISTS) throw e; }
        workspace = os.openDirectory(data, WORKSPACE); originalWorkspace = os.stat(workspace);
        privateDirectory(originalWorkspace);
        try { processLock = MutationProcessLock.acquire(originalWorkspace.device, originalWorkspace.inode); }
        catch (MutationOs.Failure busy) { throw new Failure(Code.BUSY, null, busy); }
        try { lockFile = os.createExclusive(workspace, LOCK, 0600); os.chmod(lockFile, 0600); os.sync(lockFile); }
        catch (MutationOs.Failure e) {
            if (e.error != MutationOs.Error.EXISTS) throw e;
            lockFile = os.openRegular(workspace, LOCK, true);
        }
        originalLock = os.stat(lockFile);
        if (!originalLock.regular() || originalLock.uid != 0 || originalLock.gid != 0
                || (originalLock.mode & 07777) != 0600 || originalLock.links != 1 || originalLock.size != 0
                || originalLock.device != originalData.device) fail(Code.UNSAFE);
        same(originalLock, os.childStat(workspace, LOCK));
        lock = os.exclusiveLock(lockFile);
        os.sync(workspace); os.sync(data);
        revalidate();
    }
    private MutationOs.Handle openBoundData() throws IOException {
        MutationOs.Handle current = os.openRoot();
        try {
            trustedDirectory(os.stat(current));
            for (String component : canonicalParent.substring(1).split("/")) {
                MutationOs.Handle next = os.openDirectory(current, component);
                try { trustedDirectory(os.stat(next)); } catch (IOException e) { next.close(); throw e; }
                current.close(); current = next;
            }
            MutationOs.Handle result = os.openDirectory(current, binding.packageName);
            try { liveDirectory(os.stat(result)); return result; }
            catch (IOException e) { result.close(); throw e; }
        } finally { current.close(); }
    }
    /** Borrowed descriptors for the trusted tree adapter. Do not close or expose to untrusted callers. */
    public synchronized MutationOs.Handle dataHandle() throws IOException { revalidate(); return data; }
    public synchronized MutationOs.Handle filesHandle() throws IOException { revalidate(); return files; }
    public synchronized MutationOs.Handle workspaceHandle() throws IOException { revalidate(); return workspace; }
    public String canonicalDataDir() { return canonicalParent + "/" + binding.packageName; }
    public synchronized Stat dataStat() throws IOException { active(); return os.stat(data); }
    public synchronized Stat filesStat() throws IOException { active(); return os.stat(files); }
    public synchronized Stat workspaceStat() throws IOException { active(); return os.stat(workspace); }
    public synchronized void revalidate() throws IOException {
        active();
        String now = os.canonicalSystemParent(binding.dataDir.substring(0, binding.dataDir.lastIndexOf('/')));
        if (!canonicalParent.equals(now)) fail(Code.CHANGED);
        try (MutationOs.Handle reopened = openBoundData()) {
            sameDirectory(originalData, os.stat(reopened)); sameDirectory(originalData, os.stat(data));
            sameDirectory(originalFiles, os.childStat(reopened, "files")); sameDirectory(originalFiles, os.stat(files));
            sameDirectory(originalWorkspace, os.childStat(reopened, WORKSPACE));
            sameDirectory(originalWorkspace, os.stat(workspace)); privateDirectory(os.stat(workspace));
            same(originalLock, os.stat(lockFile)); same(originalLock, os.childStat(workspace, LOCK));
        }
    }
    public synchronized SaveDirectoryPolicy.Content rootContent() throws IOException {
        revalidate(); SaveDirectoryPolicy.Content result = os.content(files); revalidate(); return result;
    }
    public synchronized List<String> listLive(String relativeDirectory, int limit) throws IOException {
        revalidate();
        try (Parent parent = parentDirectory(relativeDirectory)) {
            List<String> result = new ArrayList<>(os.children(parent.handle, limit));
            revalidateParent(parent); return Collections.unmodifiableList(result);
        }
    }
    public synchronized Stat liveDirectoryStat(String relativeDirectory) throws IOException {
        revalidate();
        try (Parent parent = parentDirectory(relativeDirectory)) { revalidateParent(parent); return os.stat(parent.handle); }
    }
    private Parent parentDirectory(String relative) throws IOException {
        if (relative == null) fail(Code.UNSAFE);
        if (relative.isEmpty()) return new Parent(files, "", false);
        if (!SaveArchive.safeName(relative) || !relative.matches("game[0-9]+(?:/.+)?")) fail(Code.UNSAFE);
        MutationOs.Handle current = files; boolean owned = false;
        try {
            for (String part : relative.split("/")) {
                Stat before = os.childStat(current, part); liveDirectory(before);
                MutationOs.Handle next = os.openDirectory(current, part);
                try { sameDirectory(before, os.stat(next)); liveDirectory(os.stat(next)); }
                catch (IOException e) { next.close(); throw e; }
                if (owned) current.close(); current = next; owned = true;
            }
            Parent result = new Parent(current, relative, true); owned = false; return result;
        } catch (MutationOs.Failure e) {
            if (e.error == MutationOs.Error.MISSING) throw new Failure(Code.UNSUPPORTED);
            throw e;
        } finally { if (owned) current.close(); }
    }
    private void revalidateParent(Parent parent) throws IOException {
        revalidate();
        try (Parent reopened = parentDirectory(parent.relative)) {
            sameDirectory(parent.original, os.stat(reopened.handle)); sameDirectory(parent.original, os.stat(parent.handle));
        }
    }
    public synchronized LiveFile openLive(String relative) throws IOException {
        revalidate(); requireLiveName(relative);
        Parent parent = parentDirectory(parentName(relative)); MutationOs.Handle h = null;
        try {
            String name = basename(relative); Stat before = os.childStat(parent.handle, name); liveFile(before);
            h = os.openRegular(parent.handle, name, false); same(before, os.stat(h));
            byte[] label = validLabel(os.selinux(h)); same(before, os.stat(h));
            revalidateParent(parent); same(before, os.childStat(parent.handle, name));
            byte[] contentHash = hashExact(h, before);
            same(before, os.childStat(parent.handle, name)); revalidateParent(parent);
            return new LiveFile(relative, parent, name, h, before, label, contentHash);
        } catch (IOException | RuntimeException e) { if (h != null) h.close(); parent.close(); throw e; }
    }
    public synchronized LocalAttributes localAttributes(LiveFile file) throws IOException {
        checkLive(file); return new LocalAttributes(file.expected, file.label, file.expected.mode & 0777);
    }
    /** Safe defaults are derived from a local parent; no archive-supplied UID/GID/mode/label is accepted. */
    public synchronized LocalAttributes defaultFileAttributes(String existingParentRelative) throws IOException {
        revalidate();
        try (Parent parent = parentDirectory(existingParentRelative)) {
            Stat before = os.stat(parent.handle); byte[] label = validLabel(os.selinux(parent.handle));
            revalidateParent(parent); sameDirectory(before, os.stat(parent.handle));
            return new LocalAttributes(before, label, 0600);
        }
    }
    public synchronized byte[] readLive(LiveFile file, int limit) throws IOException {
        checkLive(file); byte[] result = readExact(file.handle, file.expected, limit); checkLive(file); return result;
    }
    public synchronized PrivateFile createPrivate(String name, byte[] bytes, LocalAttributes attributes) throws IOException {
        mutating(); privateName(name); requireBytes(bytes, MAX_BYTES); checkAttributes(attributes);
        MutationOs.Handle h = os.createExclusive(workspace, name, 0600);
        try {
            Stat initial = os.stat(h); privateNewFile(initial); same(initial, os.childStat(workspace, name));
            os.chmod(h, 0600); writeAll(h, bytes);
            if (attributes != null) apply(h, attributes);
            os.sync(h); Stat result = os.stat(h); allowedPrivateFile(result);
            if (result.size != bytes.length) fail(Code.CHANGED);
            same(result, os.childStat(workspace, name)); os.sync(workspace); revalidate();
            return new PrivateFile(name, workspace, h, null, result, validLabel(os.selinux(h)));
        } catch (IOException | RuntimeException e) { h.close(); throw e; } // Preserve partial inodes for diagnosis/recovery.
    }
    public synchronized PrivateFile openPrivate(String name) throws IOException {
        revalidate(); privateName(name); return openPrivateAt(workspace, name, null);
    }
    public synchronized List<String> listPrivate(int limit) throws IOException {
        revalidate(); return Collections.unmodifiableList(new ArrayList<>(os.children(workspace, limit)));
    }
    private PrivateFile openPrivateAt(MutationOs.Handle parent, String name, Reservation r) throws IOException {
        Stat before = os.childStat(parent, name); allowedPrivateFile(before);
        MutationOs.Handle h = os.openRegular(parent, name, false);
        try {
            same(before, os.stat(h)); byte[] label = validLabel(os.selinux(h)); same(before, os.childStat(parent, name));
            return new PrivateFile(name, parent, h, r, before, label);
        } catch (IOException | RuntimeException e) { h.close(); throw e; }
    }
    public synchronized byte[] readPrivate(PrivateFile file, int limit) throws IOException {
        checkPrivate(file); byte[] result = readExact(file.handle, file.expected, limit); checkPrivate(file); return result;
    }
    public synchronized void applyLocalAttributes(PrivateFile file, LocalAttributes attributes) throws IOException {
        mutating(); checkPrivate(file); checkAttributes(attributes);
        if (attributes == null || file.expected.links != 1) fail(Code.UNSAFE);
        apply(file.handle, attributes); os.sync(file.handle); refresh(file); checkPrivate(file);
    }
    public synchronized void syncPrivate(PrivateFile file) throws IOException {
        checkPrivate(file); os.sync(file.handle); os.sync(file.parent); checkPrivate(file);
    }
    public synchronized void syncLiveParent(String relative) throws IOException {
        revalidate(); requireLiveName(relative);
        try (Parent parent = parentDirectory(parentName(relative))) { os.sync(parent.handle); revalidateParent(parent); }
    }
    public synchronized Reservation reserveQuarantine(String name) throws IOException {
        mutating(); privateName(name); os.mkdirExclusive(workspace, name, 0700);
        MutationOs.Handle h = os.openDirectory(workspace, name);
        try {
            Stat before = os.stat(h); privateDirectory(before); sameDirectory(before, os.childStat(workspace, name));
            if (!os.children(h, 1).isEmpty()) fail(Code.UNSAFE);
            os.sync(h); os.sync(workspace); revalidate(); return new Reservation(name, h, before);
        } catch (IOException | RuntimeException e) { h.close(); throw e; }
    }
    public synchronized Reservation openReservation(String name) throws IOException {
        revalidate(); privateName(name); Stat before = os.childStat(workspace, name); privateDirectory(before);
        MutationOs.Handle h = os.openDirectory(workspace, name);
        try {
            sameDirectory(before, os.stat(h)); Reservation r = new Reservation(name, h, before);
            List<String> entries = os.children(h, 1);
            if (!entries.isEmpty() && !entries.get(0).equals("entry")) fail(Code.UNSAFE);
            r.occupied = !entries.isEmpty(); return r;
        } catch (IOException | RuntimeException e) { h.close(); throw e; }
    }
    public synchronized PrivateFile openQuarantined(Reservation r) throws IOException {
        checkReservation(r); return openPrivateAt(r.handle, "entry", r);
    }
    public synchronized PrivateFile quarantine(LiveFile source, Reservation destination) throws IOException {
        liveMutation(); checkLive(source); checkReservation(destination);
        if (destination.occupied || !os.children(destination.handle, 1).isEmpty()) fail(Code.CONFLICT);
        // Empty leaf inside a root-private, exclusively reserved directory cannot be supplied by the game.
        try {
            os.rename(source.parent.handle, source.name, destination.handle, "entry");
            destination.occupied = true;
            Stat moved = os.childStat(destination.handle, "entry");
            if (!sameMoved(source.expected, moved) || !sameMoved(source.expected, os.stat(source.handle))
                    || !Arrays.equals(source.label, os.selinux(source.handle))) throw new Failure(Code.CONFLICT);
            PrivateFile result = openPrivateAt(destination.handle, "entry", destination);
            if (!Arrays.equals(source.label, result.label) || !Arrays.equals(source.contentHash, hashExact(result.handle, result.expected))) {
                result.close(); throw new Failure(Code.CONFLICT);
            }
            os.sync(source.parent.handle); os.sync(destination.handle); revalidateParent(source.parent);
            return result;
        } catch (IOException e) {
            // Once rename may have happened, do not guess whether the new leaf is ours or delete anything.
            destination.unknown = true; conflicted = true;
            throw new Failure(Code.CONFLICT, destination.name, e);
        }
    }
    public synchronized boolean publishNoReplace(PrivateFile source, String relative) throws IOException {
        liveMutation(); checkPrivate(source); requireLiveName(relative);
        if (source.expected.links != 1 || source.expected.uid != binding.uid) fail(Code.UNSAFE);
        byte[] originalHash = hashExact(source.handle, source.expected);
        try (Parent parent = parentDirectory(parentName(relative))) {
            revalidateParent(parent); String name = basename(relative);
            try { os.link(source.parent, source.name, parent.handle, name); }
            catch (MutationOs.Failure e) { if (e.error == MutationOs.Error.EXISTS) return false; throw e; }
            try {
                Stat linked = os.stat(source.handle), live = os.childStat(parent.handle, name);
                if (linked.links != 2 || !sameExceptLinks(source.expected, linked) || !sameMoved(linked, live)
                        || !Arrays.equals(source.label, os.selinux(source.handle))
                        || !Arrays.equals(originalHash, hashExact(source.handle, linked))) fail(Code.CONFLICT);
                source.expected = linked; // Keep the known private/live pair until terminal journal cleanup.
                os.sync(source.handle); os.sync(parent.handle); os.sync(source.parent); revalidateParent(parent);
                return true;
            } catch (IOException e) { conflicted = true; throw new Failure(Code.CONFLICT, null, e); }
        }
    }
    public synchronized void removePrivate(PrivateFile file) throws IOException {
        mutating(); checkPrivate(file);
        os.unlink(file.parent, file.name); file.removed = true;
        if (file.reservation != null) file.reservation.occupied = false;
        os.sync(file.parent); revalidate();
    }
    public synchronized void removeReservation(Reservation r) throws IOException {
        mutating(); checkReservation(r);
        if (r.unknown || r.occupied || !os.children(r.handle, 1).isEmpty()) fail(Code.CONFLICT);
        os.rmdir(workspace, r.name); r.removed = true; os.sync(workspace); revalidate();
    }
    public synchronized PrivateFile readJournal() throws IOException {
        revalidate();
        try {
            PrivateFile f = openPrivateAt(workspace, JOURNAL, null);
            if (f.expected.uid != 0 || f.expected.gid != 0 || (f.expected.mode & 07777) != 0600
                    || f.expected.links != 1 || f.expected.size > MAX_JOURNAL) { f.close(); fail(Code.UNSAFE); }
            return f;
        } catch (MutationOs.Failure e) { if (e.error == MutationOs.Error.MISSING) return null; throw e; }
    }
    /** Atomic replacement is confined to the private fixed journal; expected bytes bind the old receipt. */
    public synchronized PrivateFile replaceJournal(PrivateFile expected, byte[] expectedBytes, byte[] replacement) throws IOException {
        mutating(); requireBytes(replacement, MAX_JOURNAL);
        if (expected == null) {
            if (expectedBytes != null) fail(Code.UNSAFE);
            try { os.childStat(workspace, JOURNAL); fail(Code.CONFLICT); }
            catch (MutationOs.Failure e) { if (e.error != MutationOs.Error.MISSING) throw e; }
        } else {
            if (expected.parent != workspace || !expected.name.equals(JOURNAL)) fail(Code.UNSAFE);
            checkPrivate(expected); requireBytes(expectedBytes, MAX_JOURNAL);
            if (!Arrays.equals(readPrivate(expected, MAX_JOURNAL), expectedBytes)) fail(Code.CONFLICT);
        }
        PrivateFile fresh = createPrivate("journal-" + token(), replacement, null);
        try {
            if (expected != null) checkPrivate(expected);
            else {
                try { os.childStat(workspace, JOURNAL); fail(Code.CONFLICT); }
                catch (MutationOs.Failure e) { if (e.error != MutationOs.Error.MISSING) throw e; }
            }
            // Only cooperating root code can change this locked, root-owned 0700 directory.
            os.rename(workspace, fresh.name, workspace, JOURNAL); fresh.removed = true;
            if (expected != null) expected.removed = true;
            os.sync(workspace); revalidate(); return readJournal();
        } finally { fresh.close(); }
    }
    /** Tests only exclusively owned private probes. A failure never reaches a live mutation. */
    public synchronized void preflight(LocalAttributes attributes) throws IOException {
        mutating(); checkAttributes(attributes); if (attributes == null) fail(Code.UNSAFE);
        PrivateFile probe = createPrivate("probe-" + token(), new byte[] {37}, attributes);
        String linkedName = "probe-" + token(); PrivateFile linked = null;
        try {
            checkPrivate(probe); os.link(workspace, probe.name, workspace, linkedName);
            Stat after = os.stat(probe.handle);
            if (after.links != 2 || !sameExceptLinks(probe.expected, after)) fail(Code.CHANGED);
            probe.expected = after; linked = openPrivateAt(workspace, linkedName, null);
            if (!sameMoved(after, linked.expected)) fail(Code.CHANGED);
            if (readPrivate(linked, 1)[0] != 37) fail(Code.CHANGED);
            os.sync(probe.handle); os.sync(workspace);
            // Ensure EEXIST is truly no-replace in the injected implementation too.
            try { os.link(workspace, probe.name, workspace, linkedName); fail(Code.UNSAFE); }
            catch (MutationOs.Failure e) { if (e.error != MutationOs.Error.EXISTS) throw e; }
            checkPrivate(linked); removePrivate(linked); refresh(probe); removePrivate(probe);
            os.sync(files); os.sync(data); revalidate(); preflightPassed = true;
        } finally { if (linked != null) linked.close(); probe.close(); }
    }
    private void checkLive(LiveFile f) throws IOException {
        active(); if (f == null || f.owner() != this || f.closed) fail(Code.UNSAFE);
        revalidateParent(f.parent); same(f.expected, os.stat(f.handle)); same(f.expected, os.childStat(f.parent.handle, f.name));
        if (!Arrays.equals(f.label, os.selinux(f.handle))) fail(Code.CHANGED);
    }
    private void checkPrivate(PrivateFile f) throws IOException {
        active(); if (f == null || f.owner() != this || f.closed || f.removed) fail(Code.UNSAFE);
        revalidate(); if (f.reservation != null) checkReservation(f.reservation);
        allowedPrivateFile(os.stat(f.handle)); same(f.expected, os.stat(f.handle)); same(f.expected, os.childStat(f.parent, f.name));
        if (!Arrays.equals(f.label, os.selinux(f.handle))) fail(Code.CHANGED);
    }
    private void checkReservation(Reservation r) throws IOException {
        active(); if (r == null || r.owner() != this || r.closed || r.removed || r.unknown) fail(Code.UNSAFE);
        revalidate(); privateDirectory(os.stat(r.handle));
        sameDirectory(r.expected, os.stat(r.handle)); sameDirectory(r.expected, os.childStat(workspace, r.name));
    }
    private void refresh(PrivateFile f) throws IOException {
        Stat current = os.stat(f.handle); allowedPrivateFile(current);
        same(current, os.childStat(f.parent, f.name)); f.expected = current; f.label = validLabel(os.selinux(f.handle));
    }
    private void checkAttributes(LocalAttributes a) throws IOException {
        if (a != null && a.owner() != this) fail(Code.UNSAFE);
    }
    private void apply(MutationOs.Handle h, LocalAttributes a) throws IOException {
        os.chown(h, a.uid, a.gid); os.chmod(h, a.mode); os.selinux(h, a.label.clone());
        Stat s = os.stat(h);
        if (s.uid != a.uid || s.gid != a.gid || (s.mode & 07777) != a.mode
                || !Arrays.equals(a.label, os.selinux(h))) fail(Code.CHANGED);
    }
    private byte[] hashExact(MutationOs.Handle h, Stat expected) throws IOException {
        if (expected.size < 0 || expected.size > MAX_BYTES) fail(Code.TOO_LARGE);
        java.security.MessageDigest hash = SaveArchive.digest(); byte[] buffer = new byte[32768]; long position = 0;
        while (position < expected.size) {
            int requested = (int) Math.min(buffer.length, expected.size - position);
            int count = os.read(h, buffer, 0, requested, position);
            if (count <= 0 || count > requested) fail(Code.CHANGED);
            hash.update(buffer, 0, count); position += count;
        }
        if (os.read(h, buffer, 0, 1, position) != 0) fail(Code.CHANGED);
        same(expected, os.stat(h)); return hash.digest();
    }
    private byte[] readExact(MutationOs.Handle h, Stat expected, int limit) throws IOException {
        if (limit < 0 || limit > MAX_BYTES || expected.size < 0 || expected.size > limit) fail(Code.TOO_LARGE);
        byte[] result = new byte[(int) expected.size]; int at = 0;
        while (at < result.length) {
            int n = os.read(h, result, at, result.length - at, at);
            if (n <= 0 || n > result.length - at) fail(Code.CHANGED); at += n;
        }
        byte[] extra = new byte[1]; if (os.read(h, extra, 0, 1, at) != 0) fail(Code.CHANGED);
        same(expected, os.stat(h)); return result;
    }
    private void writeAll(MutationOs.Handle h, byte[] bytes) throws IOException {
        int at = 0;
        while (at < bytes.length) {
            int n = os.write(h, bytes, at, bytes.length - at, at);
            if (n <= 0 || n > bytes.length - at) fail(Code.CHANGED); at += n;
        }
    }
    private void liveDirectory(Stat s) throws IOException {
        if (!s.directory() || s.uid != binding.uid || unsafeMode(s)
                || (originalData != null && s.device != originalData.device)) fail(Code.UNSAFE);
    }
    private void liveFile(Stat s) throws IOException {
        if (!s.regular() || s.uid != binding.uid || unsafeMode(s) || s.size < 0 || s.size > MAX_BYTES
                || s.device != originalData.device || (s.links != 1 && !(s.links == 2 && knownPair(s)))) fail(Code.UNSAFE);
    }
    private boolean knownPair(Stat s) throws IOException {
        for (PrivateFile file : privateFiles) {
            if (!file.closed && !file.removed && sameInode(file.expected, s)) {
                Stat now = os.childStat(file.parent, file.name);
                if (sameInode(now, s) && now.links == 2) return true;
            }
        }
        return false;
    }
    private void privateDirectory(Stat s) throws IOException {
        if (!s.directory() || s.uid != 0 || s.gid != 0 || (s.mode & 07777) != 0700
                || s.device != originalData.device) fail(Code.UNSAFE);
    }
    private void privateNewFile(Stat s) throws IOException {
        if (!s.regular() || s.uid != 0 || s.gid != 0 || s.links != 1 || s.size != 0
                || (s.mode & 07177) != 0 || s.device != originalData.device) fail(Code.UNSAFE);
    }
    private void allowedPrivateFile(Stat s) throws IOException {
        if (!s.regular() || (s.uid != 0 && s.uid != binding.uid) || unsafeMode(s) || s.size < 0 || s.size > MAX_BYTES
                || s.device != originalData.device || s.links < 1 || s.links > 2) fail(Code.UNSAFE);
    }
    private boolean unsafeMode(Stat s) {
        return (s.mode & 07002) != 0 || ((s.mode & 0020) != 0 && s.gid != binding.uid);
    }
    private static void trustedDirectory(Stat s) throws IOException {
        if (!s.directory() || (s.uid != 0 && s.uid != 1000) || (s.mode & 0002) != 0
                || ((s.mode & 0020) != 0 && s.gid != 0 && s.gid != 1000)) fail(Code.UNSAFE);
    }
    private static void same(Stat expected, Stat now) throws IOException {
        if (!expected.identity().equals(now.identity())) fail(Code.CHANGED);
    }
    private static void sameDirectory(Stat expected, Stat now) throws IOException {
        if (!now.directory() || !sameInode(expected, now) || expected.uid != now.uid || expected.gid != now.gid
                || expected.mode != now.mode) fail(Code.CHANGED);
    }
    private static boolean sameInode(Stat a, Stat b) { return a.device == b.device && a.inode == b.inode; }
    private static boolean sameMoved(Stat a, Stat b) { return sameExceptLinks(a, b) && a.links == b.links; }
    private static boolean sameExceptLinks(Stat a, Stat b) {
        return sameInode(a, b) && a.mode == b.mode && a.uid == b.uid && a.gid == b.gid && a.size == b.size
                && a.modifiedSeconds == b.modifiedSeconds && modificationTime(a).equals(modificationTime(b));
    }
    private static String modificationTime(Stat s) {
        String[] parts = s.times.split(":"); return parts.length >= 2 ? parts[0] + ":" + parts[1] : s.times;
    }
    private static byte[] validLabel(byte[] label) throws IOException {
        if (label == null || label.length < 1 || label.length > 513) { fail(Code.UNSAFE); }
        int n = label.length; if (label[n - 1] == 0) n--;
        String value = new String(label, 0, n, StandardCharsets.US_ASCII);
        if (n == 0 || n > 512 || !value.matches("[A-Za-z0-9_:,.-]+")) fail(Code.UNSAFE);
        return label.clone();
    }
    private static void requireBytes(byte[] bytes, int max) throws IOException {
        if (bytes == null || bytes.length > max) fail(Code.TOO_LARGE);
    }
    private static void privateName(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}") || value.endsWith(".")
                || value.equals(LOCK) || value.equals(JOURNAL)) fail(Code.UNSAFE);
    }
    private static void requireLiveName(String relative) throws IOException {
        if (!SaveArchive.safeName(relative) || relative.matches("game[0-9]+")) fail(Code.UNSAFE);
    }
    private static String parentName(String relative) { int n = relative.lastIndexOf('/'); return n < 0 ? "" : relative.substring(0, n); }
    private static String basename(String relative) { return relative.substring(relative.lastIndexOf('/') + 1); }
    private static String token() { return UUID.randomUUID().toString().replace("-", ""); }
    private void active() throws IOException {
        if (closed) fail(Code.CLOSED);
        if (os.effectiveUid() != 0) fail(Code.NOT_ROOT);
    }
    private void mutating() throws IOException { active(); if (conflicted) fail(Code.CONFLICT); revalidate(); }
    private void liveMutation() throws IOException { mutating(); if (!preflightPassed) fail(Code.UNSUPPORTED); }
    private static void fail(Code code) throws IOException { throw new Failure(code); }
    @Override public synchronized void close() throws IOException {
        if (closed) return; closed = true; IOException error = null;
        // Handles may be closed again by callers; PFD close is idempotent. No cleanup is implicit.
        for (PrivateFile f : privateFiles) try { f.close(); } catch (IOException e) { error = e; }
        for (LiveFile f : liveFiles) try { f.close(); } catch (IOException e) { error = e; }
        for (Reservation r : reservations) try { r.close(); } catch (IOException e) { error = e; }
        AutoCloseable[] all = {lock, lockFile, workspace, files, data};
        for (AutoCloseable item : all) if (item != null) try { item.close(); }
        catch (Exception e) { if (error == null) error = new IOException(e); else error.addSuppressed(e); }
        if (processLock != null) processLock.close();
        if (error != null) throw error;
    }
}
