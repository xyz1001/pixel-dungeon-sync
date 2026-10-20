#!/usr/bin/env python3
"""Deterministically resize the supplied PDSync artwork; never redraw it.

Requires Pillow. Original PNG/SVG/JPG are retained byte-for-byte in assets/.
The supplied .jpg is actually PNG data and deliberately keeps its source name.
"""
from pathlib import Path
import shutil
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'assets/app-icon/original/pdsync-app-icon.png'
DENSITIES = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}


def generate():
    original = Image.open(SOURCE).convert('RGBA')
    if original.size != (512, 512):
        raise ValueError('Expected the approved 512×512 source artwork')
    for density, scale in DENSITIES.items():
        directory = ROOT / f'android-app/res/mipmap-{density}'
        directory.mkdir(parents=True, exist_ok=True)
        original.resize((int(48 * scale),) * 2, Image.Resampling.LANCZOS).save(directory / 'ic_launcher.png')
        # Keep the complete rounded tile and both arrows inside the 66dp safe circle.
        # The 56dp artwork has a nontransparent maximum radius below 33dp.
        canvas = Image.new('RGBA', (int(108 * scale),) * 2)
        tile = original.resize((int(56 * scale),) * 2, Image.Resampling.LANCZOS)
        canvas.alpha_composite(tile, ((canvas.width - tile.width) // 2,) * 2)
        canvas.save(directory / 'ic_launcher_foreground.png')
    desktop = ROOT / 'desktop/src/main/resources/icons'
    desktop.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(SOURCE, desktop / 'pdsync.png')
    original.save(desktop / 'pdsync.ico', sizes=[(n, n) for n in (16, 24, 32, 48, 64, 128, 256)])
    original.save(desktop / 'pdsync.icns')


if __name__ == '__main__':
    generate()
