package android.util;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
/** File-backed test analogue; never substitutes for production Android AtomicFile. */
public final class AtomicFile {
    private final File base, next, backup;
    public AtomicFile(File base) {
        this.base = base;
        this.next = new File(base.getPath() + ".new");
        this.backup = new File(base.getPath() + ".bak");
    }
    public File getBaseFile() { return base; }
    public FileInputStream openRead() throws IOException {
        if (backup.exists()) Files.move(backup.toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING);
        if (base.exists()) Files.deleteIfExists(next.toPath());
        return new FileInputStream(base);
    }
    public FileOutputStream startWrite() throws IOException {
        File directory = base.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create test receipt directory");
        return new FileOutputStream(next);
    }
    public void finishWrite(FileOutputStream stream) {
        try {
            stream.getFD().sync(); stream.close();
            Files.move(next.toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) { throw new IllegalStateException("Test atomic receipt replacement failed", error); }
    }
    public void failWrite(FileOutputStream stream) {
        try { stream.close(); Files.deleteIfExists(next.toPath()); }
        catch (IOException error) { throw new IllegalStateException("Test atomic receipt rollback failed", error); }
    }
}
