// Host-only catalog boundary. Never package these fixtures into an APK.
package android.content.pm;
public class ApplicationInfo {
    public static final int FLAG_SYSTEM = 1;
    public static final int FLAG_UPDATED_SYSTEM_APP = 1 << 7;
    public String packageName, sourceDir;
    public boolean enabled = true;
    public int flags;
}
