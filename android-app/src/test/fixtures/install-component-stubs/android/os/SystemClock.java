// Host-only isolated install-result fixture. Never package into an APK.
package android.os;
public final class SystemClock { public static long elapsedRealtime() { return System.nanoTime()/1000000L; } }
