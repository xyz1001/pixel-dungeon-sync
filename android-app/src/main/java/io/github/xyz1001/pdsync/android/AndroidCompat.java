package io.github.xyz1001.pdsync.android;

import android.content.Intent;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

/* loaded from: classes.dex */
final class AndroidCompat {
    static int mutableCallbackFlags() {
        return 33554432 | 134217728;
    }

    private AndroidCompat() {
    }

    static ParcelFileDescriptor descriptor(Bundle bundle, String str) {
        Parcelable parcelable = bundle.getParcelable(str);
        if (parcelable instanceof ParcelFileDescriptor) {
            return (ParcelFileDescriptor) parcelable;
        }
        return null;
    }

    static Intent nestedIntent(Intent intent, String str) {
        Parcelable parcelableExtra = intent.getParcelableExtra(str);
        if (parcelableExtra instanceof Intent) {
            return (Intent) parcelableExtra;
        }
        return null;
    }
}
