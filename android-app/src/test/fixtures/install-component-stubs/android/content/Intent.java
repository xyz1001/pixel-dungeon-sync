// Host-only isolated install-result fixture. Never package into an APK.
package android.content;
public class Intent {
 private String action,targetPackage;
 private final java.util.Map<String,Object> extras=new java.util.HashMap<>();
 public Intent() {} public Intent(String action) { this.action=action; } public Intent(Context context,Class<?> target) {}
 public Intent setAction(String value) { action=value;return this; } public String getAction() { return action; }
 public Intent setPackage(String value) { targetPackage=value;return this; }
 public Intent putExtra(String key,Object value) { extras.put(key,value);return this; }
 public String getStringExtra(String key) { return (String) extras.get(key); }
 public int getIntExtra(String key,int fallback) { Object v=extras.get(key);return v instanceof Integer?(Integer)v:fallback; }
 public Object getExtra(String key) { return extras.get(key); }
 public Intent cloneFilter() { return new Intent(action).setPackage(targetPackage); }
 public String toUri(int flags) { return java.util.Base64.getEncoder().encodeToString((String.valueOf(action)+"\n"+String.valueOf(targetPackage)).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
 public static Intent parseUri(String value,int flags) {
  String[] p=new String(java.util.Base64.getDecoder().decode(value),java.nio.charset.StandardCharsets.UTF_8).split("\n",-1);
  if(p.length!=2) throw new IllegalArgumentException("Bad fixture URI");
  return new Intent(p[0].equals("null")?null:p[0]).setPackage(p[1].equals("null")?null:p[1]);
 }
 public boolean filterEquals(Intent other) { return java.util.Objects.equals(action,other.action)&&java.util.Objects.equals(targetPackage,other.targetPackage); }
}
