// Host-only isolated install-result fixture. Never package into an APK.
package io.github.xyz1001.pdsync.android;
import android.content.Context;
import io.github.xyz1001.pdsync.android.prepare.GamePreparationService;
public final class SyncRuntime {
 public static int selections; public static InstalledBridgeBinding installed;
 public static void setPreparedGame(Context c,GamePreparationService.PreparedPair p) {}
 public static void setInstalledBridge(Context c,InstalledBridgeBinding b) throws Exception {
  InstalledBridgeResolver.requireUnchanged(c,b);
  // Match the production runtime's backend-switch guard; ordering is part of this test.
  InstallCoordinator checkpoint=new InstallCoordinator(c);
  if(checkpoint.hasActiveRequest()) throw new java.io.IOException("Cannot select backend during installation");
  if(!checkpoint.acceptedInstalledComponent(b)) throw new AssertionError("Persist component completion before backend switch");
  selections++;installed=b;
 }
}
