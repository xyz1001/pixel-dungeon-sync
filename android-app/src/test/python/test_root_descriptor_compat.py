"""Execute production descriptor-duplication methods with JVM platform doubles.

This verifies dispatch and fail-closed cleanup, not OEM hidden-API availability.
Android 8 source: https://android.googlesource.com/platform/libcore/+/android-8.0.0_r1/luni/src/main/java/android/system/Os.java
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[4]
SOURCE = ROOT / 'android-root/src/main/java/io/github/xyz1001/pdsync/root/mutation/AndroidMutationOs.java'


def declaration(source, signature):
    start = source.index(signature)
    brace = source.index('{', start)
    end, depth = brace + 1, 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


class RootDescriptorCompatibility(unittest.TestCase):
    def test_api26_and_public_api30_keep_cloexec_and_close_failed_duplicates(self):
        source = SOURCE.read_text(encoding='utf-8')
        methods = '\n'.join(declaration(source, signature) for signature in (
            'private static ParcelFileDescriptor duplicate(',
            'private static void setCloseOnExec(',
            'private static final class Api30'))
        harness = '''
import java.io.*;
public class DescriptorCheck {
 enum Operation { DUP, FCNTL }
 static IOException failure(Operation op, Exception e) { return new IOException(op.name(), e); }
 static class Build { static class VERSION { static int SDK_INT; } }
 static class ErrnoException extends Exception {}
 static class OsConstants { static final int F_SETFD=2, FD_CLOEXEC=1; }
 public static class Os {
  static int calls; static boolean reject, denied;
  public static int fcntlInt(FileDescriptor fd, int cmd, int value) throws ErrnoException {
   if (fd != ParcelFileDescriptor.fd || cmd != 2 || value != 1) throw new AssertionError();
   calls++;
   if(denied) throw new SecurityException("Denied");
   if(reject) throw new ErrnoException();
   return 0;
  }
 }
 static class ParcelFileDescriptor {
  static FileDescriptor fd = new FileDescriptor(); static int closed;
  static ParcelFileDescriptor dup(FileDescriptor ignored) throws IOException { return new ParcelFileDescriptor(); }
  FileDescriptor getFileDescriptor() { return fd; }
  void close() throws IOException { closed++; }
 }
 METHODS
 public static void main(String[] args) throws Exception {
  for(int sdk : new int[]{26,28,29,30,36}) {
   Build.VERSION.SDK_INT=sdk;
   Os.calls=0; Os.reject=false; Os.denied=false; ParcelFileDescriptor.closed=0;
   duplicate(new FileDescriptor());
   if(Os.calls!=1 || ParcelFileDescriptor.closed!=0) throw new AssertionError("success "+sdk);
   Os.reject=true;
   try { duplicate(new FileDescriptor()); throw new AssertionError("accepted errno"); }
   catch(IOException expected) { if(ParcelFileDescriptor.closed!=1) throw new AssertionError("leak "+sdk); }
   Os.reject=false; Os.denied=true;
   try { duplicate(new FileDescriptor()); throw new AssertionError("accepted denial"); }
   catch(IOException expected) { if(ParcelFileDescriptor.closed!=2) throw new AssertionError("denied leak "+sdk); }
  }
 }
}
'''.replace('METHODS', methods)
        java_home = os.environ.get('JAVA_HOME')
        java = str(Path(java_home) / 'bin/java') if java_home else 'java'
        with tempfile.TemporaryDirectory(prefix='root-descriptor-') as temp:
            path = Path(temp) / 'DescriptorCheck.java'
            path.write_text(harness, encoding='utf-8')
            result = subprocess.run([java, "-Duser.language=en", "-Duser.country=US", str(path)], capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr.decode("utf-8", errors="replace"))
            # Simulate a legacy runtime that refuses to expose the exact method.
            missing = harness.replace('public static int fcntlInt(', 'private static int fcntlInt(')
            begin = missing.index(' public static void main(')
            missing = missing[:begin] + '''
 public static void main(String[] args) throws Exception {
  Build.VERSION.SDK_INT=26;
  try { duplicate(new FileDescriptor()); throw new AssertionError("accepted unavailable API"); }
  catch(IOException expected) {
   if(ParcelFileDescriptor.closed!=1 || Os.calls!=0) throw new AssertionError("unsafe fallback");
  }
 }
}
'''
            path.write_text(missing, encoding='utf-8')
            result = subprocess.run([java, "-Duser.language=en", "-Duser.country=US", str(path)], capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr.decode("utf-8", errors="replace"))
