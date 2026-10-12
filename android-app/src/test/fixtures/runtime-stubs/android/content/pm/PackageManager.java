// Host-only test double. Never package this fixture into an APK.
package android.content.pm;
public abstract class PackageManager {
 public static class NameNotFoundException extends Exception {}
 public abstract PackageInfo getPackageInfo(String name, int flags) throws NameNotFoundException;
 public abstract String[] getPackagesForUid(int uid);
 public abstract ProviderInfo resolveContentProvider(String authority, int flags);
}
