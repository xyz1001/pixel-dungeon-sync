// Host-only test double. Never package this fixture into an APK.
package android.view;
public class WindowInsets {
 public int left,top,right,bottom,requestedTypes;
 public DisplayCutout cutout;
 public android.graphics.Insets modern;
 public WindowInsets(int left,int top,int right,int bottom) { this.left=left;this.top=top;this.right=right;this.bottom=bottom; }
 public int getSystemWindowInsetLeft() { return left; }
 public int getSystemWindowInsetTop() { return top; }
 public int getSystemWindowInsetRight() { return right; }
 public int getSystemWindowInsetBottom() { return bottom; }
 public DisplayCutout getDisplayCutout() { android.os.Build.requireApi(28);return cutout; }
 public android.graphics.Insets getInsets(int types) { android.os.Build.requireApi(30);requestedTypes=types;return modern; }
 public static class Type {
  public static int systemBars() { android.os.Build.requireApi(30);return 7; }
  public static int displayCutout() { android.os.Build.requireApi(30);return 128; }
  public static int ime() { android.os.Build.requireApi(30);return 8; }
 }
}
