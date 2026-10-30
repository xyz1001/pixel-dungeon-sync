import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('desktop_package', Path(__file__).with_name('desktop-package.py'))
package = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package)


class LinuxImageLayoutTest(unittest.TestCase):
    def image(self, root, name):
        app = root / name
        (app / 'lib/runtime/bin').mkdir(parents=True)
        (app / 'lib/runtime/bin/java').touch()
        (app / 'bin').mkdir()
        (app / 'bin' / name).touch()
        return app

    def test_lowercase_linux_package_name(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app = self.image(root, 'pdsync')
            self.assertEqual(app, package.linux_image(root))
            self.assertEqual(app / 'bin/pdsync', package.linux_launcher(app))

    def test_title_case_image(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app = self.image(root, 'PDSync')
            self.assertEqual(app, package.linux_image(root))

    def test_missing_runtime_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(AssertionError):
                package.linux_image(Path(directory))

    def test_ambiguous_images_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.image(root, 'pdsync')
            other = root / 'extra'
            (other / 'lib/runtime/bin').mkdir(parents=True)
            (other / 'lib/runtime/bin/java').touch()
            with self.assertRaises(AssertionError):
                package.linux_image(root)
