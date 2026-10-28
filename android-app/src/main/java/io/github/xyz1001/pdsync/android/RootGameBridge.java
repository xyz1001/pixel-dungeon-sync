package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import android.content.Context;
import io.github.xyz1001.pdsync.root.RootProtocol;
import io.github.xyz1001.pdsync.root.RootBinding;
import io.github.xyz1001.pdsync.root.RootAtomicRename;
import java.io.*;
import java.nio.file.*;
import java.util.Map;
import java.util.function.BooleanSupplier;
import pdsync.DirectoryMetadataKt;
import pdsync.android.app.AndroidGameIdentity;
import pdsync.android.app.AndroidGameTarget;
import pdsync.android.app.AndroidRestoreReceipt;
import pdsync.Snapshot;
import pdsync.android.app.AndroidSaveObservation;
import pdsync.android.app.AndroidSaveFile;
import io.github.xyz1001.pdsync.bridge.SaveArchive;

/** Short-lived root access adapter. Core comparison, DAV and UUID logic remain unchanged. */
public final class RootGameBridge implements GameAccessBackend, AutoCloseable {
    public interface TargetVerifier {
        void requireUnchanged(SelectedRootGame selected) throws Exception;
        default AutoCloseable exportScope(SelectedRootGame selected)throws Exception {
            requireUnchanged(selected);return ()->requireUnchanged(selected);
        }
    }
    private final ThreadLocal<Boolean> exportIdentity=new ThreadLocal<>();
    private static final boolean MUTATION_IMPLEMENTED=true; // Concrete RootRestoreJob revalidates every write capability.
    private final Capabilities supported;
    private final SelectedRootGame selected;
    private final AndroidGameIdentity identity;
    private final TargetVerifier verifier;
    private final RootProcessTransport.Exchange exchange;
    private final BooleanSupplier authorized;
    private final File stagingDirectory;
    private RootProcessTransport ownedTransport;
    private final RootDiagnosticLogging diagnostics = new RootDiagnosticLogging();

