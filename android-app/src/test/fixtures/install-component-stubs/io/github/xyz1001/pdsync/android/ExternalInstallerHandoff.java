// Host-only external sharing port. Never package into an APK; does not exercise Android URI tickets.
package io.github.xyz1001.pdsync.android;
import android.content.Context;
import io.github.xyz1001.pdsync.android.prepare.GamePreparationService;
import java.util.*;
public final class ExternalInstallerHandoff {
 public static int prepares, revokes;
 public static boolean failPrepare;
 public static final Set<String> tickets=new HashSet<>();
 public static final class Ticket {
  public final String nonce,packageName,signerSha256,receiptId,apkSetSha256;
  public final long versionCode;
  Ticket(GamePreparationService.PreparedPair pair) throws Exception {
   nonce=UUID.randomUUID().toString();packageName=pair.gamePackage;versionCode=7;
   signerSha256=pair.signingCertificateSha256;receiptId=pair.installationReceiptId();
   apkSetSha256=PackageTrust.digest(pair.gameApks);
  }
 }
 public static Ticket prepare(Context context,GamePreparationService.PreparedPair pair) throws Exception {
  if(failPrepare) throw new java.io.IOException("Cannot stage external APK");
  prepares++;Ticket ticket=new Ticket(pair);tickets.add(ticket.nonce);return ticket;
 }
 public static void revoke(Context context,String nonce) { revokes++;tickets.remove(nonce); }
 public static void reset() { prepares=revokes=0;failPrepare=false;tickets.clear(); }
}
