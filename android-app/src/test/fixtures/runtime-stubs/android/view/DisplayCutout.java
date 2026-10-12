// Host-only test double. Never package this fixture into an APK.
package android.view;
public class DisplayCutout {
 public int left,top,right,bottom;
 public DisplayCutout(int left,int top,int right,int bottom) { this.left=left;this.top=top;this.right=right;this.bottom=bottom; }
 public int getSafeInsetLeft() { android.os.Build.requireApi(28);return left; }
 public int getSafeInsetTop() { android.os.Build.requireApi(28);return top; }
 public int getSafeInsetRight() { android.os.Build.requireApi(28);return right; }
 public int getSafeInsetBottom() { android.os.Build.requireApi(28);return bottom; }
}
