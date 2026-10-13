# Sandbox order HTTP contract v2 (donation)

This contract is for the optional **local sandbox only**. No public endpoint,
product price, real collection image, credential or production auth is supplied.
Importing the module starts nothing. `create_server(service, enabled=True,
displays=...)` explicitly binds an ephemeral port on literal `127.0.0.1`; caller
must start/stop it. `enabled=False` is the default and refuses construction.
The underlying service defaults to `disabled` and returns `503 PAYMENTS_DISABLED`.
Live mode is not implemented. Do not embed fixture prices/images in product UI.

## Authentication and transport

Every endpoint including plan images delegates to `service.auth.authenticate`.
Default is denial. User and administrator roles are separate; the HTTP layer
does not accept a username/role from body or any self-declared identity header.
No built-in Bearer token, Basic-password adapter or anonymous test account exists.
Only integration tests inject a fake authenticator. Authentication transport for
the real app is **not yet chosen**, so the client must depend on an injectable
transport rather than fabricate a key or send actual passwords to this sandbox.

Use native HTTP requests with JSON (`Content-Type: application/json`) for POST.
GET has no body. Requests are bounded at 4096 body bytes, duplicate JSON keys and
extra request fields are rejected. Query/fragment/encoded paths, browser Origin,
cookies and non-loopback Host are rejected. TLS/public hosting, production rate
limiting and real authentication remain future integration work. Do not log
Authorization, request bodies or receipt references. All responses are no-store.

## Response schemas

See `api.schema.json` for the response/data and request JSON Schema definitions. This is a breaking sandbox revision; the client feature remains disabled until updated.
Success: HTTP 200, `{"sandbox":true,"data":...}`. Failure:
`{"error":"UPPERCASE_CODE"}` with a non-2xx status; it never contains raw upstream
errors, credentials or payment transaction references. Time fields are UTC Unix
milliseconds. `target_ms` is null until administrator approval reserves it.
Quoted `amount_minor` and `currency` are always null. Only administrator-confirmed
`actual_amount_minor` (positive integer minor units) and `actual_currency` are
recorded at approval; before approval both are null. `sandbox`
must be checked before presenting a test result as an actual subscription.

## Endpoints

| Method/path | Role | Exact request / success data |
| --- | --- | --- |
| GET `/api/order/plans` | user | Array of PlanDisplay; donation mode, no quoted price, explicit limits |
| GET `/api/order/payment-image/{plan_id}` | user | `image/png` bytes from explicit fixture; no-store |
| POST `/api/order/create` | user | `{plan_id,request_key}` -> Order |
| GET `/api/order/mine` | user | Array of Order, newest first, maximum 100 |
| GET `/api/order/entitlement` | user | `{source:"sandbox_ledger",expires_ms:null-or-integer,active:boolean}`; only caller's local sandbox ledger |
| GET `/api/order/status/{order_id}` | user | Order belonging to this caller |
| POST `/api/order/submit` | user | `{order_id}` -> Order (payment claim only) |
| POST `/api/order/cancel` | user | `{order_id}` -> Order |
| GET `/api/admin/queue` or `/api/admin/queue/{cursor}` | admin | `{items:[Order+owner+channel],next_cursor:string-or-null}`; at most 100, initial cursor `0` |
| POST `/api/admin/approve` | admin | `{order_id,channel,receipt_ref,actual_amount_minor,actual_currency}` -> Order |
| POST `/api/admin/retry` | admin | `{order_id}` -> Order; reuse persisted approval and target |
| POST `/api/admin/reject` | admin | `{order_id}` -> Order |

`request_key`: client-generated stable idempotency key, 16–80 ASCII letters,
digits, `_` or `-`. Persist/reuse it after timeout; same owner+key+plan returns
the exact original order, even after completion. A different plan with that key
is a conflict. `mine` recovers a pending order when a client has lost its key.
One pending order per owner prevents repeated taps with different keys from
silently creating multiple payable orders. Changing a plan requires explicit
cancellation/rejection first, not automatic replacement.

Queue cursors are opaque decimal strings; follow `next_cursor` until null. Rows
are ordered by durable insertion sequence. Each request sees current states,
not a frozen snapshot: after finishing a pass, a later refresh begins at `0` to
discover older orders that newly entered review. The entitlement response is
only this sandbox ledger, never a guessed/imported SFTPGo entitlement. An empty
ledger does not establish that a pre-existing remote user has zero rights.

