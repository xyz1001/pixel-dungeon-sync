# code70 / 0.5.49-test — Android available-viewport panel

## Scope and implementation
The code69 compact-card change caused unused space outside the card. Android now sizes the existing panel to the bounded space supplied by AndroidAppViewport. That host continues to apply safeDrawingPadding and imePadding once. This is constraint-based sizing, not a fixed 600dp or physical-screen height.

The existing footer is measured first and stays at the bottom of the available panel. The body gets the remaining height and scrolls. On very short or large-font keyboard viewports, the existing header scrolls with the body so it cannot consume all field space, while the footer remains pinned. No controls, notices, labels, permissions, business flows, or content were added. A short page naturally has spare space inside its body; the change removes the large background outside the panel.

Desktop does not opt into viewport filling and keeps its original minimum height, spacing and appearance. code68 selected-APK identity, code67 export behavior, code66 removed access hints, and code65 cancellation recovery remain untouched.

## Verification scope
Production Compose before/after renders exercise 280–800 logical-pixel widths, ordinary and tall screens, font scales 1 and 1.4, setup steps 1/2 and home. An initial Android selection-state render includes both access-mode choices and uses 412×921, matching the user's 916×2048 screenshot aspect ratio (rounded). These are available-viewport host Compose renders, not Android status/navigation bar screenshots. Existing input visibility tests exercise focused fields, typing, resize, rotation-like dimensions and repeated keyboard reopening. Final geometry, pixel parity, suite counts, known baseline failures and artifact hashes are recorded in the release validation report.
