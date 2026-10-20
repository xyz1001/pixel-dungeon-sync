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
        // This path remains unchanged for all existing retained preparations.
        return new BridgeClient(context, prepared, identity);
    }
    public static GameAccessBackend forInstalledBridge(Context context,
            InstalledBridgeBinding binding, AndroidGameIdentity identity) {
        return new BridgeClient(context, binding, identity);
    }
    public static GameAccessBackend forRootGame(Context context, SelectedRootGame selected,
            AndroidGameIdentity identity) throws Exception {
        return new RootGameBridge(context, selected, identity);
    }
}
