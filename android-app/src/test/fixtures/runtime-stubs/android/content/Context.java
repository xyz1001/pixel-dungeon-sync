// Host-only test double. Never package this fixture into an APK.
package android.content;
import java.io.File;
import android.content.pm.PackageManager;
public abstract class Context {
 public abstract PackageManager getPackageManager();
 public abstract String getPackageName();
 public abstract File getFilesDir();
 public SharedPreferences getSharedPreferences(String name,int mode) { throw new AssertionError("Unconfigured test preferences"); }
 public void sendBroadcast(Intent intent) { throw new AssertionError("Unconfigured test broadcast"); }
}
