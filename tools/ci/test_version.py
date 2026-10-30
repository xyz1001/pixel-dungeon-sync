import tempfile
import unittest
from pathlib import Path
from version import version

class VersionTests(unittest.TestCase):
    def test_current_tag_and_package_version(self):
        v=version()
        self.assertEqual(v, version(tag='v'+v['name']))
        self.assertEqual(3,len(v['packageVersion'].split('.')))
    def test_tag_mismatch(self):
        with self.assertRaises(ValueError): version(tag='v99.0.0')
    def test_reject_unsafe_name_and_nonpositive_code(self):
        for name,code in [('x;echo bad',1),('1.2.3',0)]:
            with tempfile.TemporaryDirectory() as d:
                p=Path(d)/'manifest.xml'
                p.write_text(f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" android:versionName="{name}" android:versionCode="{code}"/>')
                with self.assertRaises(ValueError): version(p)

if __name__=='__main__': unittest.main()
