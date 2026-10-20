package io.github.xyz1001.pdsync.android;

import android.app.Application;
import android.content.res.Configuration;
import pdsync.i18n.UiLocale;

/** Configure private diagnostics once, including cold service and installer-callback starts. */
public final class DiagnosticApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        updateLocale(getResources().getConfiguration());
        AndroidDiagnosticEnvironment.configure();
        AndroidDiagnosticLogging.configure(this);
    }
    static void updateLocale(Configuration configuration) {
        if (configuration != null && !configuration.getLocales().isEmpty())
            UiLocale.setLocale(configuration.getLocales().get(0));
        else UiLocale.useSystemLocale();
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        updateLocale(configuration);
    }
}

