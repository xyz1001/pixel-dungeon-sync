# code72: visible touch feedback on the existing export button

Version 0.5.51-test / 72. The code71 reference HTML SHA256 remains
f920afd44fe7a54dd6a4320e114eb84c834482d4e6bdd4eee7cb564d19360da2.

## Cause and minimal change

Code71 already handled press interactions and animated scale to .98, but its
#f1f5f9 fill only observed hover and its default indication was disabled. A pure
touch therefore had only a subtle scale change. Prior tests dispatched immediate
press/release pairs and verified the action, without inspecting held touch frames.

The existing background now observes hovered OR pressed, still gated by enabled.
This reuses the existing reference color, 150ms CSS ease transition, and .98 scale.
No position, dimensions, icon, label, resting color, click target, callback, busy
guard, picker behavior, control or prompt is added or changed. All code70 full-height,
code66 removed-notice, code65 cancellation recovery behavior is retained.

## Verification

SharedExportFeedbackRenderTest renders actual production Compose for steps 1/2,
dispatches PointerType.Touch down/hold/up and drag-out events, and checks background
pixels, release reset, disabled behavior, short taps, repeated taps, and mouse hover.
The code71 baseline reproduces white fill on touch hold. The fixed held fill is
exactly #f1f5f9 and returns to the original pixel after release/cancellation.
Screenshots are host Compose renders, not Android device or OEM screenshots.
Final aggregate checks and artifact hashes are in the delivered validation note.
