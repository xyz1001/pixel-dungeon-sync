# PDSync application artwork

The three files in `original/` are the user's supplied originals, preserved
byte-for-byte. `pdsync-app-icon.jpg` contains PNG bytes and intentionally retains
its original filename. The PNG is 512×512 RGBA with transparent outside margins.
The SVG contains gradients, filters, and an embedded pixel-art image; it is not
converted to Android VectorDrawable or redesigned.

`python3 tools/app-icon/generate.py` (Pillow) regenerates the platform derivatives
from the original PNG with Lanczos resampling:

- Android legacy launcher: 48/72/96/144/192 px, transparent corners preserved.
- Android adaptive/round launcher: complete original artwork centered at 56dp
  on a 108dp transparent foreground, at all five densities. An opaque backing
  uses the supplied SVG's existing `#0f3420` gradient-stop color. Both arrows,
  chest, and original rounded tile, including resampling antialiasing, remain
  inside the 66dp safe circle. This deliberately avoids cropping/repainting the
  supplied rounded-square artwork to fill a circular launcher mask.
- Android's system-provided launch splash and recent-app identity inherit the
  application icon. No custom splash UI, controls, messages, or theme changes.
- Desktop window: exact original 512px PNG, loaded from the application JAR.
- Native package configuration: PNG (Linux), multi-size ICO (Windows: 16, 24,
  32, 48, 64, 128, 256), ICNS (macOS). ICNS larger resolutions are derived from
  the 512px original; no new artwork/detail is invented.

The existing Windows portable `launcher.cmd` remains a command file, whose
Explorer icon is owned by Windows. Its running application uses the configured
window icon. No shortcuts, executable wrappers, or new launch controls are added.
Native Windows/macOS installer builds require their respective operating systems;
configuration and file-format checks are not evidence of native execution.

`python3 tools/app-icon/test_icons.py` verifies source checksums, all generated
dimensions and transparency, complete adaptive safe-circle containment, Android
manifest references, desktop format payloads, and application/build wiring.
