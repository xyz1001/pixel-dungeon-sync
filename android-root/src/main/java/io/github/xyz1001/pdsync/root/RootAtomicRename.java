package io.github.xyz1001.pdsync.root;

import java.io.File;
import java.io.IOException;

/**
 * Main-app-only JNI bridge for one atomic, no-replace directory-relative rename.
 * This is not a root launcher, arbitrary-path API, permission change, or RPC service.
 * Source/destination directory descriptors must remain pinned and owned by the caller
 * until the synchronous call returns. The caller must verify source identity, owner,
 * mode, contents and SELinux label around the operation; this syscall only prevents
 * replacement of an existing destination. No source-side identity atomicity is claimed.
 */
public final class RootAtomicRename {
    public static final String LIBRARY_NAME = "libpdsync_root_atomic.so";
    private static final String MAIN_PACKAGE = "io.github.xyz1001.pdsync.android";
    private static String loadedPath;

    public enum Result {
        OK(0), EXISTS(17), CROSS_DEVICE(18), NOT_IMPLEMENTED(38), UNSUPPORTED(95),
        INVALID_ARGUMENT(22), INVALID_NAME(0), BAD_DESCRIPTOR(9), MISSING(2),
        NOT_DIRECTORY(20), PERMISSION(0), READ_ONLY(30), NO_SPACE(28), IO(5);

        /** Fixed Linux errno category; zero when no syscall ran or codes are ambiguous. */
        public final int errno;
        Result(int errno) { this.errno = errno; }
    }

    private RootAtomicRename() { }

    /**
     * The argument MUST come from a fresh PackageManager ApplicationInfo lookup for
     * the fixed main package, never from a request, archive, environment or save path.
     * Only the standard extracted /data/app install layout is accepted. Unexpected
     * OEM layouts, wrong process bitness and Android linker restrictions fail closed.
     * Loading under app_process requires real-device verification.
     */
    public static synchronized RootAtomicRename loadFromNativeLibraryDir(String nativeLibraryDir)
            throws IOException {
        try {
            if (!validLibraryDirectory(nativeLibraryDir)) throw unavailable();
            File directory = new File(nativeLibraryDir);
            File library = new File(directory, LIBRARY_NAME);
            if (!directory.isDirectory() || !library.isFile()
                    || !directory.getPath().equals(directory.getCanonicalPath())
                    || !library.getPath().equals(library.getCanonicalPath())) throw unavailable();
            String path = library.getPath();
            if (loadedPath != null && !loadedPath.equals(path)) throw unavailable();
            if (loadedPath == null) {
                System.load(path);
                loadedPath = path;
            }
            return new RootAtomicRename();
        } catch (IOException | UnsatisfiedLinkError | SecurityException denied) {
            // Deliberately omit native loader messages, paths and environmental details.
            throw unavailable();
        }
    }

    private static IOException unavailable() {
        return new IOException("ROOT_ATOMIC_LIBRARY_UNAVAILABLE");
    }

    static boolean validLibraryDirectory(String directory) {
        if (directory == null || directory.length() > 1024 || !directory.startsWith("/data/app/")) return false;
        String[] parts = directory.substring("/data/app/".length()).split("/", -1);
        // Android's legacy <package>-<random>/lib/<abi> and modern ~~<random>/<package>-<random>/lib/<abi>.
        if (parts.length != 3 && parts.length != 4) return false;
        int packageIndex = parts.length - 3;
        if (packageIndex == 1 && !parts[0].startsWith("~~")) return false;
        if (!parts[packageIndex].startsWith(MAIN_PACKAGE + "-")
                || parts[packageIndex].length() <= MAIN_PACKAGE.length() + 1
                || !parts[packageIndex + 1].equals("lib")) return false;
        String abi = parts[packageIndex + 2];
        if (!abi.equals("arm") && !abi.equals("arm64") && !abi.equals("x86") && !abi.equals("x86_64")) return false;
        for (String part : parts) if (!safeChild(part)) return false;
        return true;
    }

    static boolean safeChild(String name) {
        if (name == null || name.isEmpty() || name.length() > 255 || name.equals(".") || name.equals("..")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x21 || c > 0x7e || c == '/' || c == '\\') return false;
        }
        return true;
    }

    public Result renameNoReplace(int sourceDirectoryFd, String sourceName,
                                  int targetDirectoryFd, String targetName) {
        if (sourceDirectoryFd < 0 || targetDirectoryFd < 0) return Result.BAD_DESCRIPTOR;
        if (!safeChild(sourceName) || !safeChild(targetName)) return Result.INVALID_NAME;
        try {
            return fromErrno(nativeRenameNoReplace(sourceDirectoryFd, sourceName,
                                                  targetDirectoryFd, targetName));
        } catch (UnsatisfiedLinkError unavailable) {
            return Result.NOT_IMPLEMENTED;
        }
    }

    // Linux errno numbers are identical on the four supported Android ABIs.
    static Result fromErrno(int errno) {
        switch (errno) {
            case 0: return Result.OK;
            case 17: return Result.EXISTS;             // EEXIST
            case 18: return Result.CROSS_DEVICE;       // EXDEV
            case 38: return Result.NOT_IMPLEMENTED;    // ENOSYS
            case 95: return Result.UNSUPPORTED;        // EOPNOTSUPP
            case 22: return Result.INVALID_ARGUMENT;   // EINVAL, flag/capability unsupported
            case 9: return Result.BAD_DESCRIPTOR;      // EBADF
            case 2: return Result.MISSING;             // ENOENT
            case 20: return Result.NOT_DIRECTORY;      // ENOTDIR
            case 1: case 13: return Result.PERMISSION; // EPERM / EACCES
            case 30: return Result.READ_ONLY;          // EROFS
            case 28: return Result.NO_SPACE;           // ENOSPC
            default: return Result.IO;
        }
    }

    private static native int nativeRenameNoReplace(int sourceDirectoryFd, String sourceName,
                                                     int targetDirectoryFd, String targetName);
}
