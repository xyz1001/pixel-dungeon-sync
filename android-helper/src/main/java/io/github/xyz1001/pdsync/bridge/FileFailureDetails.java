package io.github.xyz1001.pdsync.bridge;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.zip.ZipException;


final class FileFailureDetails {
    final Throwable cause;
    final Stage stage;

    enum Error {
        ATOMIC_MOVE_UNSUPPORTED,
        ACCESS_DENIED,
        NOT_FOUND,
        ALREADY_EXISTS,
        FILE_SYSTEM,
        ZIP_INVALID,
        ANDROID_ERRNO,
        IO,
        UNKNOWN
    }

    enum Stage {
        HELPER_WORKSPACE,
        READ_ARCHIVE,
        ARCHIVE_DESCRIPTOR_STAT,
        ARCHIVE_DESCRIPTOR_FLAGS,
        ARCHIVE_DESCRIPTOR_READ,
        ARCHIVE_DESCRIPTOR_WRITE,
        ARCHIVE_DESCRIPTOR_RECHECK,
        EXTRACT_ARCHIVE,
        CAPTURE,
        WRITE_BACKUP,
        WRITE_JOURNAL,
        MOVE_OLD,
        MOVE_NEW,
        VERIFY,
        FINALIZE,
        CLEANUP
    }

    static int boundedErrno(int i) {
        if (i < 0 || i > 4095) {
            return -1;
        }
        return i;
    }

    FileFailureDetails(Stage stage, Throwable th) {
        this.stage = stage;
        this.cause = th;
    }

    static Error classify(Throwable th, int i) {
        Set setNewSetFromMap = Collections.newSetFromMap(new IdentityHashMap());
        int i2 = 0;
        boolean z = false;
        while (th != null && i2 < 32 && setNewSetFromMap.add(th)) {
            if (th instanceof AtomicMoveNotSupportedException) {
                return Error.ATOMIC_MOVE_UNSUPPORTED;
            }
            if ((th instanceof AccessDeniedException) || (th instanceof SecurityException)) {
                return Error.ACCESS_DENIED;
            }
            if ((th instanceof NoSuchFileException) || (th instanceof FileNotFoundException)) {
                return Error.NOT_FOUND;
            }
            if (th instanceof FileAlreadyExistsException) {
                return Error.ALREADY_EXISTS;
            }
            if (th instanceof FileSystemException) {
                return Error.FILE_SYSTEM;
            }
            if (th instanceof ZipException) {
                return Error.ZIP_INVALID;
            }
            if (th instanceof IOException) {
                z = true;
            }
            i2++;
            th = th.getCause();
        }
        return boundedErrno(i) != -1 ? Error.ANDROID_ERRNO : z ? Error.IO : Error.UNKNOWN;
    }
}
