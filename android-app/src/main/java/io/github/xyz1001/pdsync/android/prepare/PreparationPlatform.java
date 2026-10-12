package io.github.xyz1001.pdsync.android.prepare;

import android.annotation.TargetApi;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.IOException;

/** Explicit Android version boundaries; no failed API lookup is treated as success. */
final class PreparationPlatform {
    static final int MIN_SUPPORTED_SDK = 26;
    static final int MAX_VERIFIED_SDK = 36;

    private PreparationPlatform() {}

    static int currentApi() throws IOException {
        return requireSupportedApi(Build.VERSION.SDK_INT);
    }

    static int requireSupportedApi(int api) throws IOException {
        if (api < MIN_SUPPORTED_SDK || api > MAX_VERIFIED_SDK) {
            throw new IOException("APK preparation supports Android API 26 through 36; device API " + api + " is unsupported");
        }
        return api;
    }

    static int signingFlags() {
        return Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    @SuppressWarnings("deprecation")
    static long versionCode(PackageInfo info) {
        // API 26/27 do not expose versionCodeMajor. Do not sign-extend the low word.
        return Build.VERSION.SDK_INT >= 28 ? Api28.versionCode(info) : info.versionCode & 0xffffffffL;
    }

    @SuppressWarnings("deprecation")
    static Signature[] currentSignatures(PackageInfo info) throws IOException {
        if (info == null) throw new IOException("Installed package information unavailable");
        // GET_SIGNATURES gives the platform's actual signer on 26/27. Never use it
        // on 28+: after rotation it may return the oldest certificate, not the current signer.
        Signature[] signatures = Build.VERSION.SDK_INT >= 28
                ? Api28.currentSignatures(info) : info.signatures;
        if (signatures == null || signatures.length == 0) {
            throw new IOException("Installed signer information unavailable; check package visibility");
        }
        for (Signature signature : signatures) {
            if (signature == null || signature.toByteArray().length == 0) {
                throw new IOException("Installed package has an unreadable signing certificate");
            }
        }
        return signatures.clone();
    }

    // Isolate references to API 28 members from classes loaded on Android 8.
    @TargetApi(28)
    private static final class Api28 {
        static long versionCode(PackageInfo info) { return info.getLongVersionCode(); }
        static Signature[] currentSignatures(PackageInfo info) {
            return info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        }
    }
}
