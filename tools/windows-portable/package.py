#!/usr/bin/env python3
"""Package a prebuilt JVM desktop application for Windows x64, without downloads."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import struct
import subprocess
import zipfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[2]
SKIKO_VERSION = "0.9.22.2"
SKIKO_NAME = "skiko-awt-runtime-windows-x64"
SKIKO_SHA256 = "2d9ada9b3f4809d2d8aa53593336b92ec8c4c6cd14d6803c5bc260db96aac6c8"
SKIKO_URL = (
    "https://repo.maven.apache.org/maven2/org/jetbrains/skiko/"
    f"{SKIKO_NAME}/{SKIKO_VERSION}/{SKIKO_NAME}-{SKIKO_VERSION}.jar"
)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def git(*args: str) -> str:
    return subprocess.check_output(["git", "-C", str(ROOT), *args], text=True).strip()


def inspect_jar(data: bytes, name: str) -> dict:
    """Validate entry names and bytecode that Java 17 may actually load."""
    maximum = 0
    native = []
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        if jar.testzip() is not None:
            raise ValueError(f"Corrupt JAR: {name}")
        entries = jar.namelist()
        if len(entries) != len(set(entries)):
            raise ValueError(f"Duplicate entries in {name}")
        for entry in entries:
            path = PurePosixPath(entry)
            lower = entry.lower()
            if path.is_absolute() or ".." in path.parts or "\\" in entry:
                raise ValueError(f"Unsafe JAR path: {name}:{entry}")
            if any(part in (".local", ".pixel-dungeon-sync") for part in path.parts):
                raise ValueError(f"Application state in JAR: {name}:{entry}")
            if (path.name.lower().startswith("config.json")
                    or lower.endswith((".jks", ".keystore", ".p12", ".pfx", ".pem", ".key", ".log"))):
                raise ValueError(f"Private state or key-like entry: {name}:{entry}")
            if lower.endswith((".so", ".dylib", ".jnilib")):
                raise ValueError(f"Non-Windows native library: {name}:{entry}")
            if lower.endswith(".dll"):
                native.append(entry)
            if not entry.endswith(".class"):
                continue
            versioned = re.match(r"META-INF/versions/(\d+)/", entry)
            if versioned and int(versioned.group(1)) > 17:
                continue
            header = jar.read(entry)[:8]
            if len(header) != 8 or header[:4] != b"\xca\xfe\xba\xbe":
                raise ValueError(f"Invalid class: {name}:{entry}")
            major = struct.unpack(">H", header[6:8])[0]
            maximum = max(maximum, major)
            if major > 61:
                raise ValueError(f"Requires newer than Java 17: {name}:{entry} ({major})")
    return {"maximumJava17VisibleClassMajor": maximum or None, "nativeLibraries": native}


def inspect_windows_runtime(data: bytes) -> dict:
    if sha256(data) != SKIKO_SHA256:
        raise ValueError("Windows Skiko JAR does not match the pinned official SHA-256")
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        dll_name = "skiko-windows-x64.dll"
        dll = jar.read(dll_name)
        expected = jar.read(dll_name + ".sha256").decode("ascii").strip()
        if sha256(dll) != expected:
            raise ValueError("Skiko DLL does not match its embedded SHA-256")
        if dll[:2] != b"MZ" or len(dll) < 64:
            raise ValueError("Skiko DLL is not a PE binary")
        offset = struct.unpack_from("<I", dll, 0x3C)[0]
        if dll[offset:offset + 4] != b"PE\0\0":
            raise ValueError("Skiko DLL has no valid PE signature")
        machine = struct.unpack_from("<H", dll, offset + 4)[0]
        if machine != 0x8664:
            raise ValueError(f"Skiko DLL is not x64: {machine:#x}")
        icu = jar.read("icudtl.dat")
        if not icu:
            raise ValueError("Skiko ICU data is empty")
        return {"dll": dll_name, "dllSha256": sha256(dll), "peMachine": "0x8664 (AMD64)",
                "icuDataBytes": len(icu), "embeddedDllChecksumVerified": True}


def inspect_release_manifest(data: bytes, version: str, code: int, commit: str) -> dict:
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        text = jar.read("META-INF/MANIFEST.MF").decode("utf-8")
    unfolded = text.replace("\r\n", "\n").replace("\n ", "")
    main = unfolded.split("\n\n", 1)[0]
    fields = dict(line.split(": ", 1) for line in main.splitlines() if ": " in line)
    expected = {"Implementation-Version": version, "PDSync-Version-Code": str(code),
                "PDSync-Source-Revision": commit}
    if any(fields.get(key) != value for key, value in expected.items()):
        raise ValueError("Application JAR manifest version, code, or source revision is stale")
    return expected


def readme(version: str, code: int, commit: str) -> str:
    return f"""像素地牢存档同步：Windows x64 便携测试包
