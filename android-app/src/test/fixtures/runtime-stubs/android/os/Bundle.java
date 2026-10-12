// Host-only test double. Never package this fixture into an APK.
package android.os;
public class Bundle extends java.util.HashMap<String, String> {
 public String getString(String key) { return get(key); }
}
