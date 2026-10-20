package android.net;
import java.net.URI;
public final class Uri {
 private final URI value;
 private Uri(String text) { value = URI.create(text); }
 public static Uri parse(String text) { return new Uri(text); }
 public String getScheme() { return value.getScheme(); }
 public String getAuthority() { return value.getRawAuthority(); }
 public String getQuery() { return value.getRawQuery(); }
 public String getFragment() { return value.getRawFragment(); }
 public String getEncodedPath() { return value.getRawPath(); }
 public String toString() { return value.toString(); }
 public boolean equals(Object other) { return other instanceof Uri && value.equals(((Uri)other).value); }
 public int hashCode() { return value.hashCode(); }
 public static final class Builder {
  private String scheme, authority, path = "";
  public Builder scheme(String value) { scheme = value; return this; }
  public Builder authority(String value) { authority = value; return this; }
  public Builder appendPath(String value) { path += "/" + value; return this; }
  public Uri build() { return Uri.parse(scheme + "://" + authority + path); }
 }
}
