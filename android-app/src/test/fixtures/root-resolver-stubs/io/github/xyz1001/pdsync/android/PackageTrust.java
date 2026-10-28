// Host-only file boundary: synthetic Android installation paths, never real /data files.
package io.github.xyz1001.pdsync.android;
import android.content.pm.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
public final class PackageTrust {
 public static final Map<String,byte[]> contents=new HashMap<>();
 public static int signingFlags() { return PackageManager.GET_SIGNING_CERTIFICATES; }
 public static Signature[] currentSigners(PackageInfo info) {
  if(info.signingInfo==null || info.signingInfo.signatures==null || info.signingInfo.signatures.length==0)
   throw new SecurityException("Missing current signer");
  return info.signingInfo.signatures.clone();
 }
 public static long versionCode(PackageInfo info) { return info.getLongVersionCode(); }
 public static List<File> installedApks(PackageInfo info) throws IOException {
  if(info.applicationInfo==null || info.applicationInfo.sourceDir==null)throw new IOException("Missing base");
  List<File> files=new ArrayList<>();files.add(apk(info.applicationInfo.sourceDir));
  if(info.applicationInfo.splitSourceDirs!=null)for(String path:info.applicationInfo.splitSourceDirs) {
   if(path==null)throw new IOException("Missing split");files.add(apk(path));
  }
  return files;
 }
 public static File apk(String path) { return new File(path) {
  @Override public String getPath(){return path;}
  @Override public long length(){return contents.containsKey(getPath())?contents.get(getPath()).length:0;}
  @Override public boolean isFile(){return contents.containsKey(getPath());}
  @Override public File getAbsoluteFile(){return this;}
  @Override public File getCanonicalFile(){return this;}
 }; }
 public static int hashCalls;
 public static String sha256(File file) throws Exception {
  hashCalls++;
  byte[] bytes=contents.get(file.getPath());if(bytes==null)throw new IOException("Missing virtual APK");
  return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
 }
 public static String apkSetSha256(List<File> files)throws Exception {
  List<String> hashes=new ArrayList<>();for(File file:files)hashes.add(sha256(file));return ApkSetIdentity.digest(hashes);
 }
 public static String hex(byte[] bytes) {
  StringBuilder value=new StringBuilder();for(byte b:bytes)value.append(String.format(Locale.ROOT,"%02x",b&255));return value.toString();
 }
}
