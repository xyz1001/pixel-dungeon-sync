"""Source seam/safety checks; executable host doubles cover caching and raster bounds separately."""
from pathlib import Path
import unittest
from test_embedded_install_contract import body

REPO = Path(__file__).resolve().parents[4]
MAIN = (REPO / 'android-app/src/main/java/io/github/xyz1001/pdsync/android/MainActivity.java').read_text()
ICON = (REPO / 'android-app/src/main/kotlin/io/github/xyz1001/pdsync/android/AndroidGameIcon.kt').read_text()


class GameIconContract(unittest.TestCase):
    def test_both_current_selection_types_supply_only_their_own_icon(self):
        identity = body(MAIN, 'private SharedGameIdentity selectedGameIdentity()')
        self.assertIn('AndroidGameIcon.load(this, this.rootGame.gamePackage, this.rootGame.sourceApkSetSha256, null)', identity)
        self.assertIn('AndroidGameIcon.load(this, this.prepared.gamePackage, this.prepared.sourceApkSetSha256, this.prepared.gameApk)', identity)
        self.assertEqual(2, identity.count('new SharedGameIdentity('))
        self.assertIn('return null;', identity)

    def test_adapter_is_metadata_only_and_has_no_persistent_selection_writes(self):
        for forbidden in ('DexClassLoader', 'Class.forName', 'startActivity', 'Runtime.getRuntime',
                          'ProcessBuilder', 'requestPermissions', 'getSharedPreferences', 'putString',
                          'FileOutputStream', 'listFiles(', 'SyncRuntime', 'loadLibrary', 'requestInstall'):
            self.assertNotIn(forbidden, ICON)
        self.assertIn('packages.getPackageInfo(packageName, 0)', ICON)
        self.assertIn('packages.getApplicationIcon(packageName)', ICON)
        self.assertIn('packages.getPackageArchiveInfo(path, 0)', ICON)
        self.assertIn('if (archive.packageName != packageName) return null', ICON)

    def test_raster_is_fixed_size_and_cache_keeps_no_android_owner(self):
        self.assertIn('private const val ICON_SIZE = 128', ICON)
        self.assertIn('Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)', ICON)
        self.assertIn('private data class CacheEntry(val key: CacheKey, val image: ImageBitmap?)', ICON)
        self.assertIn('cached?.takeIf { it.key == key }?.let { return it.image }', ICON)
        self.assertIn('cached = CacheEntry(key, image)', ICON)
        self.assertIn('catch (_: OutOfMemoryError)', ICON)


if __name__ == '__main__':
    unittest.main()
