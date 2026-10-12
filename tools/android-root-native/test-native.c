/* SPDX-License-Identifier: Apache-2.0 */
#define _GNU_SOURCE 1
#define PDSYNC_HOST_TEST 1
#include "../../android-root/src/main/native/root_atomic_rename.c"
#include <assert.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

static void make_file(int directory, const char *name, const char *bytes) {
    int file = openat(directory, name, O_CREAT | O_EXCL | O_WRONLY | O_CLOEXEC, 0600);
    assert(file >= 0);
    assert(write(file, bytes, strlen(bytes)) == (ssize_t) strlen(bytes));
    assert(close(file) == 0);
}
static void contents(int directory, const char *name, const char *expected) {
    char actual[100] = {0};
    int file = openat(directory, name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    assert(file >= 0);
    assert(read(file, actual, sizeof(actual) - 1) == (ssize_t) strlen(expected));
    assert(strcmp(actual, expected) == 0);
    assert(close(file) == 0);
}
int main(void) {
    assert(!safe_child(NULL, 1)); assert(!safe_child("", 0));
    const char *invalid[] = {".", "..", "/escape", "one/two", "back\\slash", "new\nline", "space name", "\177", "\303\251"};
    for (unsigned i = 0; i < sizeof(invalid)/sizeof(invalid[0]); ++i)
        assert(!safe_child(invalid[i], (int)strlen(invalid[i])));
    assert(!safe_child("a\0b", 3));
    char boundary[256]; memset(boundary, 'x', sizeof(boundary));
    assert(safe_child(boundary, 255)); assert(!safe_child(boundary, 256));
    assert(safe_child(".pdsync-stage-123", 17));
    assert(atomic_rename(-100, "a", 1, 0, "b", 1) == EBADF);
    assert(atomic_rename(0, "..", 2, 0, "b", 1) == EINVAL);
    int errors[] = {EEXIST, EXDEV, ENOSYS, EOPNOTSUPP, EINVAL, EBADF, ENOENT,
                    ENOTDIR, EACCES, EPERM, EROFS, ENOSPC, EIO};
    for (unsigned i = 0; i < sizeof(errors)/sizeof(errors[0]); ++i) assert(bounded_errno(errors[i]) == errors[i]);
    assert(bounded_errno(-1) == EIO); assert(bounded_errno(99999) == EIO);

    char directory[] = "/tmp/pdsync-atomic-host-XXXXXX";
    assert(mkdtemp(directory));
    int root = open(directory, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    assert(root >= 0);
    assert(mkdirat(root, "from", 0700) == 0); assert(mkdirat(root, "to", 0700) == 0);
    int from = openat(root, "from", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    int to = openat(root, "to", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    assert(from >= 0 && to >= 0);
    make_file(from, "source", "original"); make_file(to, "occupied", "must-survive");
    assert(atomic_rename(from, "source", 6, to, "occupied", 8) == EEXIST);
    contents(from, "source", "original"); contents(to, "occupied", "must-survive");
    assert(symlinkat("missing-outside-target", to, "symlink") == 0);
    assert(atomic_rename(from, "source", 6, to, "symlink", 7) == EEXIST);
    assert(mkdirat(to, "occupied-directory", 0700) == 0);
    assert(atomic_rename(from, "source", 6, to, "occupied-directory", 18) == EEXIST);
    assert(atomic_rename(from, "missing", 7, to, "new", 3) == ENOENT);
    int regular = openat(from, "source", O_RDONLY | O_CLOEXEC);
    assert(regular >= 0);
    assert(atomic_rename(regular, "source", 6, to, "new", 3) == ENOTDIR);
    assert(close(regular) == 0);
    /* Change the parent pathname: the supplied descriptors must stay anchored. */
    assert(renameat(root, "from", root, "moved-parent") == 0);
    assert(mkdirat(root, "from", 0700) == 0);
    assert(atomic_rename(from, "source", 6, to, "new", 3) == 0);
    contents(to, "new", "original");
    assert(faccessat(from, "source", F_OK, 0) == -1 && errno == ENOENT);
    assert(atomic_rename(from, "new", 3, to, "another", 7) == ENOENT);
    assert(unlinkat(to, "new", 0) == 0); assert(unlinkat(to, "occupied", 0) == 0);
    assert(unlinkat(to, "symlink", 0) == 0); assert(unlinkat(to, "occupied-directory", AT_REMOVEDIR) == 0);
    assert(close(from) == 0); assert(close(to) == 0);
    assert(unlinkat(root, "from", AT_REMOVEDIR) == 0); assert(unlinkat(root, "moved-parent", AT_REMOVEDIR) == 0);
    assert(unlinkat(root, "to", AT_REMOVEDIR) == 0); assert(close(root) == 0);
    assert(rmdir(directory) == 0);
    puts("native host tests passed: safe names, bounded errno, no replacement, pinned directories");
    return 0;
}
