package io.github.xyz1001.pdsync.root.restore;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Pure restore state machine. This class neither opens paths nor elevates privileges.
 * The Android adapter is deliberately not implemented: see FileAccess's security contract.
 * Incoming bytes must first pass the existing SaveArchive parser and schema checks.
 */
public final class RootRestoreTransaction {
    public static final int MAX_FILES = 4096;
    public static final int MAX_ENTRIES = 16384;
    public static final long MAX_BYTES = 64L * 1024 * 1024;

    public enum Code { INVALID_INPUT, STALE_EXPORT, RECOVERY_REQUIRED, RESTORE_ROLLED_BACK,
        CONCURRENT_MUTATION, TARGET_CHANGED, UNSAFE_WORKSPACE, METADATA_AMBIGUOUS }
    public enum Outcome { UNCHANGED, RESTORED, RESTORED_CLEANUP_PENDING,
        RECOVERED, RECOVERED_CLEANUP_PENDING }
    public enum Phase { FORWARD, ROLLBACK, COMMITTED, ROLLED_BACK }
    public enum Area { LIVE, INCOMING, DISPLACED }

    public static final class Failure extends IOException {
        private static final long serialVersionUID = 1;
        public final Code code;
        public Failure(Code code, String message) { super(message); this.code = code; }
        public Failure(Code code, String message, Throwable cause) { super(message, cause); this.code = code; }
    }

    /** RootBinding.fingerprint plus independently pinned canonical PM directory identities. */
    public static final class Target {
        public final String bindingFingerprint, canonicalDataDir;
        public final int gameUid;
        public final long device, dataDirInode, filesInode;
        public Target(String bindingFingerprint, String canonicalDataDir, int gameUid,
                long device, long dataDirInode, long filesInode) throws IOException {
            require(hash(bindingFingerprint) && canonicalDataDir != null
                    && canonicalDataDir.startsWith("/") && canonicalDataDir.length() <= 2048
                    && canonicalDataDir.indexOf('\0') < 0 && !canonicalDataDir.equals("/")
                    && Paths.get(canonicalDataDir).normalize().toString().equals(canonicalDataDir)
                    && gameUid > 0 && device >= 0 && dataDirInode > 0 && filesInode > 0,
                    Code.INVALID_INPUT, "Invalid pinned target");
            this.bindingFingerprint = bindingFingerprint; this.canonicalDataDir = canonicalDataDir;
            this.gameUid = gameUid; this.device = device;
            this.dataDirInode = dataDirInode; this.filesInode = filesInode;
        }
        @Override public boolean equals(Object value) {
            if (!(value instanceof Target)) return false;
            Target other = (Target) value;
            return bindingFingerprint.equals(other.bindingFingerprint)
                    && canonicalDataDir.equals(other.canonicalDataDir) && gameUid == other.gameUid
                    && device == other.device && dataDirInode == other.dataDirInode && filesInode == other.filesInode;
        }
        @Override public int hashCode() { return Objects.hash(bindingFingerprint, canonicalDataDir, gameUid, device, dataDirInode, filesInode); }
    }

    /** Always observed locally. No archive field is allowed to populate this type. */
    public static final class Attributes {
        public final int uid, gid, mode;
        public final String selinux;
        public Attributes(int uid, int gid, int mode, String selinux) throws IOException {
            require(uid > 0 && gid >= 0 && mode >= 0 && (mode & ~0777) == 0
                    && selinux != null && selinux.length() >= 1 && selinux.length() <= 512
                    && selinux.matches("[A-Za-z0-9_:,.-]+"), Code.INVALID_INPUT, "Invalid local security metadata");
            this.uid = uid; this.gid = gid; this.mode = mode; this.selinux = selinux;
        }
        Attributes privateMode(boolean directory) throws IOException {
            return new Attributes(uid, gid, directory ? 0700 : 0600, selinux);
        }
        @Override public boolean equals(Object value) {
            if (!(value instanceof Attributes)) return false;
            Attributes other = (Attributes) value;
            return uid == other.uid && gid == other.gid && mode == other.mode && selinux.equals(other.selinux);
        }
        @Override public int hashCode() { return Objects.hash(uid, gid, mode, selinux); }
    }

