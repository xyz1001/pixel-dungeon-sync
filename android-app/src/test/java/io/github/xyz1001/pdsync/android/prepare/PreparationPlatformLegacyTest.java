package io.github.xyz1001.pdsync.android.prepare;

import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.os.Build;
import java.io.IOException;

/** Runs without SigningInfo or getLongVersionCode present in the host classpath. */
public final class PreparationPlatformLegacyTest {
    private static int checks;
    interface Checked { void run() throws Exception; }
    static void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
    static void rejects(Checked action) throws Exception {
        checks++; try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Missing signer data was accepted");
    }
    public static void main(String[] args) throws Exception {
        for (int api : new int[]{26,27}) {
            Build.VERSION.SDK_INT = api;
            check(PreparationPlatform.currentApi() == api, "runtime API");
            check(PreparationPlatform.signingFlags() == 64, "GET_SIGNATURES required");
            PackageInfo info = new PackageInfo(); info.versionCode = -1;
            check(PreparationPlatform.versionCode(info) == 0xffffffffL, "unsigned legacy version");
            rejects(() -> PreparationPlatform.currentSignatures(info));
            info.signatures = new Signature[0]; rejects(() -> PreparationPlatform.currentSignatures(info));
            info.signatures = new Signature[]{null}; rejects(() -> PreparationPlatform.currentSignatures(info));
            info.signatures = new Signature[]{new Signature(new byte[0])}; rejects(() -> PreparationPlatform.currentSignatures(info));
            Signature a = new Signature(new byte[]{1}), b = new Signature(new byte[]{2});
            info.signatures = new Signature[]{a,b};
            Signature[] actual = PreparationPlatform.currentSignatures(info);
            check(actual.length == 2 && actual[0] == a && actual[1] == b, "all current signers preserved");
            actual[0] = null; check(info.signatures[0] == a, "defensive array copy");
        }
        System.out.println("Preparation API26/27 absent-member checks passed: " + checks);
    }
}
