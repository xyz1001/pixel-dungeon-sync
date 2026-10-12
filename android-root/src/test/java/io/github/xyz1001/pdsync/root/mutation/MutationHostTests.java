package io.github.xyz1001.pdsync.root.mutation;

import io.github.xyz1001.pdsync.root.RootBinding;
import io.github.xyz1001.pdsync.root.RootFileSystem.Stat;
import io.github.xyz1001.pdsync.root.mutation.RootMutationSession.LocalAttributes;
import io.github.xyz1001.pdsync.root.mutation.RootMutationSession.LiveFile;
import io.github.xyz1001.pdsync.root.mutation.RootMutationSession.PrivateFile;
import io.github.xyz1001.pdsync.root.mutation.RootMutationSession.Reservation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

public final class MutationHostTests {
    private static int assertions;
    private interface Checked { void run() throws Exception; }
    private static RootBinding binding() throws Exception {
        return new RootBinding("com.example.game", 0, 10001, "/data/user/0/com.example.game", "a".repeat(64), Map.of("/data/app/example/base.apk", "b".repeat(64)));
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    private static void check(boolean condition) { assertions++; if (!condition) throw new AssertionError("Assertion " + assertions); }
    private static void fails(Checked body) throws Exception {
        assertions++; try { body.run(); } catch (IOException expected) { return; } throw new AssertionError("Expected IOException " + assertions);
    }
    private static void code(RootMutationSession.Code code, Checked body) throws Exception {
        assertions++; try { body.run(); } catch (RootMutationSession.Failure expected) { if (expected.code == code) return; throw expected; }
        throw new AssertionError("Expected " + code + " " + assertions);
    }
    private static RootMutationSession open(FakeMutationOs os) throws Exception { return RootMutationSession.open(os, binding()); }
    public static void main(String[] args) throws Exception {
        gateBeforeTouches(); validateRoots(); workspaceOwnership(); locks(); privateCopiesAndBounds(); preflightRequired();
        hardlinkNoReplace(); quarantineAndRollback(); preserveUnknownMove(); preserveSameInodeEdit(); revalidateOriginalNames();
        privateCleanupExact(); journalReplacement(); noArchiveMetadata(); lowLevelNoReplaceDirectories(); adversarialEntries(); publishRace(); foreignCapabilities();
        System.out.println("MutationHostTests: " + assertions + " assertions passed (in-memory fake OS only; no Android/root/device execution)");
    }
    private static void gateBeforeTouches() throws Exception {
        check(new MutationOs.Failure(MutationOs.Error.PERMISSION, 13).errno == 13);
        check(new MutationOs.Failure(MutationOs.Error.IO, 4096).errno == 0);
        check(new MutationOs.Failure(MutationOs.Error.IO, -1).errno == 0);
        check(FakeMutationOs.contentFixture(io.github.xyz1001.pdsync.bridge.SaveDirectoryPolicy.ContentState.ALLOWLISTED_FILES_PRESENT).transferable());
        check(!FakeMutationOs.contentFixture(io.github.xyz1001.pdsync.bridge.SaveDirectoryPolicy.ContentState.UNRECOGNIZED_LAYOUT).transferable());
        FakeMutationOs os = new FakeMutationOs(); os.uid = 10001;
        code(RootMutationSession.Code.NOT_ROOT, () -> open(os)); check(os.touches == 0 && os.mutations == 0);
        os.uid = 0;
        try (RootMutationSession s = open(os)) {
            os.uid = 10001; int before = os.touches;
            code(RootMutationSession.Code.NOT_ROOT, s::revalidate); check(before == os.touches);
        }
    }
    private static void validateRoots() throws Exception {
        FakeMutationOs os = new FakeMutationOs(); os.canonical = "/tmp/foreign";
        fails(() -> open(os)); check(os.mutations == 0);
        for (int mode : new int[] {Stat.SYMLINK | 0777, Stat.DIRECTORY | 0777, Stat.DIRECTORY | 04700, Stat.DIRECTORY | 01700}) {
            FakeMutationOs x = new FakeMutationOs(); x.files.mode = mode; fails(() -> open(x)); check(x.mutations == 0);
        }
        FakeMutationOs wrongOwner = new FakeMutationOs(); wrongOwner.data.uid = 10002; fails(() -> open(wrongOwner));
        FakeMutationOs otherDevice = new FakeMutationOs(); otherDevice.files.device = 2; fails(() -> open(otherDevice));
        FakeMutationOs missing = new FakeMutationOs(); missing.data.children.remove("files"); fails(() -> open(missing)); check(missing.mutations == 0);
    }
    private static void workspaceOwnership() throws Exception {
        for (int owner : new int[] {10001, 1000}) {
            FakeMutationOs os = new FakeMutationOs(); os.dir(os.data, RootMutationSession.WORKSPACE, 0700, owner, owner);
            fails(() -> open(os)); check(!os.workspace().children.containsKey("lock"));
        }
        FakeMutationOs os = new FakeMutationOs(); os.dir(os.data, RootMutationSession.WORKSPACE, 0755, 0, 0); fails(() -> open(os));
        FakeMutationOs x = new FakeMutationOs(); x.dir(x.data, RootMutationSession.WORKSPACE, 0700, 0, 0).device = 2; fails(() -> open(x));
    }
    private static void locks() throws Exception {
        FakeMutationOs os = new FakeMutationOs();
        try (RootMutationSession s = open(os)) {
            code(RootMutationSession.Code.BUSY, () -> open(os)); check(os.workspace().children.get("lock").locked);
        }
        check(!os.workspace().children.get("lock").locked);
        try (RootMutationSession reopened = open(os)) { check(reopened.workspaceStat().uid == 0); }
    }
    private static void privateCopiesAndBounds() throws Exception {
        FakeMutationOs os = new FakeMutationOs(); FakeMutationOs.Node live = os.file(os.files, "rankings.dat", "baseline");
        os.maxRead = 2; os.maxWrite = 2;
        try (RootMutationSession s = open(os); LiveFile f = s.openLive("rankings.dat")) {
            LocalAttributes attr = s.localAttributes(f); byte[] read = s.readLive(f, 100); check(Arrays.equals(read, bytes("baseline")));
            try (PrivateFile staged = s.createPrivate("incoming", read, attr); PrivateFile backup = s.createPrivate("backup", read, attr)) {
                check(staged.stat().inode != live.inode && backup.stat().inode != live.inode && staged.stat().inode != backup.stat().inode);
                check(live.links == 1 && staged.stat().links == 1 && backup.stat().links == 1);
                check(staged.stat().uid == 10001 && staged.stat().gid == 10001 && (staged.stat().mode & 0777) == 0600);
                check(Arrays.equals(s.readPrivate(staged, 100), read));
                fails(() -> s.createPrivate("incoming", read, attr)); check(Arrays.equals(s.readPrivate(staged, 100), read));
                code(RootMutationSession.Code.TOO_LARGE, () -> s.readPrivate(backup, 2));
                s.removePrivate(staged); check(live.links == 1 && os.files.children.get("rankings.dat") == live);
            }
            code(RootMutationSession.Code.UNSAFE, () -> s.createPrivate("../escape", read, attr));
            code(RootMutationSession.Code.UNSAFE, () -> s.createPrivate("lock", read, attr));
            code(RootMutationSession.Code.UNSAFE, () -> s.openLive("settings.dat"));
            os.beforeRead = () -> { live.bytes = bytes("changed!"); live.mtime++; live.ctime++; };
            code(RootMutationSession.Code.CHANGED, () -> s.readLive(f, 100));
        }
        FakeMutationOs broken = new FakeMutationOs();
        try (RootMutationSession s = open(broken)) {
            broken.zeroWrite = true; code(RootMutationSession.Code.CHANGED, () -> s.createPrivate("partial", bytes("abc"), null));
            check(broken.workspace().children.containsKey("partial"));
        }
    }
    private static void preflightRequired() throws Exception {
        FakeMutationOs os = new FakeMutationOs();
        try (RootMutationSession s = open(os)) {
            LocalAttributes attr = s.defaultFileAttributes("");
            try (PrivateFile stage = s.createPrivate("stage", bytes("new"), attr)) {
                code(RootMutationSession.Code.UNSUPPORTED, () -> s.publishNoReplace(stage, "rankings.dat")); check(os.files.children.isEmpty());
                os.noLink = true; fails(() -> s.preflight(attr)); check(os.files.children.isEmpty());
                code(RootMutationSession.Code.UNSUPPORTED, () -> s.publishNoReplace(stage, "rankings.dat"));
                os.noLink = false; s.preflight(attr);
                check(s.publishNoReplace(stage, "rankings.dat")); check(stage.stat().links == 2);
            }
        }
        FakeMutationOs labelFailure = new FakeMutationOs();
        try (RootMutationSession s = open(labelFailure)) { labelFailure.noXattr = true; fails(() -> s.defaultFileAttributes("")); }
    }
    private static void hardlinkNoReplace() throws Exception {
        FakeMutationOs os = new FakeMutationOs(); FakeMutationOs.Node existing = os.file(os.files, "rankings.dat", "old");
        try (RootMutationSession s = open(os)) {
            LocalAttributes attr = s.defaultFileAttributes(""); s.preflight(attr);
            try (PrivateFile stage = s.createPrivate("stage", bytes("new"), attr)) {
                check(!s.publishNoReplace(stage, "rankings.dat")); check(os.files.children.get("rankings.dat") == existing);
                check(Arrays.equals(existing.bytes, bytes("old")) && stage.stat().links == 1);
                check(s.publishNoReplace(stage, "journal.dat")); check(stage.stat().links == 2);
                try (LiveFile live = s.openLive("journal.dat")) { check(Arrays.equals(s.readLive(live, 20), bytes("new"))); }
                code(RootMutationSession.Code.UNSAFE, () -> s.applyLocalAttributes(stage, attr));
                s.removePrivate(stage); check(os.files.children.get("journal.dat").links == 1);
            }
            long liveInode = os.files.inode;
            check(os.log.stream().noneMatch(x -> x.startsWith("unlink:" + liveInode + ":") || x.startsWith("rmdir:" + liveInode + ":")));
        }
    }
    private static void quarantineAndRollback() throws Exception {
        FakeMutationOs os = new FakeMutationOs(); FakeMutationOs.Node old = os.file(os.files, "rankings.dat", "old");
        try (RootMutationSession s = open(os); LiveFile live = s.openLive("rankings.dat")) {
            LocalAttributes attrs = s.localAttributes(live); s.preflight(attrs);
            try (Reservation q = s.reserveQuarantine("displaced"); PrivateFile moved = s.quarantine(live, q)) {
                check(!os.files.children.containsKey("rankings.dat")); check(moved.stat().inode == old.inode);
                check(s.publishNoReplace(moved, "rankings.dat")); check(old.links == 2);
                s.removePrivate(moved); s.removeReservation(q); check(old.links == 1); check(os.files.children.get("rankings.dat") == old);
            }
        }
    }
    private static void preserveUnknownMove() throws Exception {
        FakeMutationOs os = new FakeMutationOs(); os.file(os.files, "rankings.dat", "known");
        try (RootMutationSession s = open(os); LiveFile live = s.openLive("rankings.dat")) {
            s.preflight(s.localAttributes(live));
            try (Reservation q = s.reserveQuarantine("quarantine")) {
                FakeMutationOs.Node[] unknown = new FakeMutationOs.Node[1];
                os.beforeRename = () -> unknown[0] = os.file(os.files, "rankings.dat", "unknown writer");
                try { s.quarantine(live, q); throw new AssertionError("Expected move conflict"); }
                catch (RootMutationSession.Failure e) { check(e.code == RootMutationSession.Code.CONFLICT && e.preservedReservation.equals("quarantine")); }
                check(q.preservesUnknownEntry()); check(os.workspace().children.get("quarantine").children.get("entry") == unknown[0]);
                code(RootMutationSession.Code.CONFLICT, () -> s.removeReservation(q));
                code(RootMutationSession.Code.CONFLICT, () -> s.createPrivate("later", bytes("bad"), null));
                check(Arrays.equals(unknown[0].bytes, bytes("unknown writer")));
            }
        }
    }
    private static void preserveSameInodeEdit() throws Exception {
        FakeMutationOs os = new FakeMutationOs(); FakeMutationOs.Node original = os.file(os.files, "rankings.dat", "known");
        try (RootMutationSession s = open(os); LiveFile live = s.openLive("rankings.dat")) {
            s.preflight(s.localAttributes(live));
            try (Reservation q = s.reserveQuarantine("changed-content")) {
                os.beforeRename = () -> { original.bytes = bytes("other"); original.ctime++; }; // Same inode, size and restored mtime.
                code(RootMutationSession.Code.CONFLICT, () -> s.quarantine(live, q));
                check(os.workspace().children.get("changed-content").children.get("entry") == original);
                check(Arrays.equals(original.bytes, bytes("other")));
            }
        }
    }
    private static void revalidateOriginalNames() throws Exception {
        FakeMutationOs os = new FakeMutationOs(); FakeMutationOs.Node slot = os.dir(os.files, "game1", 0700, 10001, 10001);
        os.file(slot, "game.dat", "save");
        try (RootMutationSession s = open(os); LiveFile live = s.openLive("game1/game.dat")) {
            os.dir(os.files, "game1", 0700, 10001, 10001);
            code(RootMutationSession.Code.CHANGED, () -> s.readLive(live, 100));
            code(RootMutationSession.Code.UNSUPPORTED, () -> s.defaultFileAttributes("game2"));
            os.dir(os.data, "files", 0700, 10001, 10001); code(RootMutationSession.Code.CHANGED, s::revalidate);
        }
        FakeMutationOs x = new FakeMutationOs();
        try (RootMutationSession s = open(x)) {
            x.dir(x.data, RootMutationSession.WORKSPACE, 0700, 0, 0); code(RootMutationSession.Code.CHANGED, s::revalidate);
        }
    }
    private static void privateCleanupExact() throws Exception {
        FakeMutationOs os = new FakeMutationOs();
        try (RootMutationSession s = open(os); PrivateFile f = s.createPrivate("owned", bytes("mine"), null)) {
            FakeMutationOs.Node injected = os.file(os.workspace(), "owned", "unexpected");
            code(RootMutationSession.Code.CHANGED, () -> s.removePrivate(f)); check(os.workspace().children.get("owned") == injected);
        }
        FakeMutationOs unsafe = new FakeMutationOs(); FakeMutationOs.Node linked = unsafe.file(unsafe.files, "badges.dat", "x"); linked.links = 2;
        try (RootMutationSession s = open(unsafe)) { code(RootMutationSession.Code.UNSAFE, () -> s.openLive("badges.dat")); }
    }
    private static void journalReplacement() throws Exception {
        FakeMutationOs os = new FakeMutationOs();
        try (RootMutationSession s = open(os)) {
            check(s.readJournal() == null);
            try (PrivateFile first = s.replaceJournal(null, null, bytes("prepared"))) {
                check(first.stat().uid == 0 && first.stat().links == 1);
                code(RootMutationSession.Code.CONFLICT, () -> s.replaceJournal(first, bytes("wrong receipt"), bytes("committed")));
                check(Arrays.equals(s.readPrivate(first, 100), bytes("prepared")));
                try (PrivateFile second = s.replaceJournal(first, bytes("prepared"), bytes("committed"))) {
                    check(Arrays.equals(s.readPrivate(second, 100), bytes("committed")));
                    code(RootMutationSession.Code.UNSAFE, () -> s.removePrivate(first));
                    code(RootMutationSession.Code.CONFLICT, () -> s.replaceJournal(null, null, bytes("overwrite")));
                }
            }
        }
        try (RootMutationSession s = open(os); PrivateFile receipt = s.readJournal()) { check(Arrays.equals(s.readPrivate(receipt, 100), bytes("committed"))); }
    }
    private static void noArchiveMetadata() throws Exception {
        check(LocalAttributes.class.getConstructors().length == 0);
        FakeMutationOs first = new FakeMutationOs(); FakeMutationOs second = new FakeMutationOs();
        // Fake filesystems use the same inode seed; change device to model independent target devices for the process lock map.
        for (FakeMutationOs.Node node : second.root.children.values()) node.device = 2;
        try (RootMutationSession s = open(first)) {
            LocalAttributes attrs = s.defaultFileAttributes("");
            // A local label must be copied exactly, and only the fixed key exists in MutationOs.
            try (PrivateFile f = s.createPrivate("trusted", bytes("data"), attrs)) {
                check(Arrays.equals(first.workspace().children.get("trusted").label, FakeMutationOs.LABEL));
                check(attrs.selinux().equals("u:object_r:app_data_file:s0:c1,c2"));
            }
        }
    }
    private static void adversarialEntries() throws Exception {
        for (int mode : new int[] {Stat.SYMLINK | 0600, 0010000 | 0600, Stat.REGULAR | 0666, Stat.REGULAR | 04600}) {
            FakeMutationOs os = new FakeMutationOs(); os.file(os.files, "rankings.dat", "bad").mode = mode;
            try (RootMutationSession s = open(os)) { fails(() -> s.openLive("rankings.dat")); }
        }
        FakeMutationOs os = new FakeMutationOs(); FakeMutationOs.Node bad = os.file(os.files, "rankings.dat", "bad");
        bad.mode = Stat.REGULAR | 0660; bad.gid = 999;
        try (RootMutationSession s = open(os)) { fails(() -> s.openLive("rankings.dat")); }
        FakeMutationOs reads = new FakeMutationOs(); reads.file(reads.files, "rankings.dat", "not empty");
        try (RootMutationSession s = open(reads); LiveFile f = s.openLive("rankings.dat")) {
            reads.zeroRead = true; code(RootMutationSession.Code.CHANGED, () -> s.readLive(f, 100));
        }
    }
    private static void publishRace() throws Exception {
        FakeMutationOs os = new FakeMutationOs();
        try (RootMutationSession s = open(os)) {
            LocalAttributes attrs = s.defaultFileAttributes(""); s.preflight(attrs);
            try (PrivateFile stage = s.createPrivate("stage", bytes("new"), attrs)) {
                FakeMutationOs.Node[] raced = new FakeMutationOs.Node[1];
                os.beforeLink = () -> { raced[0] = os.file(os.files, "rankings.dat", "concurrent"); raced[0].mode = Stat.SYMLINK | 0777; };
                check(!s.publishNoReplace(stage, "rankings.dat")); check(os.files.children.get("rankings.dat") == raced[0]);
                check(stage.stat().links == 1);
            }
        }
    }
    private static void foreignCapabilities() throws Exception {
        FakeMutationOs first = new FakeMutationOs(), second = new FakeMutationOs();
        try (RootMutationSession a = open(first); RootMutationSession b = open(second)) {
            LocalAttributes attrs = a.defaultFileAttributes("");
            code(RootMutationSession.Code.UNSAFE, () -> b.createPrivate("foreign", bytes("data"), attrs));
            check(!second.workspace().children.containsKey("foreign"));
            try (PrivateFile file = a.createPrivate("local", bytes("data"), null)) {
                code(RootMutationSession.Code.UNSAFE, () -> b.removePrivate(file));
                check(first.workspace().children.containsKey("local"));
            }
        }
    }
    private static void lowLevelNoReplaceDirectories() throws Exception {
        FakeMutationOs os = new FakeMutationOs();
        try (RootMutationSession s = open(os)) {
            MutationOs.Handle workspace = s.workspaceHandle(), files = s.filesHandle();
            os.mkdirExclusive(workspace, "prepared-tree", 0700);
            os.mkdirExclusive(files, "game1", 0700); // Fixture only: session exposes no live mkdir operation.
            FakeMutationOs.Node old = os.files.children.get("game1");
            fails(() -> os.renameNoReplace(workspace, "prepared-tree", files, "game1")); check(os.files.children.get("game1") == old);
            os.renameNoReplace(workspace, "prepared-tree", files, "game2"); check(os.files.children.get("game2") != null);
            os.mkdirExclusive(workspace, "raced-tree", 0700);
            FakeMutationOs.Node[] raced = new FakeMutationOs.Node[1];
            os.beforeRename = () -> raced[0] = os.dir(os.files, "game3", 0700, 10001, 10001);
            fails(() -> os.renameNoReplace(workspace, "raced-tree", files, "game3"));
            check(os.files.children.get("game3") == raced[0] && os.workspace().children.containsKey("raced-tree"));
            os.noRename = true; fails(() -> os.renameNoReplace(files, "game2", workspace, "back")); check(os.files.children.containsKey("game2"));
        }
    }
}
