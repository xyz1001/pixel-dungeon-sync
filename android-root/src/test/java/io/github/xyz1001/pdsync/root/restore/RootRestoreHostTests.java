package io.github.xyz1001.pdsync.root.restore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.github.xyz1001.pdsync.root.restore.RootRestoreTransaction.*;

/** Pure host state-machine tests. No Android syscalls, su, game data or root privileges. */
public final class RootRestoreHostTests {
    private static int assertions;
    private static final int UID = 10123;
    private static final Attributes FILE = attrs(UID, 50123, 0640, "u:object_r:app_data_file:s0:c123,c456");
    private static final Attributes DIR = attrs(UID, 50123, 0750, "u:object_r:app_data_file:s0:c123,c456");
    private static final RootRestoreTransaction ENGINE = new RootRestoreTransaction();

    public static void main(String[] args) throws Exception {
        successAndNoop(); invalidInput(); metadataPolicy(); codec();
        overwriteFailures(); crashEveryForwardBoundary(); crashEveryRecoveryBoundary();
        overwriteMutationBlocks(); targetAndWorkspaceChanges(); terminalCleanup(); legacyRecovery();
        System.out.println("Root restore host checks passed: " + assertions + " assertions (pure fake filesystem; not Android validation)");
    }

    private static void successAndNoop() throws Exception {
        Memory files = new Memory();
        equal(Outcome.RESTORED, restore(files), "normal restore");
        equal(files.desired, files.live, "desired tree restored with local metadata");
        check(files.retainedBackup.entries.isEmpty(), "no original backup created");
        check(files.journal == null && !files.locked, "cleaned journal and released lock");
        check(files.operations > 0, "file operations executed");
        check(files.live.entries.containsKey("game3/game.dat"), "new slot installed");
        equal(FILE.uid, files.live.entries.get("game1/game.dat").attributes.uid, "target UID preserved");
        Memory same = new Memory();
        Incoming incoming = incoming(same.initial);
        equal(Outcome.UNCHANGED, ENGINE.restore(same, same.target, incoming, same.initial.hashes()), "same bytes are a no-op");
        equal(0, same.preparations, "no workspace for unchanged bytes");
        equal(0, same.operations, "no mutation for unchanged bytes");
        Memory empty = new Memory(); empty.live = new Tree(Collections.emptyMap()); empty.initial = empty.live;
        equal(Outcome.RESTORED, restore(empty), "restore into empty recognized save tree");
        equal(0600, empty.live.entries.get("game1/game.dat").attributes.mode, "empty target uses private file mode");
        equal(DIR.selinux, empty.live.entries.get("game1/game.dat").attributes.selinux, "empty target uses observed local category");
    }

    private static void invalidInput() throws Exception {
        for (String path : Arrays.asList("../game.dat", "/game1/game.dat", "game1/../game.dat", "game1/a.", "game1//x", "game1/x:y", "game1/x\\y", "settings.dat")) {
            rejects(() -> new Incoming("fixture", Collections.singletonMap(path, content('a'))), "unsafe path " + path);
        }
        rejects(() -> new Incoming("/arbitrary/path", Collections.singletonMap("badges.dat", content('a'))), "content token is not a path");
        rejects(() -> new Content("bad", 2, 1), "malformed hash");
        rejects(() -> new Content(hash('a'), MAX_BYTES + 1, 1), "oversize content");
        rejects(() -> new Content(hash('a'), 1, -1), "negative timestamp");
        rejects(() -> new Attributes(0, 0, 0600, "u:object_r:app_data_file:s0"), "root UID never becomes game owner");
        rejects(() -> new Attributes(UID, UID, 04777, "u:object_r:app_data_file:s0"), "privileged permission bits rejected");
        rejects(() -> new Attributes(UID, UID, 0600, "untrusted\nlabel"), "control characters in label rejected");
        rejects(() -> new Target(hash('a'), "/data/user/0/game/../other", UID, 1, 1, 2), "noncanonical target path");
        rejects(() -> new Tree(Collections.singletonMap("game1/x", file('a'))), "missing parent");
        rejects(() -> new Tree(Collections.singletonMap("badges.dat", dir())), "global file cannot become directory");
        TreeMap<String, Entry> alias = new TreeMap<>(); alias.put("game1", dir()); alias.put("game1/A", file('a')); alias.put("game1/a", file('b'));
        rejects(() -> new Tree(alias), "case alias");
        Memory stale = new Memory();
        expectCode(Code.STALE_EXPORT, () -> ENGINE.restore(stale, stale.target, requested(), Collections.emptyMap()));
        equal(0, stale.preparations, "stale inventory fails before workspace");
        Map<String, Content> conflict = new TreeMap<>(); conflict.put("game1/x", content('a')); conflict.put("game1/x/y", content('b'));
        rejects(() -> RootRestoreTransaction.desired(new LocalSnapshot(stale.initial, DIR), new Incoming("fixture", conflict), UID), "file shadows directory");
        rejects(() -> ENGINE.restore(stale, stale.target, requested(), Collections.singletonMap("../x", hash('a'))), "bad expected inventory");
    }

