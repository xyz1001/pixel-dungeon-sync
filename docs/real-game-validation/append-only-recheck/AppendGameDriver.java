import pdsync.*;
import java.nio.file.*;
import java.util.*;
import okhttp3.OkHttpClient;

public class AppendGameDriver {
 static final Path BASE=Path.of("/workspace/shared/pds-append-gameplay");
 static final String JAR=BASE.resolve("ShatteredPD-v4.0.2-Java.jar").toString();
 static final AppendOnlyWebDav STORE=new AppendOnlyWebDav("http://127.0.0.1:8777","shattered","demo","demo","demo",new OkHttpClient.Builder().retryOnConnectionFailure(false).build());
 static final Properties IDS=new Properties();
 static AppendProfileSession session(String device) throws Exception {
  Path root=BASE.resolve(device+"/data/.shatteredpixel/shattered-pixel-dungeon"); Files.createDirectories(root);
  return new AppendProfileSession(new AppConfig(root.toString(),"http://127.0.0.1:8777","demo","",JAR,"demo",false),BASE.resolve("state"),Shattered.INSTANCE,ShatteredProfileVersions.INSTANCE);
 }
 static Map<String,String> files(AppendProfileSession s){return Snapshots.INSTANCE.capture(s.getRoot(),Shattered.INSTANCE,"demo","fixture",null).getManifest().getFiles();}
 static void ensure(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
 static void record(String k,String v){IDS.setProperty(k,v);System.out.println(k+"="+v);}
 public static void main(String[] args)throws Exception {
  ensure(args.length==2&&args[1].equals("--isolated-game-stopped"),"Explicit stopped-game fixture confirmation required");
  Path ids=BASE.resolve("ids.properties");if(Files.exists(ids))try(var in=Files.newInputStream(ids)){IDS.load(in);}
  var a=session("A");var b=session("B");
  switch(args[0]){
   case "transfer":{
    var before=files(a); ensure(files(b).isEmpty(),"B must be empty");
    var push=a.push(STORE,()->true);ensure(push instanceof AppendPushResult.Success,"First upload must succeed");
    var c=((AppendPushResult.Success)push).getCommit();record("initialCommit",c.getId());record("initialSnapshot",c.getSnapshot().getId());
    var remote=b.readRemote(STORE);ensure(remote.size()==1,"Exactly one observed initial branch");
    var restore=b.restoreRemote(remote.get(0).getEntry().getManifest().getId(),STORE,()->true);
    ensure(files(a).equals(before)&&files(b).equals(before),"A/B bytes must match exact frozen files");
    ensure(b.history().stream().anyMatch(h->h.getManifest().getId().equals(restore.getBackup().getManifest().getId())),"Backup must be visible");
    record("initialBackup",restore.getBackup().getManifest().getId());System.out.println("TRANSFER_OK files="+before.size()+" versions="+b.currentVersions());break;
   }
   case "push-b":{
    var push=b.push(STORE,()->true);ensure(push instanceof AppendPushResult.Success,"B child must succeed");var c=((AppendPushResult.Success)push).getCommit();
    ensure(c.getParents().equals(List.of(IDS.getProperty("initialCommit"))),"B must descend from initial commit");record("bCommit",c.getId());record("bSnapshot",c.getSnapshot().getId());System.out.println("B_BRANCH_OK");break;
   }
   case "resolve-a-choose-b":{
    var aBefore=files(a);var bBefore=files(b);ensure(!aBefore.equals(bBefore),"Real A and B must differ");
    var result=a.push(STORE,()->true);ensure(result instanceof AppendPushResult.Diverged,"Sibling real progress must diverge");
    var conflict=((AppendPushResult.Diverged)result).getConflict();ensure(conflict.getObservedTips().size()==2,"Two tips required");
    ensure(files(a).equals(aBefore)&&files(b).equals(bBefore),"Conflict must not change either save");
    record("conflictTips",conflict.getObservedTips().toString());record("aCommit",a.baseline());
    var solved=a.resolve(conflict,IDS.getProperty("bSnapshot"),STORE,()->true);ensure(solved instanceof AppendPushResult.Success,"Explicit B choice must resolve");
    var c=((AppendPushResult.Success)solved).getCommit();ensure(new HashSet<>(c.getParents()).equals(conflict.getObservedTips()),"Resolution must retain both parents");
    ensure(files(a).equals(bBefore),"A must equal B whole snapshot");
    ensure(a.history().stream().anyMatch(h->h.getManifest().getFiles().equals(aBefore)&&h.getReason().contains("备份")),"Real A pre-restore backup retained");
    record("resolutionCommit",c.getId());record("resolutionSnapshot",c.getSnapshot().getId());System.out.println("EXPLICIT_B_CHOICE_OK original branches retained; A backup verified");break;
   }
   case "restore-history":{
    var before=files(a);var target=a.loadSnapshot(IDS.getProperty("initialSnapshot"));
    var r=a.restoreHistory(IDS.getProperty("initialSnapshot"),()->true);
    ensure(files(a).equals(target.getManifest().getFiles()),"Historical bytes restored exactly");
    ensure(r.getBackup().getManifest().getFiles().equals(before),"History restore backup exact");
    ensure(a.hasPending(),"History restore must remain pending publication");
    var push=a.push(STORE,()->true);ensure(push instanceof AppendPushResult.Success,"History restore publish");var c=((AppendPushResult.Success)push).getCommit();
    ensure(c.getKind().equals("restore")&&c.getParents().equals(List.of(IDS.getProperty("resolutionCommit"))),"Restore new child of actual baseline");
    ensure(!c.getId().equals(IDS.getProperty("initialCommit")),"Do not rewind old commit");record("historyRestoreCommit",c.getId());record("historyRestoreBackup",r.getBackup().getManifest().getId());System.out.println("HISTORY_RESTORE_OK new child; exact backup; old history retained");break;
   }
   default:throw new IllegalArgumentException("Unknown stage");
  }
  ensure(!STORE.legacyPresent(),"No head.json");try(var out=Files.newOutputStream(ids)){IDS.store(out,"Isolated append-only game verification");}
 }
}
