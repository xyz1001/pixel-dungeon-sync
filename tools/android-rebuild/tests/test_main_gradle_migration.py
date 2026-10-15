#!/usr/bin/env python3
"""The old Java-only main command must fail early, before SDK/signing access."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]


class MainGradleMigrationTests(unittest.TestCase):
    def test_main_build_directs_to_unsigned_gradle_before_accessing_sdk(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "main.apk"
            env = os.environ.copy()
            env.update(ANDROID_SDK_ROOT=str(Path(temporary) / "absent-sdk"),
                       PDSYNC_KEYSTORE_FILE=str(Path(temporary) / "absent-key"))
            result = subprocess.run(
                [sys.executable, str(REPO / "tools/android-rebuild/build-apk.py"),
                 "--module", str(REPO / "android-app"), "--unsigned", "--out", str(output)],
                env=env, capture_output=True, text=True, check=False)
            self.assertEqual(result.returncode, 2)
            for expected in (":android-app:assembleRelease", "PDSYNC_BRIDGE_RUNTIME_DEX",
                             "PDSYNC_ROOT_NATIVE_DIR", "PDSYNC_APKSIG_JAR", "standalone bridge"):
                self.assertIn(expected, result.stderr)
            self.assertNotIn("Required input missing", result.stderr)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
