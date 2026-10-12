// Host-only test double. Never package this fixture into an APK.
package android.content.pm;
public class PackageInfo {
 public String packageName, sharedUserId;
 public ApplicationInfo applicationInfo;
 public SigningInfo signingInfo;
 public long version;
 public long getLongVersionCode() { return version; }
}
