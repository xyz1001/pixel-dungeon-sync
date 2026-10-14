package io.github.xyz1001.pdsync.android;

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

/** Short-lived root access adapter. Core comparison, DAV and UUID logic remain unchanged. */
public final class RootGameBridge implements GameAccessBackend, AutoCloseable {
    public interface TargetVerifier { void requireUnchanged(SelectedRootGame selected) throws Exception; }
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
        this(selected,identity,new RootTargetResolver(context)::requireUnchanged,
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
        if(selected==null||verifier==null||exchange==null||authorized==null||stagingDirectory==null)throw new IllegalArgumentException("Missing root client dependency");
        this.supported=new Capabilities(true,mutationSupported,true,mutationSupported);
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
        return "Root 访问探测成功；每次操作仍会重新检查目标、运行状态与文件。尚未完成 Android 实机验收。";
    }
    @Override public AndroidGameTarget targetGame() {
        try {
            verifyTarget();
            AndroidGameIdentity target=identity==null?new AndroidGameIdentity(selected.gamePackage,selected.gameLabel,
                    selected.originIdentity,selected.namespaceId):identity;
            return new AndroidGameTarget(target,selected.gameVersionName,selected.gameVersionCode,
                    selected.installedApkSetSha256,selected.sourceApkSetSha256,false);
        }catch(Exception failure){throw failure("ROOT_TARGET_CHANGED",failure);}
    }
    @Override public String inspectReady() {
        RootProtocol.Response reply=call(RootProtocol.Command.INSPECT,null,false);
        if(!("EMPTY_DIRECTORY".equals(reply.contentState) || "NO_TRANSFERABLE_FILES".equals(reply.contentState)
                || "ALLOWLISTED_FILES_PRESENT".equals(reply.contentState)))throw failure("ROOT_SAVE_NOT_READY",null);
        return "Root 已核对当前目标与存档布局；读写操作仍需稳定性检查。尚未完成 Android 实机验收。";
    }
    @Override public Observation observeLifecycle() {
        try {
            RootProtocol.Response reply=call(RootProtocol.Command.OBSERVE,null,false);
            boolean foreground="FOREGROUND".equals(reply.observationState);
            boolean present=!("ABSENT".equals(reply.observationState)||"UNKNOWN".equals(reply.observationState));
            boolean quiet="CACHED".equals(reply.observationState)||"ABSENT".equals(reply.observationState);
            return new Observation(foreground,present,reply.reliable&&quiet,reply.reliable);
        }catch(Exception unavailable){return Observation.unknown();}
    }
    @Override public void exportTo(Path destination) {
        Path archive=null;
        try {
            targetGame();
            archive=Files.createTempFile(stagingDirectory.toPath(),"root-export-",".zip");
            RootProtocol.Response reply;
            try(OutputStream output=Files.newOutputStream(archive,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                reply=call(RootProtocol.Command.EXPORT,output,false);
            }
            RootExportValidation.requireCompleteArchive(archive.toFile(),reply.archiveLength,reply.fileCount);
            SaveArchiveCodec.Inventory inventory;
            try(InputStream input=Files.newInputStream(archive,LinkOption.NOFOLLOW_LINKS)) {
                inventory=SaveArchiveCodec.readExportArchive(input,destination);
            }
            if(!DirectoryMetadataKt.contentSha256(inventory.hashes).equals(reply.contentFingerprint)
                    || inventory.hashes.size()!=reply.fileCount || inventory.bytes!=reply.contentBytes)
                throw failure("ROOT_EXPORT_MISMATCH",null);
            targetGame();
        }catch(BridgeDiagnostics.Failure failure){throw atStage(BridgeDiagnostics.Stage.EXPORT,failure);}
        catch(Exception failure){throw failure("ROOT_EXPORT_FAILED",failure);}
        finally {if(archive!=null)try{Files.deleteIfExists(archive);}catch(IOException ignored){}}
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
            if(reply.outcome==RootProtocol.Outcome.UNCHANGED&&!expected.equals(desiredFingerprint))
                throw failure("ROOT_RESTORE_RECEIPT_INVALID",null);
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
            return "Root 恢复记录已处理并重新核对（"+reply.fileCount+" 个文件）。";
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
    private void verifyTarget() {
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
            if(command!=RootProtocol.Command.EXPORT && reply.archiveLength!=0)throw failure("ROOT_UNEXPECTED_ARCHIVE",null);
            if(command!=RootProtocol.Command.RESTORE&&command!=RootProtocol.Command.RECOVER&&reply.outcome!=RootProtocol.Outcome.READ_ONLY)
                throw failure("ROOT_REPLY_MISMATCH",null);
            if(command==RootProtocol.Command.INSPECT || command==RootProtocol.Command.EXPORT) {
                if(!RootBinding.isHash(reply.contentFingerprint)
                        || !("EMPTY_DIRECTORY".equals(reply.contentState) || "NO_TRANSFERABLE_FILES".equals(reply.contentState)
                        || "ALLOWLISTED_FILES_PRESENT".equals(reply.contentState)))throw failure("ROOT_INVALID_REPLY",null);
                if(!reply.reliable)throw failure("ROOT_UNRELIABLE",null);
                if(!("CACHED".equals(reply.observationState)||"ABSENT".equals(reply.observationState)))throw failure("ROOT_BUSY",null);
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
        String message=code.contains("COMMITTED_CLEANUP_PENDING")?"存档写入已经完成并核对，但恢复记录清理尚未完成。请继续检查恢复记录，不要重复覆盖或卸载游戏。":
                code.contains("UNSUPPORTED")?"当前安装或设备尚未提供可验证的安全 Root 恢复能力，未修改游戏存档。":
                "ROOT_AUTH_REQUIRED".equals(code)?"Root 游戏选择已变化或不可读取，请重新选择。只会在明确操作时向 Root 管理器请求权限。":
                "ROOT_MISSING_DIRECTORY".equals(code)?"游戏尚未创建存档目录。请启动一次游戏，正常退出后再授权并检查 Root。":
                ("ROOT_TIMEOUT".equals(code)||"ROOT_RUNNER_TIMEOUT".equals(code))?"Root 请求超时，本次结果尚未确认。若正在恢复，请先检查恢复记录，不要启动游戏或重复覆盖。":
                "Root 存档访问未完成（"+code+"）。请保留游戏安装，重新检查授权与目标。";
        BridgeDiagnostics.Stage stage = code.startsWith("ROOT_EXPORT_") || code.equals("ROOT_ARCHIVE_INCOMPLETE")
                ? BridgeDiagnostics.Stage.EXPORT : code.startsWith("ROOT_RESTORE_") ? BridgeDiagnostics.Stage.RESTORE
                : code.startsWith("ROOT_RECOVERY_") ? BridgeDiagnostics.Stage.RECOVERY
                : code.equals("ROOT_TARGET_CHANGED") ? BridgeDiagnostics.Stage.TARGET_IDENTITY
                : code.equals("ROOT_REPLY_MISMATCH")||code.equals("ROOT_INVALID_REPLY")||code.equals("ROOT_UNEXPECTED_ARCHIVE")
                    ? BridgeDiagnostics.Stage.PROTOCOL_REPLY : BridgeDiagnostics.Stage.SAVE_READINESS;
        return new BridgeDiagnostics.Failure(stage,code,message,cause);
    }
}
