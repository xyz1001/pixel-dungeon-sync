// Host-only test double. Never package this fixture into an APK.
package android.app;
import android.content.Context;
import android.content.Intent;
public final class PendingIntent {
 public static final int FLAG_UPDATE_CURRENT=134217728, FLAG_MUTABLE=33554432;
 private static final java.util.Map<String,PendingIntent> registry=new java.util.HashMap<>();
 public static int created, sent, lastFlags;
 public final Intent intent;
 private final String key;
 private PendingIntent(String key,Intent intent) { this.key=key;this.intent=intent; }
 public static PendingIntent getActivity(Context context,int requestCode,Intent intent,int flags) {
  lastFlags=flags;String key=requestCode+":"+intent.toUri(1);
  if((flags&536870912)!=0) return registry.get(key);
  if((flags&268435456)!=0) registry.remove(key);
  return registry.computeIfAbsent(key,k->{created++;return new PendingIntent(k,intent);});
 }
 public void cancel() { registry.remove(key,this); }
 public void send() { sent++; }
 public static void reset() { registry.clear();created=sent=0;lastFlags=0; }
 public static void loseSystemTokens() { registry.clear(); }
}
