// Host-only controllable stat double, not an Android implementation.
package android.system;
public final class StructTimespec {
    public long tv_sec, tv_nsec;
    public StructTimespec(long seconds, long nanos) { tv_sec = seconds; tv_nsec = nanos; }
}
