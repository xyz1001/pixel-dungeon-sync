package android.content;
import android.net.Uri;
public final class ClipData {
 private final Uri uri;
 private ClipData(Uri uri) { this.uri = uri; }
 public static ClipData newRawUri(CharSequence label, Uri uri) { return new ClipData(uri); }
 public int getItemCount() { return 1; }
 public Item getItemAt(int index) { if (index != 0) throw new IndexOutOfBoundsException(); return new Item(uri); }
 public static final class Item { private final Uri uri; Item(Uri uri) { this.uri = uri; } public Uri getUri() { return uri; } }
}
