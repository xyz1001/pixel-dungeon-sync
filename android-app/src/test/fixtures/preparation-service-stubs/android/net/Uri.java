package android.net;
import java.io.File;
public final class Uri {
    private final String value;
    private Uri(String value) { this.value = value; }
    public static Uri parse(String value) { return new Uri(value); }
    public static Uri fromFile(File file) { return parse(file.toURI().toString()); }
    public String getScheme() { return java.net.URI.create(value).getScheme(); }
    public String getPath() { return java.net.URI.create(value).getPath(); }
    @Override public String toString() { return value; }
    @Override public boolean equals(Object other) { return other instanceof Uri && value.equals(((Uri) other).value); }
    @Override public int hashCode() { return value.hashCode(); }
}
