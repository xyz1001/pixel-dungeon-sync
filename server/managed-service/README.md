# Managed SFTPGo account service

Runnable, default-disabled composition of the real SFTPGo REST adapter, existing
registration/renewal journal, account-scoped SQLite upload authority, per-request
DAV gate, and optional official Feishu long-connection worker. No daemon starts
on import. This directory does not deploy, create secrets, install network rules,
change existing listeners, configure a second Feishu bot, or grant approval to do
any of those things.

The service uses the same SFTPGo username/password as the existing version-2
account contract. It does not add a browser session, token issuer, payment SDK,
order-review API, or public Feishu event endpoint.

## Run and validate

Python 3.10+; HTTP/account/ledger code uses the standard library. From this
source checkout:

```sh
python server/managed-service/service.py validate-config server/managed-service/config.example.json
python -m unittest discover -s server/managed-service -p 'test_*.py' -v
```

Validation is offline. It reads the selected non-secret config, the configured
appreciation PNG if enabled, and the selected Feishu binding file if enabled;
it never reads runtime secret sources, probes
SFTPGo, starts the SDK, or proves proxy enforcement. Unknown fields, duplicate
JSON keys, non-JSON numbers, nonboolean flags, invalid URLs, and nonloopback
listeners are rejected. The example must validate as disabled. `serve` refuses
a disabled config rather than returning a successful no-op.

After an operator has separately completed the deployment checklist, the actual
entry point is:

```sh
python server/managed-service/service.py serve /etc/managed-account-service/config.json
```

This command is an explicit runtime operation, not a step performed by the source
implementation or tests. It reads only the configured existing credential source
and persists the selected ledger. Run under an unprivileged dedicated account,
with a restrictive umask (for example 0077), private state/config directories,
normal service supervision, and access only from the trusted local proxy. Other
untrusted local processes must not be able to connect to this loopback service.
A port bound to 127.0.0.1 alone is not a boundary against other local users.

`enabled: true` starts the listener. `mutations_enabled: false` permits validation
of authentication, confirmed existing entitlements and the gate, but rejects new
registrations and cannot start the Feishu worker. Login/download remain possible
for expired accounts. The account-lifecycle acknowledgement must already be true
for any enabled runtime because SFTPGo user-token authentication does not prove
an immutable account incarnation. All six acknowledgements must be true before
`mutations_enabled` or the bot can be enabled.

## Configuration and existing secret sources

Keep external config outside the checkout. Replace the public URL and immutable
instance namespace; review the home root, positive quota and bandwidth values.
The example's limits are illustrative, not an instruction to change existing
accounts. Existing accounts are never rewritten to this template.

- The listener accepts only literal `127.0.0.1`, not `localhost` or `0.0.0.0`.
- Production public WebDAV URL requires HTTPS. Management URL requires HTTPS
  except exact loopback HTTP explicitly allowed by `allow_loopback_http`.
  Redirects and environment proxies are not followed; TLS verification stays on.
- `admin_credentials` is either the two environment-variable **names** shown in
  the example or `{"file":"/absolute/existing/private-admin.json"}`. The file
  contains exactly username/password, is a regular nonsymlink file owned by the
  runtime user, and has no group/world permissions. Credentials are supplied by
  the operator at runtime; no key, admin account, OAuth grant or token file is
  created. Existing admin must have `view_users` and, for registration,
  `add_users`. Existing admin MFA/permissions can cause a clear failure.
- Feishu `app_secret` is either `{"env":"EXISTING_SECRET_VARIABLE"}` or
  `{"file":"/absolute/existing/private-secret-file"}`. Its private file contains
  the secret text, optionally one final newline. No secrets belong in the config,
  examples, command line, repo, screenshots, response, or logs.
- `database_path` must be the existing entitlement/order/trial SQLite database
  when history already exists, not a new empty substitute. Back it up consistently
  before schema changes. Keep it on local durable storage with this managed
  composition as its sole entitlement writer. The new tables are additive; old records are preserved.
