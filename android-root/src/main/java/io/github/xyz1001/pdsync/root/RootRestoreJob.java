package io.github.xyz1001.pdsync.root;

import io.github.xyz1001.pdsync.bridge.SaveArchive;
import io.github.xyz1001.pdsync.root.mutation.MutationOs;
import io.github.xyz1001.pdsync.root.mutation.RootMutationSession;
import io.github.xyz1001.pdsync.root.restore.RootRestoreTransaction;
import io.github.xyz1001.pdsync.root.restore.RootRestoreTransaction.*;
import java.io.IOException;
import static io.github.xyz1001.pdsync.root.RootProtocol.*;

/** One bounded restore/recovery dispatch. The session lock remains held through final verification. */
public final class RootRestoreJob {
    private final RootRuntime runtime;
    public RootRestoreJob(RootRuntime runtime){this.runtime=runtime;}
    public Response execute(RequestFrame frame) {
        Request request=frame.request; DiagnosticStage stage=DiagnosticStage.EUID;
        RootRestoreAdapter adapter=null;
        try {
            if(runtime.effectiveUid()!=0)throw new RootFailure(Status.NOT_ROOT);
            if(request.command!=Command.RESTORE&&request.command!=Command.RECOVER)throw new RootFailure(Status.INVALID_REQUEST);
            stage=DiagnosticStage.PM_BINDING; requireFullBinding(request.binding);
            stage=DiagnosticStage.UID_STATE; requireQuiet(request.binding);
            stage=DiagnosticStage.NATIVE_LOAD;
            MutationOs os=runtime.mutationOs(request.binding);
            stage=DiagnosticStage.PRIVATE_WORKSPACE;
            adapter=new RootRestoreAdapter(os,request.binding,new RootRestoreAdapter.Gate(){
                public void requireSafe(RootBinding binding)throws IOException{runtime.verifyInstalledMetadata(binding);requireQuiet(binding);}
                public void quietWait()throws IOException{runtime.quietWait();}
            });
            stage=DiagnosticStage.NONE;
            RootRestoreTransaction engine=new RootRestoreTransaction();
            RootProtocol.Outcome outcome;
            if(request.command==Command.RESTORE) {
                if(adapter.pending())throw new RootFailure(Status.RECOVERY_REQUIRED);
                Incoming incoming=adapter.receive(frame); // Exact archive + outer EOF verified before any live operation.
                LocalSnapshot before=adapter.capture(adapter.target());
                if(!SaveArchive.fingerprint(before.tree.hashes()).equals(request.expectedCurrentFingerprint))throw new RootFailure(Status.RESTORE_CONFLICT);
                RootRestoreTransaction.Outcome result=engine.restore(adapter,adapter.target(),incoming,before.tree.hashes());
                outcome=result==RootRestoreTransaction.Outcome.UNCHANGED?RootProtocol.Outcome.UNCHANGED:
                    result==RootRestoreTransaction.Outcome.RESTORED_CLEANUP_PENDING?RootProtocol.Outcome.RESTORED_CLEANUP_PENDING:RootProtocol.Outcome.RESTORED;
                adapter.discardReceivedIfUnpublished();
            } else {
                frame.finish(); boolean pending=adapter.pending();
                if(!pending)throw new RootFailure(Status.NO_PENDING_RECOVERY);
                adapter.resumePrepared();
                Journal journal=adapter.readJournal(adapter.target());
                RootRestoreTransaction.Outcome result=journal==null?RootRestoreTransaction.Outcome.RECOVERED:engine.recover(adapter,adapter.target());
                outcome=(result==RootRestoreTransaction.Outcome.RESTORED_CLEANUP_PENDING||result==RootRestoreTransaction.Outcome.RECOVERED_CLEANUP_PENDING)
                    ?RootProtocol.Outcome.RECOVERED_CLEANUP_PENDING:RootProtocol.Outcome.RECOVERED;
            }
            stage=DiagnosticStage.NONE;
            LocalSnapshot verified=adapter.capture(adapter.target());
            stage=DiagnosticStage.PM_BINDING;requireFullBinding(request.binding);
            adapter.verifyBindingAndQuiescent(adapter.target());
            long bytes=0;for(Entry entry:verified.tree.entries.values())if(!entry.directory())bytes+=entry.content.bytes;
            RootUidState.Observation observed=runtime.observe(request.binding.uid);
            if(!observed.reliable||!observed.quiescent())throw new RootFailure(Status.CHANGED);
            Response result=new Response(Status.OK,request.binding.fingerprint(),verified.tree.hashes().isEmpty()?"EMPTY_DIRECTORY":"ALLOWLISTED_FILES_PRESENT",
                verified.tree.hashes().size(),bytes,SaveArchive.fingerprint(verified.tree.hashes()),observed.state,true,null).withRequest(request,outcome);
            if(outcome==RootProtocol.Outcome.RESTORED_CLEANUP_PENDING||outcome==RootProtocol.Outcome.RECOVERED_CLEANUP_PENDING)
                result=result.withDiagnostic(DiagnosticStage.CLEANUP,DiagnosticCategory.IO,0);
            return result;
        } catch(IOException|RuntimeException failure) {
            if(stage==DiagnosticStage.NONE&&adapter!=null)stage=adapter.stage();
            return failure(request,failure,stage);
        } finally {
            if(adapter!=null){try{adapter.discardReceivedIfUnpublished();}catch(IOException preserved){}try{adapter.close();}catch(IOException ignored){}}
        }
    }
    private void requireFullBinding(RootBinding expected)throws IOException {
        if(!expected.matchesInstalled(runtime.installed(expected)))throw new RootFailure(Status.BINDING_MISMATCH);
    }
    private void requireQuiet(RootBinding binding)throws IOException {
        RootUidState.Observation state=runtime.observe(binding.uid);
        if(!state.reliable)throw new RootFailure(Status.UNRELIABLE);
        if(!state.quiescent())throw new RootFailure(Status.BUSY);
    }
    static Response failure(Request request,Throwable failure,DiagnosticStage stage) {
        Status status=request.command==Command.RECOVER?Status.RECOVERY_FAILED:Status.RESTORE_FAILED;
        DiagnosticStage logicalStage=stage;
        DiagnosticCategory category=DiagnosticCategory.IO;int errno=0;
        if(failure instanceof RootFailure){status=((RootFailure)failure).status;
            switch(status){case INVALID_REQUEST:category=DiagnosticCategory.MALFORMED;break;case TOO_LARGE:category=DiagnosticCategory.LIMIT;break;
            case CHANGED:case BINDING_MISMATCH:case RESTORE_CONFLICT:case RECOVERY_CONFLICT:category=DiagnosticCategory.CHANGED;break;
            case UNSUPPORTED:case MUTATION_UNAVAILABLE:case ATTRIBUTES_UNSUPPORTED:category=DiagnosticCategory.UNSUPPORTED;break;default:break;}}
        if(failure instanceof RootRestoreTransaction.Failure){Code code=((RootRestoreTransaction.Failure)failure).code;
            switch(code){case INVALID_INPUT:status=Status.INVALID_REQUEST;category=DiagnosticCategory.MALFORMED;break;
            case STALE_EXPORT:status=Status.RESTORE_CONFLICT;category=DiagnosticCategory.CHANGED;break;
            case TARGET_CHANGED:status=Status.BINDING_MISMATCH;category=DiagnosticCategory.CHANGED;break;
            case METADATA_AMBIGUOUS:status=Status.ATTRIBUTES_UNSUPPORTED;category=DiagnosticCategory.UNSUPPORTED;break;
            case RESTORE_ROLLED_BACK:status=Status.RESTORE_FAILED;break;
            default:status=Status.RECOVERY_REQUIRED;category=DiagnosticCategory.CHANGED;break;}}
        if(failure instanceof RootMutationSession.Failure){RootMutationSession.Code code=((RootMutationSession.Failure)failure).code;
            switch(code){case NOT_ROOT:status=Status.NOT_ROOT;break;case BUSY:status=Status.BUSY;break;case UNSUPPORTED:status=Status.MUTATION_UNAVAILABLE;category=DiagnosticCategory.UNSUPPORTED;break;
            case TOO_LARGE:status=Status.TOO_LARGE;category=DiagnosticCategory.LIMIT;break;default:status=Status.UNSAFE_PATH;category=DiagnosticCategory.CHANGED;break;}}
        // Wrapped transaction failures retain the original bounded kernel category and errno.
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof MutationOs.Failure){MutationOs.Failure kernel=(MutationOs.Failure)cause;errno=kernel.errno;
            stage=RootService.kernelStage(kernel.operation,stage);
            switch(kernel.error){case PERMISSION:category=DiagnosticCategory.PERMISSION;break;case CROSS_DEVICE:category=DiagnosticCategory.CROSS_DEVICE;break;
            case EXISTS:category=DiagnosticCategory.EXISTS;break;case UNSUPPORTED:category=DiagnosticCategory.UNSUPPORTED;if(status==Status.RESTORE_FAILED||status==Status.RECOVERY_FAILED)status=Status.MUTATION_UNAVAILABLE;break;
            case BUSY:status=Status.BUSY;break;default:category=DiagnosticCategory.IO;break;}break;}
        if(category==DiagnosticCategory.UNSUPPORTED&&logicalStage==DiagnosticStage.ATTRIBUTES_APPLY)status=Status.ATTRIBUTES_UNSUPPORTED;
        try{return Response.failure(status,request).withDiagnostic(stage,category,errno);}
        catch(IOException impossible){return Response.failure(Status.INVALID_REQUEST,"").withDiagnostic(stage,category,errno);}
    }
}
