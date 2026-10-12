# Main-app-only atomic root rename

`RootAtomicRename` exposes one fixed JNI mutation:

```
renameat2(sourceDirectoryFd, sourceChild, targetDirectoryFd, targetChild, RENAME_NOREPLACE)
```

Both directory descriptors must already be open, pinned and verified by the caller,
which retains them for the entire synchronous call. Negative descriptors, including
`AT_FDCWD`, are rejected. The kernel rejects non-directory descriptors. The Java and
native boundaries independently accept only 1–255 printable ASCII bytes, excluding
slash, backslash, spaces/control bytes, `.` and `..`. A filename outside this technical
constraint fails closed; it is not a package-name whitelist or new save allowlist.
The higher-level save policy remains responsible for permissible save entries.

An existing destination file, directory or symlink is never replaced. Native code
performs exactly one mutation syscall, with flags fixed to `RENAME_NOREPLACE`.
There is no `rename`, `renameat`, shell, `su`, path-based rename, JNI command dispatcher,
permission/owner/SELinux change, or overwrite fallback. `ENOSYS`, `EINVAL`,
`EOPNOTSUPP` and `EXDEV` are explicit capability/operation failures, even on API26+
devices whose vendor kernel or filesystem lacks support. `EEXIST` is a collision.
The remaining public outcomes are bounded errno categories; unknown errors become
`EIO`. This does not affect the existing embedded backend.

The primitive does not make the source entry immutable. The caller still checks
source inode, content hashes, type, owner, mode and security label around the rename,
and preserves uncertain backups/journal/quarantine entries rather than deleting them.
A successful syscall is not a stable game-save snapshot.

## Loading and packaging

Only the main APK packages `lib/<ABI>/libpdsync_root_atomic.so`; no native binary goes
into game APKs, the helper APK or Git. The main manifest/build uses extracted native
libraries. `RootAtomicRename.loadFromNativeLibraryDir` receives a fresh
PackageManager `ApplicationInfo.nativeLibraryDir` for the fixed main application
`io.github.xyz1001.pdsync.android`, never a request/archive/environment path. It loads
only the fixed library filename in a recognized canonical `/data/app` installation
layout. Symlinks, unexpected OEM layouts, absent files, wrong process bitness and
linker failures fail closed with `ROOT_ATOMIC_LIBRARY_UNAVAILABLE`. `app_process32`
or `app_process64` must match the ABI selected by PackageManager; no loader fallback
searches another directory. No code executes in the class's static initializer.

Actual loading inside Android `app_process`, root-manager behavior, SELinux policy,
and four-ABI device behavior are **not verified** by these host/build checks. No
phone, ADB, root process, kernel configuration or security setting was touched.

## Reproducible bounded Linux toolchain

This minimal build uses Debian's official LLVM 19.1.7-3+b1 binaries and Google's
immutable Android NDK sysroot revision
`f8b85cc5262c6e5cbc9a92c1bab2b18b32a4c63f`. The pinned API26 libc files are link stubs;
never ship these stubs. Their API26 symbol versions are resolved against the device's
system `libc.so` at runtime. The library uses no C++ runtime, constructors or NDK
compiler-runtime helpers, so the link deliberately uses `-nostdlib` plus the API26
libc stub and needs no CRT objects. Stack protection remains enabled.

`toolchain-lock.json` contains exact official HTTPS URLs, downloaded byte counts and
SHA-256 values for all inputs. Total binary/header downloads: **18,341,146 bytes**.
No full NDK, huge LLVM bundle or system package install is needed. An existing matching
`libLLVM.so.19.1` is required; its observed size and SHA-256 are also pinned. The
bootstrap checks that prerequisite and all downloaded inputs before extracting into
a caller-selected directory outside the repository. It never disables TLS checks.

From the repository root, on the prepared Debian x86-64 host:

```
python3 tools/android-root-native/prepare-toolchain.py --directory ../tools/root-native --fetch
python3 tools/android-root-native/build.py --toolchain ../tools/root-native --output ../tools/root-native/output
bash tools/android-root-native/test-host.sh ../tools/root-native
```

Omit `--fetch` for offline verification/extraction of cached inputs. Python 3.12+, a
host C compiler, Java with the `jdk.compiler` module, `dpkg-deb`, and GNU `readelf`
are needed. Packages are extracted rather than installed. Supply an absolute or
relative workspace directory outside the Git checkout; outputs/toolchain/cache are
rejected inside the checkout. Source, scripts, tests and input lock are committed;
ELFs, downloaded headers/packages and generated build reports are not.

`build.py` targets `aarch64-linux-android26`, `armv7a-linux-androideabi26`,
`i686-linux-android26`, and `x86_64-linux-android26`. It enforces the corresponding
ELF class/machine, one JNI export, a narrow system-symbol import set, sole dependency
`libc.so`, no executable stack, no writable executable segment, RELRO/NOW, no text
relocations, and 16 KiB load-segment alignment. It writes a private build report
beside the output libraries with sizes/hashes and `android_runtime_verified=false`.

## Host verification

`test-host.sh` runs without downloading anything. Its optional toolchain argument
also runs JNI validation using the VM-neutral official `jni.h` on the host JVM.

- Real Linux rename tests: success, collision with file/directory/symlink, missing
  source, non-directory descriptor, and pinned directory after its pathname moves.
- Injected syscall tests: exactly one call with the fixed flag for success and all
  required unsupported/cross-device/collision errors; invalid inputs cause zero calls.
- Native/Java validation: NULL, empty, dot/dotdot, traversal, slash/backslash, embedded
  NUL, whitespace/control, non-ASCII, surrogate, 255-byte boundary and excessive names.
- Java: strict trusted-directory syntax, fixed bounded loader failure, errno mapping,
  unavailable-library behavior, and native validation through real host JNI.

Real host filesystem/JVM success is not Android runtime success. It proves only the
bounded host properties listed above. No test claims support on a vendor kernel.

Official references:
- [NDK sysroot and API-specific stub library contract](https://android.googlesource.com/platform/ndk/+/refs/tags/ndk-r23b/docs/BuildSystemMaintainers.md)
- [Pinned Google NDK sysroot](https://android.googlesource.com/platform/prebuilts/ndk/+/f8b85cc5262c6e5cbc9a92c1bab2b18b32a4c63f/platform/sysroot/)
- [Official Debian LLVM19 package directory](https://deb.debian.org/debian/pool/main/l/llvm-toolchain-19/)
