package io.github.xyz1001.pdsync.bridge;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;


public final class VisibilityService extends Service {
    @Override // android.app.Service
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override // android.app.Service
    public int onStartCommand(Intent intent, int i, int i2) {
        stopSelf(i2);
        return 2;
    }
}
