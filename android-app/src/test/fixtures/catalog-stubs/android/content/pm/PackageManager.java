// Host-only catalog boundary. Never package these fixtures into an APK.
package android.content.pm;
import android.content.Intent;
import java.util.List;
public abstract class PackageManager {
    public static class NameNotFoundException extends Exception { }
    public abstract List<PackageInfo> getInstalledPackages(int flags);
    public abstract List<ResolveInfo> queryIntentActivities(Intent intent, int flags);
    public abstract PackageInfo getPackageInfo(String name, int flags) throws NameNotFoundException;
    public abstract CharSequence getApplicationLabel(ApplicationInfo info);
}
