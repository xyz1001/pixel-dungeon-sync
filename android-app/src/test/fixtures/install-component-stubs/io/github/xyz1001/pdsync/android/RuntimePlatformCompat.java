// Host-only isolated install-result fixture. Never package into an APK.
package io.github.xyz1001.pdsync.android;
import android.content.pm.PackageInstaller;
public final class RuntimePlatformCompat {
 static void requireInstallConfirmation(PackageInstaller.SessionParams params) { params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED); }
}
