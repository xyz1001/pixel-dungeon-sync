// Host-only isolated install-result fixture. Never package into an APK.
package io.github.xyz1001.pdsync.android;
import android.content.Intent;
public final class AndroidCompat {
 public static int mutableCallbackFlags() { return 0; }
 public static Intent nestedIntent(Intent i,String key) { return (Intent)i.getExtra(key); }
}
