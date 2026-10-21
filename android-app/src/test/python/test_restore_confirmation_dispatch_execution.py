"""Execute production confirmation dispatch without a device or a cloud account."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_embedded_install_contract import body

ROOT = Path(__file__).resolve().parents[2] / "main/java/io/github/xyz1001/pdsync/android"
MAIN = (ROOT / "MainActivity.java").read_text()

class RestoreConfirmationDispatchExecution(unittest.TestCase):
    def test_confirmation_closes_before_slow_io_and_cannot_dispatch_twice(self):
        methods = []
        for signature in ("private void operation(", "private boolean isCurrentConfirmation(", "public void cancelConfirmation("):
            start = MAIN.index(signature)
            methods.append(MAIN[start:MAIN.index("{", start)] + body(MAIN, signature))
        fixture = r'''
package io.github.xyz1001.pdsync.android;
public class MainActivity {
 enum SyncPage { HOME, CONFIRM, CONFLICT, SETTINGS }
 enum DiagnosticAction { RESTORE, RESOLVE }
 interface Task<T> { T run() throws Exception; }
 interface Done<T> { void accept(T value) throws Exception; }
 static class AndroidSyncFacade {
  static class OperationResult {}
  Object withRestoreReadScope(Task task) throws Exception { return task.run(); }
 }
 static class OperationOutcome {}
 static class Message { String text(){return "cancelled";} }
 static Message SELECTION_CANCELED_BOTH_SAVES_ARE_UNCHANGED_182 = new Message();
 boolean busy,destroyed,resumed=true;
 SyncPage page=SyncPage.CONFIRM, confirmationReturn=SyncPage.HOME;
 int settingsConfirmationToken=7,queued,writes,renders;
 Runnable confirmedAction=()->{},confirmationMerge=()->{};
 String notice="", actual="A";
 AndroidSyncFacade facade=new AndroidSyncFacade();
 Task queuedTask;
 Done queuedDone;
 boolean isDestroyed(){return destroyed;}
 void navigate(SyncPage next){page=next;renders++;}
 void work(DiagnosticAction action,String text,Task task,Done done){
  check(page!=SyncPage.CONFIRM,"modal still visible when work begins");
  check(confirmedAction==null && confirmationMerge==null,"retained modal action");
  busy=true;renders++;queued++;queuedTask=task;queuedDone=done;
 }
 OperationOutcome m54xc977a20(Task task)throws Exception{task.run();return new OperationOutcome();}
 void m55x8211a061(String message,OperationOutcome outcome){notice=message;}
 void finish()throws Exception{try{Object result=queuedTask.run();busy=false;queuedDone.accept(result);}catch(Exception e){busy=false;notice=e.getMessage();}}
 void confirm(int token,boolean fail){if(isCurrentConfirmation(token))operation(DiagnosticAction.RESTORE,"loading",()->{
  writes++;if(fail)throw new Exception("write failed");actual="B";return new AndroidSyncFacade.OperationResult();},"success");}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 /* METHODS */
 public static void main(String[]args)throws Exception{
  MainActivity slow=new MainActivity();slow.confirm(7,false);
  check(slow.page==SyncPage.HOME && slow.busy && slow.renders==1,"existing HOME loading did not activate immediately");
  check(slow.actual.equals("A") && slow.writes==0,"UI dispatch ran work or fabricated restored content");
  slow.confirm(7,false);check(slow.queued==1,"double submit while slow");
  slow.finish();check(slow.actual.equals("B") && !slow.busy && slow.notice.equals("success"),"completion");
  slow.confirm(7,false);check(slow.queued==1,"stale callback after success");
  MainActivity cancel=new MainActivity();cancel.cancelConfirmation();cancel.confirm(7,false);
  check(cancel.queued==0 && cancel.actual.equals("A") && cancel.page==SyncPage.HOME,"cancel dispatched write");
  MainActivity failed=new MainActivity();failed.confirm(7,true);failed.finish();
  check(failed.actual.equals("A") && failed.page==SyncPage.HOME && !failed.busy && failed.notice.equals("write failed"),"failure hidden or modal stranded");
  failed.confirm(7,false);check(failed.queued==1,"stale callback after failure");
  MainActivity stale=new MainActivity();stale.settingsConfirmationToken++;stale.confirm(7,false);check(stale.queued==0,"expired callback");
  MainActivity paused=new MainActivity();paused.resumed=false;paused.confirm(7,false);check(paused.queued==0,"background callback");
  MainActivity conflict=new MainActivity();conflict.confirmationReturn=SyncPage.CONFLICT;conflict.confirm(7,false);
  check(conflict.page==SyncPage.CONFLICT && conflict.busy,"wrong return page");
  System.out.println("Restore confirmation dispatch execution checks passed");
 }
}
'''
        with tempfile.TemporaryDirectory(prefix="pdsync-confirmation-dispatch-") as tmp:
            source = Path(tmp) / "MainActivity.java"
            source.write_text(fixture.replace("/* METHODS */", "\n".join(methods)))
            java = os.environ.get("JAVA_BIN", "java")
            compiled = subprocess.run([java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "-d", tmp, str(source)],capture_output=True,text=True)
            self.assertEqual(0, compiled.returncode, compiled.stderr)
            run = subprocess.run([java,"-cp",tmp,"io.github.xyz1001.pdsync/android/MainActivity".replace("/",".")],capture_output=True,text=True)
            self.assertEqual(0,run.returncode,run.stderr)
