package io.github.xyz1001.pdsync.root.mutation;

import io.github.xyz1001.pdsync.root.RootBinding;
import io.github.xyz1001.pdsync.root.RootFileSystem.Stat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Read-job interlock. It never creates, modifies, syncs, or deletes any filesystem entry.
 * An existing workspace is held under the same nonblocking exclusive lock as a restore.
 * When absent, its absence is checked again at the final read-result boundary.
 * The caller must hold this guard through the complete read job and call requireClear()
 * before the job, and revalidate() before returning its result. PM/APK checks remain caller-owned.
 */
public final class RootMutationReadGuard implements AutoCloseable {
    private static final String WORKSPACE = ".pdsync-root-restore", LOCK = "lock", ACTIVE = "active";
    private static final int MAX_PHASE = 4096, MAX_RECORD = 16 * 1024 * 1024;
    private static final Set<String> AREAS = new HashSet<>(Arrays.asList("incoming", "displaced", "backup", "cleanup"));
    private static final Set<String> RECORDS = new HashSet<>(Arrays.asList("phase", "receipt", "prepared", "terminal"));
    public enum Code { NOT_ROOT, UNSAFE, CHANGED, RECOVERY_PENDING, BUSY, CLOSED }
    public static final class Failure extends IOException {
        public final Code code;
        Failure(Code code) { super(code.name()); this.code = code; }
        Failure(Code code, Throwable cause) { super(code.name(), cause); this.code = code; }
    }
    private final MutationOs os;
    private final RootBinding binding;
    private MutationOs.Handle data, files, workspace, lockFile;
    private MutationOs.Lock lock;
    private MutationProcessLock.Lease processLock;
    private Stat originalData, originalFiles, originalWorkspace, originalLock;
    private String canonicalParent;
    private boolean closed;

