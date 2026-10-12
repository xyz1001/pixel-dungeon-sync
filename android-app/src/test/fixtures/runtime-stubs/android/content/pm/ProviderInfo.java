// Host-only test double. Never package this fixture into an APK.
package android.content.pm;
public class ProviderInfo {
 public String packageName, name, readPermission, writePermission;
 public boolean exported, grantUriPermissions;
}
