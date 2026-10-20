// Host-only controllable stat double, not an Android implementation.
package android.system;
public final class StructStat {
    public long st_dev = 1, st_ino = 1, st_nlink = 1, st_size = 3;
    public int st_mode = 0100644, st_uid = 200, st_gid = 200;
    public StructTimespec st_mtim = new StructTimespec(100, 100), st_ctim = new StructTimespec(100, 100);
}
