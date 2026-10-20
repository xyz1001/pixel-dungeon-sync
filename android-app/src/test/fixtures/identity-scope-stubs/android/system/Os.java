// Host-only stat seam. Values deliberately do not claim to model a real kernel/ART.
package android.system;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
public final class Os {
    public static final java.util.Map<String, StructStat> values = new ConcurrentHashMap<>();
    public static StructStat lstat(String path) throws IOException {
        android.os.Build.requireApi(27);
        StructStat stat = values.get(path);
        if (stat == null) throw new IOException("No fixture stat: " + path);
        return stat;
    }
}
