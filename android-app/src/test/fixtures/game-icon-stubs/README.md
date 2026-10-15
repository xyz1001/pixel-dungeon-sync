# Android icon host boundary doubles

These minimal Kotlin Android/Compose types are used only by
`run-game-icon-tests.sh` with the checks in `../game-icon-checks`. Both live
outside AGP's unit-test source set. They execute the production adapter's selection,
cache, fallback and raster bounds logic without a device. They do not simulate
Android resource decoding, adaptive-icon rendering, package visibility or
Compose display, and must never be included in the APK source set.
