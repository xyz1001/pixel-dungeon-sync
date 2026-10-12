// Host-only test double. Never package this fixture into an APK.
package android.content.pm;
public class ProviderInfo {
 public String packageName, name, readPermission, writePermission, authority, processName;
 public boolean enabled;
 public ApplicationInfo applicationInfo;
 public boolean exported, grantUriPermissions;
}