- `queue_path` is the separate existing/new durable Feishu queue database. Only
  one configured bot instance should process this binding/queue. Do not launch
  a second bot independently in parallel with this composition.

### Optional appreciation resource

`appreciation` is optional and defaults to `{"enabled":false}`. Both appreciation
routes return 404 while disabled. To enable it, the operator must supply the
actual reviewed still PNG outside Git and configure all three fields:

```json
{
  "enabled": true,
  "asset_path": "/etc/managed-account-service/assets/appreciation.png",
  "expected_sha256": "<actual 64-character lowercase SHA-256 of that PNG>"
}
```

The example config deliberately leaves this disabled and uses an all-zero
placeholder digest. Replace that digest with the independently reviewed asset's
actual hash before enabling; there is no embedded QR image, fallback image,
remote image fetch or successful metadata-only mode without a valid asset.
The image is a public donation asset, so it must not contain account secrets or
private user data. Keep its source file and parent directories under trusted
operator control, readable by the service and not writable by untrusted users.

`validate-config` and application startup validate a canonical absolute path
with no symlink components, a regular file of 1 byte through 1 MiB, its exact
SHA-256, PNG signature/chunk structure/CRC, valid IHDR parameters, and bounded
decoded scanline lengths/filter bytes. Width and height must each be 1–2048,
with at most 2,000,000 pixels. Still PNG only; APNG is rejected. This validates
the file's format, not the QR's recipient, payment meaning or visual content;
those require the operator's independent review and a client rendering check.
No credentials, network calls, payment-provider verification or production route
checks occur during offline validation. A missing or invalid enabled asset
fails startup before the listener opens. Disabled assets are not opened.

The process retains the verified immutable bytes in memory and serves only those
bytes at the one fixed image route. Replacing/removing the file later does not
change the live image; a reviewed config/hash update and service restart are
required. The metadata hash and served bytes therefore cannot drift per request.

Secret source checks happen only on `serve`; missing credentials fail before the
listener is opened. No upstream readiness claim is made until actual requests
succeed. Database, SDK or listener startup failure exits nonzero without dumping
raw exceptions. `/healthz` means only that this loopback process is listening;
its `route_enforcement_verified` field is always false.

## HTTP contract

All responses are noncacheable. The only supported public routes are:

- `POST /api/register`, JSON exactly `{version:2, action:"register"|"login",
  username:"...", password:"..."}`. It reuses `auth_v2.credentials`; named
  registration keeps its existing validation and password semantics. `{}` and
  an empty body are explicitly rejected, so legacy random registration cannot
  skip the durable trial journal. `/register` is not an alias here. Status 200
  returns the existing allowlisted quota/WebDAV fields, never a password,
  admin token, upstream object, account-generation key or registration marker.
- `GET /api/account/entitlement`, existing Basic credentials. Returns the
  authenticated account's confirmed expiry, active/managed flags, and
  `source: "managed_ledger"`. It does not expose the sandbox order API.
- `GET /api/account/appreciation`, existing valid Basic credentials, only when
  appreciation is enabled. Exact success contract:
  `{"code":200,"data":{"version":1,"payment_mode":"donation","image_path":"/api/account/appreciation/image","image_sha256":"<verified lowercase SHA-256>","term":"calendar_year","remark":"username"}}`.
  `remark` is the literal instruction `username`, not a username value. The
  client must take its account username from authenticated entitlement data.
  Metadata contains no account data, credential or server filesystem path.
- `GET /api/account/appreciation/image`, public raw `image/png`, only when enabled.
  Clients fetch this exact same-origin path without credentials and verify its
  bytes against `image_sha256` before displaying it. Response includes
  `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`, with no redirect.
  There is no directory, arbitrary path/query parameter, alternate filename,
  HEAD route, upload, order creation or payment confirmation endpoint. The image
  route does not authenticate or access the ledger. Displaying or scanning the
  asset does not grant or extend an entitlement; existing manual renewal remains
  separate. Disabled metadata and image routes return 404 even with valid auth.

