package io.github.xyz1001.pdsync.android;

import android.os.Build;
import java.util.Locale;
import java.util.TimeZone;
import pdsync.DiagnosticEnvironment;

/** Explicit, permission-free Build allowlist. Never collect a fingerprint or stable device ID. */
final class AndroidDiagnosticEnvironment {
    private AndroidDiagnosticEnvironment() { }

    static void configure() {
        try {
            DiagnosticEnvironment.configureAndroid(Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
                    Build.BRAND, Build.MODEL, Build.SUPPORTED_ABIS,
                    Locale.getDefault().toLanguageTag(), TimeZone.getDefault().getID());
        } catch (Exception ignored) { /* Optional metadata must never prevent app or service startup. */ }
    }
}