    /** Content-only fields returned by the shared validated archive reader. */
    public static final class Content {
        public final String sha256;
        public final long bytes, modifiedMillis;
        public Content(String sha256, long bytes, long modifiedMillis) throws IOException {
            require(hash(sha256) && bytes >= 0 && bytes <= MAX_BYTES && modifiedMillis >= 0,
                    Code.INVALID_INPUT, "Invalid incoming file content");
            this.sha256 = sha256; this.bytes = bytes; this.modifiedMillis = modifiedMillis;
        }
        @Override public boolean equals(Object value) {
            if (!(value instanceof Content)) return false;
            Content other = (Content) value;
            return sha256.equals(other.sha256) && bytes == other.bytes && modifiedMillis == other.modifiedMillis;
        }
        @Override public int hashCode() { return Objects.hash(sha256, bytes, modifiedMillis); }
    }

    public static final class Entry {
        public final Attributes attributes;
        /** Null for a directory. Symlinks, devices and hard links have no representation. */
        public final Content content;
        public Entry(Attributes attributes, Content content) throws IOException {
            require(attributes != null, Code.INVALID_INPUT, "Missing local attributes");
            this.attributes = attributes; this.content = content;
        }
        public boolean directory() { return content == null; }
        @Override public boolean equals(Object value) {
            if (!(value instanceof Entry)) return false;
            Entry other = (Entry) value;
            return attributes.equals(other.attributes) && Objects.equals(content, other.content);
        }
        @Override public int hashCode() { return Objects.hash(attributes, content); }
    }

    /** Complete included tree, including empty directories. Excluded game files stay outside it. */
    public static final class Tree {
        public final SortedMap<String, Entry> entries;
        public Tree(Map<String, Entry> entries) throws IOException {
            require(entries != null && entries.size() <= MAX_ENTRIES, Code.INVALID_INPUT, "Excessive tree");
            TreeMap<String, Entry> copy = new TreeMap<>(); Set<String> folded = new HashSet<>();
            long bytes = 0; int files = 0;
            for (Map.Entry<String, Entry> item : entries.entrySet()) {
                String path = item.getKey(); Entry entry = item.getValue();
                require(safeName(path) && entry != null && folded.add(path.toLowerCase(Locale.ROOT)),
                        Code.INVALID_INPUT, "Unsafe, aliased or missing tree entry");
                require(!entry.directory() || path.matches("game[0-9]+(?:/.+)?"),
                        Code.INVALID_INPUT, "Only game slots may contain directories");
                require(entry.directory() || !path.matches("game[0-9]+"), Code.INVALID_INPUT, "Slot root must be a directory");
                if (!entry.directory()) {
                    bytes += entry.content.bytes; files++;
                    require(bytes <= MAX_BYTES && files <= MAX_FILES, Code.INVALID_INPUT, "Excessive file content");
                }
                copy.put(path, entry);
            }
            for (String path : copy.keySet()) {
                String parent = parent(path);
                if (!parent.isEmpty()) require(copy.containsKey(parent) && copy.get(parent).directory(),
                        Code.INVALID_INPUT, "Tree parent missing or not a directory");
            }
            this.entries = Collections.unmodifiableSortedMap(copy);
        }
        public SortedMap<String, String> hashes() {
            TreeMap<String, String> hashes = new TreeMap<>();
            for (Map.Entry<String, Entry> item : entries.entrySet())
                if (!item.getValue().directory()) hashes.put(item.getKey(), item.getValue().content.sha256);
            return Collections.unmodifiableSortedMap(hashes);
        }
        Set<String> topNames() {
            Set<String> result = new TreeSet<>();
            for (String path : entries.keySet()) result.add(top(path));
            return result;
        }
        Tree part(String name) throws IOException {
            TreeMap<String, Entry> result = new TreeMap<>();
            for (Map.Entry<String, Entry> item : entries.entrySet())
                if (top(item.getKey()).equals(name)) result.put(item.getKey(), item.getValue());
            return new Tree(result);
        }
        @Override public boolean equals(Object value) { return value instanceof Tree && entries.equals(((Tree) value).entries); }
        @Override public int hashCode() { return entries.hashCode(); }
    }

