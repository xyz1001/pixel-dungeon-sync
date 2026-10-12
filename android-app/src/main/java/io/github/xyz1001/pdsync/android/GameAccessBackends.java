package io.github.xyz1001.pdsync.android;

import android.content.Context;
import io.github.xyz1001.pdsync.android.prepare.GamePreparationService;
import pdsync.android.app.AndroidGameIdentity;

/** Central construction boundary; existing prepared selections keep their existing bridge. */
public final class GameAccessBackends {
    private GameAccessBackends() {}

    public static GameAccessBackend forPreparedGame(Context context,
            GamePreparationService.PreparedPair prepared, AndroidGameIdentity identity) {
        // This is construction, not a trust/readiness check. BridgeClient retains those checks.
        // Root access has no selection path here until its own verified preparation exists.
        return new BridgeClient(context, prepared, identity);
    }
}
