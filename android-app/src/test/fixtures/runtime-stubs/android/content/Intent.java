// Host-only test double. Never package this fixture into an APK.
package android.content;
import android.os.Parcelable;
public class Intent implements Parcelable {
 private String action, targetPackage;
 private final java.util.Map<String,Object> extras = new java.util.HashMap<>();
 public Intent() {}
 public Intent(String action) { this.action=action; }
 public Intent setAction(String action) { this.action=action; return this; }
 public String getAction() { return action; }
 public Intent setPackage(String name) { targetPackage=name; return this; }
 public String getPackage() { return targetPackage; }
 public Intent putExtra(String key, Object value) { extras.put(key,value); return this; }
 public String getStringExtra(String key) { return (String)extras.get(key); }
 public int getIntExtra(String key, int fallback) { Object value=extras.get(key); return value instanceof Integer ? (Integer)value : fallback; }
 public Parcelable getParcelableExtra(String key) { return (Parcelable)extras.get(key); }
 public Intent cloneFilter() { return new Intent(action).setPackage(targetPackage); }
 public String toUri(int flags) { return java.util.Base64.getEncoder().encodeToString((String.valueOf(action)+"\n"+String.valueOf(targetPackage)).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
 public static Intent parseUri(String value, int flags) {
  String[] parts=new String(java.util.Base64.getDecoder().decode(value),java.nio.charset.StandardCharsets.UTF_8).split("\n",-1);
  if(parts.length!=2) throw new IllegalArgumentException("Malformed test URI");
  return new Intent(parts[0].equals("null")?"android.intent.action.VIEW":parts[0]).setPackage(parts[1].equals("null")?null:parts[1]);
 }
 public boolean filterEquals(Intent other) { return java.util.Objects.equals(action,other.action)&&java.util.Objects.equals(targetPackage,other.targetPackage); }
}
