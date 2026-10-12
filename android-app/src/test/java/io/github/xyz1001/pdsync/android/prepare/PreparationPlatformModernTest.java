package io.github.xyz1001.pdsync.android.prepare;

import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import java.io.IOException;

public final class PreparationPlatformModernTest {
    private static int checks;
    interface Checked { void run() throws Exception; }
    static void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
    static void rejects(Checked action) throws Exception {
        checks++; try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Missing modern signer metadata fell back to legacy identity");
    }
    public static void main(String[] args) throws Exception {
        for (int api : new int[]{28,36}) {
            Build.VERSION.SDK_INT = api;
            check(PreparationPlatform.currentApi() == api, "runtime API");
            check(PreparationPlatform.signingFlags() == 134217728, "GET_SIGNING_CERTIFICATES required");
            PackageInfo info = new PackageInfo(); info.versionCode = 7; info.fullVersionCode = 0x200000007L;
            check(PreparationPlatform.versionCode(info) == 0x200000007L, "major version preserved");
            Signature old = new Signature(new byte[]{1}), current = new Signature(new byte[]{2});
            info.signatures = new Signature[]{old};
            rejects(() -> PreparationPlatform.currentSignatures(info));
            info.signingInfo = new SigningInfo(null); rejects(() -> PreparationPlatform.currentSignatures(info));
            info.signingInfo = new SigningInfo(new Signature[0]); rejects(() -> PreparationPlatform.currentSignatures(info));
            info.signingInfo = new SigningInfo(new Signature[]{current});
            check(PreparationPlatform.currentSignatures(info)[0] == current, "rotation uses actual current signer");
            info.signingInfo = new SigningInfo(new Signature[]{old,current});
            check(PreparationPlatform.currentSignatures(info).length == 2, "multiple current signers preserved");
        }
        System.out.println("Preparation API28/36 current-certificate checks passed: " + checks);
    }
}