    public static final class Incoming {
        /** Adapter-owned reference to already parsed bytes; never a path or URI. */
        public final String contentToken;
        public final SortedMap<String, Content> files;
        public Incoming(String contentToken, Map<String, Content> files) throws IOException {
            require(contentToken != null && contentToken.matches("[A-Za-z0-9_-]{1,96}")
                    && files != null && !files.isEmpty() && files.size() <= MAX_FILES,
                    Code.INVALID_INPUT, "Invalid prepared content");
            TreeMap<String, Content> copy = new TreeMap<>(); long bytes = 0;
            for (Map.Entry<String, Content> item : files.entrySet()) {
                require(safeName(item.getKey()) && !item.getKey().matches("game[0-9]+") && item.getValue() != null,
                        Code.INVALID_INPUT, "Invalid prepared file");
                bytes += item.getValue().bytes;
                require(bytes <= MAX_BYTES, Code.INVALID_INPUT, "Incoming content exceeds limit");
                copy.put(item.getKey(), item.getValue());
            }
            this.contentToken = contentToken; this.files = Collections.unmodifiableSortedMap(copy);
        }
        public SortedMap<String, String> hashes() {
            TreeMap<String, String> hashes = new TreeMap<>();
            for (Map.Entry<String, Content> item : files.entrySet()) hashes.put(item.getKey(), item.getValue().sha256);
            return Collections.unmodifiableSortedMap(hashes);
        }
    }

    public static final class LocalSnapshot {
        public final Tree tree;
        public final Attributes rootAttributes;
        public LocalSnapshot(Tree tree, Attributes rootAttributes) throws IOException {
            require(tree != null && rootAttributes != null, Code.INVALID_INPUT, "Missing local snapshot");
            this.tree = tree; this.rootAttributes = rootAttributes;
        }
    }

    /** Adapter-generated receipt for one private workspace below the pinned PM dataDir. */
    public static final class Workspace {
        public final String transactionId, identityFingerprint;
        public final Target target;
        public final long device;
        public Workspace(String transactionId, String identityFingerprint, Target target, long device) throws IOException {
            require(uuid(transactionId) && hash(identityFingerprint) && target != null && device == target.device,
                    Code.UNSAFE_WORKSPACE, "Workspace is not bound to the target filesystem");
            this.transactionId = transactionId; this.identityFingerprint = identityFingerprint;
            this.target = target; this.device = device;
        }
        @Override public boolean equals(Object value) {
            if (!(value instanceof Workspace)) return false;
            Workspace other = (Workspace) value;
            return transactionId.equals(other.transactionId) && identityFingerprint.equals(other.identityFingerprint)
                    && target.equals(other.target) && device == other.device;
        }
        @Override public int hashCode() { return Objects.hash(transactionId, identityFingerprint, target, device); }
    }

    public static final class Layout {
        public final Tree live, incoming, displaced, backup;
        public Layout(Tree live, Tree incoming, Tree displaced, Tree backup) throws IOException {
            require(live != null && incoming != null && displaced != null && backup != null,
                    Code.INVALID_INPUT, "Incomplete workspace inspection");
            this.live = live; this.incoming = incoming; this.displaced = displaced; this.backup = backup;
        }
        Tree area(Area area) { return area == Area.LIVE ? live : area == Area.INCOMING ? incoming : displaced; }
        @Override public boolean equals(Object value) {
            if (!(value instanceof Layout)) return false;
            Layout other = (Layout) value;
            return live.equals(other.live) && incoming.equals(other.incoming)
                    && displaced.equals(other.displaced) && backup.equals(other.backup);
        }
        @Override public int hashCode() { return Objects.hash(live, incoming, displaced, backup); }
    }

