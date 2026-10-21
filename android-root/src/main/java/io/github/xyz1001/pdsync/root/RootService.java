package io.github.xyz1001.pdsync.root;

import io.github.xyz1001.pdsync.root.mutation.MutationOs;
import io.github.xyz1001.pdsync.root.mutation.RootMutationReadGuard;
import java.io.IOException;

/** One-job dispatcher. Reads hold an interlock; writes delegate only to the concrete transaction job. */
public final class RootService {
    private final RootRuntime runtime;
    public RootService(RootRuntime runtime) { this.runtime=runtime; }

    /** Streaming entry. The write job must finish the exact framed upload before any game mutation. */
    public RootProtocol.Response execute(RootProtocol.RequestFrame frame) {
        if(frame==null)return RootProtocol.Response.failure(RootProtocol.Status.INVALID_REQUEST,"");
        RootProtocol.Request request=frame.request;
        if(runtime.effectiveUid()!=0)return failed(request,RootProtocol.Status.NOT_ROOT,RootProtocol.DiagnosticStage.EUID,null);
        if(request.command==RootProtocol.Command.RESTORE)
            return new RootRestoreJob(runtime).execute(frame);
        try {frame.finish();}
        catch(IOException malformed){return failed(request,RootProtocol.Status.INVALID_REQUEST,RootProtocol.DiagnosticStage.ARCHIVE_READ,malformed);}
        if(request.command==RootProtocol.Command.RECOVER)
            return new RootRestoreJob(runtime).execute(frame);
        return execute(request);
    }

