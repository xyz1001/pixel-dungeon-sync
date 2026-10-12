package io.github.xyz1001.pdsync.bridge;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Process;

/** Authenticating a UID and its current certificate is independent of APK permissions. */
final class MainCallerAuthenticator {
    private MainCallerAuthenticator() { }

    static void authenticateActivity(Context context, String callingPackage) {
        // getCallingPackage is supplied by Android for startActivityForResult.
        // An intent extra or referrer is never accepted as a caller identity.
        MainCallerPolicy.requirePackage(callingPackage);
        try {
            PackageInfo main = currentMain(context);
            if (main.applicationInfo == null) throw new SecurityException("Main caller UID is unavailable");
            authenticate(context, callingPackage, main.applicationInfo.uid, main);
        } catch (PackageManager.NameNotFoundException error) {
            throw new SecurityException("Cannot authenticate main app", error);
        }
    }

    static void authenticateProvider(Context context, String callingPackage, int callingUid) {
        MainCallerPolicy.requirePackage(callingPackage);
        try {
            authenticate(context, callingPackage, callingUid, currentMain(context));
        } catch (PackageManager.NameNotFoundException error) {
            throw new SecurityException("Cannot authenticate main app", error);
        }
    }

    private static PackageInfo currentMain(Context context) throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getPackageInfo(MainCallerPolicy.MAIN, AndroidApiCompat.signingFlags());
    }

    private static void authenticate(Context context, String callingPackage, int callingUid, PackageInfo main)
            throws PackageManager.NameNotFoundException {
        MainCallerPolicy.requireIdentity(callingPackage, callingUid, Process.myUid(),
                main.applicationInfo == null ? -1 : main.applicationInfo.uid,
                context.getPackageManager().getPackagesForUid(callingUid));
        try {
            // Fail closed before exposing any provider operation if manifest
            // binding or the immutable embedded certificate pin was changed.
            new GameBinding(BridgeMetadata.read(context), context.getPackageName());
        } catch (java.io.IOException error) {
            throw new SecurityException("Cannot authenticate bridge binding", error);
        }
        Signature[] signers = AndroidApiCompat.currentSigners(main);
        byte[][] certificates = signers == null ? null : new byte[signers.length][];
        if (signers != null) for (int i = 0; i < signers.length; i++)
            certificates[i] = signers[i] == null ? null : signers[i].toByteArray();
        MainCallerPolicy.requirePinnedSigner(MainCallerPolicy.MAIN_SIGNER_SHA256, certificates);
    }
}