    public static final class Move {
        public final Area from, to;
        public final String topName;
        public final Tree expectedEntry;
        private Move(Area from, Area to, String topName, Tree expectedEntry) {
            this.from = from; this.to = to; this.topName = topName; this.expectedEntry = expectedEntry;
        }
        Move reverse() { return new Move(to, from, topName, expectedEntry); }
    }

    /** Validating constructor is also the adapter's journal-deserialization boundary. */
    public static final class Journal {
        public static final int SCHEMA = 1;
        public final Workspace workspace;
        public final Tree before, desired;
        public final Phase phase;
        /** Last durably acknowledged move count; a crash can leave at most one move ahead/behind. */
        public final int cursor;
        private final List<Move> moves;
        public Journal(int schema, Workspace workspace, Tree before, Tree desired, Phase phase, int cursor) throws IOException {
            require(schema == SCHEMA && workspace != null && before != null && desired != null && phase != null,
                    Code.INVALID_INPUT, "Invalid restore journal");
            requireOwned(before, workspace.target.gameUid); requireOwned(desired, workspace.target.gameUid);
            this.moves = plan(before, desired);
            require(cursor >= 0 && cursor <= moves.size()
                    && (phase != Phase.COMMITTED || cursor == moves.size())
                    && (phase != Phase.ROLLED_BACK || cursor == 0), Code.INVALID_INPUT, "Invalid restore journal position");
            this.workspace = workspace; this.before = before; this.desired = desired; this.phase = phase; this.cursor = cursor;
        }
        public List<Move> moves() { return moves; }
        Journal at(Phase phase, int cursor) throws IOException { return new Journal(SCHEMA, workspace, before, desired, phase, cursor); }
        public Layout expectedLayout(int count) throws IOException {
            require(count >= 0 && count <= moves.size(), Code.INVALID_INPUT, "Invalid move count");
            TreeMap<String, Entry> live = new TreeMap<>(before.entries);
            TreeMap<String, Entry> incoming = new TreeMap<>(desired.entries);
            TreeMap<String, Entry> displaced = new TreeMap<>();
            for (int i = 0; i < count; i++) {
                Move move = moves.get(i);
                Map<String, Entry> from = move.from == Area.LIVE ? live : incoming;
                Map<String, Entry> to = move.to == Area.LIVE ? live : displaced;
                for (Map.Entry<String, Entry> item : move.expectedEntry.entries.entrySet()) {
                    require(from.remove(item.getKey()) != null && to.put(item.getKey(), item.getValue()) == null,
                            Code.INVALID_INPUT, "Invalid move plan");
                }
            }
            return new Layout(new Tree(live), new Tree(incoming), new Tree(displaced), before);
        }
        @Override public boolean equals(Object value) {
            if (!(value instanceof Journal)) return false;
            Journal other = (Journal) value;
            return workspace.equals(other.workspace) && before.equals(other.before) && desired.equals(other.desired)
                    && phase == other.phase && cursor == other.cursor;
        }
        @Override public int hashCode() { return Objects.hash(workspace, before, desired, phase, cursor); }
    }

    public interface Lease extends AutoCloseable { @Override void close() throws IOException; }

