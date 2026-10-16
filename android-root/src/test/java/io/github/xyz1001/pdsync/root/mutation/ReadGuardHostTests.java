package io.github.xyz1001.pdsync.root.mutation;

import io.github.xyz1001.pdsync.root.RootBinding;
import io.github.xyz1001.pdsync.root.RootFileSystem.Stat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** No filesystem or Android calls: all read/restore races below use the in-memory fake OS. */
public final class ReadGuardHostTests {
    private static int assertions;
    private interface Checked { void run() throws Exception; }
    private static RootBinding binding() throws Exception {
        return new RootBinding("com.example.game", 0, 10001, "/data/user/0/com.example.game", "a".repeat(64), Map.of("/data/app/example/base.apk", "b".repeat(64)));
    }
    private static RootMutationReadGuard open(FakeMutationOs os) throws Exception { return RootMutationReadGuard.open(os, binding()); }
    private static void check(boolean value) { assertions++; if (!value) throw new AssertionError("Read guard assertion " + assertions); }
    private static void code(RootMutationReadGuard.Code code, Checked body) throws Exception {
        assertions++; try { body.run(); } catch (RootMutationReadGuard.Failure e) { if (e.code == code) return; throw e; }
        throw new AssertionError("Expected guard " + code + " at " + assertions);
    }
    private static void mutationBusy(Checked body) throws Exception {
        assertions++; try { body.run(); } catch (RootMutationSession.Failure e) { if (e.code == RootMutationSession.Code.BUSY) return; throw e; }
        throw new AssertionError("Expected mutation BUSY at " + assertions);
    }
    private static void workspace(FakeMutationOs os) throws Exception { try (RootMutationSession ignored = RootMutationSession.open(os, binding())) { } }
    private static FakeMutationOs.Node record(FakeMutationOs os, FakeMutationOs.Node parent, String name, String text) {
        FakeMutationOs.Node file = os.file(parent, name, text); file.uid = 0; file.gid = 0; return file;
    }
    private static FakeMutationOs.Node clean(FakeMutationOs os, boolean areas) throws Exception {
        FakeMutationOs.Node active = os.dir(os.workspace(), "active", 0700, 0, 0);
        if (areas) for (String name : new String[] {"incoming", "displaced", "backup", "cleanup"}) os.dir(active, name, 0700, 0, 0);
        String stable = active.device + ":" + active.inode + ":" + active.uid + ":" + active.gid + ":" + active.mode;
        record(os, active, "phase", "CLEAN:01234567-89ab-cdef-0123-456789abcdef:" + binding().fingerprint() + ":" + stable);
        return active;
    }
    public static void main(String[] args) throws Exception {
        rootBeforeTouch(); absentDoesNotCreate(); existingInterlock(); nativeLockBusy(); initialCreationRace();
        cleanWorkspace(); pendingStates(); unsafeWorkspace(); identityReplacement(); readRace(); closedGuard(); missingFiles();
        System.out.println("ReadGuardHostTests: " + assertions + " assertions passed (in-memory fake OS only; no filesystem writes)");
    }
    private static void rootBeforeTouch() throws Exception {
        FakeMutationOs os = new FakeMutationOs(); os.uid = 10001;
        code(RootMutationReadGuard.Code.NOT_ROOT, () -> open(os)); check(os.touches == 0 && os.mutations == 0);
    }
    private static void missingFiles() throws Exception {
        FakeMutationOs os = new FakeMutationOs(); os.data.children.remove("files");
        try (RootMutationReadGuard guard = open(os)) {
            guard.requireClear(); guard.revalidate(); check(os.mutations == 0);
            os.dir(os.data, "files", 0700, 10001, 10001);
            code(RootMutationReadGuard.Code.CHANGED, guard::revalidate);
        }
        for (int errno : new int[] {1, 5, 13}) {
            FakeMutationOs denied = new FakeMutationOs();
            MutationOs boundary = (MutationOs) java.lang.reflect.Proxy.newProxyInstance(
                    MutationOs.class.getClassLoader(), new Class<?>[]{MutationOs.class}, (proxy, method, args) -> {
                if (method.getName().equals("childStat") && "files".equals(args[1]))
                    throw new MutationOs.Failure(MutationOs.Error.IO, MutationOs.Operation.LSTAT, errno, null);
                try { return method.invoke(denied, args); }
                catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
            });
            try { RootMutationReadGuard.open(boundary, binding()); throw new AssertionError("Accepted errno " + errno); }
            catch (MutationOs.Failure failure) { check(failure.errno == errno); }
            check(denied.mutations == 0);
        }
    }
    private static void absentDoesNotCreate() throws Exception {
        FakeMutationOs os = new FakeMutationOs();
        try (RootMutationReadGuard guard = open(os)) { guard.requireClear(); guard.revalidate(); check(os.workspace() == null); }
        check(os.mutations == 0); check(os.log.isEmpty());
    }
    private static void existingInterlock() throws Exception {
        FakeMutationOs os = new FakeMutationOs(); workspace(os); int before = os.mutations;
        try (RootMutationReadGuard guard = open(os)) {
            guard.requireClear(); code(RootMutationReadGuard.Code.BUSY, () -> open(os));
            mutationBusy(() -> RootMutationSession.open(os, binding())); check(os.workspace().children.get("lock").locked);
            guard.revalidate(); check(os.mutations == before);
        }
        check(!os.workspace().children.get("lock").locked);
        try (RootMutationSession restore = RootMutationSession.open(os, binding())) {
            int during = os.mutations; code(RootMutationReadGuard.Code.BUSY, () -> open(os)); check(os.mutations == during);
        }
        try (RootMutationReadGuard guard = open(os)) { guard.requireClear(); }
    }
    private static void nativeLockBusy() throws Exception {
        FakeMutationOs os = new FakeMutationOs(); workspace(os); int before = os.mutations;
        os.workspace().children.get("lock").locked = true;
        code(RootMutationReadGuard.Code.BUSY, () -> open(os)); check(os.mutations == before);
        os.workspace().children.get("lock").locked = false;
        try (RootMutationReadGuard guard = open(os)) { guard.requireClear(); }
    }
    private static void initialCreationRace() throws Exception {
        FakeMutationOs os = new FakeMutationOs();
        try (RootMutationReadGuard guard = open(os)) {
            guard.requireClear(); workspace(os);
            code(RootMutationReadGuard.Code.CHANGED, guard::revalidate);
            code(RootMutationReadGuard.Code.CHANGED, guard::requireClear);
        }
    }
    private static void cleanWorkspace() throws Exception {
        for (boolean withAreas : new boolean[] {false, true}) {
            FakeMutationOs os = new FakeMutationOs(); workspace(os); FakeMutationOs.Node active = clean(os, withAreas);
            record(os, active, "receipt", "old local receipt"); record(os, active, "prepared", "old prepared journal"); record(os, active, "terminal", "old terminal journal");
            int before = os.mutations;
            try (RootMutationReadGuard guard = open(os)) { guard.requireClear(); guard.revalidate(); }
            check(os.mutations == before);
        }
    }
    private static void pendingStates() throws Exception {
        FakeMutationOs journal = new FakeMutationOs(); workspace(journal); record(journal, journal.workspace(), "journal", "pending");
        try (RootMutationReadGuard guard = open(journal)) { code(RootMutationReadGuard.Code.RECOVERY_PENDING, guard::requireClear); }
        FakeMutationOs missingLock = new FakeMutationOs(); missingLock.dir(missingLock.data, RootMutationSession.WORKSPACE, 0700, 0, 0);
        int emptyBefore = missingLock.mutations;
        code(RootMutationReadGuard.Code.RECOVERY_PENDING, () -> open(missingLock)); check(missingLock.mutations == emptyBefore && missingLock.workspace().children.isEmpty());
        for (String phase : new String[] {"PREPARING", "PREPARED", "CLEANER", "CLEAN", "CLEAN:", ""}) {
            FakeMutationOs os = new FakeMutationOs(); workspace(os); FakeMutationOs.Node active = clean(os, true);
            FakeMutationOs.Node receipt = active.children.get("phase");
            String original = new String(receipt.bytes, StandardCharsets.US_ASCII);
            receipt.bytes = (phase.equals("CLEAN") ? "CLEAN" + original.substring(original.indexOf(':', 6))
                    : phase + original.substring("CLEAN".length())).getBytes(StandardCharsets.US_ASCII);
            int before = os.mutations;
            try (RootMutationReadGuard guard = open(os)) { code(RootMutationReadGuard.Code.RECOVERY_PENDING, guard::requireClear); }
            check(os.mutations == before);
        }
        FakeMutationOs unknown = new FakeMutationOs(); workspace(unknown); clean(unknown, true); record(unknown, unknown.workspace(), "unknown", "preserve");
        try (RootMutationReadGuard guard = open(unknown)) { code(RootMutationReadGuard.Code.RECOVERY_PENDING, guard::requireClear); }
        FakeMutationOs occupied = new FakeMutationOs(); workspace(occupied); FakeMutationOs.Node active = clean(occupied, true);
        occupied.file(active.children.get("backup"), "rankings.dat", "retained");
        try (RootMutationReadGuard guard = open(occupied)) { code(RootMutationReadGuard.Code.RECOVERY_PENDING, guard::requireClear); }
        FakeMutationOs extra = new FakeMutationOs(); workspace(extra); FakeMutationOs.Node a = clean(extra, true); record(extra, a, "phase-new", "unexpected");
        try (RootMutationReadGuard guard = open(extra)) { code(RootMutationReadGuard.Code.RECOVERY_PENDING, guard::requireClear); }
        FakeMutationOs wrongBinding = new FakeMutationOs(); workspace(wrongBinding); FakeMutationOs.Node b = clean(wrongBinding, true);
        b.children.get("phase").bytes = new String(b.children.get("phase").bytes, StandardCharsets.US_ASCII).replace(binding().fingerprint(), "0".repeat(64)).getBytes(StandardCharsets.US_ASCII);
        try (RootMutationReadGuard guard = open(wrongBinding)) { code(RootMutationReadGuard.Code.RECOVERY_PENDING, guard::requireClear); }
    }
    private static void unsafeWorkspace() throws Exception {
        for (int mode : new int[] {Stat.SYMLINK | 0700, Stat.DIRECTORY | 0755}) {
            FakeMutationOs os = new FakeMutationOs(); os.dir(os.data, RootMutationSession.WORKSPACE, 0700, 0, 0).mode = mode;
            code(RootMutationReadGuard.Code.UNSAFE, () -> open(os)); check(os.mutations == 0);
        }
        FakeMutationOs os = new FakeMutationOs(); workspace(os); os.workspace().children.get("lock").links = 2;
        code(RootMutationReadGuard.Code.UNSAFE, () -> open(os));
        FakeMutationOs recordOwner = new FakeMutationOs(); workspace(recordOwner); FakeMutationOs.Node active = clean(recordOwner, true); active.children.get("phase").uid = 10001;
        try (RootMutationReadGuard guard = open(recordOwner)) { code(RootMutationReadGuard.Code.RECOVERY_PENDING, guard::requireClear); }
    }
    private static void identityReplacement() throws Exception {
        FakeMutationOs os = new FakeMutationOs(); workspace(os);
        try (RootMutationReadGuard guard = open(os)) {
            guard.requireClear(); os.dir(os.data, RootMutationSession.WORKSPACE, 0700, 0, 0);
            code(RootMutationReadGuard.Code.CHANGED, guard::revalidate);
        }
        FakeMutationOs files = new FakeMutationOs();
        try (RootMutationReadGuard guard = open(files)) {
            files.dir(files.data, "files", 0700, 10001, 10001); code(RootMutationReadGuard.Code.CHANGED, guard::revalidate);
        }
        FakeMutationOs lock = new FakeMutationOs(); workspace(lock);
        try (RootMutationReadGuard guard = open(lock)) {
            record(lock, lock.workspace(), "lock", ""); code(RootMutationReadGuard.Code.CHANGED, guard::revalidate);
        }
    }
    private static void readRace() throws Exception {
        FakeMutationOs os = new FakeMutationOs(); workspace(os); FakeMutationOs.Node active = clean(os, true);
        try (RootMutationReadGuard guard = open(os)) {
            os.beforeRead = () -> active.children.get("phase").ctime++;
            code(RootMutationReadGuard.Code.CHANGED, guard::requireClear);
        }
        FakeMutationOs shortRead = new FakeMutationOs(); workspace(shortRead); clean(shortRead, true);
        try (RootMutationReadGuard guard = open(shortRead)) {
            shortRead.zeroRead = true; code(RootMutationReadGuard.Code.CHANGED, guard::requireClear);
        }
    }
    private static void closedGuard() throws Exception {
        FakeMutationOs os = new FakeMutationOs(); RootMutationReadGuard guard = open(os); guard.close(); guard.close();
        code(RootMutationReadGuard.Code.CLOSED, guard::revalidate);
    }
}
