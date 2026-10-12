package io.github.xyz1001.pdsync.root;

import io.github.xyz1001.pdsync.bridge.SaveArchive;
import io.github.xyz1001.pdsync.root.mutation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static io.github.xyz1001.pdsync.root.RootProtocol.*;

public final class RootRestoreJobTests {
    static int checks;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static byte[] data(String text){return text.getBytes(StandardCharsets.US_ASCII);}
    static String fingerprint(String contents)throws IOException{return SaveArchive.fingerprint(Collections.singletonMap("rankings.dat",SaveArchive.sha256(data(contents))));}
    static byte[] zip(String contents)throws IOException{ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes)){ZipEntry entry=new ZipEntry("rankings.dat");entry.setTime(100000);zip.putNextEntry(entry);zip.write(data(contents));zip.closeEntry();}return bytes.toByteArray();}
    static final class RuntimeStub implements RootRuntime {
        final FakeMutationOs os;
        RuntimeStub(){this(RootRestoreAdapterTests.fixture());}
        RuntimeStub(FakeMutationOs os){this.os=os;}
        int full,metadata,observations;boolean changedFinal;
        RootUidState.Observation observation=RootUidState.parse(26,0,false,data("17 (CACHED_EMPTY)\n"));
        public int effectiveUid(){return os.uid;}
        public RootBinding installed(RootBinding requested)throws IOException{full++;if(changedFinal&&full>1)throw new RootFailure(Status.BINDING_MISMATCH);return requested;}
        public void verifyInstalledMetadata(RootBinding requested){metadata++;}
        public MutationOs mutationOs(RootBinding requested){return os;}
        public MutationOs readMutationOs(RootBinding requested){return os;}
        public RootFileSystem fileSystem(){throw new AssertionError("job must use injectable mutation adapter");}
        public RootUidState.Observation observe(int uid){observations++;return observation;}
        public void quietWait(){}
    }
    static byte[] wire(byte[] archive,String expected,String desired)throws IOException {
        Request request=new Request(Command.RESTORE,RootRestoreAdapterTests.binding(),expected,desired,archive.length);
        ByteArrayOutputStream output=new ByteArrayOutputStream();RootProtocol.writeRequest(output,request,new ByteArrayInputStream(archive));return output.toByteArray();
    }
    static Response run(RuntimeStub runtime,byte[] wire)throws IOException{RequestFrame frame=RootProtocol.readRequestFrame(new ByteArrayInputStream(wire));Response response=new RootService(runtime).execute(frame);ByteArrayOutputStream output=new ByteArrayOutputStream();RootProtocol.writeResponse(output,response);Response decoded=RootProtocol.readResponse(new ByteArrayInputStream(output.toByteArray()),null);RootProtocol.requireReplyMatches(frame.request,decoded);return decoded;}
    static void unchanged(RuntimeStub runtime,String reason){check(new String(runtime.os.files.children.get("rankings.dat").bytes,StandardCharsets.US_ASCII).equals("before"),reason);}
    public static void main(String[] args)throws Exception {
        byte[] valid=wire(zip("after"),fingerprint("before"),fingerprint("after"));
        RuntimeStub runtime=new RuntimeStub();Response response=run(runtime,valid);
        check(response.status==Status.OK,"restore integration status "+response.status+" stage "+response.diagnosticStage);
        check(response.outcome==RootProtocol.Outcome.RESTORED,"actual restored outcome");check(response.contentFingerprint.equals(fingerprint("after")),"verified content receipt");
        check(response.desiredFingerprint.equals(fingerprint("after")),"desired echo");check(runtime.full==2,"only start/end full APK verification");check(runtime.metadata>10&&runtime.metadata<200,"finite cheap intermediate gates "+runtime.metadata);
        check(new String(runtime.os.files.children.get("rankings.dat").bytes,StandardCharsets.US_ASCII).equals("after"),"actual adapter changed bytes");
        check(!runtime.os.workspace().children.containsKey("journal"),"completed journal cleared");
        try(RootMutationReadGuard guard=RootMutationReadGuard.open(runtime.os,RootRestoreAdapterTests.binding())){guard.requireClear();checks++;}
        RuntimeStub noOp=new RuntimeStub();Response same=run(noOp,wire(zip("before"),fingerprint("before"),fingerprint("before")));check(same.status==Status.OK&&same.outcome==RootProtocol.Outcome.UNCHANGED,"same bytes no-op");
        try(RootMutationReadGuard guard=RootMutationReadGuard.open(noOp.os,RootRestoreAdapterTests.binding())){guard.requireClear();checks++;}
        RuntimeStub stale=new RuntimeStub();Response staleResult=run(stale,wire(zip("after"),fingerprint("stale"),fingerprint("after")));check(staleResult.status==Status.RESTORE_CONFLICT,"stale expected refused");unchanged(stale,"stale leaves live");
        try(RootMutationReadGuard guard=RootMutationReadGuard.open(stale.os,RootRestoreAdapterTests.binding())){guard.requireClear();checks++;}
        RuntimeStub mismatch=new RuntimeStub();Response mismatchResult=run(mismatch,wire(zip("after"),fingerprint("before"),fingerprint("other")));check(mismatchResult.status==Status.INVALID_REQUEST,"desired mismatch rejected before mutation");unchanged(mismatch,"desired mismatch leaves live");
        byte[] trailing=Arrays.copyOf(valid,valid.length+1);RuntimeStub extra=new RuntimeStub();Response extraResult=run(extra,trailing);check(extraResult.status!=Status.OK,"trailing protocol byte rejected");unchanged(extra,"protocol trailing leaves live");
        byte[] zipTrailing=Arrays.copyOf(zip("after"),zip("after").length+1);RuntimeStub badZip=new RuntimeStub();check(run(badZip,wire(zipTrailing,fingerprint("before"),fingerprint("after"))).status!=Status.OK,"ZIP trailing rejected");unchanged(badZip,"ZIP trailing leaves live");
        byte[] wrongCentral=zip("after");wrongCentral[wrongCentral.length-6]++;RuntimeStub central=new RuntimeStub();check(run(central,wire(wrongCentral,fingerprint("before"),fingerprint("after"))).status!=Status.OK,"central offset rejected");unchanged(central,"central corruption leaves live");
        RuntimeStub active=new RuntimeStub();active.observation=RootUidState.parse(26,0,false,data("2 (TOP)\n"));check(run(active,valid).status==Status.BUSY,"active game refused");check(active.os.mutations==0,"active game no private or live writes");
        RuntimeStub unknown=new RuntimeStub();unknown.observation=RootUidState.Observation.unknown();check(run(unknown,valid).status==Status.UNRELIABLE,"unknown lifecycle refused");check(unknown.os.mutations==0,"unknown lifecycle no writes");
        RuntimeStub unroot=new RuntimeStub();unroot.os.uid=1000;check(run(unroot,valid).status==Status.NOT_ROOT,"non-root refused");check(unroot.os.touches==0,"non-root no target accesses");
        RuntimeStub unsupported=new RuntimeStub();unsupported.os.noRename=true;Response uns=run(unsupported,valid);check(uns.status==Status.MUTATION_UNAVAILABLE,"native unavailable typed");check(uns.diagnosticCategory==DiagnosticCategory.UNSUPPORTED,"unsupported category");unchanged(unsupported,"native unsupported no live writes");
        try(RootMutationReadGuard guard=RootMutationReadGuard.open(unsupported.os,RootRestoreAdapterTests.binding())){guard.requireClear();checks++;}
        RuntimeStub finalChanged=new RuntimeStub();finalChanged.changedFinal=true;Response changed=run(finalChanged,valid);check(changed.status==Status.BINDING_MISMATCH,"final installed fingerprint rechecked");check(changed.outcome==RootProtocol.Outcome.UNKNOWN,"post-mutation verification failure never claims success");
        FakeMutationOs interrupted=RootRestoreAdapterTests.fixture();
        check(RootRestoreAdapterTests.restoreWithGate(interrupted,RootRestoreAdapterTests.hook(interrupted,true,()->interrupted.noSync=true))==io.github.xyz1001.pdsync.root.restore.RootRestoreTransaction.Outcome.RESTORED_CLEANUP_PENDING,"fixture committed interrupted cleanup");
        interrupted.noSync=false;
        try(RootMutationReadGuard guard=RootMutationReadGuard.open(interrupted,RootRestoreAdapterTests.binding())){try{guard.requireClear();throw new AssertionError("pending read allowed");}catch(RootMutationReadGuard.Failure pending){check(pending.code==RootMutationReadGuard.Code.RECOVERY_PENDING,"journal blocks read jobs");}}
        ByteArrayOutputStream recoveryWire=new ByteArrayOutputStream();RootProtocol.writeRequest(recoveryWire,new Request(Command.RECOVER,RootRestoreAdapterTests.binding()));
        Response recovered=run(new RuntimeStub(interrupted),recoveryWire.toByteArray());check(recovered.status==Status.OK,"concrete recovery dispatch");check(recovered.outcome==RootProtocol.Outcome.RECOVERED,"committed cleanup normalized to RECOVERED");check(recovered.contentFingerprint.equals(fingerprint("new")),"recovery receipt reports actual committed content");
        Response noPending=run(new RuntimeStub(interrupted),recoveryWire.toByteArray());check(noPending.status==Status.NO_PENDING_RECOVERY,"no pending recovery explicit");
        FakeMutationOs.Node temporary=interrupted.fresh(RootFileSystem.Stat.REGULAR|0600,0,0);temporary.bytes=data("partial never-published journal");
        String temporaryName="journal-"+String.join("",Collections.nCopies(32,"a"));interrupted.workspace().children.put(temporaryName,temporary);
        Response cleanedTemp=run(new RuntimeStub(interrupted),recoveryWire.toByteArray());check(cleanedTemp.status==Status.OK&&cleanedTemp.outcome==RootProtocol.Outcome.RECOVERED,"orphan journal temporary explicit recovery");check(!interrupted.workspace().children.containsKey(temporaryName),"private temporary reclaimed");
        FakeMutationOs.Node alien=interrupted.fresh(RootFileSystem.Stat.REGULAR|0600,10001,10001);alien.bytes=data("unknown");interrupted.workspace().children.put(temporaryName,alien);
        Response refusedTemp=run(new RuntimeStub(interrupted),recoveryWire.toByteArray());check(refusedTemp.status!=Status.OK,"invalid journal temporary recovery refused");check(interrupted.workspace().children.get(temporaryName)==alien,"invalid private temporary preserved");
        RuntimeStub lock=new RuntimeStub();try(RootRestoreAdapter held=new RootRestoreAdapter(lock.os,RootRestoreAdapterTests.binding(),RootRestoreAdapterTests.GATE)){check(run(lock,valid).status==Status.BUSY,"orphan bounded job lock rejects retry");}
        MutationOs.Failure denied=new MutationOs.Failure(MutationOs.Error.PERMISSION,13);Request diagnostic=new Request(Command.RESTORE,RootRestoreAdapterTests.binding(),fingerprint("before"),fingerprint("after"),100);
        Response permission=RootRestoreJob.failure(diagnostic,denied,DiagnosticStage.MOVE_NEW);check(permission.diagnosticCategory==DiagnosticCategory.PERMISSION&&permission.errno==13&&permission.diagnosticStage==DiagnosticStage.MOVE_NEW,"bounded permission stage errno");
        Response crossDevice=RootRestoreJob.failure(diagnostic,new MutationOs.Failure(MutationOs.Error.CROSS_DEVICE,18),DiagnosticStage.MUTATION_PREFLIGHT);check(crossDevice.diagnosticCategory==DiagnosticCategory.CROSS_DEVICE&&crossDevice.errno==18,"cross-device distinction");
        System.out.println("Root job host tests passed: "+checks+" assertions; successful job full-hash="+runtime.full+", metadata-gates="+runtime.metadata+", lifecycle-checks="+runtime.observations+"; no Android execution");
    }
}
