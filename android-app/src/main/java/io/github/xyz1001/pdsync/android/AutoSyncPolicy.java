package io.github.xyz1001.pdsync.android;

/* loaded from: classes.dex */
public final class AutoSyncPolicy {
    private boolean foregroundSeen;
    private int retryCount;
    private boolean returned;
    private final long startedAt;
    private int unknownCount;

    public enum Step {
        WAIT,
        UPLOAD,
        STOP_UNOBSERVED,
        STOP_UNKNOWN
    }

    public AutoSyncPolicy(long j, boolean z) {
        this.startedAt = j;
        this.foregroundSeen = z;
    }

    public void knownReturn() {
        this.returned = true;
    }

    public boolean hasSeenForeground() {
        return this.foregroundSeen;
    }

    public Step observe(boolean z, boolean z2, long j) {
        if (!z) {
            int i = this.unknownCount + 1;
            this.unknownCount = i;
            return i >= 20 ? Step.STOP_UNKNOWN : Step.WAIT;
        }
        this.unknownCount = 0;
        if (z2) {
            this.foregroundSeen = true;
            this.returned = false;
            return Step.WAIT;
        }
        if (this.foregroundSeen || this.returned) {
            return Step.UPLOAD;
        }
        return j - this.startedAt > 60000 ? Step.STOP_UNOBSERVED : Step.WAIT;
    }

    public long nextTransientRetryMillis() {
        int i = this.retryCount + 1;
        this.retryCount = i;
        if (i <= 3) {
            return i * 3000;
        }
        return -1L;
    }

    public static boolean mayPublish(boolean z, boolean z2, String str, String str2) {
        return !z && z2 && str != null && str.equals(str2);
    }
}
