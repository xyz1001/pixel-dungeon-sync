package io.github.xyz1001.pdsync.android;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;

/** Small SDK gates for the main runtime. New framework references stay in API-specific classes. */
final class RuntimePlatformCompat {
    private RuntimePlatformCompat() {}

    static void applyInsets(View view, WindowInsets insets, int side, int top, int bottom) {
        if (Build.VERSION.SDK_INT >= 30) {
            Api30.applyInsets(view, insets, side, top, bottom);
        } else {
            // adjustResize is declared by MainActivity. Legacy system insets include the IME,
            // status/navigation bars; keep the original WindowInsets for child dispatch.
            int[] edges = {insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()};
            if (Build.VERSION.SDK_INT >= 28) Api28.includeDisplayCutout(insets, edges);
            view.setPadding(side + edges[0], top + edges[1], side + edges[2], bottom + edges[3]);
        }
    }

    /** A top sibling must never reserve the bottom IME: the content owns that inset. */
    static void applyToolbarInsets(View view, WindowInsets insets, int side, int top, int bottom) {
        if (Build.VERSION.SDK_INT >= 30) {
            Api30.applyToolbarInsets(view, insets, side, top, bottom);
        } else {
            int[] edges = {insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), 0};
            if (Build.VERSION.SDK_INT >= 28) Api28.includeDisplayCutout(insets, edges);
            view.setPadding(side + edges[0], top + edges[1], side + edges[2], bottom);
        }
    }

    static void startDataSyncForeground(Service service, int notificationId, Notification notification) {
        if (Build.VERSION.SDK_INT >= 29) Api29.startDataSyncForeground(service, notificationId, notification);
        else service.startForeground(notificationId, notification);
    }

    static void requireInstallConfirmation(PackageInstaller.SessionParams params) {
        if (Build.VERSION.SDK_INT >= 31) Api31.requireInstallConfirmation(params);
        // API 26–30 require system confirmation for our ordinary REQUEST_INSTALL_PACKAGES
        // installer. The manifest does not request privileged INSTALL_PACKAGES and contains
        // no device-admin receiver. No silent-install flag or hidden API is used.
    }

    /** Only for a correlated confirmation launched by the current, resumed Activity.
     * Sender-side opt-in uses that Activity's visibility; no persistent creator grant is needed.
     */
    static Bundle systemConfirmationLaunchOptions() {
        if (Build.VERSION.SDK_INT >= 36) return Api36.systemConfirmationLaunchOptions();
        if (Build.VERSION.SDK_INT >= 34) return Api34.systemConfirmationLaunchOptions();
        return null;
    }

    static void registerInternalReceiver(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= 33) Api33.registerInternalReceiver(context, receiver, filter);
        else {
            // RECEIVER_NOT_EXPORTED does not exist before API 33. Require the main app's
            // existing signature permission so untrusted packages cannot spoof this event.
            context.registerReceiver(receiver, filter, PackageTrust.ACCESS_BRIDGE, null);
        }
    }

    private static final class Api28 {
        static void includeDisplayCutout(WindowInsets insets, int[] edges) {
            android.view.DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout == null) return;
            edges[0] = Math.max(edges[0], cutout.getSafeInsetLeft());
            edges[1] = Math.max(edges[1], cutout.getSafeInsetTop());
            edges[2] = Math.max(edges[2], cutout.getSafeInsetRight());
            edges[3] = Math.max(edges[3], cutout.getSafeInsetBottom());
        }
    }

    private static final class Api29 {
        static void startDataSyncForeground(Service service, int id, Notification notification) {
            service.startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        }
    }

    private static final class Api30 {
        static void applyToolbarInsets(View view, WindowInsets insets, int side, int top, int bottom) {
            android.graphics.Insets edges = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            view.setPadding(side + edges.left, top + edges.top, side + edges.right, bottom);
        }

        static void applyInsets(View view, WindowInsets insets, int side, int top, int bottom) {
            android.graphics.Insets edges = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(side + edges.left, top + edges.top, side + edges.right, bottom + edges.bottom);
        }
    }

    private static final class Api31 {
        static void requireInstallConfirmation(PackageInstaller.SessionParams params) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        }
    }

    private static final class Api33 {
        static void registerInternalReceiver(Context context, BroadcastReceiver receiver, IntentFilter filter) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        }
    }
    private static final class Api34 {
        static Bundle systemConfirmationLaunchOptions() {
            return ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle();
        }
    }

    private static final class Api36 {
        static Bundle systemConfirmationLaunchOptions() {
            return ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE).toBundle();
        }
    }

}
