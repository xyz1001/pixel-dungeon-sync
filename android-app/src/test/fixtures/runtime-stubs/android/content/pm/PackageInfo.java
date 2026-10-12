// Host-only test double. Never package this fixture into an APK.
package android.content.pm;
public class PackageInfo {
 public String packageName, sharedUserId;
 public ApplicationInfo applicationInfo;
 public SigningInfo signingInfo;
 public Signature[] signatures;
 public int versionCode;
 public long version;
 public long getLongVersionCode() { android.os.Build.requireApi(28); return version; }
}
