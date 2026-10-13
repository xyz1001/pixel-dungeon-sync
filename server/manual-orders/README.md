# Manual payment order foundation (phase one, sandbox only)

This isolated Python/SQLite module models a manually reviewed order attached to
an existing authenticated account. It does not install HTTP routes, listen on a
port, create users/passwords, call SFTPGo, send push messages, or change the
existing free registration service. No production adapter or live-payment mode
is included. `Orders(...)` defaults to disabled; `mode='live'` is rejected.

## Explicit configuration and trusted boundary

Sandbox construction requires explicit `Plan` values: versioned ID, positive
integer minor-unit price, uppercase three-letter currency, `calendar_year` or
`fixed_days`, positive quota bytes and upload/download KB/s, and the explicit
`sandbox_ledger_only` expiry policy. There is no real price or policy default.
Plans must use a new ID when their commercial terms change. Existing order
snapshots remain immutable even when configuration changes.

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
HTTP host is supplied in this phase, so this is an in-process contract, not an
already deployed API. Invalid/unknown callers fail closed.

## Contract for the next client phase

All mutations are POST. Exact body keys are required; injecting price, currency,
owner, role, expiry or quota is rejected. Successful responses are
`200 {"sandbox":true,"data":...}`; errors are `{ "error": "CODE" }`.

| Method | Path | Body / result |
| --- | --- | --- |
| POST | `/api/order/create` | `plan_id`, `request_key` (16–80 URL-safe characters); returns server snapshot |
| POST | `/api/order/submit` | `order_id`; claims payment, does not prove it |
| GET | `/api/order/status/{order_id}` | No body; only order owner |
| POST | `/api/order/cancel` | `order_id`; owner, before approval reservation |
| GET | `/api/admin/queue` | No body; authenticated admin, oldest 100 review/applying orders |
| POST | `/api/admin/approve` | `order_id`, `channel` (`sandbox`, `wechat`, `alipay`), `receipt_ref` |
| POST | `/api/admin/reject` | `order_id`; authenticated admin, before reservation |

The `wechat`/`alipay` identifiers permit isolated contract tests; **all results
remain sandbox results**. They do not activate those payment systems. A future
reviewer must verify the actual channel statement, amount, currency and unique
transaction number; a claim, screenshot or four-digit code is not payment proof.
The receipt reference is an opaque channel transaction identifier, 8–100 ASCII
letters/digits/underscores/hyphens. Channel plus reference is globally unique in
the database. It is never returned to the user or included in push messages.

Order data: `id`, `plan_id`, `amount_minor`, `currency`, `term`, `days`,
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

Approval first commits reviewer, channel/reference and an absolute target time.
Renewal starts at `max(now, current ledger expiry)`. Calendar-year terms use UTC
anniversaries with Feb 29 clamped to Feb 28; fixed-day terms use 24-hour days.
No local timezone/DST calculation or floating-point price arithmetic is used.
The quota snapshot describes desired limits; phase one does not apply real
quotas or real expiry to SFTPGo.

The durable `applying` row is the retry job. A 30-second lease is acquired in a
short SQLite transaction, then the entitlement adapter is called **outside**
the transaction. It must use bounded I/O and implement idempotent, monotonic
`ensure(username, absolute_expiry_ms, order_id)`, never relative "add a year".
`observed_expiry(username)` must read back an integer at least the target before
approval can commit. The default adapter always refuses. Only the tests contain
a fake adapter. A timed-out call keeps the same order ID and frozen target;
retrying approval resumes it. Process death leaves a reclaimable lease. A stale
worker cannot acknowledge or overwrite a newer lease. A new renewal cannot
start until the prior job commits. External stale writes must also be prevented
by the adapter's monotonic guarantee; SQLite alone cannot fence remote writes.
There is no autonomous worker yet: an authenticated repeated approval is the
recovery trigger, and queue state exposes unfinished jobs.

Audit rows contain only order, actor, event and UTC milliseconds. Receipt and
account association live in the private DB; keep it outside Git and protect it
with host ACLs and backups. Database/schema migrations are not supplied yet;
this initial schema targets fresh sandbox databases only.

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

**Unresolved production requirements:** authenticate renewals after an account
expires; choose actual price/limits, grace/refund/retention policy and existing
free-account treatment; verify personal payment-channel eligibility; configure
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
python -m unittest -v test_orders.py
```

Tests use fresh temporary databases, fake identities and a fake remote ledger.
They cover concurrent approvals on independent connections, timeout before and
after a remote write, crash/lease recovery, stale-worker renewal, immutable
snapshots, ownership, disabled defaults, receipt reuse, code-pool exhaustion,
late code reuse, UTC/leap-year renewal and failed notification recovery. No
network, production key, real transaction or phone is involved.
