// Host-only test double. Never package this fixture into an APK.
package io.github.xyz1001.pdsync.android.prepare;
import java.io.File;
import java.util.List;
import android.content.Context;
public final class GamePreparationService {
 public GamePreparationService(Context context) {}
 public void validatePrepared(PreparedPair pair) { throw new AssertionError("Legacy preparation must not be called by these checks"); }
 public static final class PreparedPair {
  public boolean legacyUnboundHelper, legacyIdentity;
  public String gamePackage, helperPackage, sharedUid, authority, originIdentity, sourceApkSetSha256, signingCertificateSha256;
  public List<File> gameApks;
  public File helperApk;
 }
}
