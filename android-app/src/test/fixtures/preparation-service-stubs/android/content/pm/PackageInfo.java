package android.content.pm;
public final class PackageInfo {
    public String packageName, versionName, sharedUserId;
    public int versionCode;
    public long firstInstallTime, lastUpdateTime;
    public long fullVersionCode = -1;
    public ApplicationInfo applicationInfo;
    public Signature[] signatures;
    public SigningInfo signingInfo;
    public long getLongVersionCode() { return fullVersionCode >= 0 ? fullVersionCode : versionCode & 0xffffffffL; }
}
