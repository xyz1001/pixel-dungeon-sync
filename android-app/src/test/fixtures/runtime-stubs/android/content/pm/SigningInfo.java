// Host-only test double. Never package this fixture into an APK.
package android.content.pm;
public class SigningInfo {
 public Signature[] signatures;
 public boolean multiple;
 public boolean hasMultipleSigners() { return multiple; }
 public Signature[] getApkContentsSigners() { return signatures; }
}
