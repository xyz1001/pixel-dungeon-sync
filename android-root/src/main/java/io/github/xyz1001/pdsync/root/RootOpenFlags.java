package io.github.xyz1001.pdsync.root;

import java.io.IOException;

/** Linux flags missing from public Android stubs, selected from the running platform's ABI. */
public final class RootOpenFlags {
    private RootOpenFlags() { }
    public static final int CLOEXEC = 02000000;

    /**
     * Android's public O_NOFOLLOW has the same ABI split as O_DIRECTORY. Use its runtime
     * value, never the device's preferred ABI (a 32-bit process may run on a 64-bit device).
     * Values are verified against the pinned NDK headers for all four supported ABIs.
     */
    public static int directory(int platformNoFollow) throws IOException {
        if (platformNoFollow == 0100000) return 040000; // ARM and AArch64.
        if (platformNoFollow == 00400000) return 00200000; // x86 and x86_64.
        throw new IOException("Unsupported Android open-flag layout");
    }
}
