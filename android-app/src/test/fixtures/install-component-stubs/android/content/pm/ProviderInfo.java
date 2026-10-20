// Host-only isolated install-result fixture. Never package into an APK.
package android.content.pm;
public class ProviderInfo {
 public String packageName,name,readPermission,writePermission,authority,processName;
 public boolean enabled=true,exported,grantUriPermissions; public ApplicationInfo applicationInfo;
 public android.os.Bundle metaData;
}
