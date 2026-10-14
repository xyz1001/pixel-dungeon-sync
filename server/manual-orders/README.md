# Manual payment order foundation (phase one, sandbox only)

This isolated Python/SQLite module models a manually reviewed order attached to
an existing authenticated account. It does not automatically listen on a
port, create users/passwords, call SFTPGo, send push messages, or change the
existing free registration service. No production adapter or live-payment mode
is included. `Orders(...)` defaults to disabled; `mode='live'` is rejected.

## Explicit configuration and trusted boundary

Sandbox `Plan` now means a donation offer, not an annual-price subscription.
It requires an ID and explicit sandbox quota/bandwidth limits. `amount_minor`
and `currency` must be null; `payment_mode` is `donation`. Actual amount/currency
are recorded only by an authenticated administrator after statement verification.
The confirmed product duration is one UTC calendar year, starting at approval
or extending the current unexpired approved-donation expiry; Feb 29 clamps to Feb 28.
`expiry_policy` is `sandbox_upload_only`; no production permission effect exists.
No minimum donation, fixed annual fee or customer-entered amount is inferred.
Offer IDs version the limits; existing order snapshots remain immutable.

`dispatch(method, path, headers, body)` is the public integration seam. An
injected authenticator must return a `Principal` for an existing authenticated
user or an explicitly authorized administrator; request JSON never specifies
the owner or role. The default authenticator rejects everyone. Test identity
headers exist **only in the test file**, not in production code. Do not expose
internal persistence methods as routes or construct principals from user input.
There is no anonymous administrator, generated admin key, or link-based approval.

A future HTTP host must enforce TLS, bounded JSON/request sizes, rate limiting,
`Cache-Control: no-store`, redacted request logging, and authentication before
dispatch. Cookie authentication additionally needs CSRF/Origin defenses. No
production HTTP host is supplied. The explicit `http_api.create_server` sandbox
adapter only binds `127.0.0.1`, rejects browser Origin/cookie requests, and enforces
bounded JSON, Host checks and no-store responses. It does not supply authentication.
See [HTTP contract](API.md) and [machine-readable schemas](api.schema.json).
Invalid/unknown callers fail closed. Nothing is deployed.

## Contract for the next client phase

All mutations are POST. Exact body keys are required; client requests cannot
supply price, currency, owner, role, expiry or quota. The administrator approval
request alone records verified actual amount/currency. Successful responses are
`200 {"sandbox":true,"data":...}`; errors are `{ "error": "CODE" }`.

| Method | Path | Body / result |
| --- | --- | --- |
| GET | `/api/order/plans` | Configured sandbox plan snapshots; HTTP adds explicit fixture display/image path |
| GET | `/api/order/mine` | Current user's newest 100 orders; no other users' rows |
| POST | `/api/order/create` | `plan_id`, `request_key` (16–80 URL-safe characters); returns server snapshot |
| POST | `/api/order/submit` | `order_id`; claims payment, does not prove it |
| GET | `/api/order/status/{order_id}` | No body; only order owner |
| POST | `/api/order/cancel` | `order_id`; owner, before approval reservation |
| GET | `/api/admin/queue/{cursor}` | No body; admin paginated review/applying rows; start cursor `0` |
| POST | `/api/admin/approve` | `order_id`, `channel` (`sandbox`, `wechat`, `alipay`), `receipt_ref`, `actual_amount_minor`, `actual_currency` |
| POST | `/api/admin/retry` | `order_id` only; reuse durable approval after restart |
| POST | `/api/admin/reject` | `order_id`; authenticated admin, before reservation |

The `wechat`/`alipay` identifiers permit isolated contract tests; **all results
remain sandbox results**. They do not activate those payment systems. A future
reviewer must verify the actual channel statement, actual amount, currency and unique
transaction number; a claim, screenshot or four-digit code is not payment proof.
The receipt reference is an opaque channel transaction identifier, 8–100 ASCII
letters/digits/underscores/hyphens. Channel plus reference is globally unique in
the database. It is never returned to the user or included in push messages.

Order data: `id`, `plan_id`, null quoted `amount_minor`/`currency`,
`actual_amount_minor`, `actual_currency`, `term`, `days`,
`quota_bytes`, `upload_kbps`, `download_kbps`, `state`, `remark_code`, `created_ms`,
`target_ms` (null until reservation). Queue results additionally include owner
and channel. No passwords, tokens, or upstream account objects are returned.

Typical errors: 401 authentication required; 403 wrong role; 404 order missing
or not owned; 405 wrong HTTP method; 400 invalid/extra fields; 409 pending order,
approval in progress, reused receipt, or idempotency conflict; 503 disabled,
code pool exhausted, database unavailable, or entitlement retry required.

