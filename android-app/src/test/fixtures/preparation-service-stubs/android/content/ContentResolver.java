package android.content;
import android.net.Uri;
import java.io.InputStream;
import java.io.FileNotFoundException;
public abstract class ContentResolver {
    public abstract InputStream openInputStream(Uri uri) throws FileNotFoundException;
}
