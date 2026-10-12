package io.github.xyz1001.pdsync.bridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import io.github.xyz1001.pdsync.bridge.FileFailureDetails;
import io.github.xyz1001.pdsync.bridge.GameGuard;
import io.github.xyz1001.pdsync.bridge.RestoreWorkspace;
import io.github.xyz1001.pdsync.bridge.SafeSaveTree;
import io.github.xyz1001.pdsync.bridge.SaveArchive;
import io.github.xyz1001.pdsync.bridge.SaveDirectoryPolicy;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;


public final class SaveBridgeProvider extends ContentProvider {
    private static final Object LOCK = new Object();
    public static final String PERMISSION = "io.github.xyz1001.pdsync.android.ACCESS_BRIDGE";
    private File area;
    private File backups;
    private GameGuard guard;
    private File pending;
    private FileFailureDetails primaryFailure;
    private FileFailureDetails.Stage stage;

    @Override // android.content.ContentProvider
    public boolean onCreate() {
        this.guard = new GameGuard(getContext());
        this.area = new File(getContext().getFilesDir(), "save-bridge-v1");
        this.backups = new File(this.area, "backups");
        this.pending = new File(this.area, "pending.properties");
        return true;
    }

    private int authenticate() {
        Context context = getContext();
        int callingUid = Binder.getCallingUid();
        if (callingUid == Process.myUid()) {
            throw new SecurityException("Shared-UID callers are never authorized");
        }
        context.enforceCallingPermission("io.github.xyz1001.pdsync.android.ACCESS_BRIDGE", "Main-app signature permission required");
        MainCallerAuthenticator.authenticateProvider(context, getCallingPackage(), callingUid);
        return callingUid;
    }

