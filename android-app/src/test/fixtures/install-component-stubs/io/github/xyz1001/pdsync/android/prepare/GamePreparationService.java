// Host-only isolated install-result fixture. Never package into an APK.
package io.github.xyz1001.pdsync.android.prepare;
import android.content.Context;
import java.io.*;
import java.util.*;
public final class GamePreparationService {
 public static PreparedPair current; public static int reloads; public static boolean forbidReload;
 public enum InstallStrategy { DIRECT,REQUIRES_UNINSTALL }
 public static final class PreparedPair {
  public final String gamePackage,gameLabel="Fixture game",helperPackage="",originIdentity="a".repeat(64),signingCertificateSha256="b".repeat(64);
  public final List<File> gameApks; public final boolean selectedFileInstallation=true,helperOnlyUpgrade=false;
  public final InstallStrategy gameStrategy=InstallStrategy.DIRECT; private final String receipt;
  public PreparedPair(String game,List<File> apks,String receipt) { gamePackage=game;gameApks=apks;this.receipt=receipt; }
  public boolean embeddedBridge() { return true; } public String installationReceiptId() { return receipt; }
 }
 public GamePreparationService(Context context) {}
 public PreparedPair reloadPreparedForInstallation(PreparedPair existing) throws IOException { reloads++;if(forbidReload) throw new AssertionError("Result handling must not reload preparation");return current; }
 public PreparedPair reloadPrepared() { return current; } public void validatePrepared(PreparedPair pair) {}
 public String currentReceiptId() { return current==null?null:current.installationReceiptId(); }
 public String currentLocalCertificateSha256() { throw new AssertionError("Result handling must not load signer key"); }
}