Proxy-only: `GET /_internal/dav-authorize` takes Authorization plus
`X-Original-Method` **overwritten by trusted nginx**, captured from the original
parent request before the auth subrequest becomes GET. It never trusts X-User,
X-Account-ID, forwarded usernames, query parameters or JSON identities. Success
is 204; invalid credentials 401; absent/expired/conflicting upload rights 403;
DB/upstream/service failures fail closed. Unknown DAV methods fail closed.

Duplicate headers (including Authorization and Content-Length), transfer
encoding, Expect, malformed/oversized JSON, duplicate keys, ambiguous paths,
query strings and browser cookie/origin requests are rejected. The loopback Host
must exactly match its listener; nginx overwrites it. Worker count, request body,
header size, global registration/login admission and request lifetime are
bounded. Add appropriate verified client-IP rate limiting at the public proxy;
this service deliberately does not trust client-supplied forwarding headers.
Neither Python handler nor exception fallback logs request bodies, credentials,
URLs or upstream data. Review proxy/SDK/service-manager logs for the same rule.

## Trial and renewal behavior

Registration records a durable operation before creating one real SFTPGo user.
The create request atomically saves the marker in `additional_info`; recovery
requires that original marker and verification of the supplied current password.
The exact account's original **SFTPGo `created_at` + 72 hours** becomes the trial
expiry only after the local proof/application/journal commit. Repeating the same
registration never resets the password, account creation time, trial or expiry.
Crashes/lost responses are retried with that original operation.

The Feishu bot still accepts only an exact username text event from the configured
app, tenant, dedicated group and designated administrator, received through the
authenticated official SDK long connection. One authorized message applies one
calendar year using the existing paid-expiry arithmetic and same-message
idempotency. The running gate consumes only locally confirmed, identity-bound
applications in that same database; a reservation/desired target is not access.
There is no remote permission PUT or pretend atomic SFTPGo CAS operation.

For optional Feishu activation, provide the existing binding file described in
`../feishu-renewal-bot/README.md`, its externally supplied secret and reviewed
`lark-oapi==1.7.4` dependency set. The runtime checks the SDK before opening HTTP,
then starts HTTP on a thread and runs the actual `sdk_transport.run_service` long
connection on the main thread. Wrong/missing SDK, disabled/incomplete binding or
missing secret fails; no fake transport substitutes for it. SIGINT/SIGTERM or
SDK exit shuts down the HTTP listener; the existing bot stops its durable worker.

## Production activation checklist (manual, no deployment performed)

Do not infer readiness from local health or a successful test double. Each true
prerequisite is a specific operator **attestation**, not automatic discovery:

1. Audit the complete effective nginx routing: exact/prefix/regex locations,
   rewrites, error fallbacks, all server names/ports, `/dav/`, `/dav`, root DAV and
   any other aliases. Compile/enable `auth_request`, preserve each alias's exact
   URI mapping, and require the gate on every managed read/write request. Keep
   downstream SFTPGo Authorization intact and its authentication/file checks on.
2. Close direct managed DAV access, including previously all-interface 8090,
   container published ports, IPv4/IPv6, secondary proxies and local trust gaps.
   Close other write protocols and shared/unmanaged storage routes. A service
   merely *listening* on all interfaces and a port publicly reachable are
   different facts; verify both bind/publish rules and remote reachability.
3. Keep SFTPGo management/HTTP private. The reviewed layout places management
   on `127.0.0.1:8080`; port 8088 is the separate legacy auth service, not the
   management API. Close any public/direct legacy-auth registration route that
   could bypass the new trial journal, and do not fall back to its `{}` account
   creation. Do not proxy SFTPGo REST/WebClient/files/share write endpoints through
   product URLs. Existing enabled accounts' FTP/SSH/HTTP write paths need separate audit;
   the new-user template alone does not fix historical account permissions.
4. Enforce no deletion, rename or reuse of managed usernames/IDs; protect the
   marker from administrators/hooks that could overwrite it. Numeric SFTPGo IDs
   may be reused. `instance_id + numeric ID + created_at` detects many lifecycle
   changes but is not a remote atomic proof. Disable mutations and audit bindings
   during database restore/import/reset or provider changes; do not reuse the
   same instance namespace for a new provider dataset. Legacy zero-created-time
   accounts need reviewed migration instead of guessed timestamps.
