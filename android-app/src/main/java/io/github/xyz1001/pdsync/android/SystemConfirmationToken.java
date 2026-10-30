package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/* loaded from: classes.dex */
final class SystemConfirmationToken {
    static final Object CHECKPOINT_LOCK = new Object();
    private static final String NULL_ACTION = "pdsync-null-action:";
    private static final ConcurrentHashMap<String, PendingIntent> LIVE = new ConcurrentHashMap<>();

    private SystemConfirmationToken() {
    }

    static String retain(Context context, String str, Intent intent) throws Exception {
        Intent intentCloneFilter = intent.cloneFilter();
        String uri = (intentCloneFilter.getAction() == null ? NULL_ACTION : "") + intentCloneFilter.toUri(Intent.URI_INTENT_SCHEME);
        if (!intentCloneFilter.filterEquals(parseFilter(uri))) {
            throw new IOException(AndroidMessages.SYSTEM_CONFIRMATION_TOKEN_01.text());
        }
        LIVE.put(str, PendingIntent.getActivity(context, str.hashCode(), intent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return uri;
    }

    private static Intent parseFilter(String value) throws Exception {
        // Android parseUri supplies ACTION_VIEW when the original explicit Intent has no
        // action. Keep that absence separately; never invent an action or serialize extras.
        boolean noAction = value.startsWith(NULL_ACTION);
        Intent parsed = Intent.parseUri(noAction ? value.substring(NULL_ACTION.length()) : value, Intent.URI_INTENT_SCHEME);
        return noAction ? parsed.setAction(null) : parsed;
    }

    static PendingIntent find(Context context, String str, String str2) throws Exception {
        if (str == null || str.isEmpty() || str2 == null) {
            return null;
        }
        PendingIntent activity = PendingIntent.getActivity(context, str.hashCode(), parseFilter(str2), PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        ConcurrentHashMap<String, PendingIntent> concurrentHashMap = LIVE;
        if (activity != null) {
            concurrentHashMap.put(str, activity);
            return activity;
        }
        concurrentHashMap.remove(str);
        return activity;
    }

    static boolean installerPositivelyDisabled(Context context) throws Exception {
        try {
            requireEnabledActivity(context, new Intent("android.content.pm.action.CONFIRM_INSTALL").toUri(Intent.URI_INTENT_SCHEME));
            return false;
        } catch (IOException disabled) { return true; }
    }

    /** A query miss is inconclusive under package visibility. Block only positive disabled evidence. */
    static void requireEnabledActivity(Context context, String filter) throws Exception {
        if (filter == null) return;
        android.content.pm.PackageManager pm = context.getPackageManager();
        java.util.List<android.content.pm.ResolveInfo> matches;
        try { matches = pm.queryIntentActivities(parseFilter(filter), android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS); }
        catch (SecurityException unavailable) { return; }
        if (matches == null || matches.isEmpty()) return;
        boolean disabled = false;
        for (android.content.pm.ResolveInfo match : matches) {
            android.content.pm.ActivityInfo info = match.activityInfo;
            if (info == null || info.applicationInfo == null) return;
            try {
                int component = pm.getComponentEnabledSetting(new android.content.ComponentName(info.packageName, info.name));
                int application = pm.getApplicationEnabledSetting(info.packageName);
                boolean activityEnabled = component == 1 || (component == 0 && info.enabled);
                boolean appEnabled = application == 1 || (application == 0 && info.applicationInfo.enabled);
                if (activityEnabled && appEnabled) return;
                disabled = true;
            } catch (IllegalArgumentException | SecurityException unknown) { return; }
        }
        if (disabled) throw new IOException(AndroidMessages.SYSTEM_CONFIRMATION_TOKEN_02.text());
    }

    static void cancel(Context context, String str, String str2) {
        try {
            PendingIntent pendingIntentFind = find(context, str, str2);
            if (pendingIntentFind != null) {
                pendingIntentFind.cancel();
            }
        } catch (Exception unused) {
        }
        if (str != null) {
            LIVE.remove(str);
        }
    }
}
