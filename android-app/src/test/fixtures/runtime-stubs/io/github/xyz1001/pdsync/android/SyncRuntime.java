// Host-only test double. Never package this fixture into an APK.
package io.github.xyz1001.pdsync.android;
import android.content.Context;
import io.github.xyz1001.pdsync.android.prepare.GamePreparationService;
final class SyncRuntime {
 static GamePreparationService.PreparedPair requirePreparedGame(Context context) { throw new AssertionError("Unconfigured test boundary"); }
}
