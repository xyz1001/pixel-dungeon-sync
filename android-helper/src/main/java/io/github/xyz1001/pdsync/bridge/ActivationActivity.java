package io.github.xyz1001.pdsync.bridge;

import android.app.Activity;
import android.os.Bundle;

/** Explicit bounded process activation, never a storage-permission request. */
public final class ActivationActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setResult(RESULT_CANCELED);
        try {
            MainCallerAuthenticator.authenticateActivity(this, getCallingPackage());
            setResult(RESULT_OK);
        } catch (SecurityException rejected) {
            // The caller still has to perform the authenticated provider handshake.
        } finally {
            finish();
        }
    }
}
