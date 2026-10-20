// Host-only regular-file mode predicate. Never package in an APK.
package android.system;
public final class OsConstants {
    public static boolean S_ISREG(int mode) { return (mode & 0170000) == 0100000; }
}
