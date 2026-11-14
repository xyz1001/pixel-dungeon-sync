"""
Inject latest version and download links into website/js/main.js and website/index.html.
Can be executed locally or inside CI/CD workflows.
"""
import argparse
import os
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
WEBSITE_DIR = ROOT / "website"
INDEX_HTML = WEBSITE_DIR / "index.html"
MAIN_JS = WEBSITE_DIR / "js" / "main.js"

sys.path.insert(0, str(Path(__file__).resolve().parent))
try:
    from version import version
except ImportError:
    version = None

def get_target_version(tag_arg=None):
    if tag_arg:
        tag = tag_arg.strip()
        version_name = tag[1:] if tag.startswith("v") else tag
        version_tag = tag if tag.startswith("v") else f"v{tag}"
        return version_tag, version_name

    if version:
        try:
            v_info = version()
            version_name = v_info["name"]
            version_tag = f"v{version_name}"
            return version_tag, version_name
        except Exception as e:
            print(f"Warning: could not get version from version.py: {e}")

    return "v0.5.114", "0.5.114"

def update_main_js(version_tag, version_name):
    if not MAIN_JS.exists():
        print(f"Error: {MAIN_JS} does not exist")
        return False

    content = MAIN_JS.read_text(encoding="utf-8")
    content = re.sub(
        r'const CURRENT_VERSION = "[^"]*";',
        f'const CURRENT_VERSION = "{version_tag}";',
        content
    )
    content = re.sub(
        r'const VERSION_NAME = "[^"]*";',
        f'const VERSION_NAME = "{version_name}";',
        content
    )
    MAIN_JS.write_text(content, encoding="utf-8")
    print(f"Updated {MAIN_JS.name} with CURRENT_VERSION={version_tag}, VERSION_NAME={version_name}")
    return True

def update_index_html(version_tag, version_name):
    if not INDEX_HTML.exists():
        print(f"Error: {INDEX_HTML} does not exist")
        return False

    content = INDEX_HTML.read_text(encoding="utf-8")
    
    # Update brand badge and version tags
    content = re.sub(
        r'<span class="brand-badge">[^<]*</span>',
        f'<span class="brand-badge">{version_tag}</span>',
        content
    )
    content = re.sub(
        r'<span class="version-tag">[^<]*</span>',
        f'<span class="version-tag">{version_tag}</span>',
        content
    )
    # Update release header text
    content = re.sub(
        r'当前稳定发布版本：<strong class="text-gradient">[^<]*</strong>',
        f'当前稳定发布版本：<strong class="text-gradient">{version_tag}</strong>',
        content
    )
    # Update SHA256SUMS.txt version label in modal
    content = re.sub(
        r'# SHA256SUMS\.txt \([^)]*\)',
        f'# SHA256SUMS.txt ({version_tag})',
        content
    )
    # Update modal title version
    content = re.sub(
        r'<span>PDSync v[0-9.]+ 全平台软件包下载</span>',
        f'<span>PDSync {version_tag} 全平台软件包下载</span>',
        content
    )
    # Update releases/tag/ link
    content = re.sub(
        r'https://github.com/xyz1001/pixel-dungeon-sync/releases/tag/v[0-9.]+',
        f'https://github.com/xyz1001/pixel-dungeon-sync/releases/tag/{version_tag}',
        content
    )
    # Update package filenames in modal list
    content = re.sub(
        r'PDSync-[0-9.]+-android\.apk',
        f'PDSync-{version_name}-android.apk',
        content
    )
    content = re.sub(
        r'PDSync-[0-9.]+-windows-x64\.exe',
        f'PDSync-{version_name}-windows-x64.exe',
        content
    )
    content = re.sub(
        r'PDSync-[0-9.]+-windows-x64-portable\.zip',
        f'PDSync-{version_name}-windows-x64-portable.zip',
        content
    )
    content = re.sub(
        r'PDSync-[0-9.]+-darwin-arm64\.dmg',
        f'PDSync-{version_name}-darwin-arm64.dmg',
        content
    )
    content = re.sub(
        r'PDSync-[0-9.]+-linux-x64\.deb',
        f'PDSync-{version_name}-linux-x64.deb',
        content
    )
    content = re.sub(
        r'PDSync-[0-9.]+-linux-x64\.pkg\.tar\.zst',
        f'PDSync-{version_name}-linux-x64.pkg.tar.zst',
        content
    )

    INDEX_HTML.write_text(content, encoding="utf-8")
    print(f"Updated {INDEX_HTML.name} with {version_tag} ({version_name})")
    return True

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", help="Release tag e.g. v0.5.114")
    args = parser.parse_args()

    version_tag, version_name = get_target_version(args.tag)
    print(f"Injecting website version: tag={version_tag}, name={version_name}")
    update_main_js(version_tag, version_name)
    update_index_html(version_tag, version_name)

if __name__ == "__main__":
    main()
