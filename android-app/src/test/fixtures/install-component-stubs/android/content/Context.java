// Host-only isolated install-result fixture. Never package into an APK.
package android.content;
import java.io.File;
import android.content.pm.PackageManager;
public abstract class Context {
 public Context getApplicationContext() { return this; }
 public abstract PackageManager getPackageManager(); public abstract String getPackageName();
 public abstract File getFilesDir(); public abstract SharedPreferences getSharedPreferences(String name,int mode);
 public abstract void sendBroadcast(Intent intent);
}
