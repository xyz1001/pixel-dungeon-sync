"""Offline tests for distribution validation; no JVM, download, or app startup."""
import importlib.util
import io
import struct
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("windows_package", Path(__file__).with_name("package.py"))
package = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package)


def jar(entries):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        for name, data in entries.items():
            archive.writestr(name, data)
    return stream.getvalue()


def klass(major):
    return b"\xca\xfe\xba\xbe" + struct.pack(">HH", 0, major)


class JarValidationTest(unittest.TestCase):
    def test_java17_class_is_accepted(self):
        result = package.inspect_jar(jar({"pdsync/MainKt.class": klass(61)}), "desktop.jar")
        self.assertEqual(result["maximumJava17VisibleClassMajor"], 61)

    def test_java21_base_class_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "newer than Java 17"):
            package.inspect_jar(jar({"Main.class": klass(65)}), "desktop.jar")

    def test_newer_multirelease_variant_is_not_loaded_by_java17(self):
        result = package.inspect_jar(jar({"Main.class": klass(61),
                                         "META-INF/versions/21/Main.class": klass(65)}), "library.jar")
        self.assertEqual(result["maximumJava17VisibleClassMajor"], 61)

    def test_state_keys_and_unsafe_paths_are_rejected(self):
        for name in ("config.json", ".local/state.txt", ".pixel-dungeon-sync/config.json",
                     "secret.key", "signing.keystore", "operation-events.log", "../escape", "/absolute"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                package.inspect_jar(jar({name: b"private"}), "bad.jar")

    def test_unexpected_platform_native_library_is_rejected(self):
        for name in ("libskiko.so", "libskiko.dylib", "libnative.jnilib"):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "Non-Windows"):
                package.inspect_jar(jar({name: b"native"}), "bad.jar")

    def test_wrong_skiko_checksum_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "pinned official SHA-256"):
            package.inspect_windows_runtime(b"not the official artifact")

    def test_release_manifest_matches_version_code_and_commit(self):
        data = jar({"META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\r\nImplementation-Version: 0.5.32-test\r\nPDSync-Version-Code: 53\r\nPDSync-Source-Revision: abc123\r\n\r\n"})
        result = package.inspect_release_manifest(data, "0.5.32-test", 53, "abc123")
        self.assertEqual(result["Implementation-Version"], "0.5.32-test")
        for version, code, commit in (("1.0.0", 53, "abc123"), ("0.5.32-test", 52, "abc123"),
                                     ("0.5.32-test", 53, "older")):
            with self.subTest(version=version, code=code, commit=commit), self.assertRaises(ValueError):
                package.inspect_release_manifest(data, version, code, commit)

    def test_launcher_uses_stable_workspace_and_quoted_classpath(self):
        launcher = Path(__file__).with_name("launcher.cmd").read_text(encoding="ascii")
        self.assertIn('"%JAVA_EXE%" -cp "%~dp0lib\\*" pdsync.MainKt', launcher)
        self.assertNotIn("pdsync.workspace", launcher)
        self.assertIn("LSS 17", launcher)
        self.assertIn("os.arch = amd64", launcher)
        self.assertIn("DisableDelayedExpansion", launcher)


if __name__ == "__main__":
    unittest.main()
