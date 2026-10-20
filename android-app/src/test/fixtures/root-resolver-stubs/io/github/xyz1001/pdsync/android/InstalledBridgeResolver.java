package io.github.xyz1001.pdsync.android;
import android.content.Context;
public final class InstalledBridgeResolver {
 public static InstalledBridgeBinding binding;
 public static InstalledBridgeBinding resolve(Context context,String packageName) { return binding; }
}
