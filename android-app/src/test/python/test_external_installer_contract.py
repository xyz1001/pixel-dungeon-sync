from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

APP = Path(__file__).resolve().parents[3]
SRC = APP / 'src/main/java/io/github/xyz1001/pdsync/android'
NS = '{http://schemas.android.com/apk/res/android}'

class ExternalInstallerContract(unittest.TestCase):
    def test_manifest_provider_is_private_and_uri_grant_only(self):
        root = ET.parse(APP / 'AndroidManifest.xml').getroot()
        providers = [p for p in root.findall('./application/provider') if p.get(NS + 'name', '').endswith('.ExternalApkProvider')]
        self.assertEqual(len(providers), 1)
        provider = providers[0]
        self.assertEqual(provider.get(NS + 'exported'), 'false')
        self.assertEqual(provider.get(NS + 'grantUriPermissions'), 'true')
        self.assertEqual(provider.get(NS + 'authorities'), 'io.github.xyz1001.pdsync.android.external-apk')
        self.assertFalse(provider.findall('path-permission'))

    def test_apk_view_visibility_and_default_installer_is_preserved(self):
        root = ET.parse(APP / 'AndroidManifest.xml').getroot()
        matches = [i for i in root.findall('./queries/intent') if any(a.get(NS + 'name') == 'android.intent.action.VIEW' for a in i.findall('action'))]
        self.assertTrue(any(any(d.get(NS + 'scheme') == 'content' and d.get(NS + 'mimeType') == 'application/vnd.android.package-archive' for d in i.findall('data')) for i in matches))
        helper = (SRC / 'ExternalInstallerHandoff.java').read_text()
        self.assertIn('public static Intent installIntent(Ticket ticket)', helper)
        self.assertIn('new Intent(Intent.ACTION_VIEW).setDataAndType(ticket.uri, MIME_TYPE)', helper)
        for forbidden in ('ACTION_CHOOSER', 'createChooser(', 'chooser(', 'setComponent(', 'setPackage(', 'resolveActivity(', 'queryIntentActivities(', 'getComponentEnabledSetting(', 'getApplicationEnabledSetting(', 'grantUriPermission(', 'ACTION_INSTALL_PACKAGE', 'EXTRA_ALLOW_REPLACE', 'FLAG_GRANT_WRITE_URI_PERMISSION', 'FLAG_GRANT_PERSISTABLE_URI_PERMISSION', 'FLAG_GRANT_PREFIX_URI_PERMISSION', '.commit(', '.uninstall(', '.abandonSession('):
            self.assertNotIn(forbidden, helper)

    def test_provider_never_serves_arbitrary_paths_or_writable_modes(self):
        provider = (SRC / 'ExternalApkProvider.java').read_text()
        self.assertIn('if (!"r".equals(mode))', provider)
        self.assertIn('ParcelFileDescriptor.MODE_READ_ONLY', provider)
        self.assertNotIn('Uri.fromFile', provider)
        self.assertNotIn('getPath()', provider)

if __name__ == '__main__': unittest.main()