    /**
     * Implementations MUST use no-follow, identity-pinned directory-relative access. Validate
     * PackageManager binding, APKs, canonical dataDir, dev/inodes and quiet game state repeatedly.
     * Reject links, aliases, special files, changed descriptors and unknown/unobservable states.
     * The private receipt must bind the actual source/staged/backup inode identities; matching
     * content on a replacement inode does not waive the adapter's physical identity checks.
     * These are adapter requirements, not claims established by this pure state machine.
     */
    public interface FileAccess {
        /** Exclusive per-target lock, released by process death; no persistent service. */
        Lease lock(Target target) throws IOException;
        void verifyBindingAndQuiescent(Target target) throws IOException;
        Journal readJournal(Target target) throws IOException;
        /** One bounded content inventory, including the actual save-root attributes. */
        LocalSnapshot capture(Target target) throws IOException;
        /**
         * Before any live mutation: create root-private same-device staging under pinned dataDir;
         * re-read validated incoming bytes, apply desired local UID/GID/mode/security.selinux,
         * copy and verify an independent original backup, fsync everything and the exact receipt.
         * No archive ownership, labels, symlinks or paths are honored. Failure retains/cleans only
         * its own exact receipt; it must never mutate LIVE or leave an untracked workspace.
         */
        Workspace prepare(Target target, String transactionId, Incoming incoming, Tree desired, Tree before) throws IOException;
        /** Full-area inspection for an interrupted or failed transaction. */
        Layout inspect(Workspace workspace) throws IOException;
        /** Exactly one final readback on the normal path; adapters may avoid reading private copies. */
        default void verifyFinal(Workspace workspace, Tree expected) throws IOException {
            require(inspect(workspace).live.equals(expected), Code.CONCURRENT_MUTATION,
                    "Final live save does not match the transaction");
        }
        /**
         * Recheck source contents/metadata/identity and destination absence, then one atomic
         * no-replace top-level rename; fsync both parents. Android requires a proven atomic
         * RENAME_NOREPLACE primitive; ordinary rename/Files.move is not a valid fallback.
         * Equal st_dev alone is insufficient across bind mounts: preflight actual parents.
         * No-replace protects the destination, not a concurrently substituted source. Check
         * the captured inode after moving; retain unexpected captured/live data and backup,
         * then fail recovery-required rather than guessing a rollback. Never copy+delete.
         * On failure the move may or may not have happened; the engine reconciles before retry.
         */
        void move(Workspace workspace, Move move) throws IOException;
        /** Fsync all relevant directories before recording an observed uncertain move. */
        void sync(Workspace workspace) throws IOException;
        /** Durable compare-and-replace journal; expected=null only for first publication. */
        void saveJournal(Journal expected, Journal next) throws IOException;
        /**
         * Terminal journal only. Retain a verified finalized backup per local retention policy.
         * Remove only this exact workspace; remove journal last, after durable cleanup. Never
         * inspect untrusted names to choose paths, recurse outside pins, or modify live saves.
         */
        void cleanupTerminal(Journal terminal) throws IOException;
        /** Only when no journal was published; exact receipt validation is still mandatory. */
        void discardUnjournaled(Workspace workspace) throws IOException;
    }

