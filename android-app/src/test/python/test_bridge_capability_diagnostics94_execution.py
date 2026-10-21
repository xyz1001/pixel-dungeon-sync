"""Execute unchanged production capability decisions with host-only Bundle/transport doubles."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[4]
CLIENT = ROOT / 'android-app/src/main/java/io/github/xyz1001/pdsync/android/BridgeClient.java'


def method(source, signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        if source[end] == '{': depth += 1
        if source[end] == '}': depth -= 1
        end += 1
    return source[start:end]


class BridgeCapabilityDiagnostics94Test(unittest.TestCase):
    def test_actual_capability_decisions_and_single_authentication(self):
        source = CLIENT.read_text()
        methods = '\n'.join(method(source, signature) for signature in (
            'private boolean supportsCapability(', 'private static int capabilityVersion('))
        harness = r'''
import java.util.*;
public class BridgeCapabilityChecks {
 enum DiagnosticCapability { READ_SNAPSHOT, RESTORE_RESULT }
 enum DiagnosticCapabilityState { SUPPORTED, NOT_EMBEDDED, AUTHENTICATE_UNSUPPORTED, PROTOCOL_UNSUPPORTED,
  VERSION_MISSING, VERSION_UNSUPPORTED, INVALID_REPLY, AUTHENTICATION_UNVERIFIED, FAILED, CANCELED }
 static class DiagnosticCapabilities {
  static DiagnosticCapabilityState state; static List<Integer> versions;
  static void record(DiagnosticCapability c,DiagnosticCapabilityState s){state=s;}
  static void reply(int a,int b,int c,int d){versions=List.of(a,b,c,d);}
 }
 static class Bundle extends HashMap<String,Object> {
  int getInt(String k,int fallback){Object v=get(k);return v instanceof Integer?(Integer)v:fallback;}
  boolean getBoolean(String k,boolean fallback){Object v=get(k);return v instanceof Boolean?(Boolean)v:fallback;}
 }
 static class Failure extends Exception {final String code;Failure(String code,Throwable cause){super(cause);this.code=code;}}
 boolean isEmbedded=true; Bundle response; Exception error,closeError; int calls,closes;
 boolean embedded(){return isEmbedded;}
 Bundle call(String method,Bundle extras)throws Exception {check(method.equals("authenticate")&&extras==null,"original handshake only");calls++;if(error!=null)throw error;return response;}
 void closeReply(Bundle reply)throws Exception {closes++;if(closeError!=null)throw closeError;}
 // PRODUCTION_METHODS
 static int checks;
 static void check(boolean v,String message){checks++;if(!v)throw new AssertionError(message);}
 static BridgeCapabilityChecks rig(){
  DiagnosticCapabilities.state=null;DiagnosticCapabilities.versions=null;
  BridgeCapabilityChecks c=new BridgeCapabilityChecks();c.response=new Bundle();
  c.response.put("accessProtocolVersion",1);c.response.put("metadataOnly",true);c.response.put("accessAuthenticated",true);
  c.response.put("readSnapshotVersion",1);c.response.put("singlePassReadVersion",1);c.response.put("restoreResultVersion",1);return c;
 }
 static void decision(BridgeCapabilityChecks c,DiagnosticCapabilityState expected)throws Exception {
  check(c.supportsCapability(DiagnosticCapability.READ_SNAPSHOT)==(expected==DiagnosticCapabilityState.SUPPORTED),"support result");
  check(DiagnosticCapabilities.state==expected,"exact reason "+expected);
  check(c.calls==1&&c.closes==1,"one original handshake, one close");
 }
 public static void main(String[] args)throws Exception {
  BridgeCapabilityChecks c=rig();decision(c,DiagnosticCapabilityState.SUPPORTED);
  check(DiagnosticCapabilities.versions.equals(List.of(1,1,1,1)),"versions from actual reply");
  c=rig();c.response.put("readSnapshotVersion",2);c.response.put("singlePassReadVersion",7);decision(c,DiagnosticCapabilityState.VERSION_UNSUPPORTED);
  check(DiagnosticCapabilities.versions.equals(List.of(1,7,2,1)),"unsupported version preserved");
  c=rig();c.response.remove("readSnapshotVersion");decision(c,DiagnosticCapabilityState.VERSION_MISSING);
  check(DiagnosticCapabilities.versions.get(2)==-1,"missing stays absent");
  for(Object value:new Object[]{"private credential",-1,1L}) {c=rig();c.response.put("readSnapshotVersion",value);decision(c,DiagnosticCapabilityState.INVALID_REPLY);check(DiagnosticCapabilities.versions.get(2)==-1,"malformed omitted");}
  c=rig();c.response.put("accessProtocolVersion",2);decision(c,DiagnosticCapabilityState.PROTOCOL_UNSUPPORTED);
  c=rig();c.response.put("metadataOnly",false);decision(c,DiagnosticCapabilityState.INVALID_REPLY);
  c=rig();c.response.put("archive",new Object());decision(c,DiagnosticCapabilityState.INVALID_REPLY);
  c=rig();c.response.put("snapshot",new Object());decision(c,DiagnosticCapabilityState.INVALID_REPLY);
  c=rig();c.response.put("accessAuthenticated",false);decision(c,DiagnosticCapabilityState.AUTHENTICATION_UNVERIFIED);
  c=rig();c.response.put("accessAuthenticated",false);c.response.put("authenticated",true);decision(c,DiagnosticCapabilityState.SUPPORTED);
  c=rig();check(c.supportsCapability(DiagnosticCapability.RESTORE_RESULT),"restore capability supported");check(c.calls==1,"no diagnostic probe");
  c=rig();c.isEmbedded=false;check(!c.supportsCapability(DiagnosticCapability.READ_SNAPSHOT),"legacy fallback");
  check(c.calls==0&&c.closes==0&&DiagnosticCapabilities.state==DiagnosticCapabilityState.NOT_EMBEDDED,"legacy does not authenticate");
  for(Failure failure:List.of(new Failure("UNKNOWN_METHOD",null),new Failure("UNSUPPORTED_METHOD",null),new Failure("IPC_CALL_FAILED",new IllegalArgumentException()))) {
   c=rig();c.error=failure;check(!c.supportsCapability(DiagnosticCapability.READ_SNAPSHOT),"unsupported method fallback");
   check(c.calls==1&&c.closes==0&&DiagnosticCapabilities.state==DiagnosticCapabilityState.AUTHENTICATE_UNSUPPORTED,"unsupported distinct from failure");
  }
  for(Exception failure:List.of(new Failure("IPC_CALL_FAILED",null),new java.io.IOException("private"),new java.util.concurrent.CancellationException("private"))) {
   c=rig();c.error=failure;try{c.supportsCapability(DiagnosticCapability.READ_SNAPSHOT);throw new AssertionError("swallowed failure");}catch(Exception actual){check(actual==failure,"same exception propagated");}
   check(DiagnosticCapabilities.state==(failure instanceof java.util.concurrent.CancellationException?DiagnosticCapabilityState.CANCELED:DiagnosticCapabilityState.FAILED),"failure never reported as fallback");check(c.calls==1,"failure no retry probe");
  }
  c=rig();c.closeError=new java.io.IOException("private close");try{c.supportsCapability(DiagnosticCapability.READ_SNAPSHOT);throw new AssertionError();}catch(java.io.IOException expected){}
  check(c.closes==1&&DiagnosticCapabilities.state==DiagnosticCapabilityState.FAILED,"close failure is failure");
  System.out.println("Bridge capability diagnostics: "+checks+" host assertions passed");
 }
}
'''.replace('// PRODUCTION_METHODS', methods)
        with tempfile.TemporaryDirectory(prefix='pdsync-capability94-') as temp:
            target = Path(temp)
            (target / 'pdsync').mkdir()
            (target / 'BridgeCapabilityChecks.java').write_text(harness)
            (target / 'pdsync/DiagnosticFailure.java').write_text('package pdsync;public enum DiagnosticFailure {CANCELED,OTHER}')
            (target / 'pdsync/DiagnosticLog.java').write_text('package pdsync;public class DiagnosticLog {public static DiagnosticFailure classify(Throwable t){return t instanceof java.util.concurrent.CancellationException?DiagnosticFailure.CANCELED:DiagnosticFailure.OTHER;}}')
            java = os.environ.get('JAVA_BIN', 'java')
            subprocess.run([java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-source', '17', '-target', '17',
                            '-d', temp, *map(str, target.rglob('*.java'))], check=True)
            subprocess.run([java, '-cp', temp, 'BridgeCapabilityChecks'], check=True)

    def test_timing_wraps_real_transfer_and_only_existing_handshake(self):
        source = CLIENT.read_text()
        read = method(source, 'public AndroidSaveObservation readSnapshot()')
        export = method(source, 'public void exportTo(')
        for body, phase in ((read, 'BRIDGE_READ_SNAPSHOT'), (export, 'BRIDGE_SAVE_EXPORT')):
            self.assertIn('DiagnosticTiming.start(DiagnosticPhase.' + phase + ')', body)
            self.assertIn('finally { timing.close(); }', body)
            self.assertIn('timing.failed(', body)
        self.assertIn('timing.skipped(); return null;', read)
        for signature in ('public AndroidRestoreReceipt restoreVerified(', 'public void restoreFrom('):
            body = method(source, signature)
            self.assertIn('DiagnosticTiming.measure(DiagnosticPhase.BRIDGE_RESTORE_WRITE, () -> call("restore",', body)
        capability = method(source, 'private boolean supportsCapability(')
        self.assertEqual(1, capability.count('call("authenticate", null)'))
        self.assertNotIn('DiagnosticLog.record', capability)
        self.assertNotIn('DiagnosticLog.record', read)
        self.assertNotIn('DiagnosticLog.record', export)


if __name__ == '__main__':
    unittest.main()
