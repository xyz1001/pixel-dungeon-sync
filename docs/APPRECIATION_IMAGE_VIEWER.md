# Appreciation image viewer

The Android and desktop shared UI now centers the existing appreciation image within its content area. The account panel uses a centered 110 dp image frame rather than the old left-hand image/right-hand action row. Existing save/copy text and actions remain unchanged.

Clicking the image in the account panel, standalone appreciation page, or inline completion panel opens the same shared Compose Dialog. It uses the original in-memory bitmap without downloading, re-encoding, generating or altering payment data. The dialog fills the available window with a 16 dp inset and uses centered `ContentScale.Fit` and nearest-neighbor sampling (`FilterQuality.None`) to preserve aspect ratio and crisp image pixels. A tap on the overlay (including the image) or Back/Escape dismisses it. There are no new text labels or buttons. Viewer state is scoped to the image and composition, so leaving the page or replacing the bitmap resets it.

## Verification

- Shared Compose tests dispatch real pointer events and Escape to the actual Dialog at 320×757, 500×757, 1165×757 and 757×320.
- A non-square synthetic bitmap is checked at the rendered pixel level for centering, preserved 3:4 aspect ratio, margins, no cropping, and enlarged dimensions.
- Tests repeatedly open and close the viewer, assert only one viewer exists, and check that leaving and returning to the page does not resurrect it.
- Existing appreciation, built-in account connection and bilingual layout suites cover the shared pages and existing actions.
- Android shared-source compilation is checked separately.

All automated image fixtures are synthetic. A real payment code was not generated or modified. Real-device camera scanning and Android system Back behavior still require device verification with the existing service-provided image. Escape and pointer dismissal are exercised in the desktop Compose scene; Android uses Dialog's standard dismissOnBackPress behavior.

No version, signing material, service endpoints, payment targets, or publication state is changed by this implementation.
