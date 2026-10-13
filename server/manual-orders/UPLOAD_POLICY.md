# Upload-only expiry policy module (sandbox, not deployed)

`upload_policy.py` implements a deliberately narrow reconciliation core beside
the donation ledger. Nothing starts automatically. `UploadPolicy(database)` is
disabled by default; `mode='live'` is rejected. It contains no SFTPGo HTTP PUT,
account creation, password handling, listener, secret or production cron job.

## Supported sandbox scope and preflight

1. Inject a read adapter returning the bounded `AccountSnapshot`: username,
   opaque account revision, permissions, status, expiration_date, group and
   virtual-folder associations, and explicit effective-permission verification.
   Do not return passwords, hashes, tokens or full upstream user objects.
2. For new product accounts, call pure `new_account_policy(activation_permissions)`
   with a trusted server-configured template, never request JSON. It returns
   `initial_permissions` containing only list/download and a separate bounded
   `activation_permissions` template. Wildcard `*` and unknown permissions are
   rejected; explicit list/download/upload are required. The future provisioning
   caller must use the read-only initial permissions in the CREATE itself, not
   create a writable user and rely on asynchronous revocation.
3. After creating that read-only account, call
   `enroll_new_account(owner, activation_permissions)` to persist the separate
   activation template. It rejects writable initial profiles, existing rights,
   unsupported inherited/subpath policy and repeat registration. A crash before
   enrollment leaves a read-only account. This interface itself creates no users.
   Legacy `enroll(owner)` remains only for explicit old sandbox inspection; it
   is not the product registration path and must not determine new paid rights
   from a read-only current profile.
4. `dry_run(owner)` reads the authoritative `entitlements` row and server clock,
   reports before/after permissions and expiry, and makes no remote writes or
   job reservations. `executable=false` means the adapter cannot enforce atomic
   fencing. This is the deployment-preflight/execution-preview API. The module
   has no CLI accepting untrusted credentials or client-provided expiry.
5. `reconcile(owner)` rereads the ledger and applies only the permissions result
   through an explicitly fenced sandbox adapter; it reads back before confirming.

Preflight refuses non-root permission entries (including wildcard subpaths),
any group/virtual-folder association, unverified external/inherited grants,
unknown permission names, disabled/already-expired accounts, or a baseline that
does not already allow listing and download. It also refuses an unregistered
owner or externally changed permission set. An absent entitlement row explicitly
means no current upload rights and computes read-only permissions. These
are unsupported cases, not permissions to silently broaden or partially manage.
To prove a future real snapshot's effective permissions, the adapter must audit
all applicable group/default-group/virtual-folder sources, not infer absence
merely because a sparse API response omitted them.

## Permission semantics

When `expires_ms > server_now_ms`, restore the exact trusted activation template
(or the previously captured original permissions in old sandbox fixtures).
At equality or after expiry, use only `['download','list']`. The original `*`
can be restored only if it was explicitly captured as that account's baseline;
a template lacking overwrite/rename never gains them during renewal. New product
templates never permit `*`. Current read-only permissions are not recaptured as
the activation template, so the first verified donation can enable uploads.

Expired managed accounts retain existing status, expiration_date and password;
the interface has no setters for these fields. Enrollment requires status 1 and
expiration_date 0, rather than changing an incompatible login policy. The fake
adapter tests verify these fields and unrelated metadata remain unchanged.

The read-only result denies new/overwrite PUT, MKCOL, rename/MOVE, COPY target
writes, and pre-existing temporary-file-to-final commits. It also denies DELETE
and metadata/symlink mutations: allowing these would make the managed scope
writable by alternate means. **It deletes no stored file.** This explicit
read-only policy is stricter than merely hiding the app upload button.

The permission-to-method matrix is grounded in the exact version sources in
[SFTPGO_UPLOAD_POLICY.md](SFTPGO_UPLOAD_POLICY.md). Unit tests check the resulting
capability sets, not real HTTP method execution. In-memory locks may still be
possible; no LOCK may authorize a subsequent denied write. An upload already
opened before the cutoff can still finalize. This module makes no instantaneous
termination guarantee and does not disconnect live transfers.

## Concurrency, retries and honest adapter boundary

Each reconciliation reserves a monotonically increasing generation in SQLite,
with observed ledger expiry, server time and desired permissions, before remote
I/O. The database is not locked during that I/O. A retry or delayed scheduler
invocation computes from the **current** ledger and reserves a new generation;
it cannot submit an old expiry payload through this API.

The adapter's `apply_permissions(owner, expected_revision, generation, permissions)`
must atomically reject stale account revisions and older/equal generations.
Even a same-permissions update must establish the new generation. This prevents
an earlier expiry request that arrives after a newer renewal policy from taking
effect. The fake adapter implements this guarantee with one lock; tests pause
the old request, apply renewed rights, then release the old request and verify
it is rejected. Unknown outcomes remain `retry_required`; persisted jobs and
baseline survive restart. Any external permission edit must advance revision.

**SFTPGo 2.7.6 ordinary user PUT does not supply this atomic fence.** Merely
setting a boolean capability flag, doing GET-before-PUT, or reading back afterward
does not implement it. Therefore there is no enabled production adapter here.
A deployable integration needs a server-side serialized/conditionally applied
permission controller with demonstrable fencing, or an equally safe architecture;
it cannot simply wrap the management PUT endpoint. The mock demonstrates the
required contract, not ordinary SFTPGo's behavior.

The ledger and remote permission write are not a distributed transaction. If a
donation is approved while an earlier permission update is in flight, the next
reconciliation must converge to the new ledger. No newer remote policy can be
overwritten by the old request with the required adapter fence, but this alone
does not guarantee zero delay between ledger approval and restored upload rights.
There is no automatic periodic scheduler yet. Operators must not deploy a fake
adapter or claim this module alone enforces paid service in production.

## Validation

```sh
cd server/manual-orders
python -m unittest -v test_upload_policy.py
python -m unittest -q test_orders.py test_http_api.py test_upload_policy.py
```

All data are ephemeral fixtures; no actual SFTPGo users/keys are created. Tests
cover dry-run/no side effects, disabled defaults, exact expiry boundary, field
preservation, method capability sets, exact permission restoration, inherited
and nested permission rejection, external edits, timeout-after-write recovery,
durable generations, delayed expiry against renewal, and baseline protection.
The disposable real SFTPGo method/cache/in-flight matrix remains unexecuted;
production setup is blocked until it and the adapter guarantee are established.

New-account regressions additionally cover read-only CREATE planning, persistent
activation-template registration, absent-ledger read-only behavior, first donation,
expiry and renewal, wildcard rejection and repeated-registration protection.
The existing live registration template has not been changed. Therefore these
safe interfaces are not yet wired into real registration, and no production
claim is made that newly registered live users are already restricted.

## Experimental status and order integration

This CAS-based permission worker remains an experimental sandbox only. Do not
continue toward a plain SFTPGo PUT production adapter. Production direction is
an authenticated per-request DAV write gate, subject to an actual topology and
bypass review. No gate or network configuration is deployed by this module.

OrderUploadAdapter exists solely to test the corrected sandbox ordering: Orders
commits desired entitlements plus applying state first; ensure validates that
reservation and invokes reconciliation using the latest ledger. observed_expiry
checks a confirmed job and read-back permissions, not account expiration_date.
On failure the order remains applying; renewed rights are not delayed by waiting
for the very ledger entry the worker needs. Completion never adds another year.
