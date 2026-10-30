#!/usr/bin/env bash
set -euo pipefail
OUT="$RUNNER_TEMP/pacman-build"
mkdir -p "$OUT/image"
cp -a desktop/build/compose/binaries/main/app/PDSync/. "$OUT/image/"
cp tools/ci/PKGBUILD "$OUT/PKGBUILD"
VERSION=$(python3 -c 'import sys;sys.path.insert(0,"tools/ci");from version import version;print(version()["name"])')
# pacman pkgver forbids hyphens; the public artifact filename retains versionName.
sed -i "s/^pkgver=.*/pkgver=${VERSION//-/.}/" "$OUT/PKGBUILD"
docker run --rm -v "$OUT:/work" archlinux:base-devel bash -euc '
  pacman -Syu --noconfirm --needed sudo xorg-server-xvfb fontconfig freetype2 ttf-dejavu mesa libx11 libxext libxi libxrender libxtst alsa-lib libglvnd
  useradd -m builder
  chown -R builder:builder /work
  cd /work
  runuser -u builder -- makepkg --nodeps --noconfirm
  pacman -Qip ./*.pkg.tar.zst
  pacman -Qlp ./*.pkg.tar.zst
  pacman -U --noconfirm ./*.pkg.tar.zst
  test -x /opt/pdsync/lib/runtime/bin/java
  runuser -u builder -- env PDSYNC_SMOKE=1 JAVA_TOOL_OPTIONS=-Dpdsync.workspace=/tmp/pdsync-smoke xvfb-run -a /usr/bin/pdsync > /work/pacman-smoke.txt 2>&1
  grep PDSYNC_UI_SMOKE_OK /work/pacman-smoke.txt
'
cp "$OUT/"*.pkg.tar.zst "dist/PDSync-$VERSION-linux-x64.pkg.tar.zst"
cp "$OUT/pacman-smoke.txt" dist/
