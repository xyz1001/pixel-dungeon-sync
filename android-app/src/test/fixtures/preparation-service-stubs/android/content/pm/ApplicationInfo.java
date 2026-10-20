package android.content.pm;
public final class ApplicationInfo {
    public String packageName, sourceDir, publicSourceDir;
    public String[] splitSourceDirs;
    public int uid;
    public CharSequence nonLocalizedLabel;
    public CharSequence loadLabel(PackageManager manager) { return manager.getApplicationLabel(this); }
}
