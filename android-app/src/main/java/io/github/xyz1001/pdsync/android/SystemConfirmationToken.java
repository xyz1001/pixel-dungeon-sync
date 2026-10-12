package io.github.xyz1001.pdsync.android;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/* loaded from: classes.dex */
final class SystemConfirmationToken {
    private static final ConcurrentHashMap<String, PendingIntent> LIVE = new ConcurrentHashMap<>();

    private SystemConfirmationToken() {
    }

    static String retain(Context context, String str, Intent intent) throws Exception {
        Intent intentCloneFilter = intent.cloneFilter();
        String uri = intentCloneFilter.toUri(1);
        if (!intentCloneFilter.filterEquals(Intent.parseUri(uri, 1))) {
            throw new IOException("无法可靠保存系统确认身份，未打开确认界面。");
        }
        LIVE.put(str, PendingIntent.getActivity(context, str.hashCode(), intent, 335544320));
        return uri;
    }

    static PendingIntent find(Context context, String str, String str2) throws Exception {
        if (str == null || str.isEmpty() || str2 == null) {
            return null;
        }
        PendingIntent activity = PendingIntent.getActivity(context, str.hashCode(), Intent.parseUri(str2, 1), 603979776);
        ConcurrentHashMap<String, PendingIntent> concurrentHashMap = LIVE;
        if (activity != null) {
            concurrentHashMap.put(str, activity);
            return activity;
        }
        concurrentHashMap.remove(str);
        return activity;
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
