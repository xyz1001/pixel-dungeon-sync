"""Read-only source contracts for the deliberately small environment collector."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
ANDROID = ROOT / 'android-app/src/main/java/io/github/xyz1001/pdsync/android'


class DiagnosticEnvironmentContract(unittest.TestCase):
    def test_android_uses_only_public_approved_build_fields(self):
        source = (ANDROID / 'AndroidDiagnosticEnvironment.java').read_text()
        self.assertEqual(set(re.findall(r'Build\.(?:VERSION\.)?[A-Z_]+', source)),
                         {'Build.VERSION.RELEASE', 'Build.VERSION.SDK_INT', 'Build.BRAND',
                          'Build.MODEL', 'Build.SUPPORTED_ABIS'})
        self.assertIn('Locale.getDefault().toLanguageTag()', source)
        self.assertIn('TimeZone.getDefault().getID()', source)
        for forbidden in ('android.provider', 'Settings.', 'TelephonyManager', 'WifiManager',
                          'getSerial', 'ANDROID_ID', 'getDeviceId', 'getImei', 'getMacAddress',
                          'System.getProperties', 'System.getenv', 'getSystemService', 'Runtime.getRuntime'):
            self.assertNotIn(forbidden, source)

    def test_application_initializes_even_without_activity(self):
        source = (ANDROID / 'DiagnosticApplication.java').read_text()
        self.assertLess(source.index('AndroidDiagnosticEnvironment.configure()'),
                        source.index('AndroidDiagnosticLogging.configure(this)'))

    def test_desktop_reads_only_two_allowed_properties(self):
        source = (ROOT / 'desktop/src/main/kotlin/pdsync/DesktopDiagnosticEnvironment.kt').read_text()
        self.assertEqual(re.findall(r'System.getProperty\("([^\"]+)"\)', source), ['os.version', 'os.arch'])
        for forbidden in ('getProperties(', 'getenv(', 'InetAddress', 'ProcessBuilder', 'user.name', 'user.home'):
            self.assertNotIn(forbidden, source)


if __name__ == '__main__':
    unittest.main()
