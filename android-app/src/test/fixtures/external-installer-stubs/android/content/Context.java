package android.content;
import android.net.Uri;
import java.io.File;
public final class Context {
 private final File directory;
 public Uri revoked; public int revokedFlags;
 public Context(File directory) { this.directory = directory; }
 public File getNoBackupFilesDir() { return directory; }
 public String getPackageName() { return "io.github.xyz1001.pdsync.android"; }
 public void revokeUriPermission(Uri uri, int flags) { revoked = uri; revokedFlags = flags; }
}
