package android.content;
import android.content.pm.PackageManager;
import java.io.File;
public abstract class Context {
    public Context getApplicationContext() { return this; }
    public abstract PackageManager getPackageManager();
    public abstract String getPackageName();
    public abstract File getNoBackupFilesDir();
    public abstract ContentResolver getContentResolver();
}
