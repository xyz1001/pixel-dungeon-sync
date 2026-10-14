package io.github.xyz1001.pdsync.android;

import android.app.Application;

/** Configure private diagnostics once, including cold service and installer-callback starts. */
public final class DiagnosticApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        AndroidDiagnosticEnvironment.configure();
        AndroidDiagnosticLogging.configure(this);
    }
}