## Durable state and recovery

`created -> review -> applying -> approved`. Before `applying`, owner cancellation
or administrator rejection produces a terminal `cancelled`/`rejected` state.
Creating an order commits its snapshot and four-digit code before returning it.
Codes are unique across created/review/applying orders, and never authenticate
anything. Code exhaustion fails in bounded time. There is one outstanding order
per owner. No timed job silently expires a payment claim or releases its code.
Terminal rows and audit history remain when codes are reused, to investigate
late transfers; operators must never match a late transfer on the code alone.

`submit` is idempotent and only records `payment_claimed`. Notification failure
is audited and does not lose the review queue. Notifications default to no-op;
an optional adapter receives only order ID and code. This is best-effort wakeup,
not a reliable delivery queue; the admin queue is authoritative.

Approval commits reviewer, channel/reference, the absolute desired entitlement
and the durable applying order together in one SQLite transaction.
The first donation starts at approval, ignoring any remaining trial. Paid
renewal starts at `max(now, confirmed paid expiry)`. Calendar-year terms use UTC
anniversaries with Feb 29 clamped to Feb 28.
No local timezone/DST calculation or floating-point price arithmetic is used.
The quota snapshot describes desired limits; phase one does not apply real
quotas or real expiry to SFTPGo.

The durable `applying` row is the retry job. A 30-second lease is acquired in a
short SQLite transaction, then the entitlement adapter is called **outside**
the transaction. It must use bounded I/O and implement idempotent, monotonic
`ensure(username, absolute_expiry_ms, order_id)`, never relative "add a year".
`observed_expiry(username)` must confirm an applied ledger target before the
order becomes approved. It does not mean SFTPGo account expiration_date. The default adapter always refuses. Only the tests contain
a fake adapter. A timed-out call keeps the same order ID and frozen target;
retrying approval resumes it. Process death leaves a reclaimable lease. A stale
worker cannot acknowledge or overwrite a newer lease. A new renewal cannot
start until the prior job commits. External stale writes must also be prevented
by the adapter's monotonic guarantee; SQLite alone cannot fence remote writes.
There is no autonomous worker yet: authenticated admin POST retry with just the
order ID is the recovery trigger. Paginated queue state exposes unfinished jobs;
the operator need not retain the original receipt reference after a restart.

Audit rows contain only order, actor, event and UTC milliseconds. Receipt and
account association live in the private DB; keep it outside Git and protect it
with host ACLs and backups. Database/schema migrations are not supplied yet; this donation revision requires
a fresh sandbox database and must not be opened against old price-based fixtures.

## SFTPGo 2.7.6 compatibility and production blockers

Checked against the exact deployed version, not the main branch:

