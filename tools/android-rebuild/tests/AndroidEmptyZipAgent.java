import java.io.ByteArrayOutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;
import jdk.internal.org.objectweb.asm.*;

/** Test-only host emulation of android-8.0.0_r1 libcore's empty ZIP finish rejection.
 * https://android.googlesource.com/platform/libcore/+/android-8.0.0_r1/ojluni/src/main/java/java/util/zip/ZipOutputStream.java
 * Uses the host JDK's bundled ASM; no downloaded agent/framework and no production hooks. */
public final class AndroidEmptyZipAgent {
    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                    ProtectionDomain domain, byte[] bytes) {
                if (!"java/util/zip/ZipOutputStream".equals(name)) return null;
                ClassReader reader = new ClassReader(bytes);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM8, writer) {
                    @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                            String signature, String[] exceptions) {
                        MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (!name.equals("finish") || !descriptor.equals("()V")) return visitor;
                        return new MethodVisitor(Opcodes.ASM8, visitor) {
                            @Override public void visitCode() {
                                super.visitCode();
                                Label proceed = new Label();
                                visitVarInsn(Opcodes.ALOAD, 0);
                                visitFieldInsn(Opcodes.GETFIELD, "java/util/zip/ZipOutputStream", "xentries", "Ljava/util/Vector;");
                                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Vector", "isEmpty", "()Z", false);
                                visitJumpInsn(Opcodes.IFEQ, proceed);
                                visitTypeInsn(Opcodes.NEW, "java/util/zip/ZipException");
                                visitInsn(Opcodes.DUP); visitLdcInsn("No entries");
                                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/zip/ZipException", "<init>", "(Ljava/lang/String;)V", false);
                                visitInsn(Opcodes.ATHROW); visitLabel(proceed);
                            }
                        };
                    }
                }, 0);
                return writer.toByteArray();
            }
        }, true);
        // Retransformation also covers JVMs that loaded this class before premain.
        instrumentation.retransformClasses(ZipOutputStream.class);
        try {
            new ZipOutputStream(new ByteArrayOutputStream()).close();
            throw new IllegalStateException("Android empty ZIP emulation did not activate");
        } catch (ZipException expected) {
            if (!"No entries".equals(expected.getMessage())) throw expected;
        }
        System.setProperty("pdsync.androidEmptyZipEmulation", "true");
        System.err.println("Android empty ZIP emulation active: unmodified empty writer throws No entries.");
    }
}