    private static void metadataPolicy() throws Exception {
        Memory files = new Memory();
        Tree planned = RootRestoreTransaction.desired(new LocalSnapshot(files.initial, DIR), requested(), UID);
        equal(FILE, planned.entries.get("game1/game.dat").attributes, "exact old-file owner/group/mode/label retained");
        equal(DIR, planned.entries.get("game3").attributes, "new slot uses trusted local sibling directory");
        equal(DIR.selinux, planned.entries.get("game3/game.dat").attributes.selinux, "new file category derives locally");
        equal(0600, planned.entries.get("game3/game.dat").attributes.mode, "no existing parent uses private mode");
        TreeMap<String, Entry> different = new TreeMap<>(files.initial.entries);
        different.put("game1/other.dat", new Entry(attrs(UID, 50123, 0600, FILE.selinux), content('b')));
        Map<String, Content> filesWithNew = new TreeMap<>(requested().files); filesWithNew.put("game1/new.dat", content('e'));
        expectCode(Code.METADATA_AMBIGUOUS, () -> RootRestoreTransaction.desired(new LocalSnapshot(new Tree(different), DIR), new Incoming("fixture", filesWithNew), UID));
        TreeMap<String, Entry> foreign = new TreeMap<>(files.initial.entries);
        foreign.put("game1/game.dat", new Entry(attrs(10124, 10124, 0600, FILE.selinux), content('a')));
        expectCode(Code.TARGET_CHANGED, () -> RootRestoreTransaction.desired(new LocalSnapshot(new Tree(foreign), DIR), requested(), UID));
        // Incoming has content-only fields. The requested timestamp never becomes a UID, mode or label.
        equal(requested().files.get("game1/game.dat").modifiedMillis, planned.entries.get("game1/game.dat").content.modifiedMillis, "content timestamps kept separately");
    }

    private static void codec() throws Exception {
        Memory files = stoppedAfterMove(1);
        Journal journal = files.journal;
        byte[] encoded = RestoreJournalCodec.encode(journal);
        equal(journal, RestoreJournalCodec.decode(encoded), "journal round trip");
        check(Arrays.equals(encoded, RestoreJournalCodec.encode(journal)), "deterministic journal encoding");
        rejects(() -> RestoreJournalCodec.decode(Arrays.copyOf(encoded, encoded.length - 1)), "truncation");
        byte[] changed = encoded.clone(); changed[changed.length / 2] ^= 1;
        rejects(() -> RestoreJournalCodec.decode(changed), "checksum corruption");
        rejects(() -> RestoreJournalCodec.decode(new byte[RestoreJournalCodec.MAX_BYTES + 1]), "oversize journal");
        rejects(() -> new Journal(3, journal.workspace, journal.before, journal.desired, Phase.FORWARD, 0), "unknown schema");
        rejects(() -> new Journal(1, journal.workspace, journal.before, journal.desired, Phase.FORWARD, -1), "negative cursor");
        rejects(() -> new Journal(1, journal.workspace, journal.before, journal.desired, Phase.COMMITTED, 0), "premature committed marker");
        rejects(() -> new Journal(1, journal.workspace, journal.before, journal.desired, Phase.ROLLED_BACK, 1), "invalid rollback marker");
        rejects(() -> new Workspace(journal.workspace.transactionId, hash('c'), files.target, files.target.device + 1), "cross-device stage");
    }

