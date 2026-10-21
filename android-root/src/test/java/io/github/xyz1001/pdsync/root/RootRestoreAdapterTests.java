package io.github.xyz1001.pdsync.root;

import io.github.xyz1001.pdsync.bridge.SaveArchive;
import io.github.xyz1001.pdsync.bridge.SaveDirectoryPolicy;
import io.github.xyz1001.pdsync.root.mutation.FakeMutationOs;
import io.github.xyz1001.pdsync.root.mutation.RootMutationSession;
import io.github.xyz1001.pdsync.root.restore.RootRestoreTransaction;
import io.github.xyz1001.pdsync.root.restore.RootRestoreTransaction.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class RootRestoreAdapterTests {
    static int checks;
    static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    static RootBinding binding()throws IOException{return new RootBinding("com.example.game",0,10001,"/data/user/0/com.example.game",String.join("",Collections.nCopies(64,"a")),Collections.singletonMap("/data/app/com.example.game-x/base.apk",String.join("",Collections.nCopies(64,"b"))));}
    static final RootRestoreAdapter.Gate GATE=new RootRestoreAdapter.Gate(){public void requireSafe(RootBinding b){}public void quietWait(){}};
    static FakeMutationOs fixture(){FakeMutationOs os=new FakeMutationOs();os.contentResult=FakeMutationOs.contentFixture(SaveDirectoryPolicy.ContentState.ALLOWLISTED_FILES_PRESENT);os.file(os.files,"rankings.dat","before");return os;}
    static byte[] data(String s){return s.getBytes(StandardCharsets.US_ASCII);}
    static Incoming incoming(Map<String,byte[]> bytes)throws IOException {Map<String,Content> content=new TreeMap<>();for(Map.Entry<String,byte[]> e:bytes.entrySet())content.put(e.getKey(),new Content(SaveArchive.sha256(e.getValue()),e.getValue().length,100000));return new Incoming("host-verified",content);}
    static Outcome restore(FakeMutationOs os,Map<String,byte[]> bytes)throws IOException {
        try(RootRestoreAdapter adapter=new RootRestoreAdapter(os,binding(),GATE)){
            adapter.incomingSource(name->bytes.get(name));LocalSnapshot before=adapter.capture(adapter.target());
            return new RootRestoreTransaction().restore(adapter,adapter.target(),incoming(bytes),before.tree.hashes());
        }
    }
    public static void main(String[] args)throws Exception {
        FakeMutationOs os=fixture();
        for(int i=0;i<12;i++){
            Map<String,byte[]> bytes=new TreeMap<>();bytes.put("rankings.dat",data("after-"+i));bytes.put("game1/game.dat",data("run-"+i));bytes.put("game1/sub/level.dat",data("level"));
            Outcome result=restore(os,bytes);check(result==Outcome.RESTORED,"restore terminal "+i+" got "+result);
            check(new String(os.files.children.get("rankings.dat").bytes,StandardCharsets.US_ASCII).equals("after-"+i),"restored bytes");
            check(!os.workspace().children.containsKey("journal"),"journal removed after success");
            FakeMutationOs.Node active=os.workspace().children.get("active");
            for(String area:new String[]{"incoming","displaced","backup","cleanup"})check(active.children.get(area).children.isEmpty(),"successful private area reclaimed");
            try(RootRestoreAdapter adapter=new RootRestoreAdapter(os,binding(),GATE)){check(!adapter.pending(),"successful backend remains reusable");}
        }
        FakeMutationOs unsupported=fixture();unsupported.noRename=true;
        try{restore(unsupported,Collections.singletonMap("rankings.dat",data("new")));throw new AssertionError("unsupported rename accepted");}catch(IOException expected){checks++;}
        check(new String(unsupported.files.children.get("rankings.dat").bytes,StandardCharsets.US_ASCII).equals("before"),"unsupported leaves live unchanged");
        unsupported.noRename=false;
        try(RootRestoreAdapter adapter=new RootRestoreAdapter(unsupported,binding(),GATE)){check(!adapter.pending(),"failed preflight cleans private prep");}
        check(restore(unsupported,Collections.singletonMap("rankings.dat",data("retry")))==Outcome.RESTORED,"successful retry after unsupported preflight");
        singlePassReads(); preparationCrash(); cleanupCrash(); racedSource(); racedDestination(); rejectedGate();
        FakeMutationOs labels=fixture();labels.noXattr=true;
        try{restore(labels,Collections.singletonMap("rankings.dat",data("new")));throw new AssertionError("missing labels accepted");}catch(IOException expected){checks++;}
        check(new String(labels.files.children.get("rankings.dat").bytes,StandardCharsets.US_ASCII).equals("before"),"label refusal never writes live");
        System.out.println("Root adapter host tests passed: "+checks+" assertions; Android runtime not executed");
    }
    static void singlePassReads()throws Exception {
        FakeMutationOs os=fixture();FakeMutationOs.Node before=os.files.children.get("rankings.dat");
        check(restore(os,Collections.singletonMap("rankings.dat",data("new")))==Outcome.RESTORED,"single-pass restore result");
        FakeMutationOs.Node after=os.files.children.get("rankings.dat");
        check(os.dataReads.getOrDefault(before.inode,0)==1,"old save read once for capture and independent backup");
        check(os.dataReads.getOrDefault(after.inode,0)==1,"final save read once for actual receipt");
    }
    static void preparationCrash()throws Exception {
        FakeMutationOs os=fixture();os.beforeRename=()->{throw new SimulatedDeath();};
        try{restore(os,Collections.singletonMap("rankings.dat",data("new")));throw new AssertionError("preparation crash missing");}catch(SimulatedDeath expected){checks++;}
        check(os.workspace().children.get("active").children.containsKey("phase-new"),"initial phase temporary survives crash");
        try(RootRestoreAdapter adapter=new RootRestoreAdapter(os,binding(),GATE)){check(adapter.pending(),"interrupted preparation blocks reads");adapter.resumePrepared();check(!adapter.pending(),"proven preparation recovered");}
        check(restore(os,Collections.singletonMap("rankings.dat",data("retry")))==Outcome.RESTORED,"restore after preparation crash");
    }
    static Journal journal(FakeMutationOs os){try{FakeMutationOs.Node n=os.workspace()==null?null:os.workspace().children.get("journal");return n==null?null:io.github.xyz1001.pdsync.root.restore.RestoreJournalCodec.decode(n.bytes);}catch(IOException e){throw new AssertionError(e);}}
    static RootRestoreAdapter.Gate hook(FakeMutationOs os,boolean terminal,Runnable action){return new RootRestoreAdapter.Gate(){boolean armed;public void quietWait(){}public void requireSafe(RootBinding binding){Journal j=journal(os);if(!armed&&j!=null&&((j.phase==Phase.COMMITTED)==terminal)){armed=true;os.beforeRename=action;}}};}
    static Outcome restoreWithGate(FakeMutationOs os,RootRestoreAdapter.Gate gate)throws IOException{
        Map<String,byte[]> bytes=Collections.singletonMap("rankings.dat",data("new"));
        try(RootRestoreAdapter adapter=new RootRestoreAdapter(os,binding(),gate)){adapter.incomingSource(bytes::get);LocalSnapshot before=adapter.capture(adapter.target());return new RootRestoreTransaction().restore(adapter,adapter.target(),incoming(bytes),before.tree.hashes());}
    }
    static void cleanupCrash()throws Exception {
        FakeMutationOs os=fixture();
        Outcome outcome=restoreWithGate(os,hook(os,true,()->os.noSync=true));
        check(outcome==Outcome.RESTORED_CLEANUP_PENDING,"cleanup fsync failure preserves definite commit");
        check(journal(os).phase==Phase.COMMITTED,"cleanup failure retains terminal journal");
        check(!os.workspace().children.get("active").children.get("cleanup").children.isEmpty(),"moved cleanup entry retained");
        os.noSync=false;
        try(RootRestoreAdapter adapter=new RootRestoreAdapter(os,binding(),GATE)){check(new RootRestoreTransaction().recover(adapter,adapter.target())==Outcome.RESTORED,"resume terminal cleanup");check(!adapter.pending(),"cleanup restart reusable");}
        check(restore(os,Collections.singletonMap("rankings.dat",data("next")))==Outcome.RESTORED,"restore after interrupted cleanup");
    }
    static void racedSource()throws Exception {
        FakeMutationOs os=fixture();FakeMutationOs.Node original=os.files.children.get("rankings.dat");
        try{restoreWithGate(os,hook(os,false,()->os.file(os.files,"rankings.dat","raced")));throw new AssertionError("raced source accepted");}catch(IOException expected){checks++;}
        FakeMutationOs.Node active=os.workspace().children.get("active");
        check(new String(active.children.get("displaced").children.get("rankings.dat").bytes,StandardCharsets.US_ASCII).equals("raced"),"unexpected moved source preserved");
        check(new String(active.children.get("backup").children.get("rankings.dat").bytes,StandardCharsets.US_ASCII).equals("before"),"independent original backup preserved");
        check(active.children.get("backup").children.get("rankings.dat").inode!=original.inode,"backup is fresh independent inode");
        int writes=os.mutations;
        try(RootRestoreAdapter adapter=new RootRestoreAdapter(os,binding(),GATE)){try{new RootRestoreTransaction().recover(adapter,adapter.target());throw new AssertionError("unknown source recovered");}catch(IOException expected){checks++;}}
        check(os.mutations==writes,"unknown recovery performs zero writes");
    }
    static void racedDestination()throws Exception {
        FakeMutationOs os=fixture();
        try{restoreWithGate(os,hook(os,false,()->os.file(os.workspace().children.get("active").children.get("displaced"),"rankings.dat","intruder")));throw new AssertionError("destination race accepted");}catch(IOException expected){checks++;}
        check(new String(os.files.children.get("rankings.dat").bytes,StandardCharsets.US_ASCII).equals("before"),"no-replace keeps original live source");
        check(new String(os.workspace().children.get("active").children.get("displaced").children.get("rankings.dat").bytes,StandardCharsets.US_ASCII).equals("intruder"),"no-replace preserves raced destination");
        check(journal(os)!=null,"destination conflict retains journal");
    }
    static void rejectedGate()throws Exception {
        FakeMutationOs os=fixture();int initial=os.mutations;
        RootRestoreAdapter.Gate reject=new RootRestoreAdapter.Gate(){public void quietWait(){}public void requireSafe(RootBinding b)throws IOException{throw new RootFailure(RootProtocol.Status.BUSY);}};
        try{new RootRestoreAdapter(os,binding(),reject);throw new AssertionError("access gate ignored");}catch(RootFailure expected){check(expected.status==RootProtocol.Status.BUSY,"access gate preserved");}
        check(os.mutations==initial,"access gate prevents filesystem mutation");
        try(RootRestoreAdapter first=new RootRestoreAdapter(os,binding(),GATE)){
            try{new RootRestoreAdapter(os,binding(),GATE);throw new AssertionError("concurrent restore allowed");}catch(RootMutationSession.Failure expected){check(expected.code==RootMutationSession.Code.BUSY,"concurrent job lock busy");}}
    }
    static final class SimulatedDeath extends Error{}

}
