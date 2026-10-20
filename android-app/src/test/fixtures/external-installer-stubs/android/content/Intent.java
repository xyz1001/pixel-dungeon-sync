package android.content;
import android.net.Uri;
public final class Intent {
 public static final String ACTION_VIEW = "android.intent.action.VIEW", ACTION_CHOOSER = "android.intent.action.CHOOSER";
 public static final int FLAG_GRANT_READ_URI_PERMISSION = 1;
 private final String action;
 private Uri data; private String type; private int flags; private ClipData clip;
 private String packageName; private Object component;
 public Intent target;
 public Intent(String action) { this.action = action; }
 public Intent setDataAndType(Uri uri, String mime) { data = uri; type = mime; return this; }
 public void setClipData(ClipData value) { clip = value; }
 public ClipData getClipData() { return clip; }
 public Intent addFlags(int value) { flags |= value; return this; }
 public int getFlags() { return flags; }
 public String getAction() { return action; }
 public Uri getData() { return data; }
 public String getType() { return type; }
 public String getPackage() { return packageName; }
 public Object getComponent() { return component; }
 public Intent setPackage(String value) { packageName = value; return this; }
 public Intent setComponent(Object value) { component = value; return this; }
 public static Intent createChooser(Intent target, CharSequence title) { Intent chooser = new Intent(ACTION_CHOOSER); chooser.target = target; return chooser; }
}