    private static void overwriteFailures() throws Exception {
        Memory baseline = new Memory(); restore(baseline);
        int moveCount = baseline.operations;
        for (int move = 1; move <= moveCount; move++) {
            for (boolean after : new boolean[]{false, true}) {
                Memory files = new Memory(); files.failLabel = "move." + move + (after ? ".after" : ".before");
                expectCode(Code.RECOVERY_REQUIRED, () -> restore(files));
                check(files.backup.entries.isEmpty() && files.displaced.entries.isEmpty(), "failure retains no old-data copy");
                equal(Outcome.RESTORED, ENGINE.recover(files,files.target), "resume failed overwrite");
                equal(files.desired,files.live,"resumed desired bytes");
            }
        }
        Memory writeBefore = new Memory(); writeBefore.failLabel = "journal.1.before";
        rejects(() -> restore(writeBefore), "initial journal write failure");
        equal(writeBefore.initial, writeBefore.live, "initial write failure leaves live untouched");
        check(writeBefore.journal == null && writeBefore.workspace == null, "unused staging removed after definite initial failure");
        Memory writeAfter = new Memory(); writeAfter.failLabel = "journal.1.after";
        expectCode(Code.RECOVERY_REQUIRED, () -> restore(writeAfter));
        equal(writeAfter.initial, writeAfter.live, "uncertain initial durable write reconciles without guessing");
    }

    private static void crashEveryForwardBoundary() throws Exception {
        Memory completed = new Memory(); restore(completed); int boundaries = completed.events.size();
        for (int point = 1; point <= boundaries; point++) {
            Memory files = new Memory(); files.crashAt = point;
            try { restore(files); throw new AssertionError("crash point missed: " + point); } catch (Crash expected) { }
            check(!files.locked, "host crash unwinds lease");
            files.disableFaults();
            if (files.journal == null) {
                check(files.live.equals(files.initial) || files.finalized && files.live.equals(files.desired), "no-journal state is untouched or durably finalized");
            } else {
                boolean committed = files.journal.phase == Phase.COMMITTED;
                Outcome result = ENGINE.recover(files, files.target);
                equal(Outcome.RESTORED, result, "crash recovery result at " + point);
                equal(files.desired, files.live, "crash recovery content at " + point);
                check(files.journal == null, "finite crash recovery clears terminal journal");
            }
        }
        check(boundaries > 60, "crash matrix spans reads, writes, moves and cleanup");
    }

    private static void crashEveryRecoveryBoundary() throws Exception {
        Memory completed = stoppedAfterMove(2); completed.events.clear();
        ENGINE.recover(completed, completed.target); int boundaries = completed.events.size();
        for (int point = 1; point <= boundaries; point++) {
            Memory files = stoppedAfterMove(2); files.events.clear(); files.crashAt = point;
            try { ENGINE.recover(files, files.target); throw new AssertionError("recovery crash point missed"); } catch (Crash expected) { }
            files.disableFaults();
            if (files.journal != null) {
                equal(Outcome.RESTORED, ENGINE.recover(files, files.target), "retry crashed recovery");
            }
            equal(files.desired, files.live, "every interrupted overwrite resumes desired content");
        }
        check(boundaries > 30, "recovery crash matrix spans resumed operations and persistence");
    }

    private static void overwriteMutationBlocks() throws Exception {
        Memory race = new Memory(); race.injectMutationAtMove = 1;
        expectCode(Code.RECOVERY_REQUIRED, () -> restore(race));
        int operations = race.operations;
        expectCode(Code.CONCURRENT_MUTATION, () -> ENGINE.recover(race,race.target));
        equal(operations,race.operations,"unknown save state receives no recovery writes");
        check(race.backup.entries.isEmpty() && race.displaced.entries.isEmpty(),"no backup on failure");
        Journal legacy = new Journal(1,race.journal.workspace,race.initial,race.desired,Phase.FORWARD,0);
        equal(1,RestoreJournalCodec.decode(RestoreJournalCodec.encode(legacy)).schema,"legacy schema preserved");
        rejects(() -> new Journal(2,legacy.workspace,legacy.before,legacy.desired,Phase.ROLLBACK,0),"new overwrite cannot claim rollback");
    }

