# Shared UI reference contract

## Authority and scope

The visual and interaction baseline is `ui-prototype/` at
`8f1b71ff95e614f1c9c07ab2ccffdb183ba1f29c` (2026-10-10), with the later explicit
product requirements below taking precedence. Its `app.mjs`, `model.mjs` and
`style.css` define the reference; existing Android or desktop pages are not a
substitute for it. Both platforms must compile the same page implementations in
`shared-ui/src/main/kotlin`. Platform adapters own system capabilities, not a
second set of layouts or labels.

The reference was rendered and clicked in the cloud browser at 1180×757 and
500×757. All four setup pages, empty/identical/local/cloud/conflict states,
confirmation, home/history and settings were inspected. This is reference
validation, not proof that the production Compose pages match it. Screenshots,
logs and synthetic data are not part of this source contract.

## Common layout

- Pale-green `#eff3ef` background, centered shell no wider than 820 logical pixels.
- White card, `#dde6df` border, 22-pixel corner radius, minimum 570-pixel height
  when space permits. Small windows and the keyboard must not hide actions.
- Header: separate step eyebrow, title and selected game name/version. Omit the
  game subtitle only from game selection. Do not combine the step and title.
- Desktop header padding: 28 top, 32 horizontal, 18 bottom. Content: 16/32/30.
  Footer: 20/32, full-width top border, Back on the left and primary on the right.
- Form maximum width: 510. Labels above fields, 13-pixel input padding, 9-pixel
  field radius. Buttons minimum 46 high, radius 10; secondary buttons pale green.
- At widths ≤560: outer padding 15 vertical/10 horizontal, card radius 16,
  inner horizontal padding 20, title 22. Ordinary content must not cause page-wide
  horizontal overflow. The two-column history has its own horizontal scrolling.
- Content scrolls as necessary; navigation remains in the card footer rather
  than being appended to a long scrolling form. Long game names and paths wrap.

Reference: `app.mjs:8–20`, `style.css` shell/header/content/footer/form/field/button
rules and the 560-pixel media query.

## Per-page contract

### 1. Select game

Use the common game-identity card and primary Next. Android's installed-app/APK/
Root selection and preparation are approved platform-specific content; desktop
uses real JAR selection/discovery. Keep actual system selectors and required
installation/destructive-operation confirmations. No simulated chooser or fixed
game metadata enters the product. Reference: `app.mjs:78–79`.

### 2. Save path

Path, folder selection where supported, concise preview and slot times. Editing
invalidates the old preview. Reading, queued continuation, failure/retry and Back
must have explicit state; a stale result cannot advance a changed page or draft.
The primary footer action expresses Next/Waiting/Retry rather than stacking
several independent controls. Android's trusted private path can remain read-only.
Reference: `app.mjs:80–92`, `model.mjs:316–324,338–352`.

### 3. Cloud connection

Fresh setup begins with the approved official/custom choice. Custom connection
uses address, username, password and Test Connection; official connection uses
login/registration with the real service and existing appreciation/expiry flow.
All subpages use the same shell, constrained form and footer. Test success stays
on this page and enables Next; changing the tested fields invalidates that proof.
An unchanged successfully saved configuration does not require arbitrary repeated
tests. Failed test, Back and cancellation cannot discard saved configuration.
Reference layout: `app.mjs:94–102,155–162`; real state and account behavior:
`SharedConnectionController`, `ConnectionEntryModel` and production adapters.

### 4. Choose save

- Both sides empty: concise configuration-complete state, Back and one Finish.
- Verified identical: concise result, collapsed details and one Finish. No Upload,
  Restore or Postpone actions. This later requirement supersedes the mock footer.
- Local-only/cloud-only: progress choice card followed by one Finish, enabled
  after selection and current validation.
- Different/conflict: one Handle Conflict action and a shared confirmation dialog
  with Cancel, Overwrite and Merge where actually supported.
- Real multi-head cloud history must remain explicitly selectable; the mock's
  single latest-cloud fixture does not authorize silently choosing a branch.
- Unknown/unverified states are not Empty or Identical. Pending transactions and
  failures retain their real recovery actions without pretending setup completed.

Reference: `app.mjs:103–126`. Preserve current hash, revision, conflict and frozen
snapshot proofs. UI alignment does not change transaction/backup guarantees.

### Home and history

Compact header/status, Upload/Restore actions, selected/default restoration target
and two-lane history. Keep the approved Launch Game action and real naming.
Cloud is left, local right. Visible nodes share rows by save-content time in
newest-first order; local current/baseline are not pinned to row 0/1. Equal-time
nodes in different lanes may align. Preserve true parents and transfer direction.
Straight edges attach to measured card centers/edges, with the arrowhead outside
the card; same-column edges remain in that column. No separate time axis.
On narrow screens preserve readable lane/card widths and scroll the history region,
not the entire page. Complete history remains reachable even if paginated.
Reference: `app.mjs:21–61,111–112`, `model.mjs:180–233`, history CSS.

### Settings

Concise bordered horizontal rows with current value on the left and an action on
the right, Return in the shared footer. Retain real log export and necessary
maintenance capabilities in the same restrained presentation. No diagnostic or
technical explanatory panels in the normal flow. Reference: `app.mjs:113–114`.

## Later requirements that override mock content

- Step 1 uses the selected game's real icon when available. The shared page has no
  details toggle; Desktop keeps the initial automatic scan, omits manual rescan,
  and labels the selected-game file chooser `重新选择` (2026-10-15 clarification).

- Official account login/registration, 72-hour trial, expiry and appreciation are
  real later features absent from the prototype. Do not revert to custom-only.
- Count wording is `已进行x场游戏`; missing count displays zero without writing a
  fabricated zero to game data or merge metadata.
- Verified identical content has the single-Finish behavior above.
- Preserve configuration/drafts and account identity. The prototype resets sample
  form values on rerender because it deliberately does not store real credentials;
  that is a simulation limitation, not desired production behavior.
- Normal Android exit and stable cached processes remain supported. No force-stop
  requirement is introduced by UI migration.
- Later requests for minimal text supersede verbose mock explanations. Omit its
  demo banner, test panel, sample credentials, fixed dates, simulated delays and
  statements promising no backup. Keep concise real errors/overwrite confirmation.
- Directory/file selection uses the OS chooser. The mock folder picker is not a
  production design for browsing the filesystem.

## Verification and staged migration

Source sharing, matching colors and successful compilation alone do not establish
interaction parity. Each page requires both-platform compile/controller tests,
state-specific action tests, narrow-width layout assertions and rendered visual
inspection against this reference. Report actual OS/viewport and any untested
native selector, Android lifecycle or device interaction explicitly.

Initial audit found duplicated shells, in-content navigation, missing game
subtitles, multi-action comparison pages, independently indexed history lanes and
estimated desktop arrows. Migrate in order: common shell/connection; save path;
comparison/dialog; home/history; settings and game identity. Reuse existing business
controllers and platform adapters. Do not publish unaligned work merely because it
is now physically shared.
