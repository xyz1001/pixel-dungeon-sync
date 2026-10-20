# code71: existing export button matched to current local HTML

Version: 0.5.50-test / 71.

The current Windows HTML was read and rendered in Edge, transferred to this workspace, and its actual screenshots inspected before implementation. Original bytes: 106034; SHA256: f920afd44fe7a54dd6a4320e114eb84c834482d4e6bdd4eee7cb564d19360da2. This is distinct from the older reference.

## Scoped change

Only the existing export button on wizard steps 1 and 2 is moved from the step eyebrow row to the right of the complete title stack. Its former tonal/outlined appearance is replaced by the reference transparent text style: teal #006877, exact document SVG path at 18dp, 8dp icon gap, 14sp semibold label, 8dp/14dp padding plus 1dp transparent border, natural 112×38dp geometry at default font scale, full-radius shape, no shadow. Enabled hover uses #f1f5f9 with 150ms animation; press scales to .98 and disabled opacity is .4.

No new connection-settings or appreciation-page export entry, bottom failure area, message or control is introduced. The settings runtime-log row is unchanged. The existing export action, busy guard, system directory picker, cancellation behavior and diagnostic redaction are unchanged. The visibility guard retains the existing eyebrow requirement when the button moves outside that row.

The code70 Android viewport and footer geometry are retained, including compact header padding and short/keyboard viewport scrolling. The reference's browser banner, fixture controls, synthetic success message and surrounding layout are not product changes.

## Verification

Production Compose render tests exercise steps 1/2 at 280, 320, 354, 412 and 800 widths with font scales 1.0/1.4; whole-title centering and 112×38 geometry are asserted, as are single-control visibility, repeated navigation and enabled/disabled clicks. Existing mobile spacing and input visibility tests cover full-height panels, footer reachability and short keyboard-like viewports. These are host renders, not Android device/OEM screenshots.

Full regression/build/lint results and final artifact hashes are delivered in PDSync-code71-validation.txt. Existing baseline failures must remain disclosed rather than described as a completely green build. Signing is performed only after regression and lint-difference review.
