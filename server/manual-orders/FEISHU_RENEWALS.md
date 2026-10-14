# Feishu authorized-message renewal ledger (offline, disabled by default)

This is the current renewal path: a designated administrator sends exactly the
existing product username in the dedicated Feishu group. The transport must
first verify the Feishu event, application/tenant, group and sender allowlists.
No user order claim, payment amount, order ID, remark or transaction reference
is required by this path. An authorization message is the grant instruction;
it is not automatic payment reconciliation or a receipt-verification claim.

No listener, network client, credentials, real account adapter, production
permission implementation or deployment is included in this module.

## Internal API and account boundary

```python
service = FeishuRenewals(
    database_path,
    enabled=False,
    accounts=authoritative_account_lookup,
    entitlements=account_scoped_entitlement_adapter,
    clock=server_clock_ms,
)
result = service.renew(
    app_id=verified_app_id, tenant_key=verified_tenant_key,
    message_id=verified_platform_message_id,
    chat_id=verified_dedicated_group, sender_id=verified_designated_admin,
    username=exact_message_text,
)
```

`renew` is an internal service for the authorized bot worker, not a public
account-name-to-entitlement endpoint. It does not construct an `Orders.Principal`
and does not interpret self-declared role, signature or account text as identity.
The bot is responsible for accepting only an exact username-only text message.
All arguments are keyword-only. Username syntax matches the existing login
contract, `[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}`, with no trimming, case folding, fuzzy
matching or account creation. Existing uppercase/short/dotted usernames remain
eligible only if the authoritative lookup confirms the exact same spelling.

`accounts.lookup_exact(username)` must return `AccountIdentity(username,
account_id)` with a nonempty stable account ID from the authoritative account
system, or `None` for an unknown account. A dictionary, boolean, mismatched
username or ambiguous result fails closed. Lookup failures do not create a
reservation. The default lookup and entitlement adapters refuse execution.

The entitlement adapter contract is:

- `ensure_account(identity, absolute_expiry_ms, operation_id)` targets the same
  immutable account ID atomically, applies a monotonic absolute target, uses the
  durable operation ID idempotently, and has bounded I/O.
- `observed_account_expiry(identity)` reads back an applied entitlement for that
  exact account. A desired database maximum is not a readback.
- A username-only management write after a lookup is insufficient: deletion and
  recreation between those calls must not apply to a replacement account.

Every known-message retry, including an already confirmed message, resolves the
account again and checks the fixed account ID. A missing/recreated identity
returns a conflict without another application. A new message for a username
already bound to another account ID also fails closed. Unknown-user results are
persisted terminal results; registration later cannot transform the old message
into a grant. A genuinely new message can authorize that newly known account.

The existing order API and DAV write gate still authenticate by username.
Before production, either prohibit deletion/reuse of product usernames or extend
the authenticated principal and entitlement queries with a stable account-ID
check. This ledger does not claim to migrate those whole authentication flows.

## Return values

Every result includes `status`, `username`, `target_ms`, `operation_id`; values
without a reservation have null target/operation. Persisted results also include
`state`. An optional bounded `code` describes a retry or conflict without raw
upstream details. The bot should display a short human result rather than the
internal IDs.

- `confirmed`: application and readback succeeded; fixed target is usable.
- `applying`: another worker owns a live application lease; retry later.
- `retry_required`: lookup, database or application failed; retain the same
  platform message ID and retry. `state` distinguishes reserved/applying data.
- `unknown_user`: persisted rejection; no entitlement mutation or account create.
- `conflict`: platform-message content/actor/chat changed or account ID differs.
- `disabled` / `rejected`: feature disabled or malformed internal arguments.

The durable successful state progression is `reserved -> applying -> confirmed`.
A crash between reservation and application leaves `reserved`; application
failure leaves `applying`. The public call normally completes those stages in
one invocation, but records them separately for recovery and inspection.

## Durable arithmetic, concurrency and confirmation

