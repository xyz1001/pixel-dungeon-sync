/* SPDX-License-Identifier: Apache-2.0 */
#define _GNU_SOURCE 1
#include <assert.h>
#include <errno.h>
#include <stdarg.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>
static int forced_errno, calls;
static long injected_syscall(long operation, ...) {
    ++calls;
    assert(operation == SYS_renameat2);
    va_list args;
    va_start(args, operation);
    assert(va_arg(args, int) == 42);
    assert(strcmp(va_arg(args, const char *), "source") == 0);
    assert(va_arg(args, int) == 43);
    assert(strcmp(va_arg(args, const char *), "target") == 0);
    assert(va_arg(args, unsigned int) == 1U);
    va_end(args);
    errno = forced_errno;
    return forced_errno ? -1 : 0;
}
#define PDSYNC_HOST_TEST 1
#define syscall injected_syscall
#include "../../android-root/src/main/native/root_atomic_rename.c"
int main(void) {
    int errors[] = {0, ENOSYS, EOPNOTSUPP, EINVAL, EXDEV, EEXIST, EPERM, EIO, EINTR, ENOMEM};
    for (unsigned i = 0; i < sizeof(errors)/sizeof(errors[0]); ++i) {
        forced_errno = errors[i]; calls = 0;
        int result = atomic_rename(42, "source", 6, 43, "target", 6);
        assert(calls == 1); // Especially capability failure: no retry/fallback syscall.
        assert(result == (forced_errno ? bounded_errno(forced_errno) : 0));
    }
    calls = 0;
    assert(atomic_rename(42, "../escape", 9, 43, "target", 6) == EINVAL);
    assert(atomic_rename(-100, "source", 6, 43, "target", 6) == EBADF);
    assert(calls == 0);
    return 0;
}
