/* SPDX-License-Identifier: Apache-2.0 */
/* The production shared library exposes precisely one JNI operation. */
#define _GNU_SOURCE 1
#include <errno.h>
#include <sys/syscall.h>
#include <unistd.h>
#ifndef PDSYNC_HOST_TEST
#include <jni.h>
#endif

#define PDSYNC_CHILD_MAX 255
#define PDSYNC_RENAME_NOREPLACE 1U

/* A byte length is explicit: embedded NUL and truncation are never accepted. */
static int safe_child(const char *name, int length) {
    if (!name || length < 1 || length > PDSYNC_CHILD_MAX) return 0;
    if (name[0] == '.' && (length == 1 || (length == 2 && name[1] == '.'))) return 0;
    for (int i = 0; i < length; ++i) {
        unsigned char c = (unsigned char) name[i];
        if (c < 0x21 || c > 0x7e || c == '/' || c == '\\') return 0;
    }
    return 1;
}

/* Do not expose arbitrary errno values or native diagnostics over this boundary. */
static int bounded_errno(int value) {
    switch (value) {
        case EEXIST: case EXDEV: case ENOSYS: case EOPNOTSUPP: case EINVAL:
        case EBADF: case ENOENT: case ENOTDIR: case EACCES: case EPERM:
        case EROFS: case ENOSPC: case EIO: return value;
        default: return EIO;
    }
}

static int atomic_rename(int from_fd, const char *from, int from_length,
                         int to_fd, const char *to, int to_length) {
    /* In particular, AT_FDCWD and all other negative descriptors are forbidden. */
    if (from_fd < 0 || to_fd < 0) return EBADF;
    if (!safe_child(from, from_length) || !safe_child(to, to_length)) return EINVAL;
    /* Copy explicit lengths so host/native test callers cannot extend a name. */
    char source[PDSYNC_CHILD_MAX + 1], target[PDSYNC_CHILD_MAX + 1];
    for (int i = 0; i < from_length; ++i) source[i] = from[i];
    for (int i = 0; i < to_length; ++i) target[i] = to[i];
    source[from_length] = '\0'; target[to_length] = '\0';
#ifdef SYS_renameat2
    /* Exactly one mutation; never call rename/renameat or retry without the flag. */
    long result = syscall(SYS_renameat2, from_fd, source, to_fd, target,
                          PDSYNC_RENAME_NOREPLACE);
    return result == 0 ? 0 : bounded_errno(errno);
#else
    return ENOSYS;
#endif
}

#ifndef PDSYNC_HOST_TEST
static int java_child(JNIEnv *env, jstring name, char output[PDSYNC_CHILD_MAX + 1]) {
    if (!name) return -1;
    jsize length = (*env)->GetStringLength(env, name);
    if (length < 1 || length > PDSYNC_CHILD_MAX) return -1;
    jchar chars[PDSYNC_CHILD_MAX];
    (*env)->GetStringRegion(env, name, 0, length, chars);
    if ((*env)->ExceptionCheck(env)) return -1;
    for (jsize i = 0; i < length; ++i) {
        if (chars[i] < 0x21 || chars[i] > 0x7e) return -1;
        output[i] = (char) chars[i];
    }
    output[length] = '\0';
    return safe_child(output, length) ? length : -1;
}

JNIEXPORT jint JNICALL
Java_io_github_xyz1001_pdsync_root_RootAtomicRename_nativeRenameNoReplace(
        JNIEnv *env, jclass type, jint from_fd, jstring from, jint to_fd, jstring to) {
    (void) type;
    if (from_fd < 0 || to_fd < 0) return EBADF;
    char source[PDSYNC_CHILD_MAX + 1], target[PDSYNC_CHILD_MAX + 1];
    int source_length = java_child(env, from, source);
    if (source_length < 0) return EINVAL;
    int target_length = java_child(env, to, target);
    if (target_length < 0) return EINVAL;
    return atomic_rename(from_fd, source, source_length, to_fd, target, target_length);
}
#endif
