// Host-only SDK-selection double. Never package this fixture into an APK.
package android.os;
public final class Build {
 public static final class VERSION { public static int SDK_INT=36; }
 public static void requireApi(int level) {
  if(VERSION.SDK_INT<level) throw new NoSuchMethodError("Test forbids API "+level+" on SDK "+VERSION.SDK_INT);
 }
}