5. Preserve the existing trusted fixed-drop `/upload` flow separately. Its
   bounded destination and distinct fixed identity must remain isolated from
   managed personal storage. It must never become a managed-DAV alias, accept a
   caller-chosen managed identity, or bypass expiration for managed writes.
   `nginx.example.conf` deliberately contains only a comment at that location
   because the real fixed-drop handler is deployment-specific. Retain both
   exact `/upload` and slash-prefixed `/upload/` isolation before merging reviewed
   managed locations into the existing server; otherwise the root managed DAV
   location would capture them. Never replace the working drop handler or blindly
   install this review fragment.
6. Use the existing ledger. Existing username-only paid/trial history cannot be
   silently assigned to a possibly recreated account. Reconcile lifecycle proof
   first. The ledger exposes an operator-only `adopt_legacy_paid` method for a
   complete reviewed paid-operation manifest; there is no public migration API.
   Legacy trials are blocked for separate reviewed migration. Preserve and back
   up their original records; do not erase/regrant them or create a fresh DB.
7. Complete the acceptance matrix below, then set all prerequisite booleans,
   `mutations_enabled: true`, and optional Feishu binding activation. Use normal
   separately authorized deployment/config-review and rollback procedures. This
   repository intentionally provides no automatic install/reload/network script.

## Required live acceptance matrix

Before activation, use dedicated authorized test accounts and one reviewed
renewal message. Keep the fixed drop control separate. Exercise at least:

- Fresh named register creates one actual SFTPGo account, begins at server-created
  time, and grants exactly 72h. Retry after restart/lost response, concurrent retry
  and incorrect password cannot duplicate, extend or take over an account.
- Valid/invalid login and cross-account entitlement requests remain scoped.
  Expired valid credentials still log in/download; unauthenticated downloads fail
  at SFTPGo even if the gate path is misused. Every allowed read remains subject
  to SFTPGo file permissions.
- If appreciation is enabled, verify metadata is 401 without valid Basic auth,
  authenticated JSON exactly matches the documented contract, and anonymous
  image GET returns the independently reviewed PNG with its matching SHA-256,
  `image/png`, `no-store` and no redirects. Confirm encoded/double-slash/query
  aliases and directory paths cannot retrieve it. With appreciation disabled,
  both routes must return 404. Merge only the two exact nginx locations;
  never expose the external asset directory or proxy arbitrary filesystem paths.
- Before, at and after expiry, test PUT/POST/MKCOL/MOVE/COPY/DELETE/PROPPATCH/
  LOCK/UNLOCK via `/dav`, `/dav/`, root and every discovered alias. Overwrite and
  destination-changing operations cannot evade the original source account.
  COPY/MOVE `Destination` cannot switch to another account or exempt route.
- Verify direct public access to 8090/8080 and legacy auth 8088 (including IPv6), alternative listeners,
  protocols, encoded/double-slash/rewrite paths and all old registration routes
  cannot bypass gating. Verify external calls to the nginx internal location fail
  and forged X-Original-Method/X-User/X-Account-ID cannot turn a write into a read.
- Stop/corrupt/unmount the ledger and stop the gate/upstream in controlled tests:
  writes fail closed. Confirm existing legitimate downloads retain SFTPGo auth.
- Genuine authorized Feishu username message adds one year once, replies with
  confirmed expiry, and survives queue/restart/reply retry. Wrong app/tenant/group/
  sender, multiword text, forged HTTP event and duplicate message cannot renew.
- `/upload` still works using its original trusted fixed identity, cannot access
  managed personal storage, and cannot be selected by a managed COPY/MOVE target.

The gate checks **new requests**. A transfer admitted before expiry may finish
later; it does not terminate in-flight streams. Do not advertise stream revocation.
Unit/mock integration tests do not prove current production port closure, nginx
module availability, real SDK connectivity, dependency audit or end-to-end live
Feishu delivery. Those checks remain deployment gates.
