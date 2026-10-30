"""Single release version source: AndroidManifest.xml; strict tag agreement."""
import argparse
import json
import os
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
NS = '{http://schemas.android.com/apk/res/android}'

def version(manifest=ROOT / 'android-app/AndroidManifest.xml', tag=None):
    root = ET.parse(manifest).getroot()
    name, code = root.attrib[NS+'versionName'], int(root.attrib[NS+'versionCode'])
    if not re.fullmatch(r'[1-9]\d*\.\d+\.\d+(?:-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?|0\.\d+\.\d+(?:-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?', name):
        raise ValueError('Unsupported versionName')
    if code <= 0 or (tag is not None and tag != 'v' + name):
        raise ValueError('Tag must equal v<Manifest versionName>; versionCode must be positive')
    return dict(name=name, code=code, packageVersion=name.split('-')[0], prerelease='-' in name)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--tag')
    args = parser.parse_args()
    value = version(tag=args.tag)
    print(json.dumps(value))
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            for key, item in value.items():
                print(f'{key}={str(item).lower() if isinstance(item, bool) else item}', file=output)