    @SuppressWarnings("try")
    public Outcome restore(FileAccess files, Target target, Incoming incoming,
            Map<String, String> expectedCurrentHashes) throws IOException {
        require(files != null && target != null && incoming != null && expectedCurrentHashes != null,
                Code.INVALID_INPUT, "Restore arguments required");
        SortedMap<String, String> expected = checkedHashes(expectedCurrentHashes);
        try (Lease ignored = files.lock(target)) {
            files.verifyBindingAndQuiescent(target);
            require(files.readJournal(target) == null, Code.RECOVERY_REQUIRED, "An earlier transaction needs recovery");
            LocalSnapshot local = files.capture(target);
            files.verifyBindingAndQuiescent(target);
            requireOwned(local.tree, target.gameUid);
            require(local.rootAttributes.uid == target.gameUid, Code.TARGET_CHANGED, "Unexpected game-root owner");
            require(local.tree.hashes().equals(expected), Code.STALE_EXPORT, "Game save changed since comparison");
            // Byte-identical restore is a true filesystem no-op, including metadata.
            if (local.tree.hashes().equals(incoming.hashes())) return Outcome.UNCHANGED;
            Tree desired = desired(local, incoming, target.gameUid);
            String transactionId = UUID.randomUUID().toString();
            Workspace workspace = files.prepare(target, transactionId, incoming, desired, local.tree);
            require(workspace != null && workspace.target.equals(target) && workspace.transactionId.equals(transactionId),
                    Code.UNSAFE_WORKSPACE, "Adapter returned a different workspace");
            Journal journal = new Journal(Journal.SCHEMA, workspace, local.tree, desired, Phase.FORWARD, 0);
            boolean publicationAttempted = false;
            try {
                publicationAttempted = true;
                files.saveJournal(null, journal);
                while (journal.cursor < journal.moves.size()) journal = advance(files, journal);
                files.verifyFinal(journal.workspace, journal.desired);
                Journal terminal = journal.at(Phase.COMMITTED, journal.cursor);
                files.saveJournal(journal, terminal);
                return finish(files, terminal);
            } catch (IOException original) {
                if (!publicationAttempted) {
                    try { files.discardUnjournaled(workspace); } catch (IOException cleanup) { original.addSuppressed(cleanup); }
                    throw original;
                }
                Journal durable;
                try { durable = files.readJournal(target); }
                catch (IOException unavailable) {
                    original.addSuppressed(unavailable);
                    throw new Failure(Code.RECOVERY_REQUIRED, "Cannot establish durable restore state; backup retained", original);
                }
                if (durable == null) {
                    // saveJournal(null, ...) failed before publication. No move has been attempted.
                    if (journal.cursor == 0) {
                        try { verify(files, journal, 0); files.discardUnjournaled(workspace); }
                        catch (IOException uncertain) { original.addSuppressed(uncertain); throw new Failure(Code.RECOVERY_REQUIRED, "Uncertain initial journal state", original); }
                        throw original;
                    }
                    throw new Failure(Code.RECOVERY_REQUIRED, "Restore journal disappeared after mutation", original);
                }
                requireSameTransaction(journal, durable);
                if (durable.phase == Phase.COMMITTED) return finish(files, durable);
                try {
                    rollback(files, durable);
                } catch (IOException uncertain) {
                    original.addSuppressed(uncertain);
                    throw new Failure(Code.RECOVERY_REQUIRED, "Restore could not safely roll back; journal and backup retained", original);
                }
                throw new Failure(Code.RESTORE_ROLLED_BACK, "Restore failed; original save restored and verified", original);
            }
        }
    }

    /** Explicit recovery always restores the original, unless a durable COMMITTED record exists. */
    @SuppressWarnings("try")
    public Outcome recover(FileAccess files, Target target) throws IOException {
        require(files != null && target != null, Code.INVALID_INPUT, "Recovery arguments required");
        try (Lease ignored = files.lock(target)) {
            files.verifyBindingAndQuiescent(target);
            Journal durable = files.readJournal(target);
            require(durable != null, Code.RECOVERY_REQUIRED, "No recoverable transaction record");
            require(durable.workspace.target.equals(target), Code.TARGET_CHANGED, "Recovery belongs to another target");
            if (durable.phase == Phase.COMMITTED || durable.phase == Phase.ROLLED_BACK) return finish(files, durable);
            return rollback(files, durable);
        }
    }

    private Journal advance(FileAccess files, Journal journal) throws IOException {
        files.verifyBindingAndQuiescent(journal.workspace.target);
        boolean forward = journal.phase == Phase.FORWARD;
        int index = forward ? journal.cursor : journal.cursor - 1;
        require(index >= 0 && index < journal.moves.size(), Code.INVALID_INPUT, "Transaction has no next move");
        Move move = journal.moves.get(index);
        files.move(journal.workspace, forward ? move : move.reverse());
        int nextCursor = journal.cursor + (forward ? 1 : -1);
        Journal next = journal.at(journal.phase, nextCursor);
        files.saveJournal(journal, next);
        return next;
    }

