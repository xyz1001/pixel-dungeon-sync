package io.github.xyz1001.pdsync.android.prepare;
import android.content.Context;
import java.io.File;
import java.util.List;
public final class GamePreparationService {
 public enum InstallStrategy { FRESH_INSTALL, COMPATIBLE_UPDATE, REQUIRES_UNINSTALL }
 public static PreparedPair current; public static String currentId; public static int reloads;
 public GamePreparationService(Context context) { }
 public String currentReceiptId() { return currentId; }
 public PreparedPair reloadPreparedForInstallation(PreparedPair selected) { reloads++; return current; }
 public static final class PreparedPair {
  public String id, gamePackage, signingCertificateSha256;
  public long gameVersionCode;
  public List<File> gameApks;
  public boolean helperOnlyUpgrade, embedded = true;
  public InstallStrategy gameStrategy = InstallStrategy.FRESH_INSTALL;
  public String installationReceiptId() { return id; }
  public boolean embeddedBridge() { return embedded; }
 }
}
