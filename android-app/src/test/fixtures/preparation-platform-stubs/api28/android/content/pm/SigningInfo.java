package android.content.pm;
public final class SigningInfo {
    private final Signature[] current;
    public SigningInfo(Signature[] current) { this.current = current; }
    public Signature[] getApkContentsSigners() { return current; }
    public Signature[] getSigningCertificateHistory() { throw new AssertionError("Historical certificates must not authenticate current APKs"); }
}