版本：{version}（code {code}）
源代码提交：{commit}

启动
1. 将整个 ZIP 解压到可写目录，保留 launcher.cmd 与 lib 文件夹的相对位置。
2. 电脑需已安装 Windows x64 Java 17 或以上版本，建议 Java 21。
   launcher.cmd 优先使用 JAVA_HOME\\bin\\java.exe，否则使用 PATH 中的 java.exe。
3. 双击 launcher.cmd。此包不含 Java，不自动下载或安装任何程序。

升级与数据
请先关闭旧版同步器，再从新文件夹启动。不要将新版 lib 覆盖混入旧版 lib。
程序仍使用现有用户目录下的 .pixel-dungeon-sync 作为配置工作目录；
不会因解压位置改变而另建配置位置。游戏存档继续使用您原先选择的目录。
此 ZIP 不含个人配置、密码、存档、日志、签名密钥或测试数据。
这是测试版，进行同步或恢复前请保留独立的游戏存档备份。

验证边界
此包在 Linux 云端构建。已验证运行时依赖、Java 17 字节码、入口类、
Windows x64 Skiko DLL 与官方校验值；没有在 Windows 上运行或验证界面。
Windows 原生凭据存储、进程检测和真实设备同步尚需实际 Windows 验证。
它不是原生安装器，也不包含 JRE。

若启动失败，命令窗口会保留错误。先检查 Java 是否为 x64 且版本至少为 17。
构建与每个 JAR 的校验值见 BUILD-MANIFEST.json。

