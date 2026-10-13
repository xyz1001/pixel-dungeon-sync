// Host-only test double. Never package this fixture into an APK.
package android.os;
public final class Process {
 public static int uid=10001;
 public static int myUid() { return uid; }
}
