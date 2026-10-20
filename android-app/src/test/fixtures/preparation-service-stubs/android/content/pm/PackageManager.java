package android.content.pm;
import android.content.Intent;
import java.util.Collections;
import java.util.List;
public abstract class PackageManager {
    public static final int GET_SIGNATURES = 0x40;
    public static final int GET_SIGNING_CERTIFICATES = 0x08000000;
    public static class NameNotFoundException extends Exception {
        public NameNotFoundException() {}
        public NameNotFoundException(String name) { super(name); }
    }
    public abstract PackageInfo getPackageInfo(String packageName, int flags) throws NameNotFoundException;
    public PackageInfo getPackageArchiveInfo(String path, int flags) { return null; }
    public List<ResolveInfo> queryIntentActivities(Intent intent, int flags) { return Collections.emptyList(); }
    public CharSequence getApplicationLabel(ApplicationInfo info) {
        return info.nonLocalizedLabel == null ? info.packageName : info.nonLocalizedLabel;
    }
}