    private static void targetAndWorkspaceChanges() throws Exception {
        Memory changed = stoppedAfterMove(1); changed.bindingValid = false;
        rejects(() -> ENGINE.recover(changed, changed.target), "PM/UID/APK/root change blocks recovery");
        check(changed.journal != null, "target mismatch retains recovery");
        Memory changedInode = stoppedAfterMove(1); changedInode.workspaceValid = false;
        rejects(() -> ENGINE.recover(changedInode, changedInode.target), "workspace inode replacement");
        check(changedInode.journal != null, "workspace mismatch retains recovery");
        Memory nested = new Memory(); nested.locked = true;
        rejects(() -> restore(nested), "same-target concurrent operation lock");
        equal(0, nested.preparations, "second operation never reaches prepare");
    }

    private static void terminalCleanup() throws Exception {
        Memory cleanup = new Memory(); cleanup.failLabel = "cleanup.before";
        equal(Outcome.RESTORED_CLEANUP_PENDING, restore(cleanup), "committed cleanup failure distinguished");
        equal(Phase.COMMITTED, cleanup.journal.phase, "durable committed marker retained");
        int mutations = cleanup.operations;
        equal(Outcome.RESTORED, ENGINE.recover(cleanup, cleanup.target), "retry committed cleanup only");
        equal(mutations, cleanup.operations, "completed restore never rolls back during cleanup retry");
        Memory rollbackCleanup = stoppedAfterMove(1); rollbackCleanup.failLabel = "cleanup.before";
        equal(Outcome.RESTORED_CLEANUP_PENDING, ENGINE.recover(rollbackCleanup, rollbackCleanup.target), "overwrite cleanup failure distinguished");
        equal(Phase.COMMITTED, rollbackCleanup.journal.phase, "overwrite committed marker");
        equal(Outcome.RESTORED, ENGINE.recover(rollbackCleanup, rollbackCleanup.target), "retry overwrite cleanup only");
    }

    private static void legacyRecovery() throws Exception {
        Memory files = new Memory();
        files.desired = RootRestoreTransaction.desired(new LocalSnapshot(files.initial,DIR),requested(),UID);
        files.workspace = new Workspace("11111111-1111-1111-1111-111111111111",hash('b'),files.target,files.target.device);
        files.journal = new Journal(1,files.workspace,files.initial,files.desired,Phase.FORWARD,1);
        Layout layout = files.journal.expectedLayout(1);
        files.live=layout.live; files.incoming=layout.incoming; files.displaced=layout.displaced; files.backup=layout.backup;
        equal(Outcome.RECOVERED,ENGINE.recover(files,files.target),"legacy existing original can recover");
        equal(files.initial,files.live,"legacy original restored using existing displaced tree");
        equal(files.initial,files.retainedBackup,"legacy backup remains intact");
    }

