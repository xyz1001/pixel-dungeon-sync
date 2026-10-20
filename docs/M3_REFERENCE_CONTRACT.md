# v21-M3 production UI contract (code59)

## Reference and scope

The current visual authority is `m3-reference/v21-M3.html`, SHA-256
`3a81e38b4cf5052bd7e40fc8d164bbadf3c19feddcf4b0694d6d647e8c7f50e4`.
It supersedes the earlier green `ui-prototype/` visual contract. The HTML is
preserved unchanged. Its prototype viewport switch and test console are not
application features. Setup remains two steps: game selection, then service
connection, followed by the real home screen.

Both Android and desktop compile the same physical Kotlin page sources in
`shared-ui/src/main/kotlin`. Platform adapters supply observed state and guarded
callbacks. A label, timestamp, count, or colour is never permission to mutate a
save. Verified full-file comparisons remain the only content-equality evidence.

## Visual system

- Teal primary `#006877`, slate text `#0f172a`, `#f8fafc` page, white cards,
  `#cbd5e1` outlines, `#e2e8f0` separators; status colours follow the reference.
- Maximum shell width 860dp, minimum height 600dp when the viewport permits;
  24dp desktop / 16dp narrow rounded shell, ≤600dp responsive breakpoint.
- Responsive game identity, two desktop action cards / stacked narrow actions,
  440dp minimum two-lane history with its own horizontal scroll.
- Reference SVG paths are drawn by shared Compose Canvas. Buttons are pill
  shaped, forms use above-field labels, 520dp credential forms, full-width
  service and authenticated account cards. System font fallback remains native.
- History uses 16dp lane insets, 14dp top/bottom padding, 6dp editor spacing,
  minimum 126dp rows, 14sp names and 11sp pill badges. Real history identifiers
  drive selection and rename. Inline rename has explicit Save/Cancel, Enter,
  Escape, focus restoration, and lifecycle-token guards. Identity maintenance
  remains a separate protected action.
- Android Root/APK access cards reflect typed observations and actual readiness.
  Choosing APK mode is distinct from starting injection/install. Permission,
  key creation, destructive replacement and installer confirmation remain
  explicit original host/system flows.
- Settings path edit is a responsive modal over settings; real chooser and
  path-validation callbacks are retained. Android private save paths remain
  read-only. The old standalone path component is retained for compatibility.
- Confirmation text and warnings come from real operations. No illustrative
  merge-plan counts, fake renewal status, or prototype success is inserted.

## Deliberate platform and safety differences

The reference is a synthetic browser prototype. Production uses actual game,
account, path, history and entitlement data; needed recovery, diagnostics,
identity maintenance and unknown-state controls remain available. Missing proof
never receives an optimistic green state or an enabled write action. Android
system permission/install screens and desktop native file choosers remain
system-owned. Native font shaping, shadows and dialog backdrop effects may
render differently from Chromium; this is not a byte-for-byte pixel claim.

The actual cloud verification results and limits are recorded separately in
`M3_CLOUD_VALIDATION.md`. Offscreen production Compose pixels/events verify the
shared UI on the desktop JVM; they do not establish Android device, OS dialog,
Root permission, installer or IME execution. No Windows interaction was used.
