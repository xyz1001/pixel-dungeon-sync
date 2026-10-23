"""Execute production overwrite dispatch: failure/success return HOME, no restore route."""
from pathlib import Path
import subprocess,tempfile,unittest
from test_embedded_install_contract import body
MAIN=(Path(__file__).resolve().parents[2]/"main/java/io/github/xyz1001/pdsync/android/MainActivity.java").read_text()
class CloudOverwriteDispatchExecution(unittest.TestCase):
 def test_production_dispatch_leaves_modal_on_success_and_failure(self):
  methods=[]
  for signature in ('void m86xfa720d6a(', 'AndroidSyncFacade.OperationResult m85x84f7e729('):
   start=MAIN.index(signature);methods.append(MAIN[start:MAIN.index('{',start)]+body(MAIN,signature))
  source=r'''
import java.util.concurrent.Callable;
public class MainActivity {
 enum SyncPage {HOME,CONFLICT} enum DiagnosticAction {PUSH}
 interface Task<T>{T run()throws Exception;} interface Done<T>{void accept(T x)throws Exception;}
 static class Text {String text(){return "test";}}
 Text VERIFYING_AND_PUBLISHING_THE_BRANCH_SELECTION_160=new Text(),LOCAL_SAVES_UPLOADED_AND_VERIFIED_148=new Text();
 static class AndroidSyncFacade {static class Inspection{} static class OperationResult{}
  int calls;boolean fail; <T>T withUploadReadScope(Callable<T> task)throws Exception{return task.call();}
  OperationResult overwriteCloudSuccessors(Inspection i,String id)throws Exception{calls++;if(fail)throw new Exception("synthetic");return new OperationResult();}}
 static class OperationOutcome{}
 AndroidSyncFacade facade=new AndroidSyncFacade();SyncPage page=SyncPage.CONFLICT;boolean busy;int auth,errors;
 AndroidSyncFacade sync(){auth++;return facade;}
 OperationOutcome m54xc977a20(Task<AndroidSyncFacade.OperationResult> task)throws Exception{task.run();return new OperationOutcome();}
 void m55x8211a061(String s,OperationOutcome o){page=SyncPage.CONFLICT;}
 <T>void work(DiagnosticAction a,String s,Task<T> task,Done<T> done){
  if(page!=SyncPage.HOME)throw new AssertionError("modal retained before IO");busy=true;
  try{T v=task.run();done.accept(v);}catch(Exception e){errors++;}finally{busy=false;}}
 /* METHODS */
 public static void main(String[] args){for(boolean fail:new boolean[]{false,true}){
  MainActivity a=new MainActivity();a.facade.fail=fail;a.m86xfa720d6a(new AndroidSyncFacade.Inspection(),"selected");
  if(a.page!=SyncPage.HOME||a.facade.calls!=1||a.auth!=1||a.errors!=(fail?1:0))throw new AssertionError("unstable overwrite dispatch");
  a.busy=true;a.m86xfa720d6a(new AndroidSyncFacade.Inspection(),"selected");if(a.facade.calls!=1)throw new AssertionError("busy replay");}}
}
'''
  with tempfile.TemporaryDirectory(prefix="overwrite-dispatch-") as tmp:
   p=Path(tmp)/'MainActivity.java';p.write_text(source.replace('/* METHODS */','\n'.join(methods)),encoding='utf-8')
   subprocess.run(['javac',str(p)],check=True,capture_output=True)
   subprocess.run(['java','-cp',tmp,'MainActivity'],check=True,capture_output=True)
