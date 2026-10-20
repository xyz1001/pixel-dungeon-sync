package android.content.pm;
public final class SigningInfo {
    private final Signature[] current;
    public SigningInfo(Signature[] current) { this.current = current.clone(); }
    public Signature[] getApkContentsSigners() { return current.clone(); }
    public Signature[] getSigningCertificateHistory() { throw new AssertionError("Historical signers cannot authenticate current APKs"); }
}