Windows x64 portable test build, {version} (code {code}).
Extract the entire archive, then run launcher.cmd with an existing x64 Java 17+
(Java 21 recommended). A JVM is not bundled or downloaded. Close the old app
before upgrading. Keep each version's lib directory separate. Existing app
configuration stays in the normal per-user .pixel-dungeon-sync directory.
Built and statically checked on Linux; Windows execution has not been verified.
"""


def package(args: argparse.Namespace) -> dict:
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", args.version):
        raise ValueError("Invalid version label")
    if args.version_code < 1:
        raise ValueError("Version code must be positive")
    if git("status", "--porcelain", "--untracked-files=normal"):
        raise ValueError("Commit all source changes before creating a release archive")
    commit = git("rev-parse", "HEAD")
    runtime = json.loads(args.runtime.read_text(encoding="utf-8"))
    if runtime.get("schema") != 1 or runtime.get("mainClass") != "pdsync.MainKt":
        raise ValueError("Unrecognized Gradle runtime export")
    if runtime.get("sourceCommit") != commit:
        raise ValueError("Runtime export is stale; rebuild and export from the current source commit")
    if runtime.get("version") != args.version or runtime.get("versionCode") != args.version_code:
        raise ValueError("Runtime export version/code differs from the requested release label")
    artifacts = runtime["artifacts"]
    skiko = [a for a in artifacts if a["group"] == "org.jetbrains.skiko" and a["name"] == "skiko-awt"]
    if len(skiko) != 1 or skiko[0]["version"] != SKIKO_VERSION:
        raise ValueError("Resolved Skiko API version differs from the pinned Windows runtime")
    host_natives = [a for a in artifacts if a["group"] == "org.jetbrains.skiko" and a["name"].startswith("skiko-awt-runtime-")]
    if len(host_natives) != 1 or host_natives[0]["version"] != SKIKO_VERSION:
        raise ValueError("Expected exactly one matching host Skiko native runtime")
    windows_data = args.windows_runtime.read_bytes()
    native_report = inspect_windows_runtime(windows_data)
    artifacts = [a for a in artifacts if a not in host_natives]
    artifacts.append({"group": "org.jetbrains.skiko", "name": SKIKO_NAME,
                      "version": SKIKO_VERSION, "classifier": None, "file": str(args.windows_runtime)})
    artifacts.append({"group": "project", "name": "desktop", "version": args.version,
                      "classifier": None, "file": runtime["desktopJar"]})
    if len([a for a in artifacts if a["name"] == "core"]) != 1:
        raise ValueError("Expected exactly one core project runtime JAR")
    files = {}
    dependencies = []
    for artifact in sorted(artifacts, key=lambda a: Path(a["file"]).name):
        path = Path(artifact["file"])
        if path.suffix != ".jar" or not path.is_file():
            raise ValueError(f"Runtime artifact is not a JAR file: {path.name}")
        filename = "lib/" + path.name
        if filename in files:
            raise ValueError(f"Duplicate runtime filename: {path.name}")
        data = path.read_bytes()
        checks = inspect_jar(data, path.name)
        if artifact["name"] in ("core", "desktop"):
            checks["releaseManifest"] = inspect_release_manifest(data, args.version, args.version_code, commit)
        files[filename] = data
        dependencies.append({"file": filename, "group": artifact["group"],
                             "name": artifact["name"], "version": artifact["version"],
                             "classifier": artifact.get("classifier"), "bytes": len(data),
                             "sha256": sha256(data), **checks})
    with zipfile.ZipFile(io.BytesIO(files["lib/" + Path(runtime["desktopJar"]).name])) as jar:
        if "pdsync/MainKt.class" not in jar.namelist():
            raise ValueError("Desktop main class is missing")
    natives = [f"{a['file']}:{n}" for a in dependencies for n in a["nativeLibraries"]]
    if natives != [f"lib/{SKIKO_NAME}-{SKIKO_VERSION}.jar:skiko-windows-x64.dll"]:
        raise ValueError(f"Unexpected native runtime contents: {natives}")
    launcher = (Path(__file__).parent / "launcher.cmd").read_text(encoding="ascii")
    files["launcher.cmd"] = launcher.replace("\r\n", "\n").replace("\n", "\r\n").encode("ascii")
    files["README.txt"] = readme(args.version, args.version_code, commit).encode("utf-8-sig")
    manifest = {
        "schema": 1, "product": "Pixel Dungeon Save Sync", "version": args.version,
        "versionCode": args.version_code, "sourceCommit": commit,
        "packagedAtUtc": datetime.now(timezone.utc).isoformat(), "target": "windows-x64",
        "mainClass": "pdsync.MainKt", "minimumJavaVersion": 17,
        "bundledJavaRuntime": False, "windowsExecutionVerified": False,
        "runtimeClasspath": "lib/*", "dependencies": dependencies,
        "windowsNativeArtifact": {"url": SKIKO_URL, "sha256": SKIKO_SHA256, **native_report},
        "supportFiles": [{"file": n, "sha256": sha256(files[n])} for n in ("launcher.cmd", "README.txt")],
    }
    files["BUILD-MANIFEST.json"] = (json.dumps(manifest, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    top = f"PDSync-{args.version}-code{args.version_code}-windows-x64-portable"
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.output.exists():
        raise ValueError("Output exists; use a new output path to avoid replacing a delivered build")
    with zipfile.ZipFile(args.output, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for filename, data in sorted(files.items()):
            info = zipfile.ZipInfo(top + "/" + filename, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
    with zipfile.ZipFile(args.output) as archive:
        if archive.testzip() is not None or len(archive.namelist()) != len(files):
            raise ValueError("Final ZIP validation failed")
        for filename, data in files.items():
            if archive.read(top + "/" + filename) != data:
                raise ValueError(f"Final ZIP member mismatch: {filename}")
    if git("rev-parse", "HEAD") != commit or git("status", "--porcelain", "--untracked-files=normal"):
        raise ValueError("Source changed while packaging; do not deliver this archive")
    return {"output": str(args.output.resolve()), "bytes": args.output.stat().st_size,
            "sha256": sha256(args.output.read_bytes()), "sourceCommit": commit,
            "jarCount": len(dependencies), "entryCount": len(files),
            "windowsExecutionVerified": False}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--windows-runtime", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(package(args), indent=2))


if __name__ == "__main__":
    main()
