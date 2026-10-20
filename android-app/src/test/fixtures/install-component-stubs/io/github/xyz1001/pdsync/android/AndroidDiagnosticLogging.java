// Host-only isolated install-result fixture. Never package into an APK.
package io.github.xyz1001.pdsync.android;
import pdsync.*;
public final class AndroidDiagnosticLogging {
 public static int rejected,callbacks;
 static DiagnosticFailure classify(Throwable t) { rejected++;return DiagnosticFailure.OTHER; }
 static DiagnosticDetails failureDetails(Throwable t) { return DiagnosticDetails.builder().build(); }
 static void confirmationEvent(String phase, DiagnosticResult result) {}
 static void installerResult(int status) { callbacks++; }
}