Create commits the snapshot and unique active four-digit remark before returning
it. Never use that code as authentication or evidence of payment. Display plans
include `display_name`, `payment_instructions`, `payment_image_path`, all supplied
by the host's explicit `SandboxDisplay` fixture. Missing fixtures return 503
before creating orders. Image paths are relative to the same loopback base and
require the same user authentication; never open untrusted payment image URLs.

## State and client behavior

| State | Meaning | Client behavior |
| --- | --- | --- |
| created | Durable unpaid order | Show fixture instructions; user may declare or cancel |
| review | User claims paid, human has not approved | Wait/refresh; not activated |
| applying | Human approval recorded, remote effect unresolved | Wait/retry query; never create a replacement order |
| approved | Adapter effect read back; sandbox ledger committed | Sandbox result only; no password is returned |
| cancelled | Owner cancelled before approval | Terminal; preserve history |
| rejected | Admin rejected before approval | Terminal; preserve history |

Repeating submit is harmless. Repeating approve with the same channel/receipt
retries the same frozen target, never another year. Another channel/receipt for
that order conflicts; one channel/receipt cannot approve two orders. Receipt
references must come from actual statement verification in any future production
workflow. Tests use purely synthetic references. GET can never approve.

After a restart, an administrator discovers `applying` rows in the paginated
queue and POSTs only their order ID to `/api/admin/retry`. This reuses the stored
channel, receipt and fixed target without exposing them or making a new payment
decision. Retry rejects orders without a reserved approval; approved orders are
returned unchanged. No receipt needs to survive in the administrator's browser.

Foreground clients should use bounded backoff and a manual refresh option;
stop polling in the background. Do not turn `503` or an unknown outcome into a
fresh order/registration. After future real approval, retain the existing account
and normal connection-test/save-comparison gates; do not force an upload.

## Error handling

| HTTP | Codes / interpretation |
| --- | --- |
| 400 | INVALID_REQUEST, INVALID_JSON, INVALID_HOST, INVALID_REQUEST_KEY, UNKNOWN_PLAN, INVALID_CHANNEL, INVALID_RECEIPT_REFERENCE, INVALID_CURSOR |
| 401 | AUTHENTICATION_REQUIRED |
| 403 | FORBIDDEN, BROWSER_SESSION_UNSUPPORTED |
| 404 | NOT_FOUND, ORDER_NOT_FOUND (missing and other owner's orders are indistinguishable) |
| 405 | METHOD_NOT_ALLOWED |
| 409 | PENDING_ORDER_EXISTS, IDEMPOTENCY_CONFLICT, INVALID_STATE, PAYMENT_NOT_SUBMITTED, RECEIPT_ALREADY_USED, APPROVAL_CONFLICT, APPROVAL_IN_PROGRESS, APPROVAL_NOT_RESERVED |
| 413 | REQUEST_TOO_LARGE |
| 415 | JSON_REQUIRED |
| 503 | PAYMENTS_DISABLED, SANDBOX_DISPLAY_REQUIRED, REMARK_CODES_EXHAUSTED, ENTITLEMENT_RETRY_REQUIRED, DATABASE_UNAVAILABLE, SERVICE_UNAVAILABLE |

`APPROVAL_IN_PROGRESS` is recoverable: query status and let an administrator retry
the same approval later. `ENTITLEMENT_RETRY_REQUIRED` leaves the order applying;
it is not an unpaid/rejected state. Readback and remote monotonicity are adapter
contracts; the fake ledger tests do **not** prove a regular SFTPGo PUT is fenced.

## Donation rules (confirmed product change)

`payment_mode=donation`, `term=calendar_year`, `days=0`, and
`expiry_policy=sandbox_upload_only`. There is no fixed annual price. The app
shows the appreciation code; available amounts come from WeChat's code page.
Do not infer amounts from the image or let the client submit the paid amount.
An authenticated reviewer records the verified positive minor-unit amount,
currency and channel transaction number. Reapproving with different actual
amount/currency conflicts; retry uses the persisted values.

Each verified donation grants one UTC calendar year from manual approval, or
from the current unexpired expiry, whichever is later. Feb 29 maps to Feb 28
in the next year. The desired production expiry action is disabling uploads
while retaining login/download and the same login-based renewal flow, not
setting SFTPGo account `expiration_date`. No production enforcement is present.

Remark-code entry in WeChat appreciation flow is unverified. The four-digit
code is optional assistance only: never insist the user enter it. Independent
order IDs and manual transaction binding remain authoritative.

New validation errors: `400 INVALID_ACTUAL_AMOUNT`,
`400 INVALID_ACTUAL_CURRENCY`; all prior authentication/state errors remain.
Use a fresh sandbox DB for this schema change; no live DB migration was run.
