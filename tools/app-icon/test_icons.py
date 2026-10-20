#!/usr/bin/env python3
"""Resource-level regression checks; no claim of launcher/device execution."""
import hashlib
import math
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
ANDROID = '{http://schemas.android.com/apk/res/android}'


class AppIconTests(unittest.TestCase):
    def test_original_bytes_preserved(self):
        expected = {'png': '076cf1ea7cbaba1b9a63cfd62923174f1b8a1f0c1a35e1a2bc225696d3537c12',
                    'jpg': '076cf1ea7cbaba1b9a63cfd62923174f1b8a1f0c1a35e1a2bc225696d3537c12',
                    'svg': 'a34725ca0ffb446e66d77bd96b6688b879a3435410d05129fd914adf4a056d4c'}
        for extension, digest in expected.items():
            self.assertEqual(digest, hashlib.sha256((ROOT / f'assets/app-icon/original/pdsync-app-icon.{extension}').read_bytes()).hexdigest())

    def test_all_densities_and_adaptive_safe_circle(self):
        for density, scale in {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}.items():
            directory = ROOT / f'android-app/res/mipmap-{density}'
            with Image.open(directory / 'ic_launcher.png') as image:
                self.assertEqual((int(48 * scale),) * 2, image.size)
                self.assertEqual('RGBA', image.mode)
                self.assertEqual(0, image.getpixel((0, 0))[3])
            with Image.open(directory / 'ic_launcher_foreground.png') as image:
                self.assertEqual((int(108 * scale),) * 2, image.size)
                # Include low-alpha antialiasing pixels, not only visible solids.
                radius = max(math.hypot(x + .5 - image.width / 2, y + .5 - image.height / 2)
                             for y in range(image.height) for x in range(image.width)
                             if image.getpixel((x, y))[3])
                self.assertLessEqual(radius, 33 * scale)

    def test_manifest_and_adaptive_references(self):
        manifest = ET.parse(ROOT / 'android-app/AndroidManifest.xml').getroot()
        self.assertEqual('78', manifest.get(ANDROID + 'versionCode'))
        self.assertEqual('0.5.57-test', manifest.get(ANDROID + 'versionName'))
        app = manifest.find('application')
        self.assertEqual('@mipmap/ic_launcher', app.get(ANDROID + 'icon'))
        self.assertEqual('@mipmap/ic_launcher_round', app.get(ANDROID + 'roundIcon'))
        for name in ['ic_launcher', 'ic_launcher_round']:
            icon = ET.parse(ROOT / f'android-app/res/mipmap-anydpi-v26/{name}.xml').getroot()
            self.assertEqual('adaptive-icon', icon.tag)
            self.assertEqual('@mipmap/ic_launcher_foreground', icon.find('foreground').get(ANDROID + 'drawable'))
            self.assertEqual('@color/ic_launcher_background', icon.find('background').get(ANDROID + 'drawable'))

    def test_desktop_resources_and_wiring(self):
        resources = ROOT / 'desktop/src/main/resources/icons'
        self.assertEqual((ROOT / 'assets/app-icon/original/pdsync-app-icon.png').read_bytes(), (resources / 'pdsync.png').read_bytes())
        with Image.open(resources / 'pdsync.ico') as ico:
            self.assertEqual({(n, n) for n in [16, 24, 32, 48, 64, 128, 256]}, ico.ico.sizes())
        with Image.open(resources / 'pdsync.icns') as icns:
            self.assertEqual('ICNS', icns.format)
        self.assertIn('icon = painterResource("icons/pdsync.png")', (ROOT / 'desktop/src/main/kotlin/pdsync/Main.kt').read_text())
        build = (ROOT / 'desktop/build.gradle.kts').read_text()
        for ext in ['png', 'ico', 'icns']:
            self.assertIn(f'src/main/resources/icons/pdsync.{ext}', build)


if __name__ == '__main__':
    unittest.main()
