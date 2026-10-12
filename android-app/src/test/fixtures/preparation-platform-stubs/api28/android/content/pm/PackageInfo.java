package android.content.pm;
public final class PackageInfo {
    public int versionCode;
    public Signature[] signatures;
    public SigningInfo signingInfo;
    public long fullVersionCode;
    public long getLongVersionCode() { return fullVersionCode; }
}
