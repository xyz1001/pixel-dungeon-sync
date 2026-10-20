// Host-only test double. Never package this fixture into an APK.
package android.app;
public final class ActivityOptions {
 public static final int MODE_BACKGROUND_ACTIVITY_START_ALLOWED = 1;
 public static final int MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE = 4;
 public static int creations;
 private int senderMode;
 public static void reset() { creations = 0; }
 public static ActivityOptions makeBasic() { creations++; return new ActivityOptions(); }
 public ActivityOptions setPendingIntentBackgroundActivityStartMode(int mode) {
  android.os.Build.requireApi(34);
  if (mode == MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE) android.os.Build.requireApi(36);
  senderMode = mode; return this;
 }
 public android.os.Bundle toBundle() {
  android.os.Bundle bundle = new android.os.Bundle();
  bundle.put("senderBalMode", senderMode); return bundle;
 }
}
