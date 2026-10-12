// Host-only test double. Never package this fixture into an APK.
package android.content.pm;
public class Signature {
 private final byte[] bytes;
 public Signature(byte[] bytes) { this.bytes = bytes.clone(); }
 public byte[] toByteArray() { return bytes.clone(); }
}
