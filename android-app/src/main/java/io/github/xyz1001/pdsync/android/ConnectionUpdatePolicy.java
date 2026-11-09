package io.github.xyz1001.pdsync.android;

/* loaded from: classes.dex */
final class ConnectionUpdatePolicy {

    interface Operation {
        void run() throws Exception;
    }

    private ConnectionUpdatePolicy() {
    }

    static void apply(boolean z, Operation operation, Operation operation2) throws Exception {
        operation.run();
        if (!z) {
            return;
        }
        operation2.run();
    }
}
