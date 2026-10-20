package io.github.xyz1001.pdsync.android.prepare;
import java.io.IOException;
public final class ValidatedPreparationCache {
 public static int checks;
 public static boolean unchanged = true;
 public static void requireUnchanged(GamePreparationService.PreparedPair pair) throws IOException {
  checks++; if (!unchanged) throw new IOException("Fixture source changed");
 }
}
