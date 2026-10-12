#!/usr/bin/env bash
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-native-tests.XXXXXX")
trap 'rm -rf "$OUT"' EXIT
cc -std=c11 -Wall -Wextra -Werror -O2 "$HERE/test-native.c" -o "$OUT/test-native"
cc -std=c11 -Wall -Wextra -Werror -O2 "$HERE/test-errors.c" -o "$OUT/test-errors"
"$OUT/test-native"
"$OUT/test-errors"
java com.sun.tools.javac.Main -d "$OUT/classes" \
  "$REPO/android-root/src/main/java/io/github/xyz1001/pdsync/root/RootAtomicRename.java" \
  "$HERE/RootAtomicRenameHostTests.java"
JNI_ARGS=()
if [[ $# -eq 1 ]]; then
  # Copy only VM-neutral official jni.h. Do not put Android libc headers into the host build.
  mkdir "$OUT/headers"
  cp "$1/sysroot/usr/include/jni.h" "$OUT/headers/"
  cc -std=c11 -Wall -Wextra -Werror -O2 -fPIC -fvisibility=hidden -shared \
    -I "$OUT/headers" "$REPO/android-root/src/main/native/root_atomic_rename.c" \
    -o "$OUT/libpdsync_root_atomic_host.so"
  JNI_ARGS=("$OUT/libpdsync_root_atomic_host.so")
fi
java -cp "$OUT/classes" io.github.xyz1001.pdsync.root.RootAtomicRenameHostTests "${JNI_ARGS[@]}"
echo 'Host-only verification passed; no root, app_process, ADB or Android device invoked.'
