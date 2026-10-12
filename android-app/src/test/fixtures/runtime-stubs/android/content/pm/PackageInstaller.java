// Host-only test double. Never package this fixture into an APK.
package android.content.pm;
public class PackageInstaller {
 public static class SessionParams {
  public static final int USER_ACTION_REQUIRED=1;
  public int requireUserAction=0, changes=0;
  public void setRequireUserAction(int value) { android.os.Build.requireApi(31);requireUserAction=value;changes++; }
 }
}
