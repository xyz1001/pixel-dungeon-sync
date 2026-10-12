package io.github.xyz1001.pdsync.bridge;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.res.Resources;
import android.os.Process;


final class MainCallerAuthenticator {
    private static final String PIN_METADATA = "io.github.xyz1001.pdsync.EXPECTED_MAIN_CERT_SHA256";

    private MainCallerAuthenticator() {
    }

    static void authenticateActivity(Context context, String str) {
        MainCallerPolicy.requirePackage(str);
        try {
            PackageInfo packageInfoCurrentMain = currentMain(context);
            if (packageInfoCurrentMain.applicationInfo == null) {
                throw new SecurityException("Main caller UID is unavailable");
            }
            authenticate(context, str, packageInfoCurrentMain.applicationInfo.uid, packageInfoCurrentMain);
            if (context.checkPermission("io.github.xyz1001.pdsync.android.ACCESS_BRIDGE", -1, packageInfoCurrentMain.applicationInfo.uid) != 0) {
                throw new SecurityException("Main-app signature permission required");
            }
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
            throw new SecurityException("Cannot authenticate main app", e);
        }
    }

    static void authenticateProvider(Context context, String str, int i) {
        MainCallerPolicy.requirePackage(str);
        try {
            authenticate(context, str, i, currentMain(context));
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
            throw new SecurityException("Cannot authenticate main app", e);
        }
    }

    private static PackageInfo currentMain(Context context) throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getPackageInfo("io.github.xyz1001.pdsync.android", AndroidApiCompat.signingFlags());
    }

    private static void authenticate(Context context, String str, int i, PackageInfo packageInfo) throws PackageManager.NameNotFoundException {
        PackageManager packageManager = context.getPackageManager();
        MainCallerPolicy.requireIdentity(str, i, Process.myUid(), packageInfo.applicationInfo == null ? -1 : packageInfo.applicationInfo.uid, packageManager.getPackagesForUid(i));
        ApplicationInfo applicationInfo = packageManager.getApplicationInfo(context.getPackageName(), 128);
        int i2 = applicationInfo.metaData == null ? 0 : applicationInfo.metaData.getInt("io.github.xyz1001.pdsync.EXPECTED_MAIN_CERT_SHA256", 0);
        String string = i2 == 0 ? "" : context.getString(i2);
        Signature[] apkContentsSigners = AndroidApiCompat.currentSigners(packageInfo);
        byte[][] bArr = apkContentsSigners == null ? null : new byte[apkContentsSigners.length][];
        if (apkContentsSigners != null) {
            for (int i3 = 0; i3 < apkContentsSigners.length; i3++) {
                Signature signature = apkContentsSigners[i3];
                bArr[i3] = signature == null ? null : signature.toByteArray();
            }
        }
        MainCallerPolicy.requirePinnedSigner(string, bArr);
    }
}
