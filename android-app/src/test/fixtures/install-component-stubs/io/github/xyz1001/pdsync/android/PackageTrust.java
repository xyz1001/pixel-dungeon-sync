// Host-only isolated install-result fixture. Never package into an APK.
package io.github.xyz1001.pdsync.android;
import android.content.Context;
import android.content.pm.*;
import io.github.xyz1001.pdsync.android.prepare.GamePreparationService;
import java.io.*;
import java.util.*;
/** PM accessor double. Hash/key/receipt paths deliberately fail so the metadata-only boundary is executable. */
public final class PackageTrust {
 public static int hashes; public static boolean allowExternalHashes;
 public static PackageInfo installed(Context c,String p) throws PackageManager.NameNotFoundException { return c.getPackageManager().getPackageInfo(p,signingFlags()|PackageManager.GET_META_DATA); }
 public static int signingFlags() { return PackageManager.GET_SIGNATURES; }
 public static String signer(PackageInfo p) { return p.fixtureSigner; }
 public static long versionCode(PackageInfo p) { return p.versionCode; }
 public static List<File> installedApks(PackageInfo p) {
  List<File> result=new ArrayList<>();result.add(new File(p.applicationInfo.sourceDir));
  if(p.applicationInfo.splitSourceDirs!=null) for(String path:p.applicationInfo.splitSourceDirs) result.add(new File(path));return result;
 }
 public static String apkSetSha256(List<File> files) throws IOException {
  hashes++;if(!allowExternalHashes) throw new AssertionError("Installed APK hashes are forbidden after result");
  return digest(files);
 }
 /** Enabled only by explicitly external-route tests; ordinary result tests remain fail-closed. */
 public static String digest(List<File> files) throws IOException {
  List<String> digests=new ArrayList<>();
  try {
   for(File file:files) {
    byte[] value=java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(file.toPath()));
    StringBuilder hex=new StringBuilder();for(byte b:value) hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
    digests.add(hex.toString());
   }
  } catch(java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
  return ApkSetIdentity.digest(digests);
 }
 public static void requireBridge(Context c,GamePreparationService.PreparedPair p,String signer) { throw new AssertionError("Prepared binding proof is forbidden after result"); }
}
