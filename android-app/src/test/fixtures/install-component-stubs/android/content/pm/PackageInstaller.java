// Host-only isolated install-result fixture. Never package into an APK.
package android.content.pm;
import android.content.IntentSender;
import java.io.*;
import java.util.*;
public final class PackageInstaller {
 public final Map<Integer,Session> sessions=new LinkedHashMap<>(); public int commits,abandons,nextId=1;
 public static class SessionParams {
  public static final int MODE_FULL_INSTALL=1,USER_ACTION_REQUIRED=1;
  public String packageName; public long size; public int requireUserAction;
  public SessionParams(int mode) {} public void setAppPackageName(String value) { packageName=value; }
  public void setAppLabel(CharSequence value) {} public void setSize(long value) { size=value; }
  public void setRequireUserAction(int value) { requireUserAction=value; }
 }
 public static class SessionInfo {
  private final String packageName; private final int id; private final boolean active;
  SessionInfo(int id,String value,boolean active) { this.id=id;packageName=value;this.active=active; }
  public String getAppPackageName() { return packageName; }
  public int getSessionId() { return id; } public boolean isActive() { return active; }
 }
 public final class Session implements AutoCloseable {
  public final SessionParams params; public final Map<String,ByteArrayOutputStream> contents=new LinkedHashMap<>();
  public IntentSender callback; public int fsyncs; public boolean active,owned=true;
  Session(SessionParams params) { this.params=params; }
  public OutputStream openWrite(String name,long offset,long length) { ByteArrayOutputStream out=new ByteArrayOutputStream();contents.put(name,out);return out; }
  public void fsync(OutputStream out) { fsyncs++; }
  public String[] getNames() { return contents.keySet().toArray(new String[0]); }
  public void commit(IntentSender sender) { commits++;callback=sender; }
  public void close() {}
 }
 public int createSession(SessionParams params) { int id=nextId++;sessions.put(id,new Session(params));return id; }
 public Session openSession(int id) { return sessions.get(id); }
 public SessionInfo getSessionInfo(int id) { Session s=sessions.get(id);return s==null?null:new SessionInfo(id,s.params.packageName,s.active); }
 public List<SessionInfo> getMySessions() {
  List<SessionInfo> mine=new ArrayList<>();for(Map.Entry<Integer,Session> item:sessions.entrySet())
   if(item.getValue().owned) mine.add(getSessionInfo(item.getKey()));return mine;
 }
 public void abandonSession(int id) { sessions.remove(id);abandons++; }
 public void finishSession(int id) { sessions.remove(id); }
 public void uninstall(String name,IntentSender sender) { throw new AssertionError("Uninstall outside this fixture scope"); }
}
