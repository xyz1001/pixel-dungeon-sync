package io.github.xyz1001.pdsync.android;

import android.content.Context;
import io.github.xyz1001.pdsync.root.RootProtocol;
import io.github.xyz1001.pdsync.root.RootBinding;
import java.io.*;
import java.nio.file.*;
import java.util.Map;
import java.util.function.BooleanSupplier;
import pdsync.DirectoryMetadataKt;
import pdsync.android.app.AndroidGameIdentity;
import pdsync.android.app.AndroidGameTarget;

/** Read-only root access adapter. Core comparison, DAV and UUID logic remain unchanged. */
public final class RootGameBridge implements GameAccessBackend, AutoCloseable {
    public interface TargetVerifier { void requireUnchanged(SelectedRootGame selected) throws Exception; }
    private static final Capabilities CAPABILITIES=new Capabilities(true,false,true,false);
    private final SelectedRootGame selected;
    private final AndroidGameIdentity identity;
    private final TargetVerifier verifier;
    private final RootProcessTransport.Exchange exchange;
    private final BooleanSupplier authorized;
    private final File stagingDirectory;
    private RootProcessTransport ownedTransport;

    public RootGameBridge(Context context, SelectedRootGame selected, AndroidGameIdentity identity)throws Exception {
        this(context, selected, identity, new RootProcessTransport(context.getApplicationInfo().sourceDir));
    }
    private RootGameBridge(Context context, SelectedRootGame selected, AndroidGameIdentity identity,
            RootProcessTransport transport) {
        this(selected,identity,new RootTargetResolver(context)::requireUnchanged,
                transport::exchange,()->SyncRuntime.rootAuthorized(context),context.getCacheDir());
        this.ownedTransport=transport;
    }
    public RootGameBridge(SelectedRootGame selected, AndroidGameIdentity identity, TargetVerifier verifier,
            RootProcessTransport.Exchange exchange, BooleanSupplier authorized, File stagingDirectory) {
        if(selected==null||verifier==null||exchange==null||authorized==null||stagingDirectory==null)throw new IllegalArgumentException("Missing root client dependency");
        this.selected=selected;this.identity=identity;this.verifier=verifier;this.exchange=exchange;
        this.authorized=authorized;this.stagingDirectory=stagingDirectory;
    }
    @Override public void close(){if(ownedTransport!=null)ownedTransport.close();}
    @Override public Mode mode(){return Mode.ROOT_DIRECT;}
    @Override public Capabilities capabilities(){return CAPABILITIES;}
    /** Only a foreground explicit user action may call this method. */
    public String probeAuthorization() {
        call(RootProtocol.Command.PROBE,null,true);
        return "Root 只读授权探测成功；恢复未启用，尚未完成 Android 实机验收。";
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
        return "Root 已核对当前目标与存档布局；只读导出仍需稳定性检查。尚未完成 Android 实机验收。";
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
        }catch(BridgeDiagnostics.Failure failure){throw failure;}
        catch(Exception failure){throw failure("ROOT_EXPORT_FAILED",failure);}
        finally {if(archive!=null)try{Files.deleteIfExists(archive);}catch(IOException ignored){}}
    }
    @Override public void restoreFrom(Path source, Map<String,String> expectedCurrentFiles){throw failure("ROOT_RESTORE_UNSUPPORTED",null);}
    @Override public String recoverInterruptedBackup(){throw failure("ROOT_RECOVERY_UNSUPPORTED",null);}
    private void verifyTarget() {
        try {verifier.requireUnchanged(selected);}
        catch(Exception changed){throw failure("ROOT_TARGET_CHANGED",changed);}
    }
    private RootProtocol.Response call(RootProtocol.Command command,OutputStream archive,boolean explicitProbe) {
        try {
            if(!explicitProbe&&!authorized.getAsBoolean())throw failure("ROOT_AUTH_REQUIRED",null);
            verifyTarget();
            RootProtocol.Response reply=exchange.exchange(new RootProtocol.Request(command,selected.binding()),archive);
            if(!explicitProbe&&!authorized.getAsBoolean())throw failure("ROOT_CANCELLED",null);
            verifyTarget();
            if(reply.status!=RootProtocol.Status.OK)throw failure("ROOT_"+reply.status.name(),null);
            if(!selected.binding().fingerprint().equals(reply.bindingFingerprint))throw failure("ROOT_BINDING_MISMATCH",null);
            if(command!=RootProtocol.Command.EXPORT && reply.archiveLength!=0)throw failure("ROOT_UNEXPECTED_ARCHIVE",null);
            if(command==RootProtocol.Command.INSPECT || command==RootProtocol.Command.EXPORT) {
                if(!RootBinding.isHash(reply.contentFingerprint)
                        || !("EMPTY_DIRECTORY".equals(reply.contentState) || "NO_TRANSFERABLE_FILES".equals(reply.contentState)
                        || "ALLOWLISTED_FILES_PRESENT".equals(reply.contentState)))throw failure("ROOT_INVALID_REPLY",null);
                if(!reply.reliable)throw failure("ROOT_UNRELIABLE",null);
                if(!("CACHED".equals(reply.observationState)||"ABSENT".equals(reply.observationState)))throw failure("ROOT_BUSY",null);
            }
            return reply;
        }catch(BridgeDiagnostics.Failure failure){throw failure;}
        catch(RootProcessTransport.Failure failure){throw failure(failure.code,failure);}
        catch(Exception failure){throw failure("ROOT_ACCESS_FAILED",failure);}
    }
    private static BridgeDiagnostics.Failure failure(String code,Throwable cause) {
        String message=code.contains("UNSUPPORTED")?"Root 模式尚未实现安全恢复，当前只允许读取与导出。":
                "ROOT_AUTH_REQUIRED".equals(code)?"请先点击“授权并检查 Root”。不会在启动时自动请求 Root。":
                "ROOT_MISSING_DIRECTORY".equals(code)?"游戏尚未创建存档目录。请启动一次游戏，正常退出后再授权并检查 Root。":
                "ROOT_TIMEOUT".equals(code)?"Root 请求超时，已关闭本次进程。请检查 Root 管理器后手动重试。":
                "Root 存档访问未完成（"+code+"）。请保留游戏安装，重新检查授权与目标。";
        return new BridgeDiagnostics.Failure(BridgeDiagnostics.Stage.SAVE_READINESS,code,message,cause);
    }
}
