// Host-only isolated install-result fixture. Never package into an APK.
package pdsync.android.app;
public final class AndroidSyncFacade {
 public static boolean unresolvedRestore;
 public static boolean hasUnresolvedRestore(java.nio.file.Path path) { return unresolvedRestore; }
}
