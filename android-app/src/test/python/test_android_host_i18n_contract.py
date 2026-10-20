"""Android-host localization/source contracts; does not replace device lifecycle testing."""
from pathlib import Path
import json
import re
import unittest

ROOT = Path(__file__).resolve().parents[4]
CATALOG = (ROOT / 'core/src/main/java/pdsync/i18n/AndroidHostMessages.java').read_text()
MAIN = (ROOT / 'android-app/src/main/java/io/github/xyz1001/pdsync/android/MainActivity.java').read_text()
LITERAL = r'"(?:\\.|[^"\\])*"'
MESSAGES = [(name, key, json.loads(zh), json.loads(en)) for name, key, zh, en in re.findall(
    rf'public static final UiText (\w+) = new UiText\("([^"]+)", ({LITERAL}), ({LITERAL})\);', CATALOG)]

class AndroidHostI18nContract(unittest.TestCase):
    def test_complete_paired_unique_catalog(self):
        self.assertGreater(len(MESSAGES), 300)
        self.assertEqual(len(MESSAGES), len({row[1] for row in MESSAGES}))
        for name, key, zh, en in MESSAGES:
            self.assertTrue(zh.strip(), key)
            self.assertTrue(en.strip(), key)
            self.assertFalse(re.search(r'[\u4e00-\u9fff]', en), key)
            self.assertEqual(sorted(re.findall(r'%\d+\$[a-z]', zh)), sorted(re.findall(r'%\d+\$[a-z]', en)), key)

    def test_brief_notice_scope_preserves_original_length_boundary(self):
        names = set(re.findall(r'\b[A-Z][A-Z_0-9]+\b', CATALOG.split('BRIEF_NOTICES = {')[1]))
        for name, _, zh, _ in MESSAGES:
            expected = len(zh) <= 48 and name not in {'SELECT_A_GAME_0', 'SETUP_COMPLETE_124'} and '%' not in zh
            self.assertEqual(name in names, expected, name)
        self.assertNotIn('this.notice.length()', MAIN)
        self.assertIn('AndroidHostMessages.isBriefNotice(this.notice)', MAIN)

    def test_locale_initialized_before_activity_ui(self):
        body = MAIN.split('public void onCreate(Bundle bundle)', 1)[1]
        self.assertLess(body.index('UiLocale.setLocale'), body.index('super.onCreate'))
        manifest = (ROOT / 'android-app/AndroidManifest.xml').read_text()
        config = re.search(r'android:configChanges="([^"]+)"', manifest).group(1)
        self.assertNotIn('locale', config)
        self.assertNotIn('layoutDirection', config)

    def test_existing_app_name_uses_android_resources(self):
        self.assertIn('android:label="@string/app_name"', (ROOT / 'android-app/AndroidManifest.xml').read_text())
        self.assertIn('Pixel Dungeon Sync', (ROOT / 'android-app/res/values/strings.xml').read_text())
        self.assertIn('像素地牢同步', (ROOT / 'android-app/res/values-zh/strings.xml').read_text())

    def test_kotlin_display_copy_is_extracted(self):
        for path in (ROOT / 'android-app/src/main/kotlin').rglob('*.kt'):
            for literal in re.findall(LITERAL, path.read_text()):
                self.assertFalse(re.search(r'[\u4e00-\u9fff]', literal), str(path))

    def test_workflow_does_not_use_translated_labels(self):
        self.assertNotRegex(MAIN, r'(?:contains|equals|startsWith)\([A-Z][A-Z_0-9]*\.text\(')
        self.assertIn('SharedHomeStatusKind', MAIN)
        self.assertIn('this.confirmationDestructive', MAIN)
        self.assertIn('capabilities.canToggleAutomatic, automaticSupported && !automaticPaused, false', MAIN)

if __name__ == '__main__':
    unittest.main()
