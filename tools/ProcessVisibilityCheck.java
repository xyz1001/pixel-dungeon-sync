import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import pdsync.*;

/** Read-only launcher around the exact production detector classes, with no application state. */
public final class ProcessVisibilityCheck {
 private static final String OFFICIAL_SHA = "bcefd52a9c14f69c69d3ccc08a212cdd3830b2dc60d78d80895cd803fa1ec602";
 public static void main(String[] args) {
  try {
   if (args.length != 1) { System.out.println("Usage: java -jar pdsync-process-check-v8.jar <official-game.jar>"); return; }
   Path selected = Path.of(args[0]).toAbsolutePath().normalize();
   for (Path part=selected; part!=null; part=part.getParent()) if (Files.isSymbolicLink(part)) throw new IllegalArgumentException();
   if (!Files.isRegularFile(selected,LinkOption.NOFOLLOW_LINKS) || Files.size(selected)>256L*1024*1024) throw new IllegalArgumentException();
   byte[] bytes;
   try(var in=Files.newInputStream(selected)){ bytes=in.readNBytes(256*1024*1024+1); }
   if(bytes.length>256*1024*1024) throw new IllegalArgumentException();
   String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
   if (!OFFICIAL_SHA.equals(digest)) { System.out.println("target=UNTRUSTED status=UNKNOWN reason=official_jar_hash_mismatch"); return; }
   TargetGame target=new TargetGame(selected.toString(),"4.0.2",922,digest,true,850,"Verified official SHA-256");
   GameProcessPlatform platform=GameProcessPlatform.Companion.current();
   JvmProcessInventory inventory=new JvmProcessInventory();
   GameProcessDetector detector=new GameProcessDetector(target,inventory,platform,ProcessHandle.current().pid(),GameProcessDetectorKt::resolveGameProcessPath);
   GameProcessStatus state=detector.status();
   String status=state instanceof GameProcessStatus.Running?"RUNNING":state instanceof GameProcessStatus.Stopped?"STOPPED":"UNKNOWN";
   System.out.println("target=TRUSTED status="+status+" scope=visible_current_account");
   if(state instanceof GameProcessStatus.Running running) System.out.println("game_pids="+running.getPids());
   if(state instanceof GameProcessStatus.Unknown) System.out.println("reason=process_visibility_or_identity_incomplete");
   ProcessInventorySnapshot observed=inventory.snapshot();
   System.out.println("inventory_complete="+observed.getComplete()+" current_owner_visible="+(observed.getCurrentOwner()!=null));
   for(ObservedGameProcess p:observed.getProcesses()) {
    String owner=p.getOwner(); String current=observed.getCurrentOwner();
    boolean same=owner!=null&&current!=null&&(platform==GameProcessPlatform.WINDOWS?owner.equalsIgnoreCase(current):owner.equals(current));
    if(owner!=null&&current!=null&&!same) continue;
    if(p.getCommand()==null) System.out.println("pid="+p.getPid()+" reason=executable_identity_unavailable");
    else {String name=p.getCommand().replace('\\','/');name=name.substring(name.lastIndexOf('/')+1).toLowerCase(java.util.Locale.ROOT);
     if(java.util.Set.of("java","javaw","java.exe","javaw.exe").contains(name)&&p.getArguments()==null)
      System.out.println("pid="+p.getPid()+" reason=java_arguments_unavailable");
    }
   }
   System.out.println("read_only=true no_save_network_credentials_or_process_changes=true");
  } catch(Exception e) {
   // Do not echo paths, command lines, user names, exception text or other private material.
   System.out.println("status=UNKNOWN reason=target_or_process_inspection_failed type="+e.getClass().getSimpleName());
  }
 }
}
