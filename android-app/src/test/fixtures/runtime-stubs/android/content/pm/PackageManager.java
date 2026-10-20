// Host-only test double. Never package this fixture into an APK.
package android.content.pm;
public abstract class PackageManager {
 public static final int MATCH_DISABLED_COMPONENTS=512;
 public java.util.List<ResolveInfo> confirmationMatches=java.util.Collections.emptyList();
 public int componentEnabled=0, applicationEnabled=0;
 public java.util.List<ResolveInfo> queryIntentActivities(android.content.Intent intent,int flags) { return confirmationMatches; }
 public int getComponentEnabledSetting(android.content.ComponentName component) { return componentEnabled; }
 public int getApplicationEnabledSetting(String name) { return applicationEnabled; }

 public static final int GET_META_DATA=128, GET_PROVIDERS=8, GET_SIGNATURES=64, GET_SIGNING_CERTIFICATES=134217728;
 public CharSequence getApplicationLabel(ApplicationInfo info) { return info.packageName; }
 public static class NameNotFoundException extends Exception {}
 public abstract PackageInfo getPackageInfo(String name, int flags) throws NameNotFoundException;
 public abstract String[] getPackagesForUid(int uid);
 public abstract ProviderInfo resolveContentProvider(String authority, int flags);
}
