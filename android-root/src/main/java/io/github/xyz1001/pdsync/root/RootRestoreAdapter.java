package io.github.xyz1001.pdsync.root;

import io.github.xyz1001.pdsync.bridge.SaveArchive;
import io.github.xyz1001.pdsync.bridge.SaveDirectoryPolicy;
import io.github.xyz1001.pdsync.root.RootFileSystem.Stat;
import io.github.xyz1001.pdsync.root.mutation.MutationOs;
import io.github.xyz1001.pdsync.root.mutation.RootMutationSession;
import io.github.xyz1001.pdsync.root.restore.RootRestoreTransaction;
import io.github.xyz1001.pdsync.root.restore.RootRestoreTransaction.*;
import io.github.xyz1001.pdsync.root.restore.RestoreJournalCodec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Actual transaction adapter; all kernel operations are injectable and descriptor-relative. */
public final class RootRestoreAdapter implements FileAccess, AutoCloseable {
    public interface Gate { void requireSafe(RootBinding binding) throws IOException; void quietWait() throws IOException; }
    public interface IncomingSource { byte[] read(String name) throws IOException; }
    private static final String ACTIVE = "active", RECEIPT = "receipt", PREPARED = "prepared";
    private static final int MAX_RECEIPT = 16 * 1024 * 1024;
    private final MutationOs os;
    private final RootBinding binding;
    private final Gate gate;
    private final RootMutationSession session;
    private final Target target;
    private IncomingSource incomingSource;
    private MutationOs.Handle archiveInput;
    private boolean receiving;
    private Receipt receipt;
    private MutationOs.Handle active;
    private final Map<String,MutationOs.Handle> areas = new HashMap<>();
    private boolean engineLease, closed, unknown;
    /** One job owns the session lock. Reuse its capture until the first live mutation. */
    private LocalSnapshot captured;
    private Scan capturedScan;
    private boolean finalReadback;
    private static final String[] PRIVATE_AREAS = {"incoming", "cleanup"};
    private static final String[] LEGACY_AREAS = {"incoming", "displaced", "backup", "cleanup"};
    private RootProtocol.DiagnosticStage stage = RootProtocol.DiagnosticStage.NONE;
    public RootProtocol.DiagnosticStage stage() { return stage; }

