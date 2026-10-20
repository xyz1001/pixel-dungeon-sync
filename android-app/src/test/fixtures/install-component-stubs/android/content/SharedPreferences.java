// Host-only isolated install-result fixture. Never package into an APK.
package android.content;
public interface SharedPreferences {
 String getString(String key,String fallback); int getInt(String key,int fallback);
 long getLong(String key,long fallback);
 boolean getBoolean(String key,boolean fallback); boolean contains(String key); Editor edit();
 interface Editor {
  Editor putString(String key,String value); Editor putInt(String key,int value);
  Editor putLong(String key,long value);
  Editor putBoolean(String key,boolean value); Editor remove(String key); boolean commit();
 }
}
