package io.github.xyz1001.pdsync.android;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Process;
import java.io.IOException;

/** Setup only proves the app's root authorization. No game APK or save path is opened. */
public final class RootSetupAuthorization {
    private RootSetupAuthorization() { }

    public static void probe(Context context, String packageName) throws IOException {
        requireSelectedPackage(context, packageName);
        RootAuthorizationProbe.run(argv -> new ProcessBuilder(argv).start(), 60000);
        requireSelectedPackage(context, packageName);
    }

    private static void requireSelectedPackage(Context context, String packageName) throws IOException {
        try {
            if (packageName == null || packageName.equals(context.getPackageName())) throw new IOException("ROOT_TARGET_CHANGED");
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            if (info.applicationInfo == null || !info.applicationInfo.enabled
                    || !packageName.equals(info.packageName)
                    || !packageName.equals(info.applicationInfo.packageName)
                    || info.applicationInfo.uid / 100000 != Process.myUid() / 100000
                    || info.applicationInfo.uid == Process.myUid()) throw new IOException("ROOT_TARGET_CHANGED");
        } catch (IOException expected) { throw expected; }
        catch (Exception unavailable) { throw new IOException("ROOT_TARGET_CHANGED"); }
    }
}
