#!/usr/bin/env python3
"""Check pinned Android ABI macros and the adapters' source-level open contract.

This tests preprocessor constants and Java source wiring, not Android syscalls.
Only the host compiler's builtin headers are permitted; libc/ABI headers must
come from the supplied NDK sysroot, with one explicit arch include path per ABI.
"""

import os
from pathlib import Path
import re
import shlex
import subprocess
import sys


ABIS = (
    ("armeabi-v7a", "arm-linux-androideabi"),
    ("arm64-v8a", "aarch64-linux-android"),
    ("x86", "i686-linux-android"),
    ("x86_64", "x86_64-linux-android"),
)
FLAGS = ("O_NOFOLLOW", "O_DIRECTORY", "O_CLOEXEC", "O_NONBLOCK")


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def header_values(sysroot):
    include = sysroot / "usr/include"
    compiler = shlex.split(os.environ.get("CC", "cc"))
    builtins = subprocess.check_output(
        compiler + ["-print-file-name=include"], text=True
    ).strip()
    check(Path(builtins).is_dir(), "Cannot find host compiler builtin headers")
    rows = []
    for abi, triple in ABIS:
        arch = include / triple
        check((arch / "asm/fcntl.h").is_file(), f"Missing pinned {abi} fcntl.h")
        output = subprocess.check_output(
            compiler + ["-E", "-dM", "-x", "c", "-nostdinc", "-isystem", builtins,
                        "-I", str(arch), "-I", str(include), "-"],
            input="#include <asm/fcntl.h>\n", text=True,
        )
        macros = dict(re.findall(r"^#define\s+(O_\w+)\s+([0-9]+)\s*$", output, re.M))
        values = []
        for name in FLAGS:
            check(name in macros, f"{abi}: missing literal {name} macro")
            value = macros[name]
            values.append(int(value, 8 if value.startswith("0") else 10))
        rows.append("\t".join([abi] + [str(value) for value in values]))
        print(f"{abi}: " + ", ".join(f"{name}=0{value:o}" for name, value in zip(FLAGS, values)))
    return "\n".join(rows) + "\n"


def adapter_contract(source_root):
    for relative in ("AndroidRootFileSystem.java", "mutation/AndroidMutationOs.java"):
        source = (source_root / relative).read_text()
        # Ignore comments so a removed protection cannot pass through a stale comment.
        source = re.sub(r"/\*.*?\*/|//[^\n]*", "", source, flags=re.S)
        check(re.search(r"RootOpenFlags\.directory\s*\(\s*OsConstants\.O_NOFOLLOW\s*\)", source),
              f"{relative}: directory flags must come from the runtime NOFOLLOW resolver")
        check(not re.search(r"\b(?:O_DIRECTORY|DIRECTORY_FLAG)\s*=\s*(?:0[0-7]+|0x[0-9a-fA-F]+|[0-9]+)", source),
              f"{relative}: hardcoded directory flag bypasses ABI resolver")
        calls = re.findall(r"\bOs\.open\s*\((.*?)\);", source, flags=re.S)
        check(calls, f"{relative}: no Os.open call checked")
        for call in calls:
            for required in ("OsConstants.O_NOFOLLOW", "RootOpenFlags.CLOEXEC", "OsConstants.O_NONBLOCK"):
                check(required in call, f"{relative}: Os.open must retain {required}")
        if relative.startswith("mutation/"):
            check("OsConstants.F_SETFD" in source and "OsConstants.FD_CLOEXEC" in source,
                  f"{relative}: existing duplicate close-on-exec protection removed")
            mutation_operation_contract(source)
        else:
            reader_operation_contract(source)
        print(f"{relative}: source flag and operation-annotation contract passed")


def method(source, name):
    match = re.search(r"\b" + name + r"\s*\([^;{}]*\)\s*(?:throws\s+[\w., ]+)?\s*\{", source)
    check(match, f"Missing Java method {name}")
    start = match.end()
    depth = 1
    for end in range(start, len(source)):
        depth += (source[end] == "{") - (source[end] == "}")
        if depth == 0:
            return source[start:end]
    raise AssertionError(f"Unclosed Java method {name}")


def mutation_operation_contract(source):
    # These are source-wiring guards, not simulated Android kernel failures.
    mappings = {"stat": "FSTAT", "childStat": "LSTAT", "children": "READDIR",
                "mkdirExclusive": "MKDIR", "read": "PREAD", "write": "PWRITE",
                "chmod": "FCHMOD", "chown": "FCHOWN", "sync": "FSYNC",
                "rename": "RENAME", "link": "LINK", "unlink": "REMOVE",
                "rmdir": "REMOVE", "setModifiedTime": "TIMESTAMP", "exclusiveLock": "LOCK"}
    for name, operation in mappings.items():
        check(f"failure(Operation.{operation}," in method(source, name),
              f"AndroidMutationOs.{name}: expected {operation} failure annotation")
    for operation in ("GETXATTR", "SETXATTR"):
        check(f"failure(Operation.{operation}," in source, f"Missing {operation} annotation")
    duplicate = method(source, "duplicate")
    for operation in ("DUP", "FCNTL"):
        check(f"failure(Operation.{operation}," in duplicate, f"Missing duplicate {operation} annotation")
    check("failure(operation," in method(source, "open"), "open must preserve caller operation")
    for name, operation in (("openRoot", "OPEN_DIRECTORY"), ("openDirectory", "OPEN_DIRECTORY"),
                            ("openRegular", "OPEN_REGULAR"), ("createExclusive", "OPEN_REGULAR")):
        check(f"Operation.{operation}" in method(source, name), f"{name}: missing {operation}")
    for caught in re.finditer(r"catch\s*\(\s*ErrnoException\s+(\w+)\s*\)\s*\{([^}]*)", source):
        variable, body = caught.groups()
        if variable == "ignored":
            check(not body.strip(), "Only best-effort close may ignore errno")
            continue
        check(re.search(r"failure\(\s*(?:operation|Operation\.\w+)\s*,\s*" + variable + r"\s*\)", body),
              "Every non-cleanup ErrnoException catch must annotate its operation")
    check("new Failure(code, operation, e.errno, e)" in source, "errno helper must retain operation and kernel errno")


def reader_operation_contract(source):
    for name, operation in (("stat", "FSTAT"), ("childStat", "LSTAT"), ("duplicate", "DUP")):
        check(f"syscall(Operation.{operation}," in method(source, name), f"Reader {name}: missing {operation}")
    pin = method(source, "pin")
    check(re.search(r"Operation operation = directory\s*\?\s*Operation.OPEN_DIRECTORY\s*:\s*Operation.OPEN_REGULAR", pin),
          "Reader pin must distinguish directory and regular opens")
    check(re.search(r"operation = Operation.FSTAT\s*;\s*StructStat stat = Os.fstat", pin),
          "Reader fstat failures must not be labeled as open failures")
    check("syscall(operation," in pin, "Reader pin must retain the current syscall operation")
    check("new MutationOs.Failure(code, operation, e.errno, e)" in source,
          "Reader syscall helper must retain operation and kernel errno")


def main():
    if len(sys.argv) != 4:
        raise SystemExit("usage: verify-open-flags-headers.py NDK_SYSROOT OUTPUT_TSV ROOT_JAVA_SOURCE")
    Path(sys.argv[2]).write_text(header_values(Path(sys.argv[1]).resolve()))
    adapter_contract(Path(sys.argv[3]))


if __name__ == "__main__":
    main()
