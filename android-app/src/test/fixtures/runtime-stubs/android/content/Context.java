// Host-only test double. Never package this fixture into an APK.
package android.content;
import java.io.File;
import android.content.pm.PackageManager;
public abstract class Context {
 public static final int RECEIVER_NOT_EXPORTED=4;
 public String receiverPermission;
 public int receiverFlags, registrations;
 public Intent registerReceiver(BroadcastReceiver receiver,IntentFilter filter,String permission,android.os.Handler handler) {
  registrations++;receiverPermission=permission;receiverFlags=0;return null;
 }
 public Intent registerReceiver(BroadcastReceiver receiver,IntentFilter filter,int flags) {
  android.os.Build.requireApi(33);registrations++;receiverPermission=null;receiverFlags=flags;return null;
 }
 public abstract PackageManager getPackageManager();
 public abstract String getPackageName();
 public abstract File getFilesDir();
 public SharedPreferences getSharedPreferences(String name,int mode) { throw new AssertionError("Unconfigured test preferences"); }
 public void sendBroadcast(Intent intent) { throw new AssertionError("Unconfigured test broadcast"); }
}