    private RootMutationReadGuard(MutationOs os, RootBinding binding) { this.os = os; this.binding = binding; }
    public static RootMutationReadGuard open(MutationOs os, RootBinding binding) throws IOException {
        if (os == null || binding == null) throw new Failure(Code.UNSAFE);
        if (os.effectiveUid() != 0) throw new Failure(Code.NOT_ROOT);
        RootMutationReadGuard guard = new RootMutationReadGuard(os, binding);
        try { guard.initialize(); return guard; }
        catch (IOException | RuntimeException e) { try { guard.close(); } catch (IOException close) { e.addSuppressed(close); } throw e; }
    }
    private void initialize() throws IOException {
        canonicalParent = os.canonicalSystemParent(fixedParent());
        if (!canonicalParent.equals("/data/user/" + binding.userId)
                && !(binding.userId == 0 && canonicalParent.equals("/data/data"))) fail(Code.UNSAFE);
        data = openData(); originalData = os.stat(data);
        Stat filesBefore = optionalFiles(data);
        if (filesBefore != null) {
            liveDirectory(filesBefore);
            files = os.openDirectory(data, "files"); originalFiles = os.stat(files);
            sameDirectory(filesBefore, originalFiles); liveDirectory(originalFiles);
        }
        Stat found = optional(data, WORKSPACE);
        if (found == null) { revalidate(); return; }
        privateDirectory(found); workspace = os.openDirectory(data, WORKSPACE); originalWorkspace = os.stat(workspace);
        sameDirectory(found, originalWorkspace); privateDirectory(originalWorkspace);
        try { processLock = MutationProcessLock.acquire(originalWorkspace.device, originalWorkspace.inode); }
        catch (MutationOs.Failure busy) { throw new Failure(Code.BUSY, busy); }
        Stat lockBefore = optional(workspace, LOCK);
        // A creator may have made the directory but not yet published its lock. Readers cannot create it.
        if (lockBefore == null) fail(Code.RECOVERY_PENDING);
        lockStat(lockBefore);
        lockFile = os.openRegular(workspace, LOCK, true); // O_RDWR solely for the advisory exclusive lock; no write call.
        originalLock = os.stat(lockFile); same(lockBefore, originalLock); lockStat(originalLock);
        try { lock = os.exclusiveLock(lockFile); }
        catch (MutationOs.Failure e) { if (e.error == MutationOs.Error.BUSY) throw new Failure(Code.BUSY, e); throw e; }
        revalidate();
    }
    public synchronized void requireClear() throws IOException {
        revalidate();
        if (workspace == null) return;
        Stat before = os.stat(workspace);
        List<String> names = os.children(workspace, 16384);
        for (String name : names) {
            if (name.equals("journal")) fail(Code.RECOVERY_PENDING);
            if (!name.equals(LOCK) && !name.equals(ACTIVE)) fail(Code.RECOVERY_PENDING);
        }
        if (names.contains(ACTIVE)) requireCleanActive();
        same(before, os.stat(workspace));
        revalidate();
    }
    private void requireCleanActive() throws IOException {
        Stat before = os.childStat(workspace, ACTIVE);
        if (!safePrivateDirectory(before)) fail(Code.RECOVERY_PENDING);
        try (MutationOs.Handle active = os.openDirectory(workspace, ACTIVE)) {
            sameDirectory(before, os.stat(active));
            List<String> names = os.children(active, 16384);
            if (!names.contains("phase")) fail(Code.RECOVERY_PENDING);
            for (String name : names) {
                if (AREAS.contains(name)) {
                    Stat areaBefore = os.childStat(active, name);
                    if (!safePrivateDirectory(areaBefore)) fail(Code.RECOVERY_PENDING);
                    try (MutationOs.Handle area = os.openDirectory(active, name)) {
                        sameDirectory(areaBefore, os.stat(area));
                        if (!os.children(area, 16384).isEmpty()) fail(Code.RECOVERY_PENDING);
                        same(areaBefore, os.stat(area)); same(areaBefore, os.childStat(active, name));
                    }
                } else if (RECORDS.contains(name)) {
                    validateRecord(active, name, name.equals("phase") ? MAX_PHASE : MAX_RECORD, false);
                } else fail(Code.RECOVERY_PENDING);
            }
            byte[] raw = validateRecord(active, "phase", MAX_PHASE, true);
            String value = new String(raw, StandardCharsets.US_ASCII);
            if (!Arrays.equals(raw, value.getBytes(StandardCharsets.US_ASCII))) fail(Code.RECOVERY_PENDING);
            String prefix = "CLEAN:";
            String suffix = ":" + binding.fingerprint() + ":" + stable(before);
            if (value.length() != prefix.length() + 36 + suffix.length()
                    || !value.startsWith(prefix) || !value.endsWith(suffix)) fail(Code.RECOVERY_PENDING);
            String id = value.substring(prefix.length(), value.length() - suffix.length());
            if (!id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) fail(Code.RECOVERY_PENDING);
            same(before, os.stat(active)); same(before, os.childStat(workspace, ACTIVE));
        }
    }
    private byte[] validateRecord(MutationOs.Handle parent, String name, int bound, boolean read) throws IOException {
        Stat before = os.childStat(parent, name);
        if (!before.regular() || before.uid != 0 || before.gid != 0 || before.links != 1
                || (before.mode & 07777) != 0600 || before.device != originalData.device
                || before.size < 0 || before.size > bound) fail(Code.RECOVERY_PENDING);
        try (MutationOs.Handle file = os.openRegular(parent, name, false)) {
            same(before, os.stat(file)); byte[] result = read ? new byte[(int) before.size] : null;
            if (read) {
                int position = 0;
                while (position < result.length) {
                    int count = os.read(file, result, position, result.length - position, position);
                    if (count <= 0 || count > result.length - position) fail(Code.CHANGED);
                    position += count;
                }
                if (os.read(file, new byte[1], 0, 1, position) != 0) fail(Code.CHANGED);
            }
            same(before, os.stat(file)); same(before, os.childStat(parent, name)); return result;
        }
    }
    public synchronized void revalidate() throws IOException {
        active();
        if (!canonicalParent.equals(os.canonicalSystemParent(fixedParent()))) fail(Code.CHANGED);
        try (MutationOs.Handle reopened = openData()) {
            sameDirectory(originalData, os.stat(reopened)); sameDirectory(originalData, os.stat(data));
            Stat currentFiles = optionalFiles(reopened);
            if (originalFiles == null) {
                if (currentFiles != null) fail(Code.CHANGED);
            } else {
                if (currentFiles == null) fail(Code.CHANGED);
                sameDirectory(originalFiles, currentFiles); sameDirectory(originalFiles, os.stat(files));
            }
            Stat now = optional(reopened, WORKSPACE);
            if (workspace == null) { if (now != null) fail(Code.CHANGED); return; }
            if (now == null) fail(Code.CHANGED);
            sameDirectory(originalWorkspace, now); sameDirectory(originalWorkspace, os.stat(workspace));
            privateDirectory(os.stat(workspace)); same(originalLock, os.stat(lockFile));
            same(originalLock, os.childStat(workspace, LOCK));
        }
    }
    private MutationOs.Handle openData() throws IOException {
        MutationOs.Handle current = os.openRoot();
        try {
            trusted(os.stat(current));
            for (String part : canonicalParent.substring(1).split("/")) {
                MutationOs.Handle next = os.openDirectory(current, part);
                try { trusted(os.stat(next)); } catch (IOException e) { next.close(); throw e; }
                current.close(); current = next;
            }
            Stat before = lookup(current, binding.packageName, MutationOs.Operation.LSTAT_DATA_PACKAGE); liveDirectory(before);
            MutationOs.Handle result = os.openDirectory(current, binding.packageName);
            try { sameDirectory(before, os.stat(result)); liveDirectory(os.stat(result)); return result; }
            catch (IOException e) { result.close(); throw e; }
        } finally { current.close(); }
    }
    private String fixedParent() { return binding.dataDir.substring(0, binding.dataDir.lastIndexOf('/')); }
    private Stat optionalFiles(MutationOs.Handle parent) throws IOException {
        try { return lookup(parent, "files", MutationOs.Operation.LSTAT_SAVE_FILES); }
        catch (MutationOs.Failure e) {
            if (e.error == MutationOs.Error.MISSING && e.errno == 2
                    && e.operation == MutationOs.Operation.LSTAT_SAVE_FILES) return null;
            throw e;
        }
    }
    private Stat optional(MutationOs.Handle parent, String name) throws IOException {
        try { return lookup(parent, name, name.equals(WORKSPACE) ? MutationOs.Operation.LSTAT_WORKSPACE : MutationOs.Operation.LSTAT); }
        catch (MutationOs.Failure e) { if (e.error == MutationOs.Error.MISSING) return null; throw e; }
    }
    /** Keep the exact failed syscall and its fixed role, without returning target paths. */
    private Stat lookup(MutationOs.Handle parent, String name, MutationOs.Operation role) throws IOException {
        try { return os.childStat(parent, name); }
        catch (MutationOs.Failure e) {
            // Do not relabel an FD/fstat failure underneath a lookup as a missing child.
            if (e.operation != MutationOs.Operation.NONE && e.operation != MutationOs.Operation.LSTAT) throw e;
            throw new MutationOs.Failure(e.error, role, e.errno, e);
        }
    }
    private void liveDirectory(Stat stat) throws IOException {
        if (!stat.directory() || stat.uid != binding.uid || (stat.mode & 07002) != 0
                || ((stat.mode & 0020) != 0 && stat.gid != binding.uid)
                || (originalData != null && stat.device != originalData.device)) fail(Code.UNSAFE);
    }
    private boolean safePrivateDirectory(Stat stat) {
        return stat.directory() && stat.uid == 0 && stat.gid == 0 && (stat.mode & 07777) == 0700
                && stat.device == originalData.device;
    }
    private void privateDirectory(Stat stat) throws IOException { if (!safePrivateDirectory(stat)) fail(Code.UNSAFE); }
    private void lockStat(Stat stat) throws IOException {
        if (!stat.regular() || stat.uid != 0 || stat.gid != 0 || (stat.mode & 07777) != 0600
                || stat.links != 1 || stat.size != 0 || stat.device != originalData.device) fail(Code.UNSAFE);
    }
    private static void trusted(Stat stat) throws IOException {
        if (!stat.directory() || (stat.uid != 0 && stat.uid != 1000) || (stat.mode & 0002) != 0
                || ((stat.mode & 0020) != 0 && stat.gid != 0 && stat.gid != 1000)) fail(Code.UNSAFE);
    }
    private static void same(Stat a, Stat b) throws IOException { if (!a.identity().equals(b.identity())) fail(Code.CHANGED); }
    private static void sameDirectory(Stat a, Stat b) throws IOException {
        if (!b.directory() || a.device != b.device || a.inode != b.inode || a.mode != b.mode || a.uid != b.uid || a.gid != b.gid)
            fail(Code.CHANGED);
    }
    private static String stable(Stat s) { return s.device + ":" + s.inode + ":" + s.uid + ":" + s.gid + ":" + s.mode; }
    private void active() throws IOException {
        if (closed) fail(Code.CLOSED); if (os.effectiveUid() != 0) fail(Code.NOT_ROOT);
    }
    private static void fail(Code code) throws IOException { throw new Failure(code); }
    @Override public synchronized void close() throws IOException {
        if (closed) return; closed = true; IOException error = null;
        for (AutoCloseable item : new AutoCloseable[] {lock, lockFile, workspace, files, data}) if (item != null) {
            try { item.close(); } catch (Exception e) {
                if (error == null) error = new IOException(e); else error.addSuppressed(e);
            }
        }
        if (processLock != null) processLock.close();
        if (error != null) throw error;
    }
}
