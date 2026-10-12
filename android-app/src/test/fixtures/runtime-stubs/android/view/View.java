// Host-only test double. Never package this fixture into an APK.
package android.view;
public class View {
 public int left,top,right,bottom;
 public void setPadding(int left,int top,int right,int bottom) { this.left=left;this.top=top;this.right=right;this.bottom=bottom; }
}
