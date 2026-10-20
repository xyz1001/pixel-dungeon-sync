// Host-only isolated install-result fixture. Never package into an APK.
package android.content.pm;
public class PackageInfo {
 public String packageName,sharedUserId,versionName,fixtureSigner;
 public ApplicationInfo applicationInfo; public ProviderInfo[] providers;
 public long firstInstallTime,lastUpdateTime,versionCode;
}
