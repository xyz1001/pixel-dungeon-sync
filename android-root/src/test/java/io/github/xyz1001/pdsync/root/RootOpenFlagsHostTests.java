package io.github.xyz1001.pdsync.root;

import io.github.xyz1001.pdsync.root.mutation.MutationOs.Error;
import io.github.xyz1001.pdsync.root.mutation.MutationOs.Failure;
import io.github.xyz1001.pdsync.root.mutation.MutationOs.Operation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Host-only ABI constant and bounded failure metadata tests. No Android classes execute. */
public final class RootOpenFlagsHostTests {
    private static int assertions;
    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 1, "pinned header fixture is required");
        pinnedHeaders(Path.of(args[0]));
        rejectUnknownLayouts();
        boundedFailureMetadata();
        System.out.println("Root open-flag host tests passed: " + assertions
                + " assertions; four ABI headers verified; Android/root runtime NOT tested");
    }

    private static void pinnedHeaders(Path fixture) throws Exception {
        Set<String> expected = new HashSet<>(Arrays.asList("armeabi-v7a", "arm64-v8a", "x86", "x86_64"));
        for (String row : Files.readAllLines(fixture)) {
            String[] fields = row.split("\t", -1);
            check(fields.length == 5, "ABI fixture field count");
            String abi = fields[0];
            check(expected.remove(abi), "known ABI exactly once: " + abi);
            int nofollow = Integer.parseInt(fields[1]);
            int directory = Integer.parseInt(fields[2]);
            int cloexec = Integer.parseInt(fields[3]);
            int nonblock = Integer.parseInt(fields[4]);
            int resolved = RootOpenFlags.directory(nofollow);
            check(resolved == directory, abi + ": resolver equals pinned O_DIRECTORY");
            check(RootOpenFlags.CLOEXEC == cloexec, abi + ": CLOEXEC equals pinned header");
            check(directory != 0 && nofollow != 0 && cloexec != 0 && nonblock != 0, abi + ": nonzero flags");
            check((directory & (nofollow | cloexec | nonblock)) == 0, abi + ": distinct protection bits");
            check((resolved | nofollow | RootOpenFlags.CLOEXEC | nonblock)
                    == (directory | nofollow | cloexec | nonblock), abi + ": complete open mask");
            System.out.printf("%s: Java directory=0%o, CLOEXEC=0%o matches pinned headers%n", abi, resolved, cloexec);
        }
        check(expected.isEmpty(), "all four supported ABIs covered");
        // The process-provided bit, not a device ABI/preferred-ABI string, selects the layout.
        check(RootOpenFlags.directory(0100000) == 040000, "ARM process layout");
        check(RootOpenFlags.directory(00400000) == 00200000, "x86 process layout");
    }

    private static void rejectUnknownLayouts() throws Exception {
        for (int value : new int[] {0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE,
                040000, 00200000, 02000000, 04000, 0100000 | 00400000,
                0100000 | 1, 00400000 | 1, 0100000 | 04000, 00400000 | 04000}) {
            try {
                RootOpenFlags.directory(value);
                throw new AssertionError("Accepted unknown NOFOLLOW layout: " + value);
            } catch (IOException expected) {
                assertions++;
            }
        }
        for (int bit = 0; bit < 32; bit++) {
            int value = 1 << bit;
            if (value == 0100000 || value == 00400000) continue;
            try {
                RootOpenFlags.directory(value);
                throw new AssertionError("Accepted unknown single-bit NOFOLLOW: " + bit);
            } catch (IOException expected) {
                assertions++;
            }
        }
    }

    private static void boundedFailureMetadata() {
        String fixedOperations = "NONE,OPEN_DIRECTORY,OPEN_REGULAR,DUP,FCNTL,FSTAT,LSTAT,READDIR,"
                + "MKDIR,PREAD,PWRITE,FCHMOD,FCHOWN,FSYNC,GETXATTR,SETXATTR,RENAME,LINK,REMOVE,LOCK,TIMESTAMP,"
                + "LSTAT_DATA_PACKAGE,LSTAT_SAVE_FILES,LSTAT_WORKSPACE";
        check(Arrays.toString(Operation.values()).replace(" ", "")
                .equals("[" + fixedOperations + "]"), "fixed operation allowlist");
        Throwable cause = new IOException("private test cause /data/user/0/not-for-diagnostics");
        for (Error error : Error.values()) {
            Failure[] legacy = {new Failure(error), new Failure(error, cause),
                    new Failure(error, 13), new Failure(error, 13, cause)};
            for (int i = 0; i < legacy.length; i++) {
                check(legacy[i].operation == Operation.NONE, "legacy constructor operation");
                check(legacy[i].errno == (i < 2 ? 0 : 13), "legacy constructor errno");
                check(legacy[i].getCause() == (i % 2 == 0 ? null : cause), "legacy constructor cause");
                check(legacy[i].getMessage().equals(error.name()), "legacy message remains bounded");
            }
            for (Operation operation : Operation.values()) {
                for (int errno : new int[] {Integer.MIN_VALUE, -1, 0, 1, 13, 4095, 4096, Integer.MAX_VALUE}) {
                    Failure failure = new Failure(error, operation, errno, cause);
                    check(failure.error == error, "error retained");
                    check(failure.operation == operation, "operation retained");
                    check(failure.errno == (errno >= 1 && errno <= 4095 ? errno : 0), "errno bounded");
                    check(failure.getCause() == cause, "cause retained for local diagnostics");
                    check(failure.getMessage().equals(error.name()), "message does not expose cause or path");
                }
            }
            check(new Failure(error, null, 13, cause).operation == Operation.NONE, "null operation defaults to NONE");
            check(new Failure(error, -1).errno == 0, "legacy negative errno bounded");
            check(new Failure(error, 4096, cause).errno == 0, "legacy oversized errno bounded");
        }
    }
}