- [v2.7.6 OpenAPI](https://github.com/drakkan/sftpgo/blob/v2.7.6/openapi/openapi.yaml):
  `expiration_date` is Unix milliseconds, 0 means unlimited, and expired users
  cannot log in. User update must preserve unmanaged fields and omit password
  to preserve it. Do not create another account or reset a password for payment.
- [v2.7.6 user login checks](https://github.com/drakkan/sftpgo/blob/v2.7.6/internal/dataprovider/user.go):
  expiry is an account login condition. Do not promise that updating it will
  instantly terminate transfers already in progress; disconnect is a separate
  policy/operation, not enabled by this module.

**Unresolved production requirements:** preserve account login/download while disabling writes after entitlement expiry; choose actual limits and refund/retention policy; verify personal payment-channel eligibility; configure
receiving QR and admin authentication/notification without leaking secrets;
implement serialized SFTPGo update/readback and monotonic retry semantics while
preserving other fields; enforce purchased rights server-side including old
free APIs and direct/custom DAV clients. Do not clear expiry to restore login,
disable/delete old free accounts, or treat a frontend pay button as enforcement.

This module cannot enable actual collection until those decisions and production
integration exist. Activation must return through the existing connection test
and save comparison; it must not immediately upload or bypass conflict safety.

## Offline validation

Python 3.10+ with standard-library SQLite only:

```sh
cd server/manual-orders
python -m unittest -v test_orders.py test_http_api.py
```

Tests use fresh temporary databases, fake identities and a fake remote ledger.
They cover concurrent approvals on independent connections, timeout before and
after a remote write, crash/lease recovery, stale-worker renewal, immutable
snapshots, ownership, disabled defaults, receipt reuse, code-pool exhaustion,
late code reuse, UTC/leap-year renewal and failed notification recovery. No
external network, production key, real transaction or phone is involved. HTTP
tests run real requests over loopback with injected test-only identities and a
non-payment PNG, and close the listener after each test.
An empty local ledger must not be treated as an existing SFTPGo account's
baseline. Production onboarding must reject an unmanaged account or explicitly
import and validate its current entitlement before calculating any paid renewal.

The user reports no existing product accounts; no migration of old paid users is
planned. Existing development/test data are still not deleted. The selected
channel is a WeChat appreciation code: its ability to accept a remark is unproven.
Never require a four-digit remark; use the independent order ID plus manual
verification and binding of the actual transaction reference. See
[SFTPGo write-only restriction research](SFTPGO_UPLOAD_POLICY.md).

The separate [upload-policy module](UPLOAD_POLICY.md) now provides explicit
sandbox enrollment, dry-run and reconciliation against this ledger. It rejects
unsupported inheritance/subpath policy and unfenced adapters. Production SFTPGo
writes and scheduled enforcement remain disabled.

New-account policy uses `new_account_policy` plus `enroll_new_account`: create
read-only immediately, retain a separate trusted activation template without
`*`, and treat missing entitlement as read-only. The current online registration
service is unchanged; these are tested integration seams, not deployed enforcement.

The sandbox OrderUploadAdapter now connects orders to the experimental policy
worker: payment reservation writes the ledger before ensure runs; success only
marks approved, publishing its fixed target as confirmed service rights.
Failure leaves applying without undoing the recorded donation. First activation
remains inactive; an unconfirmed renewal retains only the prior confirmed
expiry. The API and write gate use the same confirmed-trial/approved-donation query. Retry/restart never extends again. The CAS policy remains
experimental; production should evaluate a per-request authenticated DAV write
gate rather than assume SFTPGo supports atomic permission updates.

The disabled [per-request write gate prototype](WRITE_GATE_PLAN.md) and fake
HTTP tests now implement that decision boundary. Its plan records all three
actual DAV proxy aliases and the unrestricted 8090 Compose publishing concern;
no production networking or auth routing has been changed.


## One registration trial per account (disabled sandbox seam)

`RegistrationTrials(orders)` is disabled by default; live mode is rejected.
An explicit sandbox host may inject it as `auth_v2`'s `registration_trials`
service. The legacy production creation callback is unchanged and is not an
acceptable durable registration adapter. No actual account, listener, deployment,
credential or production permission update is introduced.

A newly registered account gets exactly 72 elapsed hours from its successful
upstream server creation timestamp. `registration_trials` commits one stable
operation per account **before** upstream creation. The injected trusted adapter
must atomically bind that operation to the new account and its original server
time, and recover the same proof after a lost response or local ledger failure.
A callback followed by a journal insert does not meet this requirement. A
production implementation without that atomic/recoverable proof is unsupported.
A matching username or an “already exists” response is never registration proof.

Every registration retry must verify the supplied password belongs to the same
account and operation, including retries after confirmation. Existing accounts
from another operation cannot claim a trial. Passwords are never stored in the
local journal or audit; request bodies cannot supply a start time, duration,
expiry or operation ID. Login does not call the trial service. Duplicate or
late registration retries retain the original 72-hour expiry, even after it ends.

The adapter must create accounts read-only with login/download retained and
preserve a trusted activation template. It must idempotently finish template
enrollment before returning proof. The journal transitions
`pending -> applying -> confirmed`: validated creation proof and the fixed desired
expiry commit together; only successful entitlement readback confirms the trial.
The shared write gate accepts a current confirmed trial **or** approved donation.
Unconfirmed desired rows grant no writes; an existing confirmed grant remains
usable only to its original expiry. Expiry does not disable login/download.

The normal authenticated entitlement API is unchanged. It reads the maximum
confirmed trial/approved donation expiry. A trial does not carry into the first
paid year; paid renewals carry only an existing approved paid expiry. Desired
ledger updates are monotonic, so late trial recovery cannot overwrite a donation.
Registration recovery repeats the same named registration and credentials after
backoff; login never repairs or renews a pending trial. There is no recovery route
that accepts an arbitrary account name and mints rights without credential proof.
A process crash leaves a 30-second entitlement-application lease that can be
reclaimed by a credential-verified retry. A stale worker cannot confirm a newer
lease. Read-only upstream calls and writes happen outside SQLite transactions.

The schema adds an empty trial journal to compatible donation sandbox databases;
it does not import existing accounts, desired entitlements or applying orders as
confirmed trials. Prior approved-donation history remains authoritative. Tests
use a separate disposable upstream SQLite fixture to exercise the creation/ledger
failure gap; this proves the seam's recovery contract, not SFTPGo atomic support.

```sh
python -m unittest discover -s server/manual-orders -v
python -m unittest discover -s server/sftpgo-auth -v
```
