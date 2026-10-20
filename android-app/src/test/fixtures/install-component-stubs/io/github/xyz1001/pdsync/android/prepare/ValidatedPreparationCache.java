// Host-only isolated install-result fixture. Never package into an APK.
package io.github.xyz1001.pdsync.android.prepare;
public final class ValidatedPreparationCache {
 public static void requireUnchanged(GamePreparationService.PreparedPair pair) { if(pair==null) throw new AssertionError("Missing prepared pair"); }
 public static boolean isUnchanged(GamePreparationService.PreparedPair pair) { return pair!=null; }
}