    @Override // android.content.ContentProvider
    public Bundle call(String str, String str2, Bundle bundle) {
        int iAuthenticate = authenticate();
        if (str2 != null) {
            throw new IllegalArgumentException("No path or argument is accepted");
        }
        long jClearCallingIdentity = Binder.clearCallingIdentity();
        try {
            synchronized (LOCK) {
                this.stage = FileFailureDetails.Stage.HELPER_WORKSPACE;
                this.primaryFailure = null;
                try {
                    try {
                        prepareArea();
                        if ("inspect".equals(str)) {
                            noExtras(bundle);
                            return inspect();
                        }
                        if ("export".equals(str)) {
                            noExtras(bundle);
                            requireNoPending();
                            return exportSave();
                        }
                        if ("restore".equals(str)) {
                            requireNoPending();
                            return restoreSave(bundle, iAuthenticate);
                        }
                        if (!"recover".equals(str)) {
                            throw new IllegalArgumentException("Unknown bridge method");
                        }
                        noExtras(bundle);
                        return recover();
                    } catch (BridgeFailure e) {
                        return failure(e.code, e.getMessage(), e);
                    }
                } catch (ErrnoException | IOException e2) {
                    return failure("SAVE_IO_ERROR", "Save transfer failed safely; file operation details are available", e2);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(jClearCallingIdentity);
        }
    }

    private static void noExtras(Bundle bundle) {
        if (bundle != null && !bundle.isEmpty()) {
            throw new IllegalArgumentException("No extras accepted");
        }
    }

    private void prepareArea() throws IOException {
        SafeSaveTree.checkDirectory(getContext().getFilesDir());
        if (!this.backups.isDirectory() && !this.backups.mkdirs()) {
            throw new IOException("Cannot create helper-private workspace");
        }
        SafeSaveTree.checkDirectory(this.area);
        SafeSaveTree.checkDirectory(this.backups);
        cleanupFinalizedBackups();
        if (!pendingPresent() && Files.exists(new File(this.area, "restore-workspace.properties").toPath(), LinkOption.NOFOLLOW_LINKS)) {
            this.stage = FileFailureDetails.Stage.CLEANUP;
            restoreWorkspace().cleanupRecorded();
        }
        if (pendingPresent()) {
            return;
        }
        File[] fileArrListFiles = this.area.listFiles();
        if (fileArrListFiles == null) {
            throw new IOException("Cannot inspect helper-private workspace");
        }
        for (File file : fileArrListFiles) {
            if (file.getName().startsWith("work-")) {
                deleteTree(file);
            }
        }
    }

    
    public boolean pendingPresent() {
        File file = this.pending;
        return file != null && Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    private Bundle inspect() throws IOException {
        GameGuard.Observation observationObserve = this.guard.observe();
        Bundle bundleOk = ok();
        bundleOk.putBoolean("ready", (observationObserve.gameForeground || pendingPresent()) ? false : true);
        describeBinding(bundleOk);
        bundleOk.putString("gameVersion", observationObserve.game.versionName);
        bundleOk.putLong("gameVersionCode", observationObserve.game.getLongVersionCode());
        bundleOk.putBoolean("gameForeground", observationObserve.gameForeground);
        bundleOk.putBoolean("gameProcessPresent", observationObserve.gameProcessPresent);
        bundleOk.putBoolean("observationReliable", true);
        bundleOk.putBoolean("launchExclusionGuaranteed", false);
        if (pendingPresent()) {
            bundleOk.putBoolean("ok", false);
            bundleOk.putString("code", "RECOVERY_REQUIRED");
            bundleOk.putBoolean("recoveryRequired", true);
            bundleOk.putString("message", "A previous restore was interrupted. Return from the game and recover the original backup before continuing");
            return bundleOk;
        }
        if (observationObserve.gameForeground) {
            bundleOk.putBoolean("ok", false);
            bundleOk.putString("code", "GAME_FOREGROUND");
            bundleOk.putString("message", "Return from the game before syncing its saves");
            return bundleOk;
        }
        File fileRoot = this.guard.root();
        SafeSaveTree.checkDirectory(fileRoot.getParentFile());
        SaveDirectoryPolicy.Content content = SaveDirectoryPolicy.content(fileRoot);
        boolean z = content.state != SaveDirectoryPolicy.ContentState.MISSING_DIRECTORY;
        if (z) {
            SafeSaveTree.checkDirectory(fileRoot);
        }
        bundleOk.putBoolean("saveDirectoryPresent", z);
        bundleOk.putBoolean("firstLaunchNeeded", !z);
        describeContent(bundleOk, content);
        bundleOk.putBoolean("transferReady", content.transferable());
        if (!z) {
            bundleOk.putString("message", "Ready to launch. Open the game once to create its save directory before syncing");
            return bundleOk;
        }
        if (!content.transferable()) {
            bundleOk.putString("code", "UNRECOGNIZED_SAVE_LAYOUT");
            bundleOk.putString("message", "Game launch is available, but its save layout is not recognized; no empty snapshot will be assumed");
        }
        return bundleOk;
    }

    private File transferRoot() throws IOException {
        File fileRoot = this.guard.root();
        SafeSaveTree.checkDirectory(fileRoot.getParentFile());
        SaveDirectoryPolicy.requirePresent(fileRoot);
        SafeSaveTree.checkDirectory(fileRoot);
        return fileRoot;
    }

    private void requireNoPending() throws IOException {
        if (pendingPresent()) {
            throw new BridgeFailure("RECOVERY_REQUIRED", "A previous restore was interrupted; recover its original backup before continuing");
        }
    }

    private Bundle exportSave() throws IOException, ErrnoException {
        File fileWorkspace = workspace();
        try {
            try {
                this.stage = FileFailureDetails.Stage.CAPTURE;
                File fileMkdir = mkdir(new File(fileWorkspace, "tree"));
                SafeSaveTree safeSaveTree = new SafeSaveTree(transferRoot(), this.guard);
                SafeSaveTree.State stateStable = safeSaveTree.stable(fileMkdir, true);
                this.stage = FileFailureDetails.Stage.WRITE_BACKUP;
                File file = new File(fileWorkspace, "export.zip");
                writeArchive(fileMkdir, stateStable.inventory, file);
                safeSaveTree.unchanged(stateStable, true);
                ParcelFileDescriptor parcelFileDescriptorOpen = ParcelFileDescriptor.open(file, 268435456);
                if (!file.delete()) {
                    parcelFileDescriptorOpen.close();
                    throw new IOException("Cannot unlink exported temporary archive");
                }
                Bundle bundleDescribe = describe(stateStable.inventory);
                describeContent(bundleDescribe, stateStable.content);
                bundleDescribe.putParcelable("archive", parcelFileDescriptorOpen);
                return bundleDescribe;
            } catch (IOException e) {
                rememberFailure(e);
                throw e;
            }
        } finally {
            deleteTree(fileWorkspace);
        }
    }

    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    private Bundle restoreSave(Bundle extras, int callerUid) throws IOException, ErrnoException {
        if (extras == null || extras.size() != 2 || !extras.containsKey("archive") || !extras.containsKey("expectedFingerprint"))
            throw new IllegalArgumentException("restore requires exactly archive and expectedFingerprint");
        String expected = extras.getString("expectedFingerprint", "");
        if (!expected.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Expected content fingerprint is required");
        Object value = extras.getParcelable("archive");
        if (!(value instanceof ParcelFileDescriptor)) throw new IllegalArgumentException("Read-only archive descriptor is required");
        try (ParcelFileDescriptor descriptor = (ParcelFileDescriptor) value;
             RestoreSession session = newRestoreSession()) {
            boolean[] mutated = {false};
            boolean journalWritten = false;
            String backupId = null;
            SafeSaveTree tree = null;
            Map<String,String> currentHashes = null;
            try {
                this.stage = FileFailureDetails.Stage.READ_ARCHIVE;
                session.handle.validate(); this.guard.requireQuiescent();
                File incoming = mkdir(new File(session.handle.file(), "incoming"));
                StructStat descriptorBefore = Os.fstat(descriptor.getFileDescriptor());
                int access = Os.fcntlInt(descriptor.getFileDescriptor(), OsConstants.F_GETFL, 0) & OsConstants.O_ACCMODE;
                if (!OsConstants.S_ISREG(descriptorBefore.st_mode) || descriptorBefore.st_nlink != 1
                        || descriptorBefore.st_uid != callerUid || access != OsConstants.O_RDONLY
                        || descriptorBefore.st_size < 0 || descriptorBefore.st_size > SaveArchive.MAX_BYTES)
                    throw new BridgeFailure("UNSAFE_ARCHIVE_DESCRIPTOR", "Restore archive must be a bounded, read-only regular file owned by the main app");
                Os.lseek(descriptor.getFileDescriptor(), 0, OsConstants.SEEK_SET);
                SaveArchive.Inventory requested;
                try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.getFileDescriptor()))) {
                    requested = SaveArchive.extract(input, incoming);
                }
                if (!SafeSaveTree.identity(descriptorBefore).equals(SafeSaveTree.identity(Os.fstat(descriptor.getFileDescriptor()))))
                    throw new BridgeFailure("ARCHIVE_CHANGED", "Restore archive changed while reading");
                session.handle.validate(); this.stage = FileFailureDetails.Stage.CAPTURE;
                File original = mkdir(new File(session.handle.file(), "original"));
                tree = new SafeSaveTree(transferRoot(), this.guard);
                SafeSaveTree.State captured = tree.stable(original, true);
                if (!expected.equals(captured.inventory.fingerprint()))
                    throw new BridgeFailure("STALE_EXPORT", "Game save no longer matches the exported copy; refresh before restoring");
                if (captured.inventory.hashes.equals(requested.hashes)) {
                    Bundle result = describe(requested); result.putBoolean("unchanged", true); return result;
                }
                backupId = UUID.randomUUID().toString();
                this.stage = FileFailureDetails.Stage.WRITE_BACKUP; session.handle.validate();
                writeArchive(original, captured.inventory, new File(this.backups, backupId + ".zip"));
                syncDirectory(this.backups); tree.unchanged(captured, true);
                this.stage = FileFailureDetails.Stage.WRITE_JOURNAL;
                writePending(backupId, captured.inventory.fingerprint());
                journalWritten = true;
                currentHashes = new TreeMap<>(captured.inventory.hashes);
                replace(tree, incoming, requested, session.handle, "removed", true, currentHashes, mutated);
                this.stage = FileFailureDetails.Stage.FINALIZE;
                finalizeBackup(backupId); session.committed = true;
                Bundle result = describe(requested); describeFinalizedBackup(result, backupId); return result;
            } catch (IOException | ErrnoException error) {
                rememberFailure(error);
                if (session.committed || (backupId != null && !pendingPresent() && finalizedMarkerValid(backupId))) {
                    session.committed = true;
                    throw new BridgeFailure("RESTORE_COMMITTED_CLEANUP_PENDING", "Save was verified and committed; retained temporary backup cleanup will retry", error);
                }
                if (pendingPresent() && !mutated[0]) {
                    Properties journal = readPending();
                    if (backupId == null || !backupId.equals(journal.getProperty("backupId")) || !this.pending.delete())
                        throw new BridgeFailure("RECOVERY_REQUIRED", "Save was not modified, but its unused restore journal could not be cleared", error);
                    syncDirectory(this.area); deleteUnusedBackup(backupId); throw error;
                }
                if (pendingPresent()) {
                    try {
                        this.guard.requireQuiescent();
                        if (tree == null || currentHashes == null || !currentHashes.equals(tree.scan(null, false).inventory.hashes))
                            throw new BridgeFailure("GAME_SAVE_RACE", "A background writer changed the save; automatic rollback would overwrite it");
                        restorePending(session.handle, "rollback"); session.committed = true;
                    } catch (IOException | ErrnoException rollbackError) {
                        error.addSuppressed(rollbackError);
                        throw new BridgeFailure("RECOVERY_REQUIRED", "Restore and rollback could not finish; original backup retained", error);
                    }
                    throw new BridgeFailure("RESTORE_ROLLED_BACK", "Restore failed; the original save was restored", error);
                }
                deleteUnusedBackup(backupId); throw error;
            } finally {
                // Once a journal was written, retain its backup across uncertain fsync outcomes.
                if (!journalWritten && !pendingPresent() && !session.committed) deleteUnusedBackup(backupId);
            }
        }
    }

    private Bundle recover() throws IOException, ErrnoException {
        if (!pendingPresent()) {
            throw new BridgeFailure("NO_PENDING_RECOVERY", "There is no interrupted restore to recover");
        }
        this.stage = FileFailureDetails.Stage.READ_ARCHIVE;
        validatePendingBackup();
        this.stage = FileFailureDetails.Stage.CLEANUP;
        restoreWorkspace().cleanupRecorded();
        RestoreSession restoreSessionNewRestoreSession = newRestoreSession();
        try {
            try {
                Bundle bundleRestorePending = restorePending(restoreSessionNewRestoreSession.handle, "recovery");
                restoreSessionNewRestoreSession.committed = true;
                if (restoreSessionNewRestoreSession != null) {
                    restoreSessionNewRestoreSession.close();
                }
                return bundleRestorePending;
            } catch (ErrnoException | IOException e) {
                rememberFailure(e);
                throw e;
            }
        } catch (Throwable th) {
            if (restoreSessionNewRestoreSession != null) {
                try {
                    restoreSessionNewRestoreSession.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    private Bundle restorePending(RestoreWorkspace.Handle handle, String str) throws IOException, ErrnoException {
        this.guard.requireQuiescent();
        handle.validate();
        this.stage = FileFailureDetails.Stage.READ_ARCHIVE;
        Properties pending = readPending();
        String property = pending.getProperty("backupId", "");
        if (!validBackupId(property)) {
            throw new IOException("Invalid recovery journal");
        }
        File file = new File(this.backups, property + ".zip");
        SafeSaveTree.requireRegular(SafeSaveTree.stat(file));
        File fileMkdir = mkdir(new File(handle.file(), str + "-incoming"));
        InputStream inputStreamNewInputStream = Files.newInputStream(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try {
            SaveArchive.Inventory inventoryExtract = SaveArchive.extract(inputStreamNewInputStream, fileMkdir);
            if (inputStreamNewInputStream != null) {
                inputStreamNewInputStream.close();
            }
            if (!inventoryExtract.fingerprint().equals(pending.getProperty("fingerprint"))) {
                throw new IOException("Recovery backup fingerprint mismatch");
            }
            this.stage = FileFailureDetails.Stage.CAPTURE;
            handle.validate();
            SafeSaveTree safeSaveTree = new SafeSaveTree(transferRoot(), this.guard);
            SafeSaveTree.State stateStable = safeSaveTree.stable(null, false);
            safeSaveTree.unchanged(stateStable, false);
            replace(safeSaveTree, fileMkdir, inventoryExtract, handle, str + "-removed", false, new TreeMap((SortedMap) stateStable.inventory.hashes), new boolean[]{false});
            this.stage = FileFailureDetails.Stage.FINALIZE;
            finalizeBackup(property);
            Bundle bundleDescribe = describe(inventoryExtract);
            describeFinalizedBackup(bundleDescribe, property);
            bundleDescribe.putBoolean("recovered", true);
            return bundleDescribe;
        } finally {
        }
    }

    
    public void validatePendingBackup() throws IOException {
        Properties pending = readPending();
        String property = pending.getProperty("backupId", "");
        String property2 = pending.getProperty("fingerprint", "");
        if (!validBackupId(property) || !property2.matches("[0-9a-f]{64}")) {
            throw new IOException("Invalid recovery journal");
        }
        File file = new File(this.backups, property + ".zip");
        StructStat structStatStat = SafeSaveTree.stat(file);
        SafeSaveTree.requireRegular(structStatStat);
        SaveArchive.Inventory inventoryVerify = SaveArchive.verify(file);
        if (!SafeSaveTree.identity(structStatStat).equals(SafeSaveTree.identity(SafeSaveTree.stat(file))) || !property2.equals(inventoryVerify.fingerprint())) {
            throw new IOException("Recovery backup identity or fingerprint mismatch");
        }
    }

    private void replace(SafeSaveTree safeSaveTree, File file, SaveArchive.Inventory inventory, RestoreWorkspace.Handle handle, String str, boolean z, Map<String, String> map, boolean[] zArr) throws IOException, ErrnoException {
        handle.validate();
        this.guard.requireQuiescent();
        File file2 = null;
        if (!map.equals(safeSaveTree.scan(null, z).inventory.hashes)) {
            throw new BridgeFailure("GAME_SAVE_RACE", "Save changed immediately before replacement");
        }
        syncTreeDirectories(file);
        File fileMkdir = mkdir(new File(handle.file(), str));
        File[] fileArrListFiles = safeSaveTree.root.listFiles();
        if (fileArrListFiles == null) {
            throw new IOException("Cannot enumerate restore target");
        }
        int length = fileArrListFiles.length;
        int i = 0;
        boolean z2 = false;
        while (i < length) {
            File file3 = fileArrListFiles[i];
            if (SaveArchive.included(file3.getName())) {
                handle.validate();
                this.guard.requireQuiescent();
                SafeSaveTree.checkDirectory(safeSaveTree.root);
                StructStat structStatStat = SafeSaveTree.stat(file3);
                SafeSaveTree.requireOwner(structStatStat);
                if (!OsConstants.S_ISDIR(structStatStat.st_mode)) {
                    SafeSaveTree.requireRegular(structStatStat);
                }
                if (!z2 && !map.equals(safeSaveTree.scan(file2, z).inventory.hashes)) {
                    throw new BridgeFailure("GAME_SAVE_RACE", "Save changed immediately before the first mutation");
                }
                this.guard.requireQuiescent();
                handle.validate();
                SafeSaveTree.checkDirectory(fileMkdir);
                this.stage = FileFailureDetails.Stage.MOVE_OLD;
                Files.move(file3.toPath(), new File(fileMkdir, file3.getName()).toPath(), StandardCopyOption.ATOMIC_MOVE);
                zArr[0] = true;
                Iterator<String> it = map.keySet().iterator();
                while (it.hasNext()) {
                    String next = it.next();
                    if (next.equals(file3.getName()) || next.startsWith(file3.getName() + "/")) it.remove();
                }
                syncDirectory(fileMkdir);
                syncDirectory(safeSaveTree.root);
                this.guard.requireQuiescent();
                z2 = true;
            }
            i++;
            file2 = null;
        }
        if (!map.equals(safeSaveTree.scan(file2, false).inventory.hashes)) {
            throw new BridgeFailure("GAME_SAVE_RACE", "A background writer changed the save during replacement");
        }
        File[] fileArrListFiles2 = file.listFiles();
        if (fileArrListFiles2 == null) {
            throw new IOException("Cannot enumerate staged saves");
        }
        int length2 = fileArrListFiles2.length;
        int i2 = 0;
        while (i2 < length2) {
            File file4 = fileArrListFiles2[i2];
            handle.validate();
            this.guard.requireQuiescent();
            SafeSaveTree.checkDirectory(safeSaveTree.root);
            if (!SaveArchive.safeName(file4.getName())) {
                throw new IOException("Unsafe staged root");
            }
            File file5 = new File(safeSaveTree.root, file4.getName());
            if (Files.exists(file5.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw new BridgeFailure("SAVE_CHANGED", "A save entry appeared during restore");
            }
            if (!z2 && !map.equals(safeSaveTree.scan(null, false).inventory.hashes)) {
                throw new BridgeFailure("GAME_SAVE_RACE", "Save changed immediately before the first insertion");
            }
            this.guard.requireQuiescent();
            handle.validate();
            SafeSaveTree.checkDirectory(file);
            StructStat structStatStat2 = SafeSaveTree.stat(file4);
            SafeSaveTree.requireOwner(structStatStat2);
            if (!OsConstants.S_ISDIR(structStatStat2.st_mode)) {
                SafeSaveTree.requireRegular(structStatStat2);
            }
            this.stage = FileFailureDetails.Stage.MOVE_NEW;
            Files.move(file4.toPath(), file5.toPath(), StandardCopyOption.ATOMIC_MOVE);
            zArr[0] = true;
            for (Map.Entry<String, String> entry : inventory.hashes.entrySet()) {
                if (entry.getKey().equals(file4.getName()) || entry.getKey().startsWith(file4.getName() + "/"))
                    map.put(entry.getKey(), entry.getValue());
            }
            syncDirectory(file);
            syncDirectory(safeSaveTree.root);
            this.guard.requireQuiescent();
            i2++;
            z2 = true;
        }
        this.stage = FileFailureDetails.Stage.VERIFY;
        handle.validate();
        if (!inventory.hashes.equals(safeSaveTree.stable(null, true).inventory.hashes)) {
            throw new BridgeFailure("RESTORE_VERIFY_FAILED", "Restored files did not match the requested save");
        }
        this.guard.requireQuiescent();
    }

    private void writePending(String str, String str2) throws IOException, ErrnoException {
        Properties properties = new Properties();
        properties.setProperty("backupId", str);
        properties.setProperty("fingerprint", str2);
        File file = new File(this.area, "pending.new");
        Files.deleteIfExists(file.toPath());
        OwnedDescriptor ownedDescriptor = new OwnedDescriptor(Os.open(file.getPath(), OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 384));
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(ownedDescriptor.fd);
            try {
                properties.store(fileOutputStream, "Private bridge restore recovery record");
                fileOutputStream.getFD().sync();
                fileOutputStream.close();
                ownedDescriptor.close();
                Files.move(file.toPath(), this.pending.toPath(), StandardCopyOption.ATOMIC_MOVE);
                syncDirectory(this.area);
            } finally {
            }
        } catch (Throwable th) {
            try {
                ownedDescriptor.close();
            } catch (Throwable th2) {
                th.addSuppressed(th2);
            }
            throw th;
        }
    }

    private void finalizeBackup(String str) throws IOException, ErrnoException {
        Properties pending = readPending();
        if (!str.equals(pending.getProperty("backupId"))) {
            throw new IOException("Recovery journal changed");
        }
        File file = new File(this.backups, str + ".finalized");
        if (!file.exists()) {
            File file2 = new File(this.backups, str + ".finalizing");
            Files.deleteIfExists(file2.toPath());
            OwnedDescriptor ownedDescriptor = new OwnedDescriptor(Os.open(file2.getPath(), OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 384));
            try {
                FileOutputStream fileOutputStream = new FileOutputStream(ownedDescriptor.fd);
                try {
                    pending.setProperty("state", "verified-finalized");
                    pending.store(fileOutputStream, "Verified bridge transaction completion");
                    fileOutputStream.getFD().sync();
                    fileOutputStream.close();
                    ownedDescriptor.close();
                    Files.move(file2.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE);
                } finally {
                }
            } catch (Throwable th) {
                try {
                    ownedDescriptor.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
                throw th;
            }
        }
        if (!finalizedMarkerValid(str)) {
            throw new IOException("Invalid finalized transaction marker");
        }
        syncDirectory(this.backups);
        if (!this.pending.delete()) {
            throw new IOException("Cannot finalize restore journal");
        }
        syncDirectory(this.area);
        try {
            cleanupFinalized(str);
        } catch (ErrnoException | IOException unused) {
        }
    }

    private boolean finalizedMarkerValid(String str) throws IOException {
        if (!validBackupId(str)) {
            return false;
        }
        File file = new File(this.backups, str + ".finalized");
        if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return BackupCleanupPolicy.validFinalizedMarker(str, readRecord(file));
        }
        return false;
    }

    private void cleanupFinalizedBackups() throws IOException {
        File[] fileArrListFiles = this.backups.listFiles();
        if (fileArrListFiles == null) {
            throw new IOException("Cannot inspect finalized transaction records");
        }
        int i = 0;
        for (File file : fileArrListFiles) {
            String name = file.getName();
            if (name.endsWith(".finalized")) {
                i++;
                if (i > 16) {
                    return;
                }
                String strSubstring = name.substring(0, name.length() - 10);
                if (validBackupId(strSubstring)) {
                    try {
                        cleanupFinalized(strSubstring);
                    } catch (ErrnoException | IOException unused) {
                    }
                }
            }
        }
    }

    private void cleanupFinalized(String str) throws IOException, ErrnoException {
        if (validBackupId(str)) {
            File file = new File(this.backups, str + ".finalized");
            if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) && BackupCleanupPolicy.mayReclaim(str, readRecord(file), pendingPresent())) {
                syncDirectory(this.area);
                syncDirectory(this.backups);
                Files.deleteIfExists(new File(this.backups, str + ".zip").toPath());
                syncDirectory(this.backups);
                Files.deleteIfExists(new File(this.backups, str + ".finalized").toPath());
                syncDirectory(this.backups);
            }
        }
    }

    private void deleteUnusedBackup(String str) throws IOException {
        if (!validBackupId(str) || pendingPresent()) {
            return;
        }
        try {
            Files.deleteIfExists(new File(this.backups, str + ".zip").toPath());
            Files.deleteIfExists(new File(this.backups, str + ".finalized").toPath());
            syncDirectory(this.backups);
        } catch (ErrnoException | IOException unused) {
        }
    }

    private static boolean validBackupId(String str) {
        return BackupCleanupPolicy.validId(str);
    }

    private void describeFinalizedBackup(Bundle bundle, String str) {
        bundle.putString("transactionId", str);
        boolean zExists = new File(this.backups, str + ".zip").exists();
        bundle.putBoolean("backupRetained", zExists);
        if (zExists) {
            bundle.putString("backupId", str);
        }
    }

    private Properties readRecord(File file) throws IOException {
        SafeSaveTree.requireRegular(SafeSaveTree.stat(file));
        if (file.length() > 4096) {
            throw new IOException("Invalid recovery record size");
        }
        Properties properties = new Properties();
        InputStream inputStreamNewInputStream = Files.newInputStream(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try {
            properties.load(inputStreamNewInputStream);
            if (inputStreamNewInputStream != null) {
                inputStreamNewInputStream.close();
            }
            return properties;
        } catch (Throwable th) {
            if (inputStreamNewInputStream != null) {
                try {
                    inputStreamNewInputStream.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    private Properties readPending() throws IOException {
        return readRecord(this.pending);
    }

    private static void writeArchive(File file, SaveArchive.Inventory inventory, File file2) throws IOException {
        SaveArchive.write(file, inventory, Files.newOutputStream(file2.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
        FileOutputStream fileOutputStream = new FileOutputStream(file2, true);
        try {
            fileOutputStream.getFD().sync();
            fileOutputStream.close();
        } catch (Throwable th) {
            try {
                fileOutputStream.close();
            } catch (Throwable th2) {
                th.addSuppressed(th2);
            }
            throw th;
        }
    }

    private RestoreWorkspace restoreWorkspace() throws IOException {
        final File parentFile = this.guard.root().getParentFile();
        SafeSaveTree.checkDirectory(parentFile);
        GameBinding gameBindingBinding = this.guard.binding();
        return new RestoreWorkspace(parentFile, this.area, SaveArchive.sha256((gameBindingBinding.gamePackage + "\n" + gameBindingBinding.helperPackage + "\n" + gameBindingBinding.sharedUserId + "\n" + gameBindingBinding.originIdentity + "\n" + gameBindingBinding.namespaceId + "\n" + gameBindingBinding.sourceApkSha256 + "\n" + gameBindingBinding.gameVersionCode).getBytes(StandardCharsets.UTF_8)), Process.myUid(), new RestoreWorkspace.Access() { // from class: io.github.xyz1001.pdsync.bridge.SaveBridgeProvider.1
            @Override // io.github.xyz1001.pdsync.bridge.RestoreWorkspace.Access
            public void validateBinding() throws IOException {
                if (!parentFile.equals(SaveBridgeProvider.this.guard.root().getParentFile())) {
                    throw new IOException("Android game directory changed");
                }
                SafeSaveTree.checkDirectory(parentFile);
                SafeSaveTree.checkDirectory(SaveBridgeProvider.this.area);
            }

            @Override // io.github.xyz1001.pdsync.bridge.RestoreWorkspace.Access
            public RestoreWorkspace.Stamp stat(Path path) throws IOException {
                try {
                    StructStat structStatLstat = Os.lstat(path.toString());
                    String strKey = RestoreWorkspace.key(Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
                    StructStat structStatLstat2 = Os.lstat(path.toString());
                    RestoreWorkspace.Stamp stamp = SaveBridgeProvider.stamp(structStatLstat, strKey);
                    RestoreWorkspace.Stamp stamp2 = SaveBridgeProvider.stamp(structStatLstat2, strKey);
                    if (stamp.stable().equals(stamp2.stable())) {
                        return stamp2;
                    }
                    throw new IOException("Restore workspace changed while checking identity");
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.ENOENT) {
                        NoSuchFileException noSuchFileException = new NoSuchFileException("Private restore entry is absent");
                        noSuchFileException.initCause(e);
                        throw noSuchFileException;
                    }
                    throw new IOException("Cannot inspect private restore workspace", e);
                }
            }

            @Override // io.github.xyz1001.pdsync.bridge.RestoreWorkspace.Access
            public void sync(Path path) throws IOException {
                try {
                    SaveBridgeProvider.syncDirectory(path.toFile());
                } catch (ErrnoException e) {
                    throw new IOException("Cannot sync private restore workspace", e);
                }
            }
        });
    }

    
    public static RestoreWorkspace.Stamp stamp(StructStat structStat, String str) {
        return new RestoreWorkspace.Stamp(structStat.st_dev, structStat.st_ino, structStat.st_mode & OsConstants.S_IFMT, structStat.st_uid, structStat.st_nlink, structStat.st_size, str);
    }

    private RestoreSession newRestoreSession() throws IOException {
        this.stage = FileFailureDetails.Stage.HELPER_WORKSPACE;
        return new RestoreSession(restoreWorkspace().create());
    }

    private final class RestoreSession implements AutoCloseable {
        boolean committed;
        final RestoreWorkspace.Handle handle;

        RestoreSession(RestoreWorkspace.Handle handle) {
            this.handle = handle;
        }

        @Override // java.lang.AutoCloseable
        public void close() throws IOException {
            try {
                SaveBridgeProvider.this.stage = FileFailureDetails.Stage.CLEANUP;
                if (SaveBridgeProvider.this.pendingPresent()) {
                    SaveBridgeProvider.this.validatePendingBackup();
                }
                this.handle.cleanup();
            } catch (IOException e) {
                SaveBridgeProvider.this.rememberFailure(e);
                if (!this.committed) {
                    throw e;
                }
                throw new BridgeFailure("RESTORE_COMMITTED_CLEANUP_PENDING", "Save was verified and committed; temporary staging cleanup will retry", e);
            }
        }
    }

    private File workspace() throws IOException {
        return Files.createTempDirectory(this.area.toPath(), "work-", new FileAttribute[0]).toFile();
    }

    private static File mkdir(File file) throws IOException {
        if (file.mkdir()) {
            return file;
        }
        throw new IOException("Cannot create private staging directory");
    }

    private static void syncTreeDirectories(File file) throws IOException, ErrnoException {
        SafeSaveTree.checkDirectory(file);
        File[] fileArrListFiles = file.listFiles();
        if (fileArrListFiles == null) {
            throw new IOException("Cannot inspect staged tree durability");
        }
        for (File file2 : fileArrListFiles) {
            StructStat structStatStat = SafeSaveTree.stat(file2);
            if (OsConstants.S_ISDIR(structStatStat.st_mode)) {
                syncTreeDirectories(file2);
            } else {
                SafeSaveTree.requireRegular(structStatStat);
            }
        }
        syncDirectory(file);
    }

    
    public static void syncDirectory(File file) throws ErrnoException {
        OwnedDescriptor ownedDescriptor = new OwnedDescriptor(Os.open(file.getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0));
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(ownedDescriptor.fd).st_mode)) {
                throw new ErrnoException("Directory changed", OsConstants.ENOTDIR);
            }
            Os.fsync(ownedDescriptor.fd);
            ownedDescriptor.close();
        } catch (Throwable th) {
            try {
                ownedDescriptor.close();
            } catch (Throwable th2) {
                th.addSuppressed(th2);
            }
            throw th;
        }
    }

    private static final class OwnedDescriptor implements AutoCloseable {
        final FileDescriptor fd;

        OwnedDescriptor(FileDescriptor fileDescriptor) {
            this.fd = fileDescriptor;
        }

        @Override // java.lang.AutoCloseable
        public void close() throws ErrnoException {
            if (this.fd.valid()) Os.close(this.fd);
        }
    }

    private static void deleteTree(File file) {
        File[] fileArrListFiles;
        if (Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS) && (fileArrListFiles = file.listFiles()) != null) {
            for (File file2 : fileArrListFiles) {
                deleteTree(file2);
            }
        }
        file.delete();
    }

    private static Bundle ok() {
        Bundle bundle = new Bundle();
        bundle.putInt("apiVersion", 1);
        bundle.putBoolean("ok", true);
        bundle.putString("code", "OK");
        bundle.putString("message", "Ready");
        return bundle;
    }

    private Bundle describe(SaveArchive.Inventory inventory) throws IOException {
        Bundle bundleOk = ok();
        describeBinding(bundleOk);
        bundleOk.putString("fingerprint", inventory.fingerprint());
        bundleOk.putInt("fileCount", inventory.hashes.size());
        bundleOk.putLong("byteCount", inventory.bytes);
        return bundleOk;
    }

    private void describeBinding(Bundle bundle) throws IOException {
        GameBinding gameBindingBinding = this.guard.binding();
        bundle.putString("gamePackage", gameBindingBinding.gamePackage);
        bundle.putString("helperPackage", gameBindingBinding.helperPackage);
        bundle.putString("sharedUserId", gameBindingBinding.sharedUserId);
        bundle.putString("authority", gameBindingBinding.authority);
        bundle.putString("originIdentity", gameBindingBinding.originIdentity);
        bundle.putString("namespaceId", gameBindingBinding.namespaceId);
        bundle.putString("sourceApkSha256", gameBindingBinding.sourceApkSha256);
        bundle.putString("sourceApkSetSha256", gameBindingBinding.sourceApkSha256);
        bundle.putLong("boundGameVersionCode", gameBindingBinding.gameVersionCode);
        bundle.putString("boundGameVersionName", gameBindingBinding.gameVersionName);
        bundle.putString("fileAccessAdapter", "pixel-dungeon-slot-files-v1");
        bundle.putBoolean("payloadFormatValidated", false);
    }

    private static void describeContent(Bundle bundle, SaveDirectoryPolicy.Content content) {
        bundle.putString("saveContentState", content.state.name());
        bundle.putInt("includedRootEntries", content.includedEntries);
        bundle.putInt("excludedRootEntries", content.excludedEntries);
        bundle.putInt("unrecognizedRootEntries", content.unrecognizedEntries);
    }

    
    public void rememberFailure(Throwable th) {
        if (this.primaryFailure == null) {
            this.primaryFailure = new FileFailureDetails(this.stage, th);
        }
    }

    private static int errno(Throwable th) {
        Set setNewSetFromMap = Collections.newSetFromMap(new IdentityHashMap());
        int i = 0;
        while (th != null && i < 32 && setNewSetFromMap.add(th)) {
            if (th instanceof ErrnoException) {
                return FileFailureDetails.boundedErrno(((ErrnoException) th).errno);
            }
            i++;
            th = th.getCause();
        }
        return -1;
    }

    private Bundle failure(String str, String str2, Throwable th) {
        Bundle bundleOk = ok();
        bundleOk.putBoolean("ok", false);
        bundleOk.putBoolean("ready", false);
        bundleOk.putString("code", str);
        bundleOk.putString("message", str2);
        bundleOk.putBoolean("observationReliable", false);
        bundleOk.putBoolean("gameForeground", false);
        bundleOk.putBoolean("gameProcessPresent", false);
        bundleOk.putBoolean("recoveryRequired", pendingPresent());
        FileFailureDetails fileFailureDetails = this.primaryFailure;
        if (fileFailureDetails == null) {
            fileFailureDetails = new FileFailureDetails(this.stage, th);
        }
        int iErrno = errno(fileFailureDetails.cause);
        bundleOk.putString("fileStage", fileFailureDetails.stage.name());
        bundleOk.putString("fileError", FileFailureDetails.classify(fileFailureDetails.cause, iErrno).name());
        bundleOk.putInt("fileErrno", iErrno);
        return bundleOk;
    }

    @Override // android.content.ContentProvider
    public Cursor query(Uri uri, String[] strArr, String str, String[] strArr2, String str2) {
        authenticate();
        throw new UnsupportedOperationException("Use call");
    }

    @Override // android.content.ContentProvider
    public String getType(Uri uri) {
        authenticate();
        throw new UnsupportedOperationException("Use call");
    }

    @Override // android.content.ContentProvider
    public Uri insert(Uri uri, ContentValues contentValues) {
        authenticate();
        throw new UnsupportedOperationException("Use call");
    }

    @Override // android.content.ContentProvider
    public int delete(Uri uri, String str, String[] strArr) {
        authenticate();
        throw new UnsupportedOperationException("Use call");
    }

    @Override // android.content.ContentProvider
    public int update(Uri uri, ContentValues contentValues, String str, String[] strArr) {
        authenticate();
        throw new UnsupportedOperationException("Use call");
    }

    @Override // android.content.ContentProvider
    public ParcelFileDescriptor openFile(Uri uri, String str) throws FileNotFoundException {
        authenticate();
        throw new FileNotFoundException("Use call");
    }
}
