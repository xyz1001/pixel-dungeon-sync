// Host-only test double. Never package this fixture into an APK.
package android.os;
public class Bundle extends java.util.HashMap<String, Object> {
 public String getString(String key) { return (String)get(key); }
 public Parcelable getParcelable(String key) { return (Parcelable)get(key); }
}