    public RootRestoreAdapter(MutationOs os, RootBinding binding, Gate gate) throws IOException {
        if (os == null || binding == null || gate == null) throw new IOException("Missing restore dependencies");
        if (os.effectiveUid() != 0) throw new RootFailure(RootProtocol.Status.NOT_ROOT);
        gate.requireSafe(binding);
        this.os = os; this.binding = binding; this.gate = gate;
        this.session = RootMutationSession.open(os, binding);
        Stat data = session.dataStat(), files = session.filesStat();
        this.target = new Target(binding.fingerprint(), session.canonicalDataDir(), binding.uid,
                data.device, data.inode, files.inode);
    }
    public Target target() { return target; }
    public void incomingSource(IncomingSource source) { this.incomingSource = source; }
    public boolean pending() throws IOException {
        try (RootMutationSession.PrivateFile journal = session.readJournal()) {
            if (journal != null) return true;
            for(String name:os.children(session.workspaceHandle(),SaveArchive.MAX_ENTRIES))if(!name.equals("lock")&&!name.equals(ACTIVE))return true;
            if (optional(session.workspaceHandle(), ACTIVE) == null) return false;
            openActive(); return !cleanState();
        }
    }
    /** Receives into fresh root-private staging, then validates exact framing before live mutation. */
    public Incoming receive(RootProtocol.RequestFrame frame) throws IOException {
        stage=RootProtocol.DiagnosticStage.ARCHIVE_READ;
        if(pending())throw new Failure(Code.RECOVERY_REQUIRED,"A transaction is pending");
        if(active==null){os.mkdirExclusive(session.workspaceHandle(),ACTIVE,0700);os.sync(session.workspaceHandle());openActive();}
        for(String name:new String[]{RECEIPT,PREPARED,"terminal"})removeRecordIfPresent(active,name);
        setPhase("PREPARING",UUID.randomUUID().toString()); receiving=true;
        boolean complete=false;
        try {
            os.mkdirExclusive(active,"archive-input",0700);os.sync(active);
            RootArchiveInput input=new RootArchiveInput(frame.archive(),frame.request.archiveLength);
            SaveArchive.Inventory inventory=os.extractArchive(active,"archive-input",input);
            input.verify(inventory.hashes.size());frame.finish();
            if(!inventory.fingerprint().equals(frame.request.desiredFingerprint))throw new RootFailure(RootProtocol.Status.INVALID_REQUEST);
            archiveInput=os.openDirectory(active,"archive-input");privateRoot(archiveInput);
            Map<String,Content> content=new TreeMap<>();long total=0;
            for(Map.Entry<String,String> item:inventory.hashes.entrySet()) {
                try(Parent parent=parent(archiveInput,item.getKey());MutationOs.Handle file=os.openRegular(parent.handle,basename(item.getKey()),false)) {
                    Stat stat=os.stat(file);if(!stat.regular()||stat.uid!=0||stat.links!=1||stat.size<0||stat.size>SaveArchive.MAX_BYTES-total)throw new IOException("Unsafe archive staging");
                    // The archive reader already hashed and fsynced these private files.
                    total+=stat.size;content.put(item.getKey(),new Content(item.getValue(),stat.size,Math.max(0,stat.modifiedSeconds*1000)));
                }
            }
            if(total!=inventory.bytes)throw new IOException("Archive staging size changed");
            incomingSource=name->{try(Parent parent=parent(archiveInput,name);MutationOs.Handle file=os.openRegular(parent.handle,basename(name),false)){
                Stat stat=os.stat(file);if(stat.uid!=0||!stat.regular()||stat.links!=1||stat.size<0||stat.size>SaveArchive.MAX_BYTES)throw new IOException("Unsafe archive source");byte[] bytes=read(file,(int)stat.size);same(stat,os.stat(file));return bytes;}};
            complete=true;return new Incoming(inventory.fingerprint(),content);
        }finally {if(!complete)try{clearPreparation();}catch(IOException preserved){/* Keep proven private preparation for RECOVER. */}}
    }
    public void discardReceivedIfUnpublished() throws IOException {
        if(!receiving||active==null)return;
        try(RootMutationSession.PrivateFile journal=session.readJournal()){if(journal!=null)return;}
        if(phase().startsWith("PREPARING:"))clearPreparation();
    }
    @Override public Lease lock(Target expected) throws IOException {
        sameTarget(expected); if (engineLease) throw new IOException("Restore already leased");
        engineLease = true; return () -> { engineLease = false; };
    }
    @Override public void verifyBindingAndQuiescent(Target expected) throws IOException {
        sameTarget(expected); RootProtocol.DiagnosticStage previous=stage; stage=RootProtocol.DiagnosticStage.UID_STATE;
        gate.requireSafe(binding); stage=RootProtocol.DiagnosticStage.ROOT_OPEN; session.revalidate();stage=previous;
        if (unknown) throw new Failure(Code.CONCURRENT_MUTATION, "Unexpected moved content is retained");
    }
    private void sameTarget(Target expected) throws IOException {
        if (closed || !target.equals(expected)) throw new Failure(Code.TARGET_CHANGED, "Restore target changed");
    }
    @Override public Journal readJournal(Target expected) throws IOException {
        sameTarget(expected);
        try (RootMutationSession.PrivateFile file = session.readJournal()) {
            if (file == null) {
                if (optional(session.workspaceHandle(), ACTIVE) != null) {
                    openActive();
                    if (!cleanState() && !receiving) throw new Failure(Code.RECOVERY_REQUIRED, "A private preparation requires recovery");
                }
                return null;
            }
            Journal journal = RestoreJournalCodec.decode(session.readPrivate(file, RestoreJournalCodec.MAX_BYTES));
            sameTarget(journal.workspace.target); load(journal.workspace); return journal;
        }
    }
    /** An interrupted preparation can be recovered only once its complete prepared receipt exists. */
    public void resumePrepared() throws IOException {
        verifyBindingAndQuiescent(target);
        session.cleanupJournalTemporaries();requireWorkspaceNames();
        try (RootMutationSession.PrivateFile journal = session.readJournal()) { if (journal != null) return; }
        if (optional(session.workspaceHandle(), ACTIVE) == null) return;
        openActive();
        if (cleanState()) return;
        recoverPhaseTemporary();
        if (cleanState()) return;
        if (optional(active, "phase") == null) {
            if (!os.children(active, SaveArchive.MAX_ENTRIES).isEmpty()) throw new Failure(Code.UNSAFE_WORKSPACE,"Unrecorded private preparation");
            setPhase("CLEAN", UUID.randomUUID().toString()); return;
        }
        String phase = phase();
        if (phase.startsWith("PREPARING:")) { clearPreparation(); return; }
        Journal prepared = RestoreJournalCodec.decode(readPrivateBytes(active, PREPARED, RestoreJournalCodec.MAX_BYTES));
        sameTarget(prepared.workspace.target); load(prepared.workspace);
        if (!inspect(prepared.workspace).equals(prepared.expectedLayout(0)))
            throw new Failure(Code.CONCURRENT_MUTATION, "Incomplete preparation is preserved for review");
        saveJournal(null, prepared);
    }
    @Override public LocalSnapshot capture(Target expected) throws IOException {
        stage = RootProtocol.DiagnosticStage.LAYOUT_SCAN;
        verifyBindingAndQuiescent(expected);
        if (captured != null) return captured;
        capturedScan = scan(session.filesHandle(), true);
        SaveArchive.requireCompleteRuns(capturedScan.tree.hashes().keySet());
        captured = new LocalSnapshot(capturedScan.tree, attributes(session.filesHandle()));
        return captured;
    }
    @Override public Workspace prepare(Target expected, String transactionId, Incoming incoming,
            Tree desired, Tree before) throws IOException {
        verifyBindingAndQuiescent(expected);
        if (incomingSource == null) throw new IOException("Missing verified incoming content");
        stage = RootProtocol.DiagnosticStage.PRIVATE_WORKSPACE;
        if (optional(session.workspaceHandle(), ACTIVE) != null) {
            openActive();
            if (!receiving && !cleanState()) throw new Failure(Code.RECOVERY_REQUIRED,"Private preparation already exists");
            for (String name : new String[]{RECEIPT,PREPARED,"terminal"}) removeRecordIfPresent(active,name);
        }
        if (capturedScan == null || !capturedScan.tree.equals(before))
            throw new IOException("Restore preparation has no matching captured source");
        Scan live = capturedScan;
        // The receipt is persisted before any live save mutation. All directories are created privately.
        if (optional(session.workspaceHandle(), ACTIVE) == null) os.mkdirExclusive(session.workspaceHandle(), ACTIVE, 0700);
        os.sync(session.workspaceHandle()); openActive(); setPhase("PREPARING", transactionId);
        boolean complete = false;
        try {
            for (String area : PRIVATE_AREAS) {
                if (optional(active, area) == null) os.mkdirExclusive(active, area, 0700);
                if (!areas.containsKey(area)) areas.put(area,os.openDirectory(active,area));
                privateRoot(areas.get(area));
                if (!os.children(areas.get(area),1).isEmpty()) throw new Failure(Code.UNSAFE_WORKSPACE,"Private area not empty");
            }
            stage = RootProtocol.DiagnosticStage.MUTATION_PREFLIGHT; preflight();
            stage = RootProtocol.DiagnosticStage.STAGE_PREPARE;
            Scan staged = writeTree(areas.get("incoming"), desired, incomingSource);
            // Old save bytes are never copied or retained for new overwrites.
            incomingSource = null;
            if (archiveInput != null) { archiveInput.close(); archiveInput=null; clearFresh(active,"archive-input",new int[]{0}); }
            receipt = new Receipt(transactionId, target, stable(os.stat(active)), areaIdentities(), live.ids, staged.ids, Collections.emptyMap());
            byte[] encoded = receipt.encode();
            writeNew(active, RECEIPT, encoded, null, 0);
            Workspace workspace = new Workspace(transactionId, SaveArchive.sha256(encoded), target, target.device);
            Journal prepared = new Journal(Journal.SCHEMA, workspace, before, desired, Phase.FORWARD, 0);
            writeNew(active, PREPARED, RestoreJournalCodec.encode(prepared), null, 0);
            sync(workspace); setPhase("PREPARED",transactionId); complete = true;
            return workspace;
        } finally {
            if (!complete) {
                // PREPARING is durable before copies and never survives journal publication.
                // This cleanup is limited to fresh, never-published private preparation data.
                try { clearPreparation(); } catch (IOException preserved) { os.sync(active); os.sync(session.workspaceHandle()); }
            }
        }
    }
    private Map<String,String> areaIdentities() throws IOException {
        Map<String,String> result = new TreeMap<>();
        for (String area : PRIVATE_AREAS) result.put(area, stable(os.stat(areas.get(area))));
        return result;
    }
    private void openActive() throws IOException {
        if (active == null) { active = os.openDirectory(session.workspaceHandle(), ACTIVE); privateRoot(active); }
    }
    private void load(Workspace workspace) throws IOException {
        sameTarget(workspace.target); openActive();
        byte[] bytes = readPrivateBytes(active, RECEIPT, MAX_RECEIPT);
        if (!SaveArchive.sha256(bytes).equals(workspace.identityFingerprint)) throw new Failure(Code.UNSAFE_WORKSPACE, "Private receipt changed");
        Receipt loaded = Receipt.decode(bytes);
        if (!loaded.transactionId.equals(workspace.transactionId) || !loaded.target.equals(target)
                || !loaded.active.equals(stable(os.stat(active)))) throw new Failure(Code.UNSAFE_WORKSPACE, "Private workspace changed");
        for (String name : loaded.areas.keySet()) {
            if (!areas.containsKey(name)) areas.put(name, os.openDirectory(active, name));
            privateRoot(areas.get(name));
            if (!loaded.areas.get(name).equals(stable(os.stat(areas.get(name))))) throw new Failure(Code.UNSAFE_WORKSPACE, "Private area changed");
        }
        receipt = loaded;
    }
    @Override public Layout inspect(Workspace workspace) throws IOException {
        verifyBindingAndQuiescent(target); load(workspace);
        Scan live = scan(session.filesHandle(), true), incoming = scan(areas.get("incoming"), false);
        Scan displaced = legacyScan("displaced"), backup = legacyScan("backup");
        known(live.ids, receipt.before, receipt.desired); known(incoming.ids, receipt.desired, null);
        known(displaced.ids, receipt.before, null); known(backup.ids, receipt.backup, null);
        if (!backup.ids.equals(receipt.backup)) throw new Failure(Code.UNSAFE_WORKSPACE, "Independent backup is incomplete");
        verifyBindingAndQuiescent(target);
        return new Layout(live.tree, incoming.tree, displaced.tree, backup.tree);
    }
    private Scan legacyScan(String name) throws IOException {
        if (areas.containsKey(name)) return scan(areas.get(name),false);
        if (optional(active,name) != null) throw new Failure(Code.UNSAFE_WORKSPACE,"Unexpected legacy data retained");
        return new Scan(new Tree(Collections.emptyMap()),Collections.emptyMap());
    }
    /** Normal completion hashes LIVE once. Full-area inspection is reserved for recovery. */
    @Override public void verifyFinal(Workspace workspace, Tree expected) throws IOException {
        verifyBindingAndQuiescent(target); load(workspace);
        stage = RootProtocol.DiagnosticStage.VERIFY;
        Scan live = scan(session.filesHandle(), true);
        if (!live.tree.equals(expected))
            throw new Failure(Code.CONCURRENT_MUTATION, "Restored save did not match the requested content");
        known(live.ids, receipt.before, receipt.desired);
        capturedScan = live;
        captured = new LocalSnapshot(live.tree, attributes(session.filesHandle()));
        finalReadback = true;
    }
    private static void known(Map<String,String> actual, Map<String,String> first, Map<String,String> second) throws IOException {
        for (Map.Entry<String,String> item : actual.entrySet())
            if (!item.getValue().equals(first.get(item.getKey())) && (second == null || !item.getValue().equals(second.get(item.getKey()))))
                throw new Failure(Code.CONCURRENT_MUTATION, "Unexpected physical file identity retained");
    }
    private MutationOs.Handle area(Area area) throws IOException {
        return area == Area.LIVE ? session.filesHandle() : areas.get(area == Area.INCOMING ? "incoming" : "displaced");
    }
    @Override public void move(Workspace workspace, Move move) throws IOException {
        verifyBindingAndQuiescent(target); load(workspace);
        if (move.to == Area.REMOVED) { removeOriginal(move); return; }
        MutationOs.Handle from = area(move.from), to = area(move.to);
        Stat source = os.childStat(from, move.topName); gameOwned(source);
        Entry expected = move.expectedEntry.entries.get(move.topName);
        if (expected == null || expected.directory() != source.directory()
                || (!expected.directory() && (!source.regular() || source.links != 1)))
            throw new Failure(Code.CONCURRENT_MUTATION, "Move source type changed");
        String sourceId = stable(source);
        Map<String,String> allowed = move.from == Area.INCOMING ? receipt.desired : receipt.before;
        if (!sourceId.equals(allowed.get(move.topName))
                && !(move.from == Area.LIVE && sourceId.equals(receipt.desired.get(move.topName))))
            throw new Failure(Code.CONCURRENT_MUTATION, "Move source identity changed");
        if (optional(to, move.topName) != null) throw new Failure(Code.CONCURRENT_MUTATION, "Move destination already exists");
        verifyBindingAndQuiescent(target);
        stage = move.to == Area.LIVE ? RootProtocol.DiagnosticStage.MOVE_NEW : RootProtocol.DiagnosticStage.MOVE_OLD;
        captured = null; capturedScan = null; finalReadback = false;
        os.renameNoReplace(from, move.topName, to, move.topName);
        try {
            os.sync(from); os.sync(to);
            if (!sourceId.equals(stable(os.childStat(to, move.topName))))
                throw new Failure(Code.CONCURRENT_MUTATION, "Raced move source is preserved");
            verifyBindingAndQuiescent(target);
        } catch (IOException failure) { unknown = true; throw failure; }
    }
    /** Delete only the captured allowlisted tree; never create an old-data/rollback copy. */
    private void removeOriginal(Move move) throws IOException {
        stage = RootProtocol.DiagnosticStage.MOVE_OLD;
        Scan current = scanPart(session.filesHandle(), move.topName);
        if (!current.tree.equals(move.expectedEntry)) throw new Failure(Code.CONCURRENT_MUTATION,"Overwrite source changed");
        known(current.ids,receipt.before,receipt.desired);
        List<String> paths = new ArrayList<>(move.expectedEntry.entries.keySet()); Collections.reverse(paths);
        captured = null; capturedScan = null; finalReadback = false;
        for (String path : paths) {
            verifyBindingAndQuiescent(target);
            try (Parent parent = parent(session.filesHandle(),path)) {
                Entry entry = move.expectedEntry.entries.get(path);
                verifyCleanupNode(parent.handle,basename(path),entry,receipt.before.get(path));
                if (entry.directory()) os.rmdir(parent.handle,basename(path)); else os.unlink(parent.handle,basename(path));
                os.sync(parent.handle);
            }
        }
    }
    @Override public void sync(Workspace workspace) throws IOException {
        session.revalidate(); if (active != null) os.sync(active);
        for (MutationOs.Handle directory : areas.values()) os.sync(directory);
        os.sync(session.filesHandle()); os.sync(session.workspaceHandle());
    }
    @Override public void saveJournal(Journal expected, Journal next) throws IOException {
        stage = RootProtocol.DiagnosticStage.JOURNAL_WRITE;
        sameTarget(next.workspace.target); load(next.workspace);
        try (RootMutationSession.PrivateFile old = session.readJournal()) {
            byte[] previous = old == null ? null : session.readPrivate(old, RestoreJournalCodec.MAX_BYTES);
            if (expected == null ? previous != null : previous == null || !Arrays.equals(previous, RestoreJournalCodec.encode(expected)))
                throw new Failure(Code.RECOVERY_REQUIRED, "Durable journal changed");
            try (RootMutationSession.PrivateFile written = session.replaceJournal(old, previous, RestoreJournalCodec.encode(next))) { }
        }
    }
    @Override public void cleanupTerminal(Journal terminal) throws IOException {
        stage = RootProtocol.DiagnosticStage.CLEANUP;
        verifyBindingAndQuiescent(target); load(terminal.workspace);
        session.cleanupJournalTemporaries();requireWorkspaceNames();
        Layout expected = terminal.expectedLayout(terminal.cursor);
        if (!finalReadback || captured == null || !captured.tree.equals(expected.live))
            verifyFinal(terminal.workspace, expected.live);
        byte[] terminalBytes = RestoreJournalCodec.encode(terminal);
        if (optional(active,"terminal") == null) writeNew(active,"terminal",terminalBytes,null,0);
        else if (!Arrays.equals(readPrivateBytes(active,"terminal",RestoreJournalCodec.MAX_BYTES),terminalBytes))
            throw new Failure(Code.UNSAFE_WORKSPACE,"Terminal receipt differs");
        // Deterministic private quarantine slots make each cleanup rename/remove resumable.
        cleanupArea("incoming",expected.incoming,receipt.desired);
        // Existing legacy originals/backups must remain untouched, even after recovery.
        if (!expected.displaced.entries.isEmpty() || !expected.backup.entries.isEmpty())
            throw new Failure(Code.RECOVERY_REQUIRED,"Legacy backup retained; cleanup remains pending");
        if (!os.children(areas.get("cleanup"),1).isEmpty()) throw new Failure(Code.CONCURRENT_MUTATION,"Unknown cleanup entry retained");
        setPhase("CLEAN",terminal.workspace.transactionId);
        try (RootMutationSession.PrivateFile journal = session.readJournal()) {
            if (journal == null || !Arrays.equals(session.readPrivate(journal,RestoreJournalCodec.MAX_BYTES),terminalBytes))
                throw new Failure(Code.RECOVERY_REQUIRED,"Terminal journal changed");
            session.removePrivate(journal);
        }
        os.sync(session.workspaceHandle());
    }
    private void cleanupArea(String areaName,Tree expected,Map<String,String> expectedIds)throws IOException {
        MutationOs.Handle area=areas.get(areaName), gc=areas.get("cleanup");
        List<String> paths=new ArrayList<>(expected.entries.keySet()); Collections.reverse(paths);
        for(String path:paths) {
            Entry entry=expected.entries.get(path); String token="delete-"+SaveArchive.sha256((areaName+"/"+path).getBytes(StandardCharsets.UTF_8));
            if(optional(gc,token)==null) {
                Parent source=null;
                try { source=parent(area,path); } catch(MutationOs.Failure missing){if(missing.error!=MutationOs.Error.MISSING)throw missing;}
                if(source!=null) try(Parent owned=source) {
                    if(optional(owned.handle,basename(path))!=null) {
                        verifyCleanupNode(owned.handle,basename(path),entry,expectedIds.get(path));
                        os.renameNoReplace(owned.handle,basename(path),gc,token);os.sync(owned.handle);os.sync(gc);
                    }
                }
            }
            if(optional(gc,token)!=null) {
                verifyCleanupNode(gc,token,entry,expectedIds.get(path));
                if(entry.directory())os.rmdir(gc,token);else os.unlink(gc,token);os.sync(gc);
            }
        }
        if(!os.children(area,1).isEmpty())throw new Failure(Code.CONCURRENT_MUTATION,"Unknown private cleanup content retained");
    }
    private void verifyCleanupNode(MutationOs.Handle parent,String leaf,Entry entry,String expectedId)throws IOException {
        Stat stat=os.childStat(parent,leaf);
        if(!stable(stat).equals(expectedId))throw new Failure(Code.CONCURRENT_MUTATION,"Unexpected private cleanup inode retained");
        try(MutationOs.Handle handle=entry.directory()?os.openDirectory(parent,leaf):os.openRegular(parent,leaf,false)) {
            if(!stable(os.stat(handle)).equals(expectedId)||!entry.attributes.equals(attributes(handle)))throw new Failure(Code.CONCURRENT_MUTATION,"Private cleanup metadata changed");
            if(entry.directory()) { if(!os.children(handle,1).isEmpty())throw new Failure(Code.CONCURRENT_MUTATION,"Private cleanup directory gained content"); }
            else if(!stat.regular()||stat.links!=1)
                throw new Failure(Code.CONCURRENT_MUTATION,"Private cleanup file type changed");
            same(stat,os.stat(handle));same(stat,os.childStat(parent,leaf));
        }
    }
    @Override public void discardUnjournaled(Workspace workspace)throws IOException {
        verifyBindingAndQuiescent(target);load(workspace);
        try(RootMutationSession.PrivateFile journal=session.readJournal()){if(journal!=null)throw new Failure(Code.RECOVERY_REQUIRED,"Journal already published");}
        // Complete PREPARED data has a before-state receipt; never discard if live differs.
        Journal prepared=RestoreJournalCodec.decode(readPrivateBytes(active,PREPARED,RestoreJournalCodec.MAX_BYTES));
        if(!inspect(workspace).equals(prepared.expectedLayout(0)))throw new Failure(Code.CONCURRENT_MUTATION,"Preparation changed");
        setPhase("PREPARING",workspace.transactionId);clearPreparation();
    }
    private boolean cleanState()throws IOException {
        if(optional(active,"phase")==null)return false;
        if(!phase().startsWith("CLEAN:"))return false;
        for(String area:LEGACY_AREAS)if(optional(active,area)!=null)try(MutationOs.Handle dir=os.openDirectory(active,area)){privateRoot(dir);if(!os.children(dir,1).isEmpty())return false;}
        for(String name:os.children(active,32))if(!Arrays.asList("phase",RECEIPT,PREPARED,"terminal","incoming","displaced","backup","cleanup").contains(name))return false;
        return true;
    }
    private String phase()throws IOException {
        byte[] bytes=readPrivateBytes(active,"phase",4096);
        String value=new String(bytes,StandardCharsets.US_ASCII);
        String suffix=":"+target.bindingFingerprint+":"+stable(os.stat(active));
        if(!value.endsWith(suffix)||!value.matches("(PREPARING|PREPARED|CLEAN):[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}:[0-9a-f]{64}:[0-9:]+"))throw new Failure(Code.UNSAFE_WORKSPACE,"Invalid private phase receipt");
        return value;
    }
    private void setPhase(String state,String id)throws IOException {
        String value=state+":"+id+":"+target.bindingFingerprint+":"+stable(os.stat(active));
        recoverPhaseTemporary();
        String temporary="phase-new";
        writeNew(active,temporary,value.getBytes(StandardCharsets.US_ASCII),null,0);
        if(optional(active,"phase")!=null)readPrivateBytes(active,"phase",4096);
        os.rename(active,temporary,active,"phase");os.sync(active);
    }
    private void recoverPhaseTemporary()throws IOException {
        if(optional(active,"phase-new")==null)return;
        String value=new String(readPrivateBytes(active,"phase-new",4096),StandardCharsets.US_ASCII);
        String suffix=":"+target.bindingFingerprint+":"+stable(os.stat(active));
        if(!value.endsWith(suffix)||!value.matches("(PREPARING|PREPARED|CLEAN):[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}:[0-9a-f]{64}:[0-9:]+"))throw new Failure(Code.UNSAFE_WORKSPACE,"Invalid temporary phase receipt");
        if(optional(active,"phase")==null) {
            if(!value.startsWith("PREPARING:")||os.children(active,3).size()!=1)throw new Failure(Code.UNSAFE_WORKSPACE,"Ambiguous initial preparation");
            os.rename(active,"phase-new",active,"phase");
        }else{phase();os.unlink(active,"phase-new");}
        os.sync(active);
    }
    private void requireWorkspaceNames()throws IOException {
        for(String name:os.children(session.workspaceHandle(),SaveArchive.MAX_ENTRIES))if(!name.equals("lock")&&!name.equals(ACTIVE)&&!name.equals("journal"))throw new Failure(Code.UNSAFE_WORKSPACE,"Unknown workspace entry retained");
    }
    private void clearPreparation()throws IOException {
        try(RootMutationSession.PrivateFile journal=session.readJournal()){if(journal!=null)throw new Failure(Code.RECOVERY_REQUIRED,"Published journal cannot be discarded");}
        if(!phase().startsWith("PREPARING:"))throw new Failure(Code.UNSAFE_WORKSPACE,"Preparation phase not proven");
        session.cleanupJournalTemporaries();requireWorkspaceNames();
        recoverPhaseTemporary();
        if(archiveInput!=null){archiveInput.close();archiveInput=null;}
        int[] cleanupCount={0};
        for(String name:os.children(active,SaveArchive.MAX_ENTRIES)) {
            if(name.equals("phase"))continue;
            if(!Arrays.asList("incoming","displaced","backup","cleanup","archive-input",RECEIPT,PREPARED,"probe-a","probe-b","probe-c").contains(name))
                throw new Failure(Code.UNSAFE_WORKSPACE,"Unknown preparation entry retained");
            if (name.equals("backup") || name.equals("displaced")) {
                try (MutationOs.Handle retained = os.openDirectory(active,name)) {
                    if (!os.children(retained,1).isEmpty()) throw new Failure(Code.RECOVERY_REQUIRED,"Existing backup retained");
                }
            }
            clearFresh(active,name,cleanupCount);
        }
        for(MutationOs.Handle handle:areas.values())handle.close();areas.clear();
        setPhase("CLEAN",UUID.randomUUID().toString());
    }
    private void clearFresh(MutationOs.Handle parent,String name,int[] count)throws IOException {
        if(++count[0]>SaveArchive.MAX_ENTRIES*4)throw new IOException("Preparation cleanup limit");
        Stat stat=os.childStat(parent,name);
        if(stat.device!=target.device||(stat.uid!=0&&stat.uid!=binding.uid))throw new Failure(Code.UNSAFE_WORKSPACE,"Unexpected preparation owner");
        if(stat.directory()) {
            try(MutationOs.Handle dir=os.openDirectory(parent,name)){same(stat,os.stat(dir));for(String child:os.children(dir,SaveArchive.MAX_ENTRIES))clearFresh(dir,child,count);}
            os.rmdir(parent,name);
        } else if(stat.regular()&&stat.links==1){os.unlink(parent,name);}else throw new Failure(Code.UNSAFE_WORKSPACE,"Unsafe preparation entry retained");
        os.sync(parent);
    }
    private void removeRecordIfPresent(MutationOs.Handle parent,String name)throws IOException {
        if(optional(parent,name)!=null){readPrivateBytes(parent,name,RestoreJournalCodec.MAX_BYTES);os.unlink(parent,name);os.sync(parent);}
    }
    private void preflight() throws IOException {
        // Same actual filesystem and directory publication semantics, before any live mutation.
        os.mkdirExclusive(active, "probe-a", 0700); os.mkdirExclusive(active, "probe-b", 0700);
        boolean refused = false;
        try { os.renameNoReplace(active, "probe-a", active, "probe-b"); }
        catch (MutationOs.Failure expected) { if (expected.error != MutationOs.Error.EXISTS) throw expected; refused = true; }
        if (!refused) throw new RootFailure(RootProtocol.Status.MUTATION_UNAVAILABLE);
        os.renameNoReplace(active, "probe-a", active, "probe-c");
        os.rmdir(active, "probe-b"); os.rmdir(active, "probe-c"); os.sync(active);
    }
    private Scan writeTree(MutationOs.Handle root, Tree tree, IncomingSource source) throws IOException {
        Map<String,String> ids = new TreeMap<>();
        for (Map.Entry<String,Entry> item : tree.entries.entrySet()) {
            String path = item.getKey(); Entry entry = item.getValue();
            try (Parent parent = parent(root, path)) {
                if (entry.directory()) os.mkdirExclusive(parent.handle, basename(path), 0700);
                else {
                    byte[] bytes = source.read(path);
                    if (bytes == null || bytes.length != entry.content.bytes || !SaveArchive.sha256(bytes).equals(entry.content.sha256))
                        throw new Failure(Code.CONCURRENT_MUTATION, "Copy source differs");
                    ids.put(path, stable(writeNew(parent.handle, basename(path), bytes, entry.attributes, entry.content.modifiedMillis)));
                }
            }
        }
        // Parent directory metadata is applied after population, all while still private.
        List<String> names = new ArrayList<>(tree.entries.keySet()); Collections.reverse(names);
        for (String name : names) if (tree.entries.get(name).directory()) {
            try (Parent parent = parent(root, name); MutationOs.Handle directory = os.openDirectory(parent.handle, basename(name))) {
                apply(directory, tree.entries.get(name).attributes); os.sync(directory);
                ids.put(name, stable(os.stat(directory)));
            }
        }
        os.sync(root);
        return new Scan(tree, ids);
    }
    private Stat writeNew(MutationOs.Handle parent, String name, byte[] bytes, Attributes attrs, long modified) throws IOException {
        if (bytes == null || bytes.length > Math.max(SaveArchive.MAX_BYTES, RestoreJournalCodec.MAX_BYTES)) throw new IOException("Copy exceeds limit");
        Stat written;
        try (MutationOs.Handle file = os.createExclusive(parent, name, 0600)) {
            Stat created = os.stat(file);
            if (!created.regular() || created.uid != 0 || created.links != 1 || created.device != target.device) throw new IOException("Unsafe private file");
            long offset = 0;
            while (offset < bytes.length) { int n = os.write(file, bytes, (int)offset, bytes.length-(int)offset, offset); if (n <= 0) throw new IOException("Short private write"); offset += n; }
            if (attrs != null) { apply(file, attrs); os.setModifiedTime(file, modified); }
            os.sync(file);
            written = os.stat(file);
        }
        os.sync(parent);
        return written;
    }
    private void apply(MutationOs.Handle handle, Attributes attrs) throws IOException {
        if (attrs.uid != binding.uid || (attrs.mode & 0002) != 0 || ((attrs.mode & 0020) != 0 && attrs.gid != binding.uid))
            throw new RootFailure(RootProtocol.Status.ATTRIBUTES_UNSUPPORTED);
        stage = RootProtocol.DiagnosticStage.ATTRIBUTES_APPLY;
        os.chown(handle, attrs.uid, attrs.gid); os.chmod(handle, attrs.mode);
        os.selinux(handle, (attrs.selinux + "\0").getBytes(StandardCharsets.US_ASCII));
        if (!attrs.equals(attributes(handle))) throw new RootFailure(RootProtocol.Status.ATTRIBUTES_UNSUPPORTED);
    }
    private Attributes attributes(MutationOs.Handle handle) throws IOException {
        RootProtocol.DiagnosticStage previous=stage;stage=RootProtocol.DiagnosticStage.ATTRIBUTES_APPLY;
        Stat stat = os.stat(handle); byte[] raw = os.selinux(handle);
        if (raw == null || raw.length == 0 || raw.length > 513) throw new RootFailure(RootProtocol.Status.ATTRIBUTES_UNSUPPORTED);
        int length = raw.length; if (raw[length-1] == 0) length--;
        String label = new String(raw, 0, length, StandardCharsets.US_ASCII);
        if (!Arrays.equals(label.getBytes(StandardCharsets.US_ASCII), Arrays.copyOf(raw,length))) throw new IOException("Invalid local label");
        Attributes result=new Attributes(stat.uid, stat.gid, stat.mode & 0777, label);stage=previous;return result;
    }
    private static final class Scan {
        final Tree tree; final Map<String,String> ids;
        Scan(Tree tree, Map<String,String> ids) { this.tree=tree; this.ids=ids; }
        boolean same(Scan other) { return tree.equals(other.tree) && ids.equals(other.ids); }
    }
    private Scan scan(MutationOs.Handle root, boolean live) throws IOException {
        return scan(root, live, null);
    }
    private Scan scan(MutationOs.Handle root, boolean live, Map<String,byte[]> content) throws IOException {
        if (live) {
            SaveDirectoryPolicy.Content layout = session.rootContent();
            if (!layout.transferable()) throw new RootFailure(RootProtocol.Status.UNRECOGNIZED_LAYOUT);
        } else privateRoot(root);
        Map<String,Entry> entries = new TreeMap<>(); Map<String,String> ids = new TreeMap<>();
        walk(root,"",entries,ids,new HashSet<>(),new long[]{0},new int[]{0},live,content);
        return new Scan(new Tree(entries), ids);
    }
    private Scan scanPart(MutationOs.Handle root, String top) throws IOException {
        Map<String,Entry> entries = new TreeMap<>(); Map<String,String> ids = new TreeMap<>();
        readEntry(root, top, top, entries, ids, new HashSet<>(), new long[]{0}, new int[]{0}, null);
        return new Scan(new Tree(entries), ids);
    }
    private void walk(MutationOs.Handle directory, String prefix, Map<String,Entry> entries, Map<String,String> ids,
            Set<String> folded, long[] bytes, int[] count, boolean liveRoot, Map<String,byte[]> content) throws IOException {
        Stat before = os.stat(directory);
        List<String> names = new ArrayList<>(os.children(directory, SaveArchive.MAX_ENTRIES)); Collections.sort(names);
        for (String leaf : names) {
            String path = prefix.isEmpty() ? leaf : prefix + "/" + leaf;
            if (liveRoot && !SaveArchive.included(path)) continue;
            readEntry(directory,leaf,path,entries,ids,folded,bytes,count,content);
        }
        if (!before.identity().equals(os.stat(directory).identity())) throw new Failure(Code.CONCURRENT_MUTATION,"Directory changed during scan");
    }
    private void readEntry(MutationOs.Handle parent, String leaf, String path, Map<String,Entry> entries,
            Map<String,String> ids, Set<String> folded, long[] total, int[] count, Map<String,byte[]> content) throws IOException {
        if (!SaveArchive.safeName(path) || !folded.add(path.toLowerCase(Locale.ROOT)) || ++count[0] > SaveArchive.MAX_ENTRIES)
            throw new RootFailure(RootProtocol.Status.UNSAFE_PATH);
        Stat before = os.childStat(parent,leaf); gameOwned(before);
        if (before.directory()) {
            try (MutationOs.Handle directory = os.openDirectory(parent,leaf)) {
                same(before,os.stat(directory)); entries.put(path,new Entry(attributes(directory),null)); ids.put(path,stable(before));
                walk(directory,path,entries,ids,folded,total,count,false,content);
            }
        } else {
            if (!before.regular() || before.links != 1 || before.size < 0 || before.size > SaveArchive.MAX_BYTES-total[0])
                throw new RootFailure(RootProtocol.Status.UNSAFE_PATH);
            try (MutationOs.Handle file = os.openRegular(parent,leaf,false)) {
                same(before,os.stat(file)); byte[] bytes=read(file,(int)before.size); same(before,os.stat(file));
                total[0]+=bytes.length; entries.put(path,new Entry(attributes(file),new Content(SaveArchive.sha256(bytes),bytes.length, Math.max(0,before.modifiedSeconds*1000)))); ids.put(path,stable(before));
                if (content != null) content.put(path, bytes);
            }
        }
        same(before,os.childStat(parent,leaf));
    }
    private byte[] readPrivateBytes(MutationOs.Handle parent,String name,int maximum) throws IOException {
        try (MutationOs.Handle file = os.openRegular(parent,name,false)) {
            Stat stat=os.stat(file);
            if (stat.uid!=0 || !stat.regular() || stat.links!=1 || (stat.mode&0777)!=0600 || stat.size<0 || stat.size>maximum)
                throw new Failure(Code.UNSAFE_WORKSPACE,"Private record invalid");
            byte[] result=read(file,(int)stat.size); same(stat,os.stat(file)); same(stat,os.childStat(parent,name)); return result;
        }
    }
    private byte[] read(MutationOs.Handle file,int size) throws IOException {
        byte[] bytes=new byte[size]; int offset=0;
        while(offset<size) { int n=os.read(file,bytes,offset,size-offset,offset); if(n<=0)throw new IOException("Short bounded read");offset+=n; }
        byte[] extra=new byte[1]; if(os.read(file,extra,0,1,size)>0)throw new IOException("File grew"); return bytes;
    }
    private final class Parent implements AutoCloseable {
        final MutationOs.Handle handle; final boolean own;
        Parent(MutationOs.Handle handle,boolean own){this.handle=handle;this.own=own;}
        public void close()throws IOException {if(own)handle.close();}
    }
    private Parent parent(MutationOs.Handle root,String path)throws IOException {
        if(!SaveArchive.safeName(path))throw new IOException("Invalid save path");
        int slash=path.lastIndexOf('/'); if(slash<0)return new Parent(root,false);
        MutationOs.Handle current=root; boolean own=false;
        try {
            for(String leaf:path.substring(0,slash).split("/")) {
                MutationOs.Handle next=os.openDirectory(current,leaf); Stat stat=os.stat(next);
                if(!stat.directory() || stat.device!=target.device || (stat.uid!=0&&stat.uid!=binding.uid)){next.close();throw new IOException("Unsafe directory");}
                if(own)current.close(); current=next;own=true;
            }
            Parent result=new Parent(current,true);own=false;return result;
        } finally {if(own)current.close();}
    }
    private static String basename(String path){return path.substring(path.lastIndexOf('/')+1);}
    private Stat optional(MutationOs.Handle parent,String leaf)throws IOException {
        try{return os.childStat(parent,leaf);}catch(MutationOs.Failure missing){if(missing.error==MutationOs.Error.MISSING)return null;throw missing;}
    }
    private void privateRoot(MutationOs.Handle directory)throws IOException {
        Stat stat=os.stat(directory);if(!stat.directory()||stat.uid!=0||stat.gid!=0||(stat.mode&07777)!=0700||stat.device!=target.device)
            throw new Failure(Code.UNSAFE_WORKSPACE,"Private directory invalid");
    }
    private void gameOwned(Stat stat)throws IOException {
        if(stat.device!=target.device||stat.uid!=binding.uid||(stat.mode&07002)!=0||((stat.mode&0020)!=0&&stat.gid!=binding.uid))
            throw new RootFailure(RootProtocol.Status.UNSAFE_PATH);
    }
    private static void same(Stat a,Stat b)throws IOException {if(!a.identity().equals(b.identity()))throw new Failure(Code.CONCURRENT_MUTATION,"Entry changed");}
    private static String stable(Stat stat){return stat.device+":"+stat.inode+":"+stat.uid+":"+stat.gid+":"+stat.mode;}
    @Override public void close()throws IOException {
        closed=true; IOException failure=null;
        if(archiveInput!=null)try{archiveInput.close();}catch(IOException e){failure=e;}
        for(MutationOs.Handle area:areas.values())try{area.close();}catch(IOException e){failure=e;}
        if(active!=null)try{active.close();}catch(IOException e){failure=e;}
        try{session.close();}catch(IOException e){failure=e;} if(failure!=null)throw failure;
    }
    private static final class Receipt {
        final String transactionId,active;final Target target;
        final Map<String,String> areas,before,desired,backup;
        Receipt(String id,Target target,String active,Map<String,String> areas,Map<String,String> before,Map<String,String> desired,Map<String,String> backup){
            this.transactionId=id;this.target=target;this.active=active;this.areas=areas;this.before=before;this.desired=desired;this.backup=backup;
        }
        byte[] encode()throws IOException {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
            out.writeInt(0x50525231);out.writeUTF(transactionId);out.writeUTF(target.bindingFingerprint);out.writeUTF(target.canonicalDataDir);
            out.writeInt(target.gameUid);out.writeLong(target.device);out.writeLong(target.dataDirInode);out.writeLong(target.filesInode);out.writeUTF(active);
            for(Map<String,String> map:Arrays.asList(areas,before,desired,backup)){out.writeInt(map.size());for(Map.Entry<String,String> item:map.entrySet()){out.writeUTF(item.getKey());out.writeUTF(item.getValue());if(bytes.size()>MAX_RECEIPT)throw new IOException("Receipt too large");}}
            out.flush();return bytes.toByteArray();
        }
        static Receipt decode(byte[] bytes)throws IOException {
            if(bytes.length>MAX_RECEIPT)throw new IOException("Receipt too large");DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
            if(in.readInt()!=0x50525231)throw new IOException("Invalid receipt");String id=in.readUTF();
            if(!id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IOException("Invalid transaction identity");
            Target target=new Target(in.readUTF(),in.readUTF(),in.readInt(),in.readLong(),in.readLong(),in.readLong());String active=in.readUTF();
            List<Map<String,String>> maps=new ArrayList<>();
            for(int index=0;index<4;index++){int count=in.readInt();if(count<0||count>SaveArchive.MAX_ENTRIES)throw new IOException("Invalid receipt count");Map<String,String> map=new TreeMap<>();
                for(int i=0;i<count;i++){String key=in.readUTF(),value=in.readUTF();if(key.length()>1024||value.length()>160||map.put(key,value)!=null)throw new IOException("Invalid receipt entry");}maps.add(map);}
            if(in.read()!=-1||(!maps.get(0).keySet().equals(new TreeSet<>(Arrays.asList(PRIVATE_AREAS))) && !maps.get(0).keySet().equals(new TreeSet<>(Arrays.asList(LEGACY_AREAS)))))throw new IOException("Invalid receipt fields");
            return new Receipt(id,target,active,maps.get(0),maps.get(1),maps.get(2),maps.get(3));
        }
    }
}
