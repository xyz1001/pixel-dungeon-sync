package android.os;
import java.io.File;
import java.io.RandomAccessFile;
import java.io.FileNotFoundException;
import java.io.IOException;
public final class ParcelFileDescriptor implements AutoCloseable {
 public static final int MODE_READ_ONLY = 0x10000000;
 public final RandomAccessFile file;
 private ParcelFileDescriptor(File file) throws FileNotFoundException { this.file = new RandomAccessFile(file, "r"); }
 public static ParcelFileDescriptor open(File file, int mode) throws FileNotFoundException {
  if (mode != MODE_READ_ONLY) throw new FileNotFoundException("test rejects non-read modes");
  return new ParcelFileDescriptor(file);
 }
 public void close() throws IOException { file.close(); }
}
