// Host-only isolated install-result fixture. Never package into an APK.
package android.app;
import android.content.*;
public final class PendingIntent {
 public static final int FLAG_CANCEL_CURRENT=268435456, FLAG_NO_CREATE=536870912, FLAG_IMMUTABLE=67108864;
 public static boolean failBroadcastCreation;
 private static final java.util.Map<String,PendingIntent> tokens=new java.util.HashMap<>();
 private final String key; public final Intent intent;
 private PendingIntent(String key,Intent intent) { this.key=key;this.intent=intent; }
 public static PendingIntent getBroadcast(Context c,int code,Intent intent,int flags) {
  if(failBroadcastCreation) throw new SecurityException("Injected callback creation failure");
  return new PendingIntent("broadcast:"+code,intent);
 }
 public static PendingIntent getActivity(Context c,int code,Intent intent,int flags) {
  String key=code+":"+intent.toUri(1);
  if((flags&536870912)!=0) return tokens.get(key);
  if((flags&268435456)!=0) tokens.remove(key);
  return tokens.computeIfAbsent(key,k->new PendingIntent(k,intent));
 }
 public IntentSender getIntentSender() { return new IntentSender(intent); }
 public void cancel() { tokens.remove(key,this); }
}
