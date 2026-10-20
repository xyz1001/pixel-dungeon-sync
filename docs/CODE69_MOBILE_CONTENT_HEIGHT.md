# code69 / 0.5.48-test — Android content-sized page spacing

## Change
Android shared pages no longer force a 600dp minimum card height. Short content measures naturally and its existing footer follows the content. Long content remains bounded by the effective viewport and scrolls while the footer stays visible; the existing whole-card scrolling fallback remains for very short or large-font keyboard viewports.

Only hosts providing LocalSharedInputViewport opt in. Desktop retains its original 600dp minimum and all original spacing. Android vertical design spacing is reduced: outer 4dp, header 12/10dp, content 12/16dp, footer 10dp. Horizontal spacing, control sizes, labels, selected-game metadata and operations are unchanged. No controls, notices or permissions were added.

AndroidAppViewport continues to own safeDrawingPadding and imePadding once. The branch-choice adapter now uses this existing host instead of duplicating an inset-only Box, so it shares viewport awareness. The shell never removes system safe areas or treats the keyboard as available content space.

## Rendered evidence
Production Compose with Android's host viewport supplied; 412×900 logical pixels, density 1, default font, empty-selection step 1:
- Card top: 12 → 4px.
- Identity top/bottom: 141/235 → 117/211px.
- Next button top/bottom: 557/599 → 238/280px.
- Identity bottom to next button top: 322 → 27px.
- Card bottom: 614 → 291px. The unused background below the compact card remains (286 → 609px). This change eliminates stretched card-internal space; it does not promise every pixel of a tall screen is filled.
- Ten desktop renders at the same dimensions/font scales are pixel-identical before and after.

New executable production-render tests cover 280/320/412/800 widths, tall and ordinary viewports, 1.0/1.4 font scales, setup steps 1 and 2, home, and short 740×210 keyboard/landscape-like viewports. Existing input tests exercise actual focus/typing, resize, repeated keyboard reopen and rotation, with field relocation and reachable footer behavior. These are host-side Compose tests, not Android device/IME evidence.

## Preserved behavior and limits
Selected-APK real name/version/icon (code68), step-row diagnostic export and credential rules (code67), removed access-mode bottom status text (code66), and canceled-install state preservation (code65) are unchanged. The physical phone was still ADB offline during this release; actual Android screenshot, OEM IME and insets remain unverified. Full lint retains the pre-existing 76 errors and 39 warnings; no new issue is accepted. Final counts and artifact hashes appear in the delivered validation report.
