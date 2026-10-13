// Host-only catalog boundary. Never package these fixtures into an APK.
package android.content.pm;
public class ActivityInfo {
    public String packageName;
    public boolean enabled = true;
    public ApplicationInfo applicationInfo;
}