`(app_id, tenant_key, message_id)` is the unique platform idempotency key. Its
chat, sender, username and stable account ID are immutable. Duplicate deliveries,
restarts, timeout/readback failure, concurrent workers and a bot reply failure
cannot add another year. Different message IDs for the same account each reserve
one year. Unknown results share the same idempotency protection.

The same SQLite `BEGIN IMMEDIATE` transaction reads the paid base and writes the
fixed target for both new message grants and legacy order approval. The base is:

- previously approved or currently applying legacy paid orders;
- reserved, applying or confirmed authorized-message paid grants;
- never a trial or the undifferentiated `entitlements` desired maximum.

The target is one UTC calendar year after `max(server_processing_time,
paid_reserved_expiry)`, with February 29 clamped to February 28. First/expired
paid grants start at server processing time. Trial time never gets added.
The target stays frozen even when the retry happens much later.

Reservations count for later paid arithmetic because they are already
independent administrator authorizations. If message A reserves year one but
its apply fails, message B reserves year two and may successfully apply the
cumulative two-year target. Retrying A still applies only its original target
and cannot shorten B. This prevents a lost year without treating A as confirmed.

The public entitlement endpoint and DAV write gate use `confirmed_expiry`:
maximum of confirmed registration trial, approved legacy donation and confirmed
Feishu grant. Reserved/applying targets do not independently authorize writes.
An existing confirmed grant remains usable only until its own expiry. Login and
read/download remain unaffected by this ledger decision.

The `entitlements` table is a monotonic desired cache, not confirmation evidence.
In a shared ledger, ordinary permission reconciliation uses confirmed rights.
An explicit application may use only its own fixed target plus already
confirmed rights. It must not consume a different unexecuted reservation merely
because the global desired maximum is higher.

The sandbox `OrderUploadAdapter` supports `ensure_account` only with a snapshot
account ID and an adapter that supplies atomic account-ID plus revision/generation
fencing. This is an experimental contract, not a real SFTPGo API capability.
No default production adapter is enabled.

A 30-second database lease bounds ownership. Remote I/O happens after the
reservation/lease commits, outside any SQLite transaction. A process crash
leaves a reclaimable lease; an older worker cannot acknowledge a newer lease.
Adapters must independently prevent stale external writes. Bot inbox/outbox
recovery is separate: after application succeeds, delivery failure retries only
the reply, or safely re-queries this same message record if outbox commit was lost.

## Compatibility and migration

Opening a compatible existing donation database creates an empty
`feishu_renewals` table and index, keeping all orders, targets, receipts, trials,
entitlements and audit rows. No approved/applying rows are rewritten or inferred
as confirmed Feishu grants. Legacy applying retries keep their frozen targets.
`confirmed_expiry` can read a compatible older DB before the optional new table
is present. Older price-based schemas remain unsupported rather than being
silently rewritten or deleted.

Upload-policy migration adds nullable `operation_id` and `account_id` columns to
existing policy jobs under a SQLite transaction. Existing generations, baselines
and job records are retained. Standalone sandbox policy fixtures without any
order/trial/message journal retain their pre-existing confirmed-only table
semantics; this compatibility path is not used once the shared ledger exists.

Legacy order mutation endpoints remain for old-record compatibility. New clients
need only existing read-only plans/payment-image/entitlement endpoints; they do
not create or submit an order to receive an authorized Feishu renewal.

## Offline validation

```sh
python -m unittest discover -s server/manual-orders -v
python -m unittest discover -s server/sftpgo-auth -v
```

`test_feishu_renewals.py` covers duplicate/concurrent delivery; independent
concurrent years including an earlier failed apply; cross-account key conflicts;
unknown-user permanence; account recreation; UTC leap-year and expiry arithmetic;
trial exclusion; lease/process/reply failure recovery; legacy and new mixed
renewal ordering; additive migrations; confirmed-only write decisions; and
policy application that cannot promote another unexecuted desired target.
All accounts and adapters are disposable local fixtures. These tests do not
prove live Feishu callback delivery or production SFTPGo enforcement.
