// Host-only isolated install-result fixture. Never package into an APK.
package android.os;
public class Bundle extends java.util.HashMap<String,Object> { public String getString(String key) { return (String)get(key); } }