    private Outcome rollback(FileAccess files, Journal durable) throws IOException {
        Journal reconciled = reconcile(files, durable);
        if (reconciled.phase != Phase.ROLLBACK) {
            Journal next = reconciled.at(Phase.ROLLBACK, reconciled.cursor);
            files.saveJournal(reconciled, next); reconciled = next;
        }
        while (reconciled.cursor > 0) reconciled = advance(files, reconciled);
        files.verifyFinal(reconciled.workspace, reconciled.before);
        Journal terminal = reconciled.at(Phase.ROLLED_BACK, 0);
        files.saveJournal(reconciled, terminal);
        return finish(files, terminal);
    }

    private Journal reconcile(FileAccess files, Journal journal) throws IOException {
        files.verifyBindingAndQuiescent(journal.workspace.target);
        Layout actual = files.inspect(journal.workspace);
        int cursor = journal.cursor;
        if (!actual.equals(journal.expectedLayout(cursor))) {
            int uncertainCursor = cursor + (journal.phase == Phase.FORWARD ? 1 : -1);
            require(uncertainCursor >= 0 && uncertainCursor <= journal.moves.size()
                    && actual.equals(journal.expectedLayout(uncertainCursor)), Code.CONCURRENT_MUTATION,
                    "Workspace is not either permitted crash state; no recovery writes made");
            files.sync(journal.workspace);
            verify(files, journal, uncertainCursor);
            Journal next = journal.at(journal.phase, uncertainCursor);
            files.saveJournal(journal, next); return next;
        }
        files.sync(journal.workspace);
        verify(files, journal, cursor);
        return journal;
    }

    private Outcome finish(FileAccess files, Journal terminal) throws IOException {
        require(terminal.phase == Phase.COMMITTED || terminal.phase == Phase.ROLLED_BACK,
                Code.INVALID_INPUT, "Only terminal transactions may be cleaned");
        // A finalized marker is definitive. Cleanup cannot trigger rollback or report an unknown commit.
        try {
            files.verifyBindingAndQuiescent(terminal.workspace.target);
            files.cleanupTerminal(terminal);
            return terminal.phase == Phase.COMMITTED ? Outcome.RESTORED : Outcome.RECOVERED;
        } catch (IOException cleanup) {
            return terminal.phase == Phase.COMMITTED ? Outcome.RESTORED_CLEANUP_PENDING : Outcome.RECOVERED_CLEANUP_PENDING;
        }
    }

    private void verify(FileAccess files, Journal journal, int cursor) throws IOException {
        files.verifyBindingAndQuiescent(journal.workspace.target);
        require(files.inspect(journal.workspace).equals(journal.expectedLayout(cursor)), Code.CONCURRENT_MUTATION,
                "Game save, staged content, metadata or backup changed; mutation refused");
        files.verifyBindingAndQuiescent(journal.workspace.target);
    }

    /** Derive ownership/security metadata exclusively from the freshly captured local target. */
    public static Tree desired(LocalSnapshot local, Incoming incoming, int gameUid) throws IOException {
        require(local != null && incoming != null && local.rootAttributes.uid == gameUid,
                Code.TARGET_CHANGED, "Unexpected local metadata owner");
        requireOwned(local.tree, gameUid);
        TreeMap<String, Entry> result = new TreeMap<>();
        for (Map.Entry<String, Content> file : incoming.files.entrySet()) {
            String path = file.getKey();
            result.put(path, new Entry(localAttributes(local, path, false), file.getValue()));
            String directory = parent(path);
            while (!directory.isEmpty()) {
                Entry prior = result.putIfAbsent(directory, new Entry(localAttributes(local, directory, true), null));
                require(prior == null || prior.directory(), Code.INVALID_INPUT, "Incoming file shadows a directory");
                directory = parent(directory);
            }
        }
        return new Tree(result);
    }

