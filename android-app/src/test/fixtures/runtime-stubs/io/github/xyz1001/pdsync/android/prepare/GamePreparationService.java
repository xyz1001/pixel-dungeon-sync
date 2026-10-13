// Host-only test double. Never package this fixture into an APK.
package io.github.xyz1001.pdsync.android.prepare;
import java.io.File;
import java.util.List;
import android.content.Context;
public final class GamePreparationService {
 public static PreparedPair retained;
 public static RuntimeException reloadFailure;
 public PreparedPair reloadPrepared() {
  if (reloadFailure != null) throw reloadFailure;
  return retained;
 }
 public GamePreparationService(Context context) {}
 public void validatePrepared(PreparedPair pair) {
  if (!pair.embeddedBridge()) throw new AssertionError("Legacy preparation must not be called by these checks");
  pair.validationCount++;
  if (pair.invalidReceipt) throw new SecurityException("Receipt validation failed");
 }
 public static final class PreparedPair {
  public boolean legacyUnboundHelper, legacyIdentity, invalidReceipt;
  public int validationCount;
  public long gameVersionCode;
  public List<String> sourceSignerCertificateSha256;
  public String bridgeMode, bridgeHostPackage, runtimeDexName, runtimeDexSha256, bridgeRuntimeVersion, mainAppCertSha256, namespaceId;
  public boolean embeddedBridge() { return "embedded-v1".equals(bridgeMode); }
  public String gamePackage, helperPackage, sharedUid, authority, originIdentity, sourceApkSetSha256, signingCertificateSha256;
  public List<File> gameApks;
  public File helperApk;
 }
}