    private static Memory stoppedAfterMove(int number) throws Exception {
        Memory files = new Memory(); files.crashLabel = "move." + number + ".after";
        try { restore(files); throw new AssertionError("expected stop after move"); } catch (Crash expected) { }
        files.disableFaults(); return files;
    }
    private static Outcome restore(Memory files) throws IOException { return ENGINE.restore(files, files.target, requested(), files.initial.hashes()); }
    private static Incoming requested() throws IOException {
        Map<String, Content> files = new TreeMap<>();
        files.put("game1/game.dat", content('d')); files.put("game1/levels/1.dat", content('e'));
        files.put("game3/game.dat", content('f')); files.put("badges.dat", content('c'));
        return new Incoming("prepared_archive_fixture", files);
    }
    private static Incoming incoming(Tree tree) throws IOException {
        Map<String, Content> files = new TreeMap<>();
        for (Map.Entry<String, Entry> entry : tree.entries.entrySet()) if (!entry.getValue().directory()) files.put(entry.getKey(), entry.getValue().content);
        return new Incoming("same_bytes", files);
    }
    private static Tree initial() throws IOException {
        Map<String, Entry> entries = new TreeMap<>();
        entries.put("game1", dir()); entries.put("game1/game.dat", file('a'));
        entries.put("game1/levels", dir()); entries.put("game1/levels/1.dat", file('b'));
        entries.put("game2", dir()); entries.put("game2/game.dat", file('c'));
        entries.put("game7", dir()); // Preserve exact empty-directory transaction state too.
        entries.put("badges.dat", file('b')); return new Tree(entries);
    }
    private static Tree tree(String path, char value) throws IOException { return new Tree(Collections.singletonMap(path, file(value))); }
    private static Entry dir() throws IOException { return new Entry(DIR, null); }
    private static Entry file(char value) throws IOException { return new Entry(FILE, content(value)); }
    private static Content content(char value) throws IOException { return new Content(hash(value), 17, 12345); }
    private static String hash(char value) { char[] values = new char[64]; Arrays.fill(values, value); return new String(values); }
    private static Attributes attrs(int uid, int gid, int mode, String label) {
        try { return new Attributes(uid, gid, mode, label); } catch (IOException error) { throw new AssertionError(error); }
    }
    private static final long MAX_BYTES = RootRestoreTransaction.MAX_BYTES;
    private interface Action { void run() throws Exception; }
    private static void rejects(Action action, String message) throws Exception {
        try { action.run(); throw new AssertionError("accepted: " + message); } catch (IOException expected) { assertions++; }
    }
    private static void expectCode(Code code, Action action) throws Exception {
        try { action.run(); throw new AssertionError("expected " + code); }
        catch (Failure error) { equal(code, error.code, "failure code"); }
    }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) { check(java.util.Objects.equals(expected, actual), message + ": expected " + expected + ", actual " + actual); }
    private static final class Crash extends Error { private static final long serialVersionUID = 1; }

    /** Immutable tree values model durable side effects; events inject failure before/after each step. */
    private static final class Memory implements FileAccess {
        final Target target = new Target(hash('a'), "/data/user/0/com.example.game", UID, 3, 11, 12);
        Tree initial = initial(), live = initial, desired, incoming, displaced, backup, retainedBackup;
        Workspace workspace; Journal journal;
        boolean locked, bindingValid = true, workspaceValid = true, quiet = true, finalized;
        boolean injectDestinationRace, injectSourceRace;
        int preparations, operations, journalWrites, crashAt = -1, injectMutationAtMove = -1;
        String failLabel, crashLabel;
        final List<String> events = new ArrayList<>();
        Memory() throws IOException { }
        void event(String label) throws IOException {
            events.add(label);
            if (events.size() == crashAt || label.equals(crashLabel)) { crashLabel = null; throw new Crash(); }
            if (label.equals(failLabel)) { failLabel = null; throw new IOException("Injected I/O failure at " + label); }
        }
        void disableFaults() { crashAt = -1; crashLabel = null; failLabel = null; injectMutationAtMove = -1; }
        @Override public Lease lock(Target target) throws IOException {
            event("lock.before"); if (locked) throw new IOException("Already locked");
            locked = true;
            // No failure after acquisition until the caller has its lease.
            return () -> { locked = false; };
        }
        @Override public void verifyBindingAndQuiescent(Target expected) throws IOException {
            event("verify.before");
            if (!bindingValid || !target.equals(expected)) throw new IOException("Binding changed");
            if (!quiet) throw new IOException("Game is running or visibility unknown");
            event("verify.after");
        }
        @Override public Journal readJournal(Target target) throws IOException {
            event("readJournal.before"); Journal result = journal;
            event("readJournal.after"); return result;
        }
        @Override public LocalSnapshot capture(Target target) throws IOException {
            event("capture.before"); LocalSnapshot result = new LocalSnapshot(live, DIR);
            event("capture.after"); return result;
        }
        @Override public Workspace prepare(Target target, String transaction, Incoming contents, Tree desired, Tree before) throws IOException {
            event("prepare.before"); preparations++;
            this.desired = desired; this.incoming = desired; this.displaced = new Tree(Collections.emptyMap()); this.backup = new Tree(Collections.emptyMap());
            workspace = new Workspace(transaction, hash('b'), target, target.device);
            event("prepare.after"); return workspace;
        }
        @Override public Layout inspect(Workspace expected) throws IOException {
            event("inspect.before");
            if (!workspaceValid || !expected.equals(workspace)) throw new IOException("Workspace identity changed");
            Layout result = new Layout(live, incoming, displaced, backup); event("inspect.after"); return result;
        }
        @Override public void move(Workspace expected, Move move) throws IOException {
            int number = ++operations; event("move." + number + ".before");
            if (!expected.equals(workspace) || !workspaceValid) throw new IOException("Invalid workspace");
            Tree source = area(move.from), destination = move.to == Area.REMOVED ? new Tree(Collections.emptyMap()) : area(move.to);
            if (!move.expectedEntry.equals(source.part(move.topName))) throw new IOException("Source changed before move");
            if (!destination.part(move.topName).entries.isEmpty()) throw new IOException("Destination already present");
            if (injectDestinationRace && move.to == Area.LIVE) {
                injectDestinationRace = false;
                Map<String, Entry> racing = new TreeMap<>(destination.entries);
                racing.put("badges.dat", file('f')); destination = new Tree(racing); set(move.to, destination);
            }
            // Model the native primitive's atomic no-replace check, not ordinary Java rename.
            if (!destination.part(move.topName).entries.isEmpty()) throw new IOException("Atomic destination-exists rejection");
            if (injectSourceRace && move.from == Area.LIVE) {
                injectSourceRace = false;
                Map<String, Entry> racing = new TreeMap<>(source.entries);
                racing.put("badges.dat", file('f')); source = new Tree(racing); set(move.from, source);
            }
            Tree actualSource = source.part(move.topName);
            Map<String, Entry> from = new TreeMap<>(source.entries), to = new TreeMap<>(destination.entries);
            for (Map.Entry<String, Entry> item : actualSource.entries.entrySet()) { from.remove(item.getKey()); to.put(item.getKey(), item.getValue()); }
            set(move.from, new Tree(from)); if (move.to != Area.REMOVED) set(move.to, new Tree(to));
            if (!move.expectedEntry.equals(actualSource)) throw new IOException("Captured source changed; preserve unknown entry");
            if (number == injectMutationAtMove) {
                Map<String, Entry> changed = new TreeMap<>(live.entries); changed.put("journal.dat", file('f')); live = new Tree(changed);
            }
            event("move." + number + ".after");
        }
        Tree area(Area area) { return area == Area.LIVE ? live : area == Area.INCOMING ? incoming : displaced; }
        void set(Area area, Tree tree) { if (area == Area.LIVE) live = tree; else if (area == Area.INCOMING) incoming = tree; else displaced = tree; }
        @Override public void sync(Workspace workspace) throws IOException { event("sync.before"); event("sync.after"); }
        @Override public void saveJournal(Journal expected, Journal next) throws IOException {
            int number = ++journalWrites; event("journal." + number + ".before");
            if (!java.util.Objects.equals(expected, journal)) throw new IOException("Journal compare-and-replace failed");
            journal = RestoreJournalCodec.decode(RestoreJournalCodec.encode(next));
            event("journal." + number + ".after");
        }
        @Override public void cleanupTerminal(Journal terminal) throws IOException {
            event("cleanup.before");
            if (!terminal.equals(journal)) throw new IOException("Different terminal journal");
            check(terminal.phase == Phase.COMMITTED || terminal.phase == Phase.ROLLED_BACK, "cleanup terminal only");
            retainedBackup = backup; finalized = true; journal = null; workspace = null;
            event("cleanup.after");
        }
        @Override public void discardUnjournaled(Workspace expected) throws IOException {
            event("discard.before");
            if (journal != null || !expected.equals(workspace)) throw new IOException("Unsafe discard");
            workspace = null; event("discard.after");
        }
    }
}