    private static Attributes localAttributes(LocalSnapshot local, String path, boolean directory) throws IOException {
        Entry existing = local.tree.entries.get(path);
        if (existing != null) {
            require(existing.directory() == directory, Code.METADATA_AMBIGUOUS, "Local entry type would change");
            return existing.attributes;
        }
        String parent = parent(path); Attributes exemplar = null;
        for (Map.Entry<String, Entry> item : local.tree.entries.entrySet()) {
            if (parent(item.getKey()).equals(parent) && item.getValue().directory() == directory) {
                Attributes candidate = item.getValue().attributes;
                require(exemplar == null || exemplar.equals(candidate), Code.METADATA_AMBIGUOUS,
                        "Local sibling security metadata differs; cannot infer new entry attributes");
                exemplar = candidate;
            }
        }
        if (exemplar != null) return exemplar;
        // No same-type local sibling: use the nearest verified local directory's owner/category,
        // with private 0600/0700 permissions. Never guess an SELinux context or use archive metadata.
        String ancestor = parent;
        while (!ancestor.isEmpty()) {
            Entry entry = local.tree.entries.get(ancestor);
            if (entry != null) {
                require(entry.directory(), Code.METADATA_AMBIGUOUS, "Local ancestor is not a directory");
                return entry.attributes.privateMode(directory);
            }
            ancestor = parent(ancestor);
        }
        return local.rootAttributes.privateMode(directory);
    }

    private static List<Move> plan(Tree before, Tree desired) throws IOException {
        TreeSet<String> all = new TreeSet<>(before.topNames()); all.addAll(desired.topNames());
        List<Move> old = new ArrayList<>(), fresh = new ArrayList<>();
        for (String name : all) {
            Tree prior = before.part(name), next = desired.part(name);
            if (prior.equals(next)) continue;
            if (!prior.entries.isEmpty()) old.add(new Move(Area.LIVE, Area.DISPLACED, name, prior));
            if (!next.entries.isEmpty()) fresh.add(new Move(Area.INCOMING, Area.LIVE, name, next));
        }
        old.addAll(fresh); return Collections.unmodifiableList(old);
    }

    private static void requireSameTransaction(Journal original, Journal durable) throws IOException {
        require(original.workspace.equals(durable.workspace) && original.before.equals(durable.before)
                && original.desired.equals(durable.desired), Code.RECOVERY_REQUIRED, "Journal changed transaction identity");
    }
    private static void requireOwned(Tree tree, int uid) throws IOException {
        for (Entry entry : tree.entries.values()) require(entry.attributes.uid == uid,
                Code.TARGET_CHANGED, "Included entry is owned by another UID");
    }
    private static SortedMap<String, String> checkedHashes(Map<String, String> hashes) throws IOException {
        require(hashes.size() <= MAX_FILES, Code.INVALID_INPUT, "Excessive expected inventory");
        TreeMap<String, String> copy = new TreeMap<>(); Set<String> folded = new HashSet<>();
        for (Map.Entry<String, String> entry : hashes.entrySet()) {
            require(safeName(entry.getKey()) && !entry.getKey().matches("game[0-9]+") && hash(entry.getValue())
                    && folded.add(entry.getKey().toLowerCase(Locale.ROOT)), Code.INVALID_INPUT, "Invalid expected file hash");
            copy.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableSortedMap(copy);
    }
    private static String top(String path) { int slash = path.indexOf('/'); return slash < 0 ? path : path.substring(0, slash); }
    private static String parent(String path) { int slash = path.lastIndexOf('/'); return slash < 0 ? "" : path.substring(0, slash); }
    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static boolean uuid(String value) { return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"); }
    private static boolean safeName(String path) {
        if (path == null || path.length() > 1024 || path.isEmpty()) return false;
        if (!path.equals("rankings.dat") && !path.equals("badges.dat") && !path.equals("journal.dat")
                && !path.matches("game[0-9]+(?:/.+)?")) return false;
        for (String piece : path.split("/", -1))
            if (!piece.matches("[A-Za-z0-9_.-]+") || piece.equals(".") || piece.equals("..") || piece.endsWith(".")) return false;
        return true;
    }
    private static void require(boolean value, Code code, String message) throws Failure {
        if (!value) throw new Failure(code, message);
    }
}
