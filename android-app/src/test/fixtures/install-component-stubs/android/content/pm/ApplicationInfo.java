// Host-only isolated install-result fixture. Never package into an APK.
package android.content.pm;
public class ApplicationInfo {
 public int uid; public boolean enabled=true; public String packageName,sourceDir,dataDir;
 public String[] splitSourceDirs; public android.os.Bundle metaData;
}
