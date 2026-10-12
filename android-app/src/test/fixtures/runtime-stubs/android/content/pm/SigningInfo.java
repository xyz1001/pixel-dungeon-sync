// Host-only test double. Never package this fixture into an APK.
package android.content.pm;
public class SigningInfo {
 public Signature[] signatures;
 public boolean multiple;
 public boolean hasMultipleSigners() { android.os.Build.requireApi(28); return multiple; }
 public Signature[] getApkContentsSigners() { android.os.Build.requireApi(28); return signatures; }
}
