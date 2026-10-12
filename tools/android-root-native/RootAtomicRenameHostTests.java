package io.github.xyz1001.pdsync.root;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public final class RootAtomicRenameHostTests {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        String[] unsafe = {null, "", ".", "..", "/escape", "a/b", "a\\b", "a\0b", "a b", "a\nb", "é", "\ud800", "x".repeat(256)};
        for (String name : unsafe) check(!RootAtomicRename.safeChild(name));
        check(RootAtomicRename.safeChild("x".repeat(255)));
        check(RootAtomicRename.safeChild(".pdsync-stage-123"));
        String prefix = "/data/app/io.github.xyz1001.pdsync.android-token/lib/";
        for (String abi : new String[]{"arm", "arm64", "x86", "x86_64"})
            check(RootAtomicRename.validLibraryDirectory(prefix + abi));
        check(RootAtomicRename.validLibraryDirectory("/data/app/~~token/io.github.xyz1001.pdsync.android-token/lib/arm64"));
        String[] invalid = {null, "/tmp/lib", "/data/local/tmp/lib", prefix + "wrong", prefix + "arm64/", prefix + "../arm64",
                "/data/app/other-token/lib/arm64", "/data/app/io.github.xyz1001.pdsync.android-/lib/arm64",
                "/data/app/arbitrary/io.github.xyz1001.pdsync.android-token/lib/arm64"};
        for (String directory : invalid) {
            check(!RootAtomicRename.validLibraryDirectory(directory));
            try { RootAtomicRename.loadFromNativeLibraryDir(directory); throw new AssertionError(); }
            catch (IOException expected) { check(expected.getMessage().equals("ROOT_ATOMIC_LIBRARY_UNAVAILABLE")); }
        }
        check(RootAtomicRename.fromErrno(0) == RootAtomicRename.Result.OK);
        check(RootAtomicRename.fromErrno(17) == RootAtomicRename.Result.EXISTS);
        check(RootAtomicRename.fromErrno(18) == RootAtomicRename.Result.CROSS_DEVICE);
        check(RootAtomicRename.fromErrno(38) == RootAtomicRename.Result.NOT_IMPLEMENTED);
        check(RootAtomicRename.fromErrno(95) == RootAtomicRename.Result.UNSUPPORTED);
        check(RootAtomicRename.fromErrno(22) == RootAtomicRename.Result.INVALID_ARGUMENT);
        check(RootAtomicRename.fromErrno(123456) == RootAtomicRename.Result.IO);
        check(RootAtomicRename.Result.EXISTS.errno == 17);
        check(RootAtomicRename.Result.CROSS_DEVICE.errno == 18);
        check(RootAtomicRename.Result.INVALID_ARGUMENT.errno == 22);
        check(RootAtomicRename.Result.PERMISSION.errno == 0);
        check(RootAtomicRename.Result.INVALID_NAME.errno == 0);
        Constructor<RootAtomicRename> constructor = RootAtomicRename.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        RootAtomicRename bridge = constructor.newInstance();
        check(bridge.renameNoReplace(-100, "source", 42, "target") == RootAtomicRename.Result.BAD_DESCRIPTOR);
        for (String name : unsafe) check(bridge.renameNoReplace(42, name, 43, "target") == RootAtomicRename.Result.INVALID_NAME);
        check(bridge.renameNoReplace(42, "source", 43, "target") == RootAtomicRename.Result.NOT_IMPLEMENTED);
        if (args.length == 1) {
            // Explicit host-test library, never the production trusted-loader path.
            System.load(args[0]);
            Method call = RootAtomicRename.class.getDeclaredMethod("nativeRenameNoReplace", int.class, String.class, int.class, String.class);
            call.setAccessible(true);
            for (String name : unsafe) {
                check((Integer) call.invoke(null, 42, name, 43, "target") == 22);
                check((Integer) call.invoke(null, 42, "source", 43, name) == 22);
            }
            check((Integer) call.invoke(null, -100, "source", 43, "target") == 9);
            check((Integer) call.invoke(null, Integer.MAX_VALUE, "source", Integer.MAX_VALUE, "target") == 9);
        }
        System.out.println("Java host tests passed: loader constraints, bounded results, name validation" + (args.length == 1 ? ", real host JNI" : ""));
    }
}