    public RootGameBridge(Context context, SelectedRootGame selected, AndroidGameIdentity identity)throws Exception {
        this(context, selected, identity, new RootProcessTransport(context.getApplicationInfo().sourceDir));
    }
    private RootGameBridge(Context context, SelectedRootGame selected, AndroidGameIdentity identity,
            RootProcessTransport transport) {
        this(selected,identity,new TargetVerifier() {
            final RootTargetResolver resolver=new RootTargetResolver(context);
            public void requireUnchanged(SelectedRootGame game)throws Exception {resolver.requireUnchanged(game);}
            public AutoCloseable exportScope(SelectedRootGame game)throws Exception {return resolver.exportScope(game);}
        },
                new RootProcessTransport.Exchange() {
                    @Override public RootProtocol.Response exchange(RootProtocol.Request request,OutputStream archive)throws IOException {
                        return transport.exchange(request,archive);
                    }
                    @Override public RootProtocol.Response exchange(RootProtocol.Request request,InputStream upload,OutputStream archive)throws IOException {
                        return transport.exchange(request,upload,archive);
                    }
                },()->SyncRuntime.rootSelectionMatches(context,selected),context.getCacheDir(),
                mutationSupportedForPackage(nativeCandidate(context)));
        this.ownedTransport=transport;
    }
    public RootGameBridge(SelectedRootGame selected, AndroidGameIdentity identity, TargetVerifier verifier,
            RootProcessTransport.Exchange exchange, BooleanSupplier authorized, File stagingDirectory) {
        this(selected,identity,verifier,exchange,authorized,stagingDirectory,false);
    }
    public RootGameBridge(SelectedRootGame selected, AndroidGameIdentity identity, TargetVerifier verifier,
            RootProcessTransport.Exchange exchange, BooleanSupplier authorized, File stagingDirectory, boolean mutationSupported) {
        if(selected==null||verifier==null||exchange==null||authorized==null||stagingDirectory==null)throw new IllegalArgumentException(AndroidMessages.ROOT_GAME_BRIDGE_01.text());
        this.supported=new Capabilities(true,mutationSupported,false,mutationSupported);
        this.selected=selected;this.identity=identity;this.verifier=verifier;this.exchange=exchange;
        this.authorized=authorized;this.stagingDirectory=stagingDirectory;
    }
    private static boolean nativeCandidate(Context context) {
        String directory=context.getApplicationInfo().nativeLibraryDir;
        return directory!=null&&new File(directory,RootAtomicRename.LIBRARY_NAME).isFile();
    }
    /** Metadata-only implementation availability; neither a Root grant nor a successful native probe. */
    static boolean mutationSupportedForPackage(boolean nativePackaged) {
        return MUTATION_IMPLEMENTED&&nativePackaged;
    }
    @Override public void close(){if(ownedTransport!=null)ownedTransport.close();}
    @Override public Mode mode(){return Mode.ROOT_DIRECT;}
    @Override public Capabilities capabilities(){return supported;}
    /** Only a foreground explicit user action may call this method. */
    public String probeAuthorization() {
        call(RootProtocol.Command.PROBE,null,true);
        return AndroidMessages.ROOT_GAME_BRIDGE_02.text();
    }
    @Override public AndroidGameTarget targetGame() {
        try {
            verifyTarget();
            AndroidGameIdentity target=identity==null?AndroidGameIdentity.forPackage(selected.gamePackage,selected.gameLabel,
                    selected.originIdentity):identity;
            return new AndroidGameTarget(target,selected.gameVersionName,selected.gameVersionCode,
                    selected.installedApkSetSha256,selected.sourceApkSetSha256);
        }catch(Exception failure){throw failure("ROOT_TARGET_CHANGED",failure);}
    }
    @Override public String inspectReady() {
        RootProtocol.Response reply=call(RootProtocol.Command.INSPECT,null,false);
        if(!("EMPTY_DIRECTORY".equals(reply.contentState) || "NO_TRANSFERABLE_FILES".equals(reply.contentState)
                || "ALLOWLISTED_FILES_PRESENT".equals(reply.contentState)))throw failure("ROOT_SAVE_NOT_READY",null);
        return AndroidMessages.ROOT_GAME_BRIDGE_03.text();
    }
    @Override public Observation observeLifecycle() {
        return Observation.unknown();
    }
    @Override public byte[] exportArchive() {
        try(AutoCloseable scope=beginExportIdentity()) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            call(RootProtocol.Command.EXPORT,new SaveArchive.LimitedOutputStream(bytes,SaveArchive.MAX_BYTES),false);
            return bytes.toByteArray();
        } catch(BridgeDiagnostics.Failure failure) {throw atStage(BridgeDiagnostics.Stage.EXPORT,failure);}
        catch(Exception failure) {throw failure("ROOT_EXPORT_FAILED",failure);}
        finally {exportIdentity.remove();}
    }
    @Override public void exportTo(Path destination) {
        Path archive=null;
        try(AutoCloseable scope=beginExportIdentity()) {
            archive=Files.createTempFile(stagingDirectory.toPath(),"root-export-",".zip");
            RootProtocol.Response reply;
            try(OutputStream output=Files.newOutputStream(archive,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                reply=call(RootProtocol.Command.EXPORT,output,false);
            }
            SaveArchiveCodec.Inventory inventory;
            try(InputStream input=Files.newInputStream(archive,LinkOption.NOFOLLOW_LINKS)) {
                inventory=SaveArchiveCodec.readExportArchive(input,destination);
            }

        }catch(BridgeDiagnostics.Failure failure){throw atStage(BridgeDiagnostics.Stage.EXPORT,failure);}
        catch(Exception failure){throw failure("ROOT_EXPORT_FAILED",failure);}
        finally {exportIdentity.remove();if(archive!=null)try{Files.deleteIfExists(archive);}catch(IOException ignored){}}
    }
    /** Sends the already verified snapshot archive unchanged; the runner owns its sole extraction. */
    @Override public AndroidSaveObservation readSnapshot() {
        try {
            AndroidGameTarget target = targetGame();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            RootProtocol.Response reply = call(RootProtocol.Command.READ_SNAPSHOT,
                    new SaveArchive.LimitedOutputStream(bytes, SaveArchive.MAX_OBSERVATION_BYTES), false);
            SaveArchive.Observation observation = SaveArchive.readObservation(new ByteArrayInputStream(bytes.toByteArray()));
            requireInventory(reply, observation.inventory.fingerprint(), observation.inventory.hashes.size(), observation.inventory.bytes, "ROOT_INVALID_REPLY");
            AndroidGameTarget after = targetGame();
            if (!target.equals(after)) throw failure("ROOT_BINDING_MISMATCH", null);
            Map<String,AndroidSaveFile> inventory = new java.util.TreeMap<>();
            for (Map.Entry<String,String> item : observation.inventory.hashes.entrySet())
                inventory.put(item.getKey(), new AndroidSaveFile(item.getValue(), observation.sizes.get(item.getKey()), observation.modifiedMillis.get(item.getKey())));
            return new AndroidSaveObservation(after, inventory, observation.payloads);
        } catch (BridgeDiagnostics.Failure failure) { throw failure; }
        catch (Exception failure) { throw failure("ROOT_INVALID_REPLY", failure); }
    }

    @Override public AndroidRestoreReceipt restoreVerified(Snapshot snapshot, Map<String,String> expectedCurrentFiles) {
        if (!supported.restoreSupported) throw failure("ROOT_RESTORE_UNSUPPORTED", null);
        try {
            AndroidGameTarget target = targetGame();
            if (expectedCurrentFiles == null || expectedCurrentFiles.size() > 4096)
                throw failure("ROOT_INVALID_REQUEST", null);
            for (Map.Entry<String,String> entry : expectedCurrentFiles.entrySet()) {
                SaveArchiveCodec.validateName(entry.getKey());
                if (!RootBinding.isHash(entry.getValue())) throw failure("ROOT_INVALID_REQUEST", null);
            }
            Map<String,String> desired = new java.util.TreeMap<>(snapshot.getManifest().getFiles());
            if (desired.size() > 4096) throw failure("ROOT_INVALID_REQUEST", null);
            for (Map.Entry<String,String> entry : desired.entrySet()) {
                SaveArchiveCodec.validateName(entry.getKey());
                if (!RootBinding.isHash(entry.getValue())) throw failure("ROOT_INVALID_REQUEST", null);
            }
            byte[] archive = snapshot.getZip();
            if (archive.length < 22 || archive.length > SaveArchiveCodec.MAX_BYTES)
                throw failure("ROOT_INVALID_REQUEST", null);
            String expected = DirectoryMetadataKt.contentSha256(expectedCurrentFiles);
            String desiredFingerprint = DirectoryMetadataKt.contentSha256(desired);
            RootProtocol.Request request = new RootProtocol.Request(RootProtocol.Command.RESTORE,
                    selected.binding(), expected, desiredFingerprint, archive.length);
            RootProtocol.Response reply;
            try (InputStream upload = new ByteArrayInputStream(archive)) {
                reply = call(request, upload, null, false);
            }
            if (reply.outcome != RootProtocol.Outcome.RESTORED
                    && reply.outcome != RootProtocol.Outcome.RESTORED_CLEANUP_PENDING
                    && reply.outcome != RootProtocol.Outcome.UNCHANGED)
                throw failure("ROOT_RESTORE_RECEIPT_INVALID", null);
            if (!desiredFingerprint.equals(reply.contentFingerprint) || desired.size() != reply.fileCount
                    || reply.contentBytes < 0 || reply.contentBytes > SaveArchiveCodec.MAX_BYTES)
                throw failure("ROOT_RESTORE_VERIFY_FAILED", null);
            AndroidGameTarget after = targetGame();
            if (!target.equals(after)) throw failure("ROOT_TARGET_CHANGED", null);
            if (reply.outcome == RootProtocol.Outcome.RESTORED_CLEANUP_PENDING)
                throw failure("ROOT_RESTORE_COMMITTED_CLEANUP_PENDING", BridgeDiagnostics.rootDetail(
                        reply.diagnosticStage.name(), reply.diagnosticCategory.name(), reply.errno));
            return new AndroidRestoreReceipt(after, desired, reply.contentBytes);
        } catch (BridgeDiagnostics.Failure failure) { throw atStage(BridgeDiagnostics.Stage.RESTORE, failure); }
        catch (Exception failure) {
            throw atStage(BridgeDiagnostics.Stage.RESTORE, failure("ROOT_RESTORE_FAILED", failure));
        }
    }
    @Override public void restoreFrom(Path source, Map<String,String> expectedCurrentFiles) {
        if(!supported.restoreSupported)throw failure("ROOT_RESTORE_UNSUPPORTED",null);
        Path archive=null,verification=null;
        try {
            targetGame();
            if(expectedCurrentFiles==null||expectedCurrentFiles.size()>4096)throw failure("ROOT_INVALID_REQUEST",null);
            for(Map.Entry<String,String> entry:expectedCurrentFiles.entrySet()) {
                SaveArchiveCodec.validateName(entry.getKey());
                if(!RootBinding.isHash(entry.getValue()))throw failure("ROOT_INVALID_REQUEST",null);
            }
            String expected=DirectoryMetadataKt.contentSha256(expectedCurrentFiles);
            archive=Files.createTempFile(stagingDirectory.toPath(),"root-restore-",".zip");
            SaveArchiveCodec.writeRestoreArchive(source,archive.toFile());
            verification=Files.createTempDirectory(stagingDirectory.toPath(),"root-restore-verify-");
            SaveArchiveCodec.Inventory desired;
            try(InputStream input=Files.newInputStream(archive,LinkOption.NOFOLLOW_LINKS)) {
                desired=SaveArchiveCodec.readExportArchive(input,verification);
            }
            RootExportValidation.requireCompleteArchive(archive.toFile(),(int)Files.size(archive),desired.hashes.size());
            String desiredFingerprint=DirectoryMetadataKt.contentSha256(desired.hashes);
            RootProtocol.Request request=new RootProtocol.Request(RootProtocol.Command.RESTORE,selected.binding(),expected,desiredFingerprint,(int)Files.size(archive));
            RootProtocol.Response reply;
            try(InputStream upload=Files.newInputStream(archive,LinkOption.NOFOLLOW_LINKS)) {
                reply=call(request,upload,null,false);
            }
            if(reply.outcome!=RootProtocol.Outcome.RESTORED&&reply.outcome!=RootProtocol.Outcome.RESTORED_CLEANUP_PENDING
                    &&reply.outcome!=RootProtocol.Outcome.UNCHANGED)throw failure("ROOT_RESTORE_RECEIPT_INVALID",null);
            requireInventory(reply,desiredFingerprint,desired.hashes.size(),desired.bytes,"ROOT_RESTORE_VERIFY_FAILED");
            targetGame();
            if(reply.outcome==RootProtocol.Outcome.RESTORED_CLEANUP_PENDING)
                throw failure("ROOT_RESTORE_COMMITTED_CLEANUP_PENDING",BridgeDiagnostics.rootDetail(
                        reply.diagnosticStage.name(),reply.diagnosticCategory.name(),reply.errno));
        }catch(BridgeDiagnostics.Failure failure){throw atStage(BridgeDiagnostics.Stage.RESTORE,failure);}
        catch(Exception failure){throw atStage(BridgeDiagnostics.Stage.RESTORE,failure("ROOT_RESTORE_FAILED",failure));}
        finally {
            if(archive!=null)try{Files.deleteIfExists(archive);}catch(IOException ignored){}
            if(verification!=null)deleteTemporaryTree(verification);
        }
    }
    @Override public String recoverInterruptedBackup() {
        if(!supported.interruptedBackupRecovery)throw failure("ROOT_RECOVERY_UNSUPPORTED",null);
        try {
            RootProtocol.Response reply=call(RootProtocol.Command.RECOVER,null,false);
            if(reply.outcome!=RootProtocol.Outcome.RECOVERED&&reply.outcome!=RootProtocol.Outcome.RECOVERED_CLEANUP_PENDING
                    &&reply.outcome!=RootProtocol.Outcome.UNCHANGED)throw failure("ROOT_RECOVERY_RECEIPT_INVALID",null);
            if(!RootBinding.isHash(reply.contentFingerprint))throw failure("ROOT_RECOVERY_RECEIPT_INVALID",null);
            if(reply.outcome==RootProtocol.Outcome.RECOVERED_CLEANUP_PENDING)
                throw failure("ROOT_RECOVERY_COMMITTED_CLEANUP_PENDING",BridgeDiagnostics.rootDetail(
                        reply.diagnosticStage.name(),reply.diagnosticCategory.name(),reply.errno));
            RootProtocol.Response verified=call(RootProtocol.Command.INSPECT,null,false);
            requireInventory(verified,reply.contentFingerprint,reply.fileCount,reply.contentBytes,"ROOT_RECOVERY_VERIFY_FAILED");
            return AndroidMessages.ROOT_RECOVERED_FILES.text(reply.fileCount);
        }catch(BridgeDiagnostics.Failure failure){throw atStage(BridgeDiagnostics.Stage.RECOVERY,failure);}
    }
    private static void requireInventory(RootProtocol.Response reply,String hash,int count,long bytes,String code) {
        if(!hash.equals(reply.contentFingerprint)||count!=reply.fileCount||bytes!=reply.contentBytes)
            throw failure(code,null);
    }
    private static void deleteTemporaryTree(Path root) {
        try(java.util.stream.Stream<Path> paths=Files.walk(root)) {
            for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(path);
        }catch(IOException ignored){}
    }
    private AutoCloseable beginExportIdentity() {
        try {
            AutoCloseable scope=verifier.exportScope(selected);exportIdentity.set(true);
            return ()->{try {scope.close();}catch(Exception changed){throw failure("ROOT_TARGET_CHANGED",changed);}};
        }catch(Exception changed){throw failure("ROOT_TARGET_CHANGED",changed);}
    }
    private void verifyTarget() {
        if(Boolean.TRUE.equals(exportIdentity.get()))return;
        try {verifier.requireUnchanged(selected);}
        catch(Exception changed){throw failure("ROOT_TARGET_CHANGED",changed);}
    }
    private RootProtocol.Response call(RootProtocol.Command command,OutputStream archive,boolean explicitProbe) {
        return call(new RootProtocol.Request(command,selected.binding()),null,archive,explicitProbe);
    }
    private RootProtocol.Response call(RootProtocol.Request request,InputStream upload,OutputStream archive,boolean explicitProbe) {
        RootProtocol.Command command=request.command;
        try {
            if(!explicitProbe&&!authorized.getAsBoolean())throw failure("ROOT_AUTH_REQUIRED",null);
            verifyTarget();
            RootProtocol.Response reply=exchange.exchange(request,upload,archive);
            try {RootProtocol.requireReplyMatches(request,reply);}
            catch(IOException mismatch){throw failure("ROOT_REPLY_MISMATCH",mismatch);}
            // Protocol success is distinct from the readiness checks below; preserve this verified observation.
            if(reply.status==RootProtocol.Status.OK)diagnostics.response(reply);
            if(!explicitProbe&&!authorized.getAsBoolean())throw failure("ROOT_CANCELLED",null);
            verifyTarget();
            if(reply.status!=RootProtocol.Status.OK)throw failure("ROOT_"+reply.status.name(),
                    BridgeDiagnostics.rootDetail(reply.diagnosticStage.name(),reply.diagnosticCategory.name(),reply.errno));
            if(!selected.binding().fingerprint().equals(reply.bindingFingerprint))throw failure("ROOT_BINDING_MISMATCH",null);
            if(command!=RootProtocol.Command.EXPORT && command!=RootProtocol.Command.READ_SNAPSHOT && reply.archiveLength!=0)throw failure("ROOT_UNEXPECTED_ARCHIVE",null);
            if(command!=RootProtocol.Command.RESTORE&&command!=RootProtocol.Command.RECOVER&&reply.outcome!=RootProtocol.Outcome.READ_ONLY)
                throw failure("ROOT_REPLY_MISMATCH",null);
            if(command==RootProtocol.Command.INSPECT || command==RootProtocol.Command.EXPORT || command==RootProtocol.Command.READ_SNAPSHOT) {
                if(!RootBinding.isHash(reply.contentFingerprint)
                        || !("EMPTY_DIRECTORY".equals(reply.contentState) || "NO_TRANSFERABLE_FILES".equals(reply.contentState)
                        || "ALLOWLISTED_FILES_PRESENT".equals(reply.contentState)))throw failure("ROOT_INVALID_REPLY",null);
            }
            return reply;
        }catch(BridgeDiagnostics.Failure failure){diagnostics.failure(command,failure);throw failure;}
        catch(RootProcessTransport.Failure failure){
            BridgeDiagnostics.Failure diagnostic = failure(failure.code,failure);
            diagnostics.failure(command,diagnostic);throw diagnostic;
        }
        catch(Exception failure){
            BridgeDiagnostics.Failure diagnostic = failure("ROOT_ACCESS_FAILED",failure);
            diagnostics.failure(command,diagnostic);throw diagnostic;
        }
    }
    private static BridgeDiagnostics.Failure atStage(BridgeDiagnostics.Stage stage, BridgeDiagnostics.Failure failure) {
        return new BridgeDiagnostics.Failure(stage,failure.code,failure.getMessage(),failure.getCause());
    }
    private static BridgeDiagnostics.Failure failure(String code,Throwable cause) {
        String message=code.contains("COMMITTED_CLEANUP_PENDING")?AndroidMessages.ROOT_GAME_BRIDGE_05.text():
                code.contains("UNSUPPORTED")?AndroidMessages.ROOT_GAME_BRIDGE_06.text():
                "ROOT_AUTH_REQUIRED".equals(code)?AndroidMessages.ROOT_GAME_BRIDGE_07.text():
                "ROOT_MISSING_DIRECTORY".equals(code)?AndroidMessages.ROOT_GAME_BRIDGE_08.text():
                ("ROOT_TIMEOUT".equals(code)||"ROOT_RUNNER_TIMEOUT".equals(code))?AndroidMessages.ROOT_GAME_BRIDGE_09.text():
                AndroidMessages.ROOT_ACCESS_INCOMPLETE.text(code);
        BridgeDiagnostics.Stage stage = code.startsWith("ROOT_EXPORT_") || code.equals("ROOT_ARCHIVE_INCOMPLETE")
                ? BridgeDiagnostics.Stage.EXPORT : code.startsWith("ROOT_RESTORE_") ? BridgeDiagnostics.Stage.RESTORE
                : code.startsWith("ROOT_RECOVERY_") ? BridgeDiagnostics.Stage.RECOVERY
                : code.equals("ROOT_TARGET_CHANGED") ? BridgeDiagnostics.Stage.TARGET_IDENTITY
                : code.equals("ROOT_REPLY_MISMATCH")||code.equals("ROOT_INVALID_REPLY")||code.equals("ROOT_UNEXPECTED_ARCHIVE")
                    ? BridgeDiagnostics.Stage.PROTOCOL_REPLY : BridgeDiagnostics.Stage.SAVE_READINESS;
        return new BridgeDiagnostics.Failure(stage,code,message,cause);
    }
}