    /** Read-only local compatibility entry; writes require a validated RequestFrame. */
    public RootProtocol.Response execute(RootProtocol.Request request) {
        RootProtocol.DiagnosticStage stage=RootProtocol.DiagnosticStage.EUID;
        try {
            if(runtime.effectiveUid()!=0)return failed(request,RootProtocol.Status.NOT_ROOT,stage,null);
            if(request==null)return RootProtocol.Response.failure(RootProtocol.Status.INVALID_REQUEST,"");
            if(request.command==RootProtocol.Command.RESTORE||request.command==RootProtocol.Command.RECOVER)
                return failed(request,RootProtocol.Status.UNSUPPORTED,stage,null);
            stage=RootProtocol.DiagnosticStage.PM_BINDING;requireBinding(request.binding);
            RootSaveTree tree=new RootSaveTree(runtime.fileSystem(),request.binding);
            if(request.command==RootProtocol.Command.PROBE) {
                // Setup proves root and package identity only. Save directories and lifecycle
                // belong to the later read operation, including when files does not exist yet.
                stage=RootProtocol.DiagnosticStage.PM_BINDING;runtime.verifyInstalledMetadata(request.binding);
                return readReply(request,"",0,0,"","UNKNOWN",false,null);
            }
            stage=RootProtocol.DiagnosticStage.PRIVATE_WORKSPACE;
            try(RootRuntime.ReadGuard guard=runtime.openReadGuard(request.binding)) {
                guard.requireClear();
                if(request.command==RootProtocol.Command.OBSERVE) {
                    stage=RootProtocol.DiagnosticStage.ROOT_OPEN;tree.probe();
                    stage=RootProtocol.DiagnosticStage.PM_BINDING;runtime.verifyInstalledMetadata(request.binding);
                    stage=RootProtocol.DiagnosticStage.PRIVATE_WORKSPACE;guard.requireClear();guard.revalidate();
                    return readReply(request,"",0,0,"","UNKNOWN",false,null);
                }
                boolean export=request.command==RootProtocol.Command.EXPORT;
                boolean observe=request.command==RootProtocol.Command.READ_SNAPSHOT;
                if(!export&&!observe&&request.command!=RootProtocol.Command.INSPECT)throw new RootFailure(RootProtocol.Status.INVALID_REQUEST);
                stage=RootProtocol.DiagnosticStage.LAYOUT_SCAN;
                RootSaveTree.Snapshot captured=observe?tree.observe():tree.scan(export);
                stage=RootProtocol.DiagnosticStage.ARCHIVE_READ;
                byte[] archive=observe?tree.observation(captured):export?tree.archive(captured):null;
                stage=RootProtocol.DiagnosticStage.PM_BINDING;runtime.verifyInstalledMetadata(request.binding);
                stage=RootProtocol.DiagnosticStage.ROOT_OPEN;tree.requireRootIdentity(captured);
                stage=RootProtocol.DiagnosticStage.PRIVATE_WORKSPACE;guard.requireClear();guard.revalidate();
                return readReply(request,captured.contentState,captured.inventory.hashes.size(),captured.inventory.bytes,
                        captured.inventory.fingerprint(),"UNKNOWN",false,archive);
            }
        }catch(RootFailure failure){return failed(request,failure.status,stage,failure);}
        catch(RootMutationReadGuard.Failure failure) {
            RootProtocol.Status status=failure.code==RootMutationReadGuard.Code.RECOVERY_PENDING?RootProtocol.Status.RECOVERY_REQUIRED:
                    failure.code==RootMutationReadGuard.Code.BUSY?RootProtocol.Status.BUSY:
                    failure.code==RootMutationReadGuard.Code.CHANGED?RootProtocol.Status.CHANGED:
                    failure.code==RootMutationReadGuard.Code.NOT_ROOT?RootProtocol.Status.NOT_ROOT:RootProtocol.Status.UNSAFE_PATH;
            return failed(request,status,stage,failure);
        }catch(IOException|RuntimeException failure){return failed(request,RootProtocol.Status.IO_ERROR,stage,failure);}
    }
    private static RootProtocol.Response readReply(RootProtocol.Request request,String content,int count,long bytes,
            String fingerprint,String observation,boolean reliable,byte[] archive)throws IOException {
        return new RootProtocol.Response(RootProtocol.Status.OK,request.binding.fingerprint(),content,count,bytes,
                fingerprint,observation,reliable,archive).withRequest(request,RootProtocol.Outcome.READ_ONLY);
    }
    private static RootProtocol.Response failed(RootProtocol.Request request,RootProtocol.Status status,
            RootProtocol.DiagnosticStage stage,Throwable cause) {
        String binding="";
        if(request!=null)try{binding=request.binding.fingerprint();}catch(IOException invalid){status=RootProtocol.Status.INVALID_REQUEST;}
        RootProtocol.DiagnosticCategory category=RootProtocol.DiagnosticCategory.IO;int errno=0;
        if(status==RootProtocol.Status.NOT_ROOT)category=RootProtocol.DiagnosticCategory.PERMISSION;
        else if(status==RootProtocol.Status.UNSUPPORTED||status==RootProtocol.Status.MUTATION_UNAVAILABLE)category=RootProtocol.DiagnosticCategory.UNSUPPORTED;
        else if(status==RootProtocol.Status.CHANGED||status==RootProtocol.Status.BINDING_MISMATCH||status==RootProtocol.Status.RECOVERY_REQUIRED)category=RootProtocol.DiagnosticCategory.CHANGED;
        else if(status==RootProtocol.Status.INVALID_REQUEST||status==RootProtocol.Status.UNSAFE_PATH)category=RootProtocol.DiagnosticCategory.MALFORMED;
        else if(status==RootProtocol.Status.TOO_LARGE)category=RootProtocol.DiagnosticCategory.LIMIT;
        for(int i=0;cause!=null&&i<8;i++,cause=cause.getCause()) {
            if(cause instanceof MutationOs.Failure) {
                MutationOs.Failure os=(MutationOs.Failure)cause;errno=os.errno;
                stage=kernelStage(os.operation,stage);
                switch(os.error) {
                    case MISSING:category=RootProtocol.DiagnosticCategory.MISSING;break;
                    case PERMISSION:category=RootProtocol.DiagnosticCategory.PERMISSION;break;
                    case CROSS_DEVICE:category=RootProtocol.DiagnosticCategory.CROSS_DEVICE;break;
                    case UNSUPPORTED:category=RootProtocol.DiagnosticCategory.UNSUPPORTED;break;
                    case EXISTS:category=RootProtocol.DiagnosticCategory.EXISTS;break;
                    default:break;
                }
                break;
            }
            if(cause instanceof SecurityException)category=RootProtocol.DiagnosticCategory.PERMISSION;
        }
        RootProtocol.Response result=RootProtocol.Response.failure(status,binding).withDiagnostic(stage,category,errno);
        return request==null?result:result.withRequest(request,RootProtocol.Outcome.UNKNOWN);
    }
    static RootProtocol.DiagnosticStage kernelStage(MutationOs.Operation operation,RootProtocol.DiagnosticStage fallback) {
        if(operation==null||operation==MutationOs.Operation.NONE)return fallback;
        return RootProtocol.DiagnosticStage.valueOf("KERNEL_"+operation.name());
    }
    private void requireBinding(RootBinding expected)throws IOException {
        if(!expected.matchesInstalled(runtime.installed(expected)))throw new RootFailure(RootProtocol.Status.BINDING_MISMATCH);
    }
}
